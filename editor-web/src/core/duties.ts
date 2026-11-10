/**
 * The duty rules (运用) of the old editor.js (retiring the old code, step 5): finding and numbering duties, editing a depot's
 * duties in the draft, the day groups, the segments' services and times, which services have a train on a day
 * (coverage), the services paired at their routes' ends (terminal pairs), what is wrong with a duty, what keeps a stop
 * of a route from being taken away, and the duties and draft renumbered to a route's new stops. The functions take the
 * data, draft and weekday they read; caching and drawing stay with the caller.
 */
import { tr } from "../i18n"
import { clock, DAY, formatDuration, mod, orderDepotName, routeTitle, shortName, weekdayName } from "./format"
import { servedStops } from "./diagram"
import { timesOf } from "./services"
import { dutiesOf, stopLabel, turnTimeOf } from "./depots"
import { isLoop, loopWaitMillis } from "./dispatch"
import { gameTimetable, renumberLinksInto, renumberTimetable, timetableOf } from "./timetable"

export { dutiesOf, stopLabel, turnTimeOf }

/** TerminalPairs (terminal.js), a script of the page */
const pairs = () => (globalThis as any).TerminalPairs

type Data = any
type Draft = any
type Slot = any[]

const sortedObject = (object: Record<string, unknown>) => Object.fromEntries(Object.keys(object).sort().map(k => [k, object[k]]))
/** A depot's duties in a fixed order, so a draft changed back to the game's matches it */
export const canonDuties = (duties: any) => JSON.stringify({ mode: duties.mode, sidings: sortedObject(Object.fromEntries(Object.entries(duties.sidings).filter(([, slots]: any) => slots.length))) })
/** A loop: marked clockwise or anticlockwise, or ending at the platform it starts from */
export const routeIsLoop = (r: any) => r.stops.length > 2 && (Boolean(r.circular) || r.stops[0].platformId === r.stops[r.stops.length - 1].platformId)
export const dutyOn = (slot: Slot, weekday: number) => slot.find(day => (day.days >> weekday) & 1) || null
export const segmentKey = (segment: { route: string; service: number }) => segment.route + "@" + segment.service
/** Where a duty's train comes from (S8), the same on each of its days: "prefer", "only" or "any" */
export const startOf = (slot: Slot) => (slot.find(day => day.start) || {}).start || "prefer"
/** A new duty number, as a decimal string of a long like the game's */
export const newDutyId = () => String(1e15 + Math.floor(Math.random() * 8e15))
/** A slot's own number, from any of its days, or null if it has none yet */
export const dutyIdOf = (slot: Slot) => (slot.find(day => day.duty && day.duty !== "0") || {}).duty || null

/** The slot with a duty number among a depot's duties: its siding, index and the slot, or null */
export function findDutySlot(duties: any, id: string | null | undefined) {
  if (!id || id === "0") return null
  for (const [siding, slots] of Object.entries(duties.sidings) as [string, Slot[]][]) {
    const index = slots.findIndex(slot => dutyIdOf(slot) === id)
    if (index >= 0) return { siding, index, slot: slots[index] }
  }
  return null
}

/** A duty's number as the player sees it: on through its pool, siding after siding in the pool's order */
export function dutyNumber(draft: Draft, depot: any, sidingId: string, index: number) {
  if (!depot) return index + 1
  const pool = (depot.pools || []).find((each: any) => (each.sidings || []).some((id: unknown) => String(id) === String(sidingId)))
  const duties = dutiesOf(draft, depot)
  let before = 0
  for (const id of pool ? pool.sidings : [sidingId]) {
    if (String(id) === String(sidingId)) break
    before += (duties.sidings[id] || []).length
  }
  return before + index + 1
}

/**
 * Marks the days a train staying overnight from another siding's duty takes over (fromOvernight), so no train leaves the
 * siding for them and they start on the line, as the game reads them
 */
