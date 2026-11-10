/**
 * Dispatch and simulation of the old editor.js (retiring the old code, step 7): the simulated day as the server runs it
 * with the draft (asking, waiting for the answer, what went wrong, how a depot's duties ran), the dispatcher's console
 * (the trains of a rail system, what is going on with each, sending commands) and how the trains of a loop go round. The
 * state lives in the objects given (the page's sim and console state); asking the game and drawing are the caller's.
 */
import { tr, trc } from "../i18n"
import { DAY, formatDuration, mod, shortName } from "./format"
import { timetableOf } from "./timetable"

type Data = any

// ---------- Simulated day ----------
export const SIM_LATE_MILLIS = 30000, SIM_MAX_LISTED = 300
export const SIM_KINDS: Record<string, string> = { LATE: "晚点", WAITING_FOR_PLATFORM: "等站台", HELD_ON_LINE: "区间扣停", LONG_DWELL: "站内久停", DEADLOCK: "锁死", NOT_RUN: "未开行" }
/** The weekday of the operating day the game's day numbers start at */
export const FIRST_DAY_WEEKDAY = 3

/** The page's simulated day: what was asked, the answer, and what is shown of it */
export type Sim = {
  on: boolean; request: number; state: string; result: any; trips: Map<string, any>; notRun: Set<string>; listed: any[]; madeAt: number
  line: string | null; weekday: number | null; timer: ReturnType<typeof setTimeout> | null; polling: boolean; notSimulated: number; ignored: number
  missingPaths: number; scopeDepots: number; allDepots: number; checkDepot: string | null; fromNow: boolean
}
export const newSim = (): Sim => ({ on: false, request: 0, state: "idle", result: null, trips: new Map(), notRun: new Set(), listed: [], madeAt: 0, line: null, weekday: null, timer: null, polling: false, notSimulated: 0, ignored: 0, missingPaths: 0, scopeDepots: 0, allDepots: 0, checkDepot: null, fromNow: false })
/** What is cleared when the simulation is turned off */
export const clearSim = (sim: Sim) => Object.assign(sim, { state: "idle", result: null, trips: new Map(), notRun: new Set(), listed: [], checkDepot: null })

/** The operating day number of now */
export const nowDay = (data: Data) => (data ? Math.floor((Date.now() - data.dayStartMillis) / DAY) : Math.floor(Date.now() / DAY))
/** Only today can carry on from the trains as they are in the game now (G0) */
export const simFromNow = (sim: Sim, data: Data, weekday: number) => sim.fromNow && weekday === mod(nowDay(data) + FIRST_DAY_WEEKDAY, 7)

/** The routes simulated: the line's, and those of the depot whose duties are checked */
export function simRoutes(line: { routes: { id: string }[] } | null | undefined, checkDepot: { routes: string[] } | null | undefined) {
  const routes = new Set(line ? line.routes.map(r => r.id) : [])
  if (checkDepot) checkDepot.routes.forEach(id => routes.add(id))
  return [...routes]
}

export type SimIO = {
  send: (body: any) => Promise<any>
  fetch: () => Promise<any>
  wait: (millis: number) => Promise<void>
}

/** Asks the server to simulate the day; the state is "running" until {@link pollSimulation} has the answer */
export async function startSimulation(sim: Sim, io: SimIO, ask: { line: string | null; weekday: number; fromNow: boolean; routes: string[]; operations: any[] }) {
  sim.line = ask.line
  sim.weekday = ask.weekday
  try {
    const response = await io.send({ weekday: ask.weekday, fromNow: ask.fromNow, routes: ask.routes, operations: ask.operations })
    if (response.error) sim.state = response.error
    else {
      sim.request = response.request
      sim.state = "running"
    }
  } catch {
    sim.state = "error"
  }
}

/** Waits for the server's answer while the simulation is running, half a second at a time */
export async function pollSimulation(sim: Sim, io: SimIO) {
  if (sim.polling) return false
  sim.polling = true
  try {
    while (sim.on && sim.state === "running") {
      await io.wait(500)
      const status = await io.fetch()
      // Another page asked since, or this request's answer hasn't come yet
      if (status.request > sim.request) sim.state = "idle"
      if (status.request !== sim.request || status.state === "running") continue
      Object.assign(sim, { state: status.state, notSimulated: status.notSimulated, ignored: status.ignored, missingPaths: status.missingPaths || 0, scopeDepots: status.scopeDepots || 0, allDepots: status.allDepots || 0 })
      if (status.state === "done" && status.result) setSimResult(sim, status.result)
    }
  } catch {
    sim.state = "error"
  }
  sim.polling = false
  return true
}

