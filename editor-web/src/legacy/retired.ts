/**
 * The old code's functions now made by the new page's modules (retiring the old code): given to the legacy engine through
 * gen-legacy's FROM_ENV, so the old functions still there, and the new page's own calls (legacy.fn falls back to these),
 * all run the new rules. Each keeps what the old one did after the rule itself: saving the draft, making the services
 * again, drawing, the message under the table, and (until step 8 replaces them) the old prompts.
 */
import { tr } from "../i18n"
import * as T from "../core/timetable"
import * as S from "../core/services"
import { clock, DAY, mod, routeTitle, shortName } from "../core/format"
import * as X from "../core/settings"
import * as P from "../core/dispatch"
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
    setLink: (s: any, fromStop: number, target: any, toStop: number) => editTimetable(s.route, T.setLinkEdit(s, fromStop, target, toStop)),

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

/** Step 6: the network settings (core/settings.ts); a rail's section is the old real map's (sectionOf, netRailKey) */
export function settingsRules(get: () => Legacy) {
  const keysOf = (index: number) => { const { v } = get(); return { key: v.netRailKey(index), section: v.sectionOf(index).map(v.netRailKey) as string[] } }
  const done = (network = false) => {
    const { fn } = get()
    fn.saveDraft()
    fn.renderAll()
    if (network) { fn.renderNetworkTools(); fn.drawNetwork() }
  }
  /** A menu of a platform setting's choices, ticked where it is now */
  const platformItems = (part: X.PlatformPart, choices: [number | string, string][]) => (platformId: string) => {
    const { v } = get()
    const p = v.platformById(platformId)
    if (!p || !v.data.canEdit) return null
    const current = X.platformSetting(v.draft, part, p)
    return choices.map(([value, label]) => [`${current === value ? "✓ " : ""}${label}`, () => { X.setPlatformSetting(get().v.draft, part, p, value); done() }])
  }
  return {
    draftSettings: () => X.draftSettings(get().v.draft),
    savedSettings: () => X.savedSettings(get().v.data),
    compareRailKeys: X.compareRailKeys,
    logicalLinesShown: () => X.logicalLinesShown(get().v.data, get().v.draft),
    usesLogicalLines: () => X.usesLogicalLines(get().v.data, get().v.draft),
    railLinesShown: () => X.railLinesShown(get().v.data, get().v.draft),
    railLogicalLine: (index: number) => X.sectionLogicalLine(get().v.data, get().v.draft, keysOf(index).section),
    railLogicalDrafted: (index: number) => X.sectionLogicalDrafted(get().v.draft, keysOf(index).section),
    draftSettingsPart(part: string, change: (drafted: Record<string, any>, saved: Record<string, any>) => void) {
      X.setSettingsPart(get().v.data, get().v.draft, part, change)
      done(true)
    },
    setRailLogicalLine(index: number, id: string | null) {
      const { key, section } = keysOf(index)
      X.setSectionLogicalLine(get().v.data, get().v.draft, key, section, id)
      done(true)
    },
    newLogicalId: X.newLogicalId,
    draftOwnerOverrides: () => X.draftOwnerOverrides(get().v.draft),
    savedOwnerOverrides: () => X.savedOwnerOverrides(get().v.data),
    railOwnersShown: (index: number) => { const { v } = get(); return X.sectionOwnersShown(v.data, v.draft, keysOf(index).section, v.net.ownerOf && v.net.ownerOf.get(index)) },
    setOwnerOverride(index: number, lines: string[] | null) {
      const { key, section } = keysOf(index)
      X.setSectionOwners(get().v.data, get().v.draft, key, section, lines)
      done()
      get().fn.drawNetwork()
    },
    waitThresholdMillis: () => X.waitThresholdMillis(get().v.data, get().v.draft),
    platformSpeedItems: platformItems("platformSpeeds", X.PASS_SPEEDS.map(speed => [speed, `${speed} km/h`])),
    platformStopItems: (platformId: string) => platformItems("platformStops", X.STOP_POSITIONS.map(position => [position, get().fn.stopPositionText(position)]))(platformId),
    platformStationItems(platformId: string) {
      const { v, fn } = get()
      const p = v.platformById(platformId)
      if (!p || !v.data.canEdit) return null
      const current = X.platformSetting(v.draft, "platformStations", p)
      const stations = v.topo ? v.topo.stations : []
      // Automatic, by the areas and the nearest platform, or any of the nearest stations, wherever the platform is
      const near = stations.slice().sort((a: any, b: any) => Math.hypot(a.x - p.x, a.z - p.z) - Math.hypot(b.x - p.x, b.z - p.z)).slice(0, 8)
      if (current !== "0" && !near.some((station: any) => station.id === current)) {
        const set = stations.find((station: any) => station.id === current)
        if (set) near.push(set)
      }
      const choose = (stationId: string) => () => { X.setPlatformSetting(get().v.draft, "platformStations", p, stationId); done() }
      return [[`${current === "0" ? "✓ " : ""}${fn.stationNameOf("0")}`, choose("0")], ...near.map((station: any) => [`${current === station.id ? "✓ " : ""}${shortName(station.name) || tr("（未命名车站）")}`, choose(station.id)])]
    },
    rulesOf: (depot: any) => X.rulesOf(get().v.draft, depot),
    sameVehicle: X.sameVehicle,
    depotEntryOf: (depot: any) => X.depotEntryOf(get().v.draft, depot),
    storeDepotEntry(depot: any, entry: any, sameAdded?: boolean) {
      X.storeDepotEntry(get().v.draft, depot, entry, sameAdded)
      done()
    },
    setBoundary(a: string, b: string, boundary: boolean, wasBoundary: boolean) {
      const { v } = get()
      const key = v.railKey(a, b)
      X.setBoundary(v.draft, key, boundary, wasBoundary)
      delete v.boundaryErrors[key]
      done()
    },
  }
}