export function relinkOvernight(duties: any) {
  const all = (Object.values(duties.sidings) as Slot[][]).flatMap(slots => slots.flatMap(slot => slot))
  all.forEach(day => { day.fromOvernight = false })
  all.filter(day => day.stay && day.nextDuty && day.nextDuty !== "0").forEach(day => {
    const nextDays = ((day.days << 1) | (day.days >> 6)) & 0x7f
    all.filter(other => other.duty === day.nextDuty && other.days & nextDays).forEach(other => { other.fromOvernight = true })
  })
}

/** The sidings of a depot a siding's trains can go back to, or take duties of: the same vehicle */
export const poolSidings = (depot: any, sidingId: string) => {
  const own = depot.sidings.find((siding: any) => siding.id === sidingId)
  return depot.sidings.filter((siding: any) => !own || siding.vehicle === own.vehicle)
}

/** The service a segment runs on the day the services are built for, or null when it doesn't run that day */
export const segmentService = (data: Data, segment: any) => {
  const r = data.routeById.get(segment.route)
  return r && r.services ? r.services.find((s: any) => s.service === segment.service) || null : null
}
export const lastStopOf = (segment: any, r: any) => (segment.to < 0 ? r.stops.length - 1 : segment.to)
export function segmentStart(data: Data, segment: any) {
  const s = segmentService(data, segment)
  return s ? timesOf(s)[Math.min(segment.from, s.plans.length - 1)][0] : Infinity
}
export function segmentEnd(data: Data, segment: any) {
  const s = segmentService(data, segment)
  return s ? timesOf(s)[lastStopOf(segment, s.route)][1] : -Infinity
}

/**
 * Changes a depot's duties in the draft: the segments in time order, the overnight links again, and the draft dropped
 * when it ends up the same as the game's
 *
 * @return the message the change gives, or ""
 */
export function applyDutyEdit(data: Data, draft: Draft, depot: any, change: (duties: any) => unknown): string {
  if (!draft.duties[depot.id]) {
    const start = JSON.parse(JSON.stringify(dutiesOf(draft, depot)))
    delete start.auto
    draft.duties[depot.id] = { duties: start }
  }
  const entry = draft.duties[depot.id]
  const message = change(entry.duties)
  ;(Object.values(entry.duties.sidings) as Slot[][]).forEach(slots => slots.forEach(slot => slot.forEach(day => day.segments.sort((a: any, b: any) => segmentStart(data, a) - segmentStart(data, b)))))
  relinkOvernight(entry.duties)
  delete entry.error
  if (canonDuties(entry.duties) === canonDuties(depot.duties || { mode: "online", sidings: {} })) delete draft.duties[depot.id]
  return typeof message === "string" ? message : ""
}

/**
 * The weekdays that run the same tables of a depot's routes as the given one (运行日类型). Editing a train's duty on a
 * day changes it for all of them
 */
export function dayGroup(data: Data, draft: Draft, depot: any, weekday: number) {
  const signature = (day: number) => depot.routes.map((id: string) => {
    const r = data.routeById.get(id)
    return r ? timetableOf(draft, r).tables.findIndex((table: any) => (table.weekdays >> day) & 1) : -2
  }).join(",")
  const own = signature(weekday)
  let mask = 0
  for (let day = 0; day < 7; day++) if (signature(day) === own) mask |= 1 << day
  return mask
}

/** A slot's duty for the day group of a weekday, split off from the days it shared with others first */
export function dutyForEditing(data: Data, draft: Draft, depot: any, slot: Slot, weekday: number) {
  const group = dayGroup(data, draft, depot, weekday), current = dutyOn(slot, weekday)
  if (current && current.days === group) return current
  const made: any = {
    days: group, stay: current ? current.stay : false, next: current && current.next !== undefined ? current.next : -1, segments: current ? current.segments.map((segment: any) => ({ ...segment })) : [],
    // The duty's own number, the same on all its days (P2-D), and where it goes on to and back to
    duty: dutyIdOf(slot) || newDutyId(), nextDuty: (current && current.nextDuty) || "0", returnSiding: (current && current.returnSiding) || "0", fromOvernight: false, start: startOf(slot),
  }
  // Where it waits, when it leaves, its deadhead paths and its own exceptions, as the day it was split from
  if (current) ["waits", "outLead", "outEntrance", "backEntrance", "outPath", "backPath", "exceptions"].forEach(key => { if (current[key] !== undefined) made[key] = JSON.parse(JSON.stringify(current[key])) })
  slot.forEach(day => { day.days &= ~group })
  for (let i = slot.length - 1; i >= 0; i--) if (!slot[i].days) slot.splice(i, 1)
  slot.push(made)
  return made
}