export function setSimResult(sim: Sim, result: any, now = Date.now()) {
  sim.result = result
  sim.madeAt = now
  sim.trips = new Map(result.trips.map((trip: any) => [trip.route + "@" + trip.service, trip]))
  sim.notRun = new Set(result.problems.filter((problem: any) => problem.kind === "NOT_RUN").map((problem: any) => problem.route + "@" + problem.service))
}

/** The simulation's state in a few words, or "" */
export function simStatusText(sim: Sim) {
  if (!sim.on) return ""
  const texts: Record<string, string> = {
    running: tr("模拟中…"),
    done: tr("按 {0} 的草稿模拟", new Date(sim.madeAt).toTimeString().slice(0, 5)),
    busy: sim.allDepots ? tr("一分钟内没有模拟完（{0} / {1} 个车厂），稍后再试", sim.scopeDepots, sim.allDepots) : tr("服务端忙，稍后再试"),
    permission: tr("没有权限模拟"),
    error: tr("模拟失败"),
    disabled: tr("服务端没有开启模拟（调试功能）"),
  }
  return texts[sim.state] || ""
}

/** A stop as [stop, arrival, departure, planned arrival, planned departure, passed], times in the operating day or -1: how late */
export const simLate = (stop: number[]) => Math.max(stop[1] >= 0 && stop[3] >= 0 ? stop[1] - stop[3] : 0, stop[2] >= 0 && stop[4] >= 0 ? stop[2] - stop[4] : 0)

/** How the depot's generated duties ran in the simulated day, in a line, or nothing if they aren't being checked */
export function dutyCheckText(sim: Sim, depot: any) {
  if (!sim.on || !depot || sim.checkDepot !== depot.id) return ""
  if (sim.state === "running") return tr("内核验证中…")
  if (!sim.result) return ""
  const routes = new Set(depot.routes)
  const count = (kind: string) => sim.result.problems.filter((problem: any) => problem.kind === kind && routes.has(problem.route)).length
  const parts: string[] = []
  if (sim.missingPaths) parts.push(tr("{0} 种运用找不到路径", sim.missingPaths))
  if (count("NOT_RUN")) parts.push(tr("未开行 {0} 班", count("NOT_RUN")))
  if (count("LATE")) parts.push(tr("晚点 {0} 班", count("LATE")))
  const deadlocks = sim.result.problems.filter((problem: any) => problem.kind === "DEADLOCK").length
  if (deadlocks) parts.push(tr("锁死 {0} 次", deadlocks))
  return parts.length ? tr("内核验证：{0}，见右侧“模拟问题”。", parts.join(trc("list separator", "、"))) : tr("内核验证：全部开行、准点。")
}

/** What a service did in the simulated day, in a line, or "" */
export function simServiceText(sim: Sim, s: any) {
  if (!sim.on || !sim.result) return ""
  const key = s.route.id + "@" + s.service, trip = sim.trips.get(key)
  if (!trip) return sim.notRun.has(key) ? tr("模拟：没有车跑这一班") : ""
  const late = trip.stops.reduce((most: number, stop: number[]) => Math.max(most, simLate(stop)), 0)
  return late > SIM_LATE_MILLIS ? tr("模拟：最多晚 {0}", formatDuration(late)) : tr("模拟：准点")
}

/**
 * What went wrong on the line in the simulated day (and on the routes of the depot checked; deadlocks wherever they
 * are), with the routes they are on, and the notes on what was not simulated
 */
export function simProblems(sim: Sim, data: Data, line: { routes: any[] } | null | undefined, checkDepot: { routes: string[] } | null | undefined) {
  const routes = new Map<string, any>(line ? line.routes.map(r => [r.id, r]) : [])
  if (checkDepot) checkDepot.routes.forEach(id => { if (!routes.has(id) && data.routeById.get(id)) routes.set(id, data.routeById.get(id)) })
  const problems: any[] = sim.result ? sim.result.problems.filter((problem: any) => problem.kind === "DEADLOCK" || routes.has(problem.route)) : []
  const notes: string[] = []
  if (sim.notSimulated) notes.push(tr("改站序、新建或删除种别的修改没有模拟（{0} 项）", sim.notSimulated))
  if (sim.ignored) notes.push(tr("{0} 项草稿和服务端的线路对不上，没有用上", sim.ignored))
  if (sim.missingPaths) notes.push(tr("{0} 种运用在轨道上找不到路径，这些车不会发车", sim.missingPaths))
  if (sim.result && sim.allDepots && sim.scopeDepots < sim.allDepots) notes.push(tr("只模拟了有关的 {0} / {1} 个车厂：跑这些线路的，和车经过同样轨道的；其余的车没有模拟", sim.scopeDepots, sim.allDepots))
  return { routes, problems, notes }
}

