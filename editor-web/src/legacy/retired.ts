/**
 * The old code's functions now made by the new page's modules (retiring the old code): given to the legacy engine through
 * gen-legacy's FROM_ENV, so the old functions still there, and the new page's own calls (legacy.fn falls back to these),
 * all run the new rules. Each keeps what the old one did after the rule itself: saving the draft, making the services
 * again, drawing, the message under the table, and (until step 8 replaces them) the old prompts.
 */
import { tr } from "../i18n"
import * as T from "../core/timetable"
import * as S from "../core/services"
import { clock, DAY, mod, tableName } from "../core/format"
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