/** Where each service is run on a day: route@service → {depot, siding, index} */
export function dutyAssignments(data: Data, draft: Draft, weekday: number) {
  const assigned = new Map<string, { depot: string; siding: string; index: number }[]>()
  if (!data) return assigned
  data.depots.forEach((depot: any) => (Object.entries(dutiesOf(draft, depot).sidings) as [string, Slot[]][]).forEach(([siding, slots]) => slots.forEach((slot, index) => {
    const day = dutyOn(slot, weekday)
    if (day) day.segments.forEach((segment: any) => {
      const key = segmentKey(segment)
      if (!assigned.has(key)) assigned.set(key, [])
      assigned.get(key)!.push({ depot: depot.id, siding, index })
    })
  })))
  return assigned
}

export type Coverage = { weekday: number; routes: Set<string>; noTrain: Set<string> }
/** On a day, the routes a depot running by its duties runs and the services no train of their duties runs */
export function coverage(data: Data, draft: Draft, weekday: number): Coverage {
  const assigned = dutyAssignments(data, draft, weekday)
  const routes = new Set<string>(data ? data.depots.filter((depot: any) => dutiesOf(draft, depot).mode === "diagram").flatMap((depot: any) => depot.routes || []) : [])
  const noTrain = new Set<string>()
  if (data) data.routes.forEach((r: any) => {
    if (routes.has(r.id) && r.hasTimes) (r.services || []).forEach((s: any) => { if (!s.suspended && !assigned.has(s.id)) noTrain.add(s.id) })
  })
  return { weekday, routes, noTrain }
}

/** The least time a train changes ends where it turns back, as the planner takes it: the network's setting, as drafted */
export function minReverseMillis(data: Data, draft: Draft) {
  const settings = (data && data.networkSettings) || {}
  const pending = draft.networkSettings && Number.isFinite(draft.networkSettings.minReverseMillis) ? draft.networkSettings.minReverseMillis : undefined
  return pending !== undefined ? pending : Number.isFinite(settings.minReverseMillis) ? settings.minReverseMillis : 30000
}

/**
 * On a day, which service each train runs next after the end of a route and where it waits meanwhile (TerminalPairs,
 * terminal.js): set by the player, in a duty, or the nearest; with the platforms it may turn back at in turn and the turn
 * back rails beyond, as the depots' path generation measured them
 */
export function terminalPairs(data: Data, draft: Draft, weekday: number) {
  const runs: any[] = [], manual: Record<string, string> = {}, turnTimes: Record<string, any> = {}, holdIns = {}, holdCounts = {}, duties: string[][] = []
  if (data) {
    data.routes.forEach((r: any) => {
      if (!r.hasTimes) return
      const loopWait = isLoop(r) ? loopWaitMillis(data, r) : undefined
      ;(r.services || []).forEach((s: any) => {
        const [first, last] = servedStops(s)
        runs.push({
          id: s.id, routeId: r.id, platforms: r.stops.map((stop: any) => stop.platformId), stations: r.stops.map((stop: any) => stop.station || "p" + stop.platformId),
          times: timesOf(s), first, last, suspended: !!s.suspended, returns: !!(s.returns || s.returnOrder), terminalPlatforms: r.terminalPlatforms || [],
          deploy: !!s.deployOrder, through: !!s.link, loopWait,
        })
        if (s.link && s.link.fromStop < 0 && s.link.toStop === 0) manual[s.id] = s.link.route + "@" + s.link.toService
      })
    })
    data.depots.forEach((depot: any) => {
      Object.entries((depot.path || {}).turnTimes || {}).forEach(([from, times]) => { turnTimes[from] = Object.assign(turnTimes[from] || {}, times) })
      Object.assign(holdIns, (depot.path || {}).holdIns || {})
      Object.assign(holdCounts, (depot.path || {}).holdCounts || {})
      ;(Object.values(dutiesOf(draft, depot).sidings) as Slot[][]).forEach(slots => slots.forEach(slot => {
        const day = dutyOn(slot, weekday)
        if (day && day.segments.length) duties.push(day.segments.map(segmentKey))
      }))
    })
  }
  return Object.assign(pairs().pair({ runs, manual, duties, turnTimes, holdIns, holdCounts, minTurn: minReverseMillis(data, draft) }), { weekday })
}