// ---------- Dispatcher's console ----------
export type ConsoleState = { open: boolean; data: any; timer: ReturnType<typeof setInterval> | null; system: string; selected: string }
export const CONSOLE_POLL_MS = 2000

/** The trains of the rail system chosen, or all */
export function consoleTrains(data: Data, consoleState: ConsoleState) {
  const trains = (consoleState.data && consoleState.data.trains) || []
  const system = consoleState.system ? (data.contingencySystems || []).find((each: any) => each.anchor === consoleState.system) || null : null
  return system ? trains.filter((train: any) => system.routes.includes(train.route)) : trains
}

export const consoleTrainName = (train: any) => (train.slot < 0 ? tr("{0} 的车", shortName(train.sidingName) || tr("存车线")) : tr("{0} 车 {1}", shortName(train.sidingName) || tr("存车线"), train.slot + 1))

/** What is going on with a train, as the console lists it */
export function consoleTrainState(train: any) {
  const parts: string[] = []
  // Out on the line with no duty, or a duty no longer there: it takes track but runs no service
  if (train.noDuty) parts.push(tr("不在运用中（没有运用，或者运用已经改了）"))
  if (train.late >= 60) parts.push(tr("晚点 {0} 分钟", Math.floor(train.late / 60)))
  if (train.waiting >= 60) parts.push(tr("等进路已 {0} 分钟", Math.floor(train.waiting / 60)))
  if (train.dispatch & 1) parts.push(tr("扣停"))
  if (train.dispatch & 8) parts.push(tr("延长停站"))
  if (train.dispatch & 16) parts.push(tr("将在指定站折返"))
  if (train.dispatch & 2) parts.push(tr("下一站清客"))
  if (train.dispatch & 4) parts.push(tr("本段跑完回库"))
  if (train.skipping) parts.push(tr("跳站中"))
  if (train.turn === 2) parts.push(tr("已提前折返"))
  if (train.takeover === 2) parts.push(tr("已交给备车"))
  if (train.outOfService) parts.push(tr("不载客"))
  return parts.join("，")
}

/** A dispatch command as the game takes it */
export const dispatchOperation = (operation: Record<string, unknown>) => ({ operations: [Object.assign({ type: "dispatch" }, operation)] })

// ---------- Loops ----------
export const MAX_LAYOVER_MILLIS = 30 * 60000, MAX_STAY_MILLIS = 8 * DAY
/** Ending at the platform it starts from */
export const isLoop = (r: any) => r.stops.length > 2 && r.stops[0].platformId === r.stops[r.stops.length - 1].platformId
export const loopWaitMillis = (data: Data, r: any) => data.depots.some((depot: any) => depot.cycleRoutes && depot.routes.includes(r.id)) ? MAX_STAY_MILLIS : MAX_LAYOVER_MILLIS

/** The stop where trains from the depot join a loop, which is its first stop unless another is set */
export function loopEntryStop(draft: any, r: any) {
  const timetable = timetableOf(draft, r)
  const platformId = timetable.entryPlatform
  const stop = r.stops.findIndex((s: any, i: number) => i > 0 && i < r.stops.length - 1 && s.platformId === platformId)
  return stop > 0 && !timetable.pass[stop] ? stop : 0
}

/**
 * List the same valid continuations as the running diagram, grouped into each train's laps.
 */
export function loopRuns(data: Data, r: any, pairs: Map<string, any>) {
  const services: any[] = r.services.filter((s: any) => !s.suspended)
  const byId = new Map<string, any>(services.map((s: any) => [s.id, s]))
  const next = new Map<string, any>(), taken = new Set<string>()
  byId.forEach((s, id) => {
    const pairing = pairs.get(id)
    const following = pairing?.loop && !pairing.overlap && byId.get(pairing.to.id)
    if (!following) return
    taken.add(following.id)
    next.set(s.id, following)
  })
  const trains = services.filter(s => !taken.has(s.id)).map(first => {
    const laps = [first]
    while (next.has(laps[laps.length - 1].id)) laps.push(next.get(laps[laps.length - 1].id))
    return laps
  })
  return { next, trains, maxWait: loopWaitMillis(data, r) }
}