/** Step 7: dispatch and simulation (core/dispatch.ts); the state stays the old page's sim and consoleState objects */
export function dispatchRules(get: () => Legacy) {
  const io: P.SimIO = {
    send: body => get().fn.sendJson("api/simulate", "POST", body),
    fetch: () => get().fn.fetchJson("api/simulate"),
    wait: millis => new Promise(resolve => setTimeout(resolve, millis)),
  }
  const simulate = async () => {
    const { fn, v } = get()
    const sim: P.Sim = v.sim
    if (sim.timer) clearTimeout(sim.timer)
    sim.timer = null
    const line = fn.currentLine(), checkDepot = sim.checkDepot && fn.depotById(sim.checkDepot)
    if (!sim.on || (!line && !checkDepot)) return
    await P.startSimulation(sim, io, { line: v.state.line, weekday: v.state.weekday, fromNow: P.simFromNow(sim, v.data, v.state.weekday), routes: P.simRoutes(line, checkDepot), operations: fn.draftOperations().operations })
    fn.renderSimStatus()
    pollSimulation()
  }
  const pollSimulation = async () => {
    const { v } = get()
    if (!(await P.pollSimulation(v.sim, io))) return
    const { fn } = get()
    fn.renderSimStatus()
    fn.draw()
    fn.renderInspector()
    fn.renderBanner()
  }
  /** Simulates again a moment after the draft, day or line changes, so a run of changes is only simulated once */
  const scheduleSimulation = () => {
    const sim: P.Sim = get().v.sim
    if (!sim.on) return
    if (sim.timer) clearTimeout(sim.timer)
    sim.timer = setTimeout(simulate, 2000)
  }
  const loadConsole = async () => {
    const { fn, v } = get()
    try {
      v.consoleState.data = await fn.fetchJson("api/console")
    } catch {
      v.consoleState.data = null
    }
    fn.renderConsole()
    fn.drawNetwork()
  }
  return {
    setSimulation(on: boolean) {
      const { fn, v } = get()
      const sim: P.Sim = v.sim
      sim.on = on
      if (sim.timer) clearTimeout(sim.timer)
      sim.timer = null
      if (on) simulate()
      else P.clearSim(sim)
      fn.renderSimStatus()
      fn.draw()
      fn.renderInspector()
    },
    scheduleSimulation,
    simulate,
    pollSimulation,
    setSimResult: (result: any) => P.setSimResult(get().v.sim, result),
    simFromNow: () => P.simFromNow(get().v.sim, get().v.data, get().v.state.weekday),
    simLate: P.simLate,
    simTooltip: (s: any) => { const sim: P.Sim = get().v.sim; return !sim.on || !sim.result ? "" : `<div class="hint">${P.simServiceText(sim, s)}</div>` },
    dutyCheckText: (depot: any) => P.dutyCheckText(get().v.sim, depot),
    loadConsole,
    toggleConsole(open: boolean) {
      const { fn, v } = get()
      const consoleState: P.ConsoleState = v.consoleState
      consoleState.open = open
      document.getElementById("net-console-toggle")?.setAttribute("aria-pressed", String(open))
      if (open && !consoleState.timer) {
        loadConsole()
        consoleState.timer = setInterval(() => { const { v } = get(); if (!document.hidden && v.state.view === "network" && v.inTimetable()) loadConsole() }, P.CONSOLE_POLL_MS)
      } else if (!open && consoleState.timer) {
        clearInterval(consoleState.timer)
        consoleState.timer = null
      }
      fn.renderConsole()
      fn.drawNetwork()
    },
    consoleTrains: () => P.consoleTrains(get().v.data, get().v.consoleState),
    consoleTrainName: P.consoleTrainName,
    consoleTrainState: P.consoleTrainState,
    async consoleCommand(button: HTMLButtonElement | null, operation: Record<string, unknown>) {
      if (button) button.disabled = true
      try {
        await get().fn.sendJson("api/apply", "POST", P.dispatchOperation(operation))
        if (button) button.textContent = tr("已发送")
        setTimeout(loadConsole, 600)
        return true
      } catch {
        if (button) button.disabled = false
        return false
      }
    },
    isLoop: P.isLoop,
    loopEntryStop: (r: any) => P.loopEntryStop(get().v.draft, r),
    loopRuns: (r: any) => P.loopRuns(get().v.data, r, get().fn.terminalNow().next),
  }
}