/** The slot a train staying overnight runs the next day: the one chosen, or its own */
export const nextSlotOf = (day: any, index: number) => (day.next !== undefined && day.next >= 0 ? day.next : index)
export function dutyEndPlatform(data: Data, day: any) {
  const segment = day.segments[day.segments.length - 1], r = segment && data.routeById.get(segment.route)
  return r ? (r.stops[lastStopOf(segment, r)] || {}).platformId : null
}
export function dutyStartPlatform(data: Data, day: any) {
  const segment = day.segments[0], r = segment && data.routeById.get(segment.route)
  return r ? (r.stops[segment.from] || {}).platformId : null
}

/** What is wrong with a train's duty on a day: services it can't reach in time or from where it is, or that don't run */
export function dutyProblems(data: Data, draft: Draft, depot: any, sidingId: string, index: number, weekday: number) {
  const duties = dutiesOf(draft, depot)
  const slots: Slot[] = duties.sidings[sidingId] || [], day = slots[index] ? dutyOn(slots[index], weekday) : null
  if (!day) return []
  const problems: { i: number; text: string }[] = [], assigned = dutyAssignments(data, draft, weekday)
  const minReverse = minReverseMillis(data, draft)
  day.segments.forEach((segment: any, i: number) => {
    const s = segmentService(data, segment)
    if (!s) {
      // A deleted route is told once for the whole duty; a route still there doesn't have this service
      if (data.routeById.get(segment.route)) problems.push({ i, text: tr("{0}的时刻表里没有 {1} 这一班，可能时刻表改过", weekdayName(weekday), clock(data, mod(segment.service - data.dayStartMillis, DAY))) })
      return
    }
    if (!depot.routes.includes(segment.route)) problems.push({ i, text: tr("线路不在这个车厂") })
    if (s.suspended) problems.push({ i, text: tr("这一班停运了：自动排班不会排它，这份运用里请移出，或在时刻表里恢复") })
    // A service set to come out of a depot starts a train's duty, from a siding of that depot
    if (s.deployOrder && i > 0 && segment.from === s.deployOrder.stop) problems.push({ i, text: tr("这一班设了从车厂出车，不能接在上一班后面，要放在一份运用的开头") })
    else if (s.deployOrder && i === 0 && s.deployOrder.depot !== "0" && s.deployOrder.depot !== depot.id) problems.push({ i, text: tr("这一班设了由车厂 {0} 出车，这份运用却在车厂 {1}", orderDepotName(data, s.deployOrder), shortName(depot.name)) })
    if ((assigned.get(segmentKey(segment)) || []).length > 1) problems.push({ i, text: tr("这一班也分给了别的车") })
    const before = day.segments[i - 1], beforeService = before && segmentService(data, before)
    if (beforeService) {
      const last = lastStopOf(before, beforeService.route)
      const endStop = beforeService.route.stops[last], startStop = s.route.stops[segment.from]
      const goesRound = beforeService.route.id === s.route.id && isLoop(s.route) && last === beforeService.route.stops.length - 1 && segment.from === 0
      const gap = goesRound ? timesOf(s)[0][1] - timesOf(beforeService)[last][0] : segmentStart(data, segment) - segmentEnd(data, before)
      if (gap < 0) problems.push({ i, text: tr("衔接时间不够，上一班还没到") })
      else if (endStop && startStop && endStop.station !== startStop.station) problems.push({ i, text: `${tr("站台对不上：上一班在{0}结束", shortName(endStop.name))}` })
      else if (endStop && startStop && endStop.platformId === startStop.platformId) {
        // A loop going round again from where it came round doesn't change ends
        if (!goesRound && gap < minReverse) problems.push({ i, text: tr("折返时间不够：同一站台折返至少要 {0}，这里只有 {1}", formatDuration(minReverse), formatDuration(gap)) })
      } else if (endStop && startStop) {
        // A change of platform takes as long as the server measured on the rails, or has no way at all
        const turn = turnTimeOf(depot, endStop.platformId, startStop.platformId)
        if (turn === null) problems.push({ i, text: tr("这两个站台之间没有路：{0} 到 {1} 换不过去，刷新路径后还是这样就要加折返线或渡线", stopLabel(endStop), stopLabel(startStop)) })
        // As the planner: the least turnaround at the platform arrived at, then the way to the other one
        else if (turn !== undefined && gap < minReverse + turn) problems.push({ i, text: tr("衔接时间不够：从 {0} 换到 {1} 实际要 {2}，这里只有 {3}", stopLabel(endStop), stopLabel(startStop), formatDuration(minReverse + turn), formatDuration(gap)) })
      }
    }
  })
  if (day.stay && day.nextDuty && day.nextDuty !== "0") {
    // Another siding's duty, by its number (P2-D)
    const found = findDutySlot(duties, day.nextDuty)
    const next = found ? dutyOn(found.slot, (weekday + 1) % 7) : null
    if (!found) problems.push({ i: -1, text: tr("留在线上过夜，但第二天接的运用已经不在了") })
    else if (!next || !next.segments.length) problems.push({ i: -1, text: `${tr("留在线上过夜，但第二天接的车 {0} 没有运用", dutyNumber(draft, depot, found.siding, found.index))}` })
    else if (dutyEndPlatform(data, day) !== dutyStartPlatform(data, next)) problems.push({ i: -1, text: `${tr("第二天接的车 {0} 的运用不从这个站台出发（过夜的车第二天从停着的站台接着跑）", dutyNumber(draft, depot, found.siding, found.index))}` })
  } else if (day.stay) {
    const target = nextSlotOf(day, index)
    const next = slots[target] ? dutyOn(slots[target], (weekday + 1) % 7) : null
    if (!next || !next.segments.length) problems.push({ i: -1, text: `${tr("留在线上过夜，但第二天接的车 {0} 没有运用", target + 1)}` })
    else if (dutyEndPlatform(data, day) !== dutyStartPlatform(data, next)) problems.push({ i: -1, text: `${tr("第二天接的车 {0} 的运用不从这个站台出发（过夜的车第二天从停着的站台接着跑）", target + 1)}` })
    if (slots.some((slot, other) => other !== index && dutyOn(slot, weekday) && dutyOn(slot, weekday).stay && nextSlotOf(dutyOn(slot, weekday), other) === target)) problems.push({ i: -1, text: `${tr("还有一列过夜的车第二天也接车 {0}", target + 1)}` })
  }
  return problems
}

