/**
 * The old code's functions now made by the new page's modules (retiring the old code): given to the legacy engine through
 * gen-legacy's FROM_ENV, so the old functions still there, and the new page's own calls (legacy.fn falls back to these),
 * all run the new rules. Each keeps what the old one did after the rule itself: saving the draft, making the services
 * again, drawing, the message under the table, and (until step 8 replaces them) the old prompts.
 */
import { tr } from "../i18n"
import * as T from "../core/timetable"
import * as S from "../core/services"
import { clock, DAY, mod, routeTitle, tableName } from "../core/format"
import * as D from "../core/duties"
import { DraftTimes } from "../core/draftTimes"

type Legacy = { fn: Record<string, any>; v: Record<string, any> }

/** Step 4: the timetable rules (core/timetable.ts) */
export function timetableRules(get: () => Legacy) {
  const find = (id: string) => S.findService(get().v.data.routeById, id)
  const editTimetable = (r: any, change: T.Change) => {
    const { fn, v } = get()
    const message = T.applyEdit(v.draft, r, change)
    fn.saveDraft()
    fn.buildServices()
    if (v.state.selected && !find(v.state.selected)) v.state.selected = null
    v.tableMessage = message
    fn.renderAll()
  }
  // The draft's times as the game works them out
  const times = new DraftTimes(
    routes => get().fn.sendJson("api/times", "POST", { routes }),
    r => T.draftOf(get().v.draft, r)?.timetable,
    id => get().v.data.routeById.get(id),
    () => { const { fn, v } = get(); fn.buildServices(); if (v.state.selected && !find(v.state.selected)) v.state.selected = null; fn.renderAll() },
  )
  return {
    timesSource: (r: any) => times.source(r),
    sendTimes: () => times.flush(),
    draftOf: (r: any) => T.draftOf(get().v.draft, r),
    draftEntry: (r: any) => T.draftEntry(get().v.draft, r),
    tidyDraft: (id: string) => T.tidyDraft(get().v.draft, id),
    gameTimetable: T.gameTimetable,
    fromGame: T.fromGame,
    normalize: T.normalize,
    timetableOf: (r: any) => T.timetableOf(get().v.draft, r),
    editTimetable,
    setStopException: T.setStopException,
    syncExceptions: T.syncExceptions,
    retimeService: T.retimeService,
    setSuspended: T.setSuspended,
    removeService: T.removeService,
    setServiceOrder: T.setServiceOrder,
    laterServices: T.laterServices,
    parseShift: T.parseShift,
    renumberTimetable: T.renumberTimetable,
    renumberLinksInto: T.renumberLinksInto,
    servicesToAdd: (start: string, end: string, interval: string) => T.servicesToAdd(get().v.data, start, end, interval),

    changeTime(s: any, stop: number, kind: string, time: number, toLater: boolean) {
      const times = S.timesOf(s)
      // Shift+Enter: the same change for this service and every later one of its table
      const services = toLater ? [s, ...T.laterServices(s)] : [s]
      editTimetable(s.route, T.changeTimeEdit(s, times, stop, kind, time, services))
      if (stop === 0) T.selectMoved(get().v.state, get().v.data.routeById.get(s.route.id), s, time - times[0][1])
    },
    moveServices(ids: string[], shift: number) {
      const { fn, v } = get()
      const state = v.state, data = v.data
      const services = ids.map(find).filter(Boolean)
      if (!services.length || !shift) return
      const wasSelected = state.selected && ids.includes(state.selected) ? state.selected : null
      // Only those moved get their new times: a line dragged on its own leaves the others chosen as they are
      const movedIds = new Set(ids)
      const moved = (id: string) => {
        if (!movedIds.has(id)) return id
        const [routeId, service] = id.split("@")
        return routeId + "@" + mod(Number(service) + shift, DAY)
      }
      let failed = false
      editTimetable(services[0].route, T.moveServicesEdit(services[0].tableIndex, services.map((o: any) => o.service), shift, at => clock(data, mod(at - data.dayStartMillis, DAY)), () => { failed = true }))
      // The moved services keep being chosen, under their new times
      if (!failed) {
        if (state.range) state.range = { anchor: moved(state.range.anchor), ids: state.range.ids.map(moved) }
        if (wasSelected) state.selected = moved(wasSelected)
        fn.renderAll()
      }
    },
    shiftLaterDialog(s: any) {
      const later = T.laterServices(s)
      const shift = T.parseShift(prompt(tr("这一班和之后的 {0} 班平移多少？写分钟，或写秒：90s、1:30、-0:45；负数是提前", later.length), "5"))
      if (!shift) return
      editTimetable(s.route, T.shiftLaterEdit(s, shift))
    },
    respaceRange() {
      const state = get().v.state
      const run = T.rangeServices(state, find)
      if (run.length < 2) return
      const minutes = Number(prompt(tr("这 {0} 班相邻两班之间隔多少分钟？", run.length), String(Math.round((run[1].start - run[0].start) / 60000) || 5)))
      if (!(minutes > 0)) return
      editTimetable(run[0].route, T.respaceEdit(run, minutes, () => { state.range = null }))
    },
    setLink: (s: any, fromStop: number, target: any, toStop: number) => editTimetable(s.route, T.setLinkEdit(s, fromStop, target, toStop)),
    clearTable(r: any, index: number) {
      const { v } = get()
      const table = T.timetableOf(v.draft, r).tables[index]
      if (!table || !table.services.length) return
      if (!confirm(tr("清空“{0}”的 {1} 个班次？改动先放进草稿，应用前都可以放弃。", tableName(table, index), table.services.length))) return
      v.state.range = null
      editTimetable(r, T.clearTableEdit(index, tableName))
    },

    inRange: (s: any) => T.inRange(get().v.state, s),
    rangeServices: () => T.rangeServices(get().v.state, find),
    chooseRange: (s: any) => T.chooseRange(get().v.state, s, find),
    toggleChosen: (s: any, span: boolean) => T.toggleChosen(get().v.state, s, span, find),
    chooseLater(s: any) {
      const { fn, v } = get()
      const count = T.chooseLater(v.state, s)
      v.tableMessage = tr("选中 {0} 班，右键表头可以调整间隔", count)
      fn.renderAll()
    },
    servicesToMove: (s: any) => T.servicesToMove(get().v.state, s, Boolean(get().v.data.canEdit), find),
  }
}