/**
 * @return what keeps a stop of a route from being taken away, as the game and the draft have them: through services
 * from it or into it, depot orders at it, duty segments starting or ending at it
 */
export function stopHangers(data: Data, draft: Draft, r: any, stop: number) {
  const found: string[] = []
  const at = (service: number) => clock(data, mod(service - data.dayStartMillis, DAY))
  ;[gameTimetable(r), timetableOf(draft, r)].forEach(tt => tt.tables.forEach((table: any) => {
    table.links.forEach((link: any) => { if (link.fromStop === stop) found.push(tr("{0} 班直通", at(link.service))) })
    ;(table.orders || []).forEach((order: any) => { if (order.stop === stop) found.push(tr("{0} 班出入库指令", at(order.service))) })
  }))
  data.routes.forEach((other: any) => [gameTimetable(other), timetableOf(draft, other)].forEach(tt => tt.tables.forEach((table: any) => table.links.forEach((link: any) => {
    if (link.route === r.id && link.toStop === stop) found.push(tr("{0} 直通接入", routeTitle(other)))
  }))))
  data.depots.forEach((depot: any) => [depot.duties, dutiesOf(draft, depot)].forEach(duties => (Object.values((duties || {}).sidings || {}) as Slot[][]).forEach(slots => slots.forEach(slot => slot.forEach(day => day.segments.forEach((segment: any) => {
    if (segment.route === r.id && ((segment.from === stop && stop > 0) || segment.to === stop)) found.push(tr("车厂{0}的运用起止", shortName(depot.name)))
  }))))))
  return [...new Set(found)]
}

/** A depot's duties with the segments of this route renumbered to its new stops, or null if none is */
export function renumberDuties(duties: any, routeId: string, newOf: number[]) {
  if (!duties) return null
  const copy = JSON.parse(JSON.stringify(duties))
  let changed = false
  ;(Object.values(copy.sidings || {}) as Slot[][]).forEach(slots => slots.forEach(slot => slot.forEach(day => day.segments.forEach((segment: any) => {
    if (segment.route !== routeId) return
    // From the first stop and to the last stay so; others follow their stops
    const from = segment.from > 0 ? newOf[segment.from] : 0
    const to = segment.to < 0 ? segment.to : newOf[segment.to]
    if (from !== segment.from || to !== segment.to) {
      segment.from = from
      segment.to = to
      changed = true
    }
  }))))
  return changed ? copy : null
}

export type StopListItem = { platformId: string; from: number; pass: boolean }
/**
 * A route's new stops: the operations sending them to the game with what counts its stops (its timetable, through
 * services into it, the depots' duties), and the same renumbering in the draft
 *
 * @return the operations, or why it can't
 */
export function stopListChange(data: Data, draft: Draft, r: any, list: StopListItem[]): { operations: any[] } | string {
  if (list.length < 2) return tr("至少要两站")
  const newOf = r.stops.map(() => -1)
  list.forEach((stop, i) => { if (stop.from >= 0) newOf[stop.from] = i })
  const operations: any[] = [{ type: "stops", route: r.id, force: true, stops: list.map(stop => ({ platformId: stop.platformId, from: stop.from, pass: stop.pass })) }]
  operations.push({ type: "timetable", route: r.id, force: true, timetable: renumberTimetable(gameTimetable(r), list, newOf) })
  data.routes.forEach((other: any) => {
    if (other === r) return
    const renumbered = renumberLinksInto(gameTimetable(other), r.id, newOf)
    if (renumbered) operations.push({ type: "timetable", route: other.id, force: true, timetable: renumbered })
  })
  data.depots.forEach((depot: any) => {
    const renumbered = renumberDuties(depot.duties, r.id, newOf)
    if (renumbered && renumbered.mode === "diagram") operations.push({ type: "duties", depot: depot.id, duties: renumbered })
  })
  // The draft follows the new stops too
  const entry = draft.routes[r.id]
  if (entry && entry.timetable) entry.timetable = renumberTimetable(entry.timetable, list, newOf)
  if (entry) delete entry.platforms
  data.routes.forEach((other: any) => {
    const otherEntry = draft.routes[other.id]
    if (other !== r && otherEntry && otherEntry.timetable) {
      const renumbered = renumberLinksInto(otherEntry.timetable, r.id, newOf)
      if (renumbered) otherEntry.timetable = renumbered
    }
  })
  Object.values(draft.duties || {}).forEach((drafted: any) => {
    const renumbered = renumberDuties(drafted.duties, r.id, newOf)
    if (renumbered) drafted.duties = renumbered
  })
  return { operations }
}