/** Step 5: the duty rules (core/duties.ts) */
export function dutyRules(get: () => Legacy) {
  const at = () => { const { v } = get(); return { data: v.data, draft: v.draft, weekday: v.state.weekday } }
  // Worked out once until the data, the draft or the day changes: the old page's own coverage and terminal, which its
  // setData and saveDraft clear
  const coverageNow = (): D.Coverage => {
    const { v } = get()
    if (v.coverage && v.coverage.weekday === v.state.weekday) return v.coverage
    const { data, draft, weekday } = at()
    return (v.coverage = D.coverage(data, draft, weekday))
  }
  const terminalNow = () => {
    const { v } = get()
    if (v.terminal && v.terminal.weekday === v.state.weekday) return v.terminal
    const { data, draft, weekday } = at()
    return (v.terminal = D.terminalPairs(data, draft, weekday))
  }
  return {
    canonDuties: D.canonDuties,
    dutiesOf: (depot: any) => D.dutiesOf(get().v.draft, depot),
    routeIsLoop: D.routeIsLoop,
    dutyOn: D.dutyOn,
    segmentKey: D.segmentKey,
    startOf: D.startOf,
    newDutyId: D.newDutyId,
    dutyIdOf: D.dutyIdOf,
    findDutySlot: D.findDutySlot,
    dutyNumber: (depot: any, sidingId: string, index: number) => D.dutyNumber(get().v.draft, depot, sidingId, index),
    relinkOvernight: D.relinkOvernight,
    poolSidings: D.poolSidings,
    editDuties(depot: any, change: (duties: any) => unknown) {
      const { fn, v } = get()
      v.dutyNotice = D.applyDutyEdit(v.data, v.draft, depot, change)
      fn.saveDraft()
      fn.renderAll()
    },
    dayGroup: (depot: any, weekday: number) => D.dayGroup(get().v.data, get().v.draft, depot, weekday),
    dutyForEditing: (depot: any, slot: any[], weekday: number) => D.dutyForEditing(get().v.data, get().v.draft, depot, slot, weekday),
    segmentService: (segment: any) => D.segmentService(get().v.data, segment),
    lastStopOf: D.lastStopOf,
    segmentStart: (segment: any) => D.segmentStart(get().v.data, segment),
    segmentEnd: (segment: any) => D.segmentEnd(get().v.data, segment),
    coverageNow,
    hasNoTrain: (s: any) => coverageNow().noTrain.has(s.id),
    terminalNow,
    dutyAssignments: () => { const { data, draft, weekday } = at(); return D.dutyAssignments(data, draft, weekday) },
    dutyProblems: (depot: any, sidingId: string, index: number, weekday: number) => D.dutyProblems(get().v.data, get().v.draft, depot, sidingId, index, weekday),
    nextSlotOf: D.nextSlotOf,
    dutyEndPlatform: (day: any) => D.dutyEndPlatform(get().v.data, day),
    dutyStartPlatform: (day: any) => D.dutyStartPlatform(get().v.data, day),
    minReverseMillis: () => D.minReverseMillis(get().v.data, get().v.draft),
    turnTimeOf: D.turnTimeOf,
    stopLabel: D.stopLabel,
    stopHangers: (r: any, stop: number) => D.stopHangers(get().v.data, get().v.draft, r, stop),
    renumberDuties: (duties: any, routeId: string, newOf: number[]) => D.renumberDuties(duties, routeId, newOf),
    /** Sends a route's new stops to the game, renumbering the same in the draft @return why it can't, or nothing */
    sendStopList(r: any, list: D.StopListItem[]) {
      const { fn, v } = get()
      const change = D.stopListChange(v.data, v.draft, r, list)
      if (typeof change === "string") return change
      fn.saveDraft()
      ;(async () => {
        try {
          const result = await fn.sendJson("api/apply", "POST", { operations: change.operations })
          const errors = (result.errors || []).map((error: any) => error.message).filter(Boolean)
          get().v.dutyNotice = errors.length ? tr("停站没改成：{0}", errors.join(tr("；"))) : tr("“{0}”的停站已发给游戏，{1} 站。车厂路径刷新后各站时刻会更新。", routeTitle(r), list.length)
        } catch {
          get().v.dutyNotice = tr("发送失败，游戏可能已经关闭")
        }
        fn.renderBanner()
        get().v.revision = null
        fn.poll(true)
      })()
      return undefined
    },
  }
}
