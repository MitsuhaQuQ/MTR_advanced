/**
 * The rules for changing timetables in the draft (retiring the old code, step 4): the draft's entry for a route, a change
 * applied to a copy of the game's timetable and kept only while it differs, and the changes themselves — a stop's own
 * times, services moved, suspended, removed, linked, added, spaced out, a table cleared — and choosing several services.
 * These work on the draft and on plain timetable objects; the legacy engine calls them (gen-legacy FROM_ENV) and adds
 * what follows a change (saving the draft, the services again, drawing). test/timetable.test.ts runs the old ones beside.
 */
import { tr } from "../i18n"
import { DAY, mod } from "./model"
import { normalizeTimetable, gameTimetable as fromGame } from "./draft"
import { durationText, parseClock, parsePattern, type Day } from "./format"

export { normalizeTimetable as normalize, fromGame }

/** TimetableBatch (batch.js), a script of the page */
const Batch = () => (globalThis as any).TimetableBatch

// ---------- The draft ----------

/** The game's timetable of a route, in the form the editor sends back, made once */
export const gameTimetable = (r: any) => r.gameTimetable || (r.gameTimetable = fromGame(r))
export const draftOf = (draft: any, r: any) => draft.routes[r.id]
/** The route's entry, made with the revision of each part it starts from, which the game checks part by part */
export const draftEntry = (draft: any, r: any) => draft.routes[r.id] || (draft.routes[r.id] = { base: r.revision, parts: Object.assign({}, r.parts || {}) })
/** The timetable as drafted, or as the game has it */
export const timetableOf = (draft: any, r: any) => (draftOf(draft, r) && draftOf(draft, r).timetable ? draftOf(draft, r).timetable : gameTimetable(r))
/** A route's entry goes once nothing is left in it but where it came from */
export function tidyDraft(draft: any, id: string) {
  const entry = draft.routes[id]
  if (entry && Object.keys(entry).every(k => k === "base" || k === "force" || k === "error" || k === "conflict")) delete draft.routes[id]
}

/** A change, made to the timetable being drafted; what it returns is a message, or a reason it failed */
export type Change = (timetable: any) => string | void | undefined

/**
 * Applies a change to a route's timetable in the draft, starting from a copy of the game's: sorted the same way after,
 * and taken out again if it ends up as the game has it.
 *
 * @return the change's message, or ""
 */
export function applyEdit(draft: any, r: any, change: Change): string {
  const entry = draftEntry(draft, r)
  if (!entry.timetable) entry.timetable = JSON.parse(JSON.stringify(gameTimetable(r)))
  const previousPass = [...entry.timetable.pass]
  const message = change(entry.timetable)
  entry.timetable.pass.forEach((pass: boolean, i: number) => {
    if (!pass || previousPass[i]) return
    entry.timetable.tables.forEach((table: any) => {
      table.stops[i] = { arrival: -1, departure: -1, overtake: false }
      table.exceptions.forEach((exception: any) => exception.stops.forEach((stop: any) => { if (stop.stop === i) { stop.run = -1; stop.dwell = -1; stop.overtake = -1 } }))
    })
  })
  normalizeTimetable(entry.timetable)
  if (JSON.stringify(entry.timetable) === JSON.stringify(gameTimetable(r))) delete entry.timetable
  if (!entry.conflict) delete entry.error
  tidyDraft(draft, r.id)
  return typeof message === "string" ? message : ""
}

// ---------- A table's services ----------

/** Changes what one service does at one stop; a value of -1 follows the table again */
export function setStopException(table: any, service: number, stop: number, changes: Record<string, number>) {
  let exception = table.exceptions.find((e: any) => e.service === service)
  if (!exception) table.exceptions.push((exception = { service, stops: [] }))
  let stopException = exception.stops.find((e: any) => e.stop === stop)
  if (!stopException) exception.stops.push((stopException = { stop, run: -1, dwell: -1, pass: -1, overtake: -1 }))
  if (changes.pass === 1 && stopException.pass !== 1 && changes.run === undefined) stopException.run = -1
  Object.assign(stopException, changes)
  if (stopException.run < 0 && stopException.dwell < 0 && stopException.pass < 0 && stopException.overtake < 0) exception.stops.splice(exception.stops.indexOf(stopException), 1)
  if (!exception.stops.length) table.exceptions.splice(table.exceptions.indexOf(exception), 1)
}

/** Makes the services the same as one service in all it changes at its stops @return what was done */
export function syncExceptions(table: any, from: number, services: number[]) {
  const source = table.exceptions.find((e: any) => e.service === from)
  const targets = new Set(services.filter(service => service !== from))
  table.exceptions = table.exceptions.filter((e: any) => !targets.has(e.service))
  if (source && source.stops.length) targets.forEach(service => table.exceptions.push({ service, stops: source.stops.map((stop: any) => ({ ...stop })) }))
  return tr("{0} 班已经和这一班的各站时刻改动一样", targets.size)
}

/** Moves a service to another time, with what belongs to it @return why it can't, or nothing */
export function retimeService(table: any, oldService: number, newService: number): string | void {
  if (oldService === newService) return
  if (table.services.includes(newService)) return tr("这张表已经有这个时刻的班次")
  table.services[table.services.indexOf(oldService)] = newService
  table.links.forEach((link: any) => { if (link.service === oldService) link.service = newService })
  table.returns = table.returns.map((service: number) => (service === oldService ? newService : service))
  table.suspended = (table.suspended || []).map((service: number) => (service === oldService ? newService : service))
  ;(table.orders || []).forEach((order: any) => { if (order.service === oldService) order.service = newService })
  table.exceptions.forEach((exception: any) => { if (exception.service === oldService) exception.service = newService })
}

/** Sets services not to run, or to run again; their times and everything else stay @return what was done */
export function setSuspended(table: any, services: number[], suspended: boolean) {
  const set = new Set<number>(table.suspended || [])
  services.forEach(service => (suspended ? set.add(service) : set.delete(service)))
  table.suspended = [...set].filter(service => table.services.includes(service)).sort((a, b) => a - b)
  return suspended ? tr("{0} 班停运，留在表里，右键可以恢复", services.length) : tr("{0} 班恢复运行，和别的班次冲突的话同步后会提示", services.length)
}

export function removeService(table: any, service: number) {
  table.services = table.services.filter((s: number) => s !== service)
  table.links = table.links.filter((link: any) => link.service !== service)
  table.returns = table.returns.filter((s: number) => s !== service)
  table.suspended = (table.suspended || []).filter((s: number) => s !== service)
  table.orders = (table.orders || []).filter((order: any) => order.service !== service)
  table.exceptions = table.exceptions.filter((exception: any) => exception.service !== service)
}

/** Sets or clears where a service is joined by a train from a depot, or left for one; depot "0" for any, null to clear */
export function setServiceOrder(table: any, service: number, kind: string, stop: number, depot: string | number | null) {
  table.orders = (table.orders || []).filter((order: any) => !(order.service === service && order.kind === kind))
  if (depot !== null) table.orders.push({ service, kind, stop, depot: String(depot) })
}

/** Services moved by the same time, those moving later from the last, earlier from the first, so none lands on another */
function retimeAll(table: any, services: number[], shift: number): string | void {
  const order = services.slice().sort((a, b) => (shift > 0 ? b - a : a - b))
  for (const service of order) {
    const error = retimeService(table, service, mod(service + shift, DAY))
    if (error) return error
  }
}

// ---------- Changes ----------

/** Services of the same table that run after this one, in the operating day */
export const laterServices = (s: any) => s.route.services.filter((o: any) => o.tableIndex === s.tableIndex && o.start > s.start)

/**
 * A time typed into a cell: the first stop's departure moves the services, a departure sets the dwell, an arrival the
 * running time from the stop before; for this service, or (toLater) this and every later one of its table.
 */
export function changeTimeEdit(s: any, times: number[][], stop: number, kind: string, time: number, services: any[]): Change {
  return timetable => {
    const table = timetable.tables[s.tableIndex]
    if (stop === 0) {
      const error = retimeAll(table, services.map(o => o.service), time - times[0][1])
      if (error) return error
    } else if (kind === "dep") {
      const dwell = Math.max(0, Math.round((time - times[stop][0]) / 1000))
      services.forEach(o => setStopException(table, o.service, stop, { dwell }))
    } else {
      const run = Math.max(1, Math.round((time - times[stop - 1][1]) / 1000))
      services.forEach(o => setStopException(table, o.service, stop, { run }))
    }
    if (services.length > 1) return tr("这一班和之后的 {0} 班一起改了", services.length - 1)
  }
}

/** After the first stop's time is changed, the service is chosen at its new time (r: the route with its services again) */
export function selectMoved(state: { selected: string | null }, r: any, s: any, shift: number) {
  const moved = r.services.find((o: any) => o.tableIndex === s.tableIndex && o.service === mod(s.service + shift, DAY))
  if (moved) state.selected = moved.id
}

/**
 * Services moved by the same time each, keeping their stops and running times; all or none: one landing on a service
 * that stays where it is stops the move.
 *
 * @param blockedText how a time blocking the move is written ("07:10")
 */
export function moveServicesEdit(tableIndex: number, services: number[], shift: number, blockedText: (service: number) => string, onBlocked: () => void): Change {
  return timetable => {
    const table = timetable.tables[tableIndex]
    const moving = new Set(services)
    const blocked = services.map(service => mod(service + shift, DAY)).find(time => table.services.includes(time) && !moving.has(time))
    if (blocked !== undefined) {
      onBlocked()
      return tr("{0} 已经有一班，没有移动", blockedText(blocked))
    }
    const error = retimeAll(table, services, shift)
    if (error) return error
    return tr("{0} 班{1}了 {2}", services.length, shift > 0 ? tr("推后") : tr("提前"), durationText(Math.abs(shift)))
  }
}

/** A service and every later one of its table moved by a shift */
export function shiftLaterEdit(s: any, shift: number): Change {
  return timetable => {
    const moving = [s, ...laterServices(s)].map(o => o.service)
    const error = retimeAll(timetable.tables[s.tableIndex], moving, shift)
    if (error) return error
    return tr("{0} 班{1}了 {2}", moving.length, shift > 0 ? tr("推后") : tr("提前"), durationText(Math.abs(shift)))
  }
}

/**
 * A time typed as a shift in milliseconds: minutes (5, -2.5, 5m, 5分), seconds (90s, 90秒), m:ss (1:30, -0:45) or minutes
 * and seconds (1m30s, 1分30秒); null if it isn't one
 */
export function parseShift(text: string | null | undefined): number | null {
  if (text === null || text === undefined) return null
  const typed = String(text).trim().replace(/\s+/g, "").replace(/^−/, "-")
  const sign = typed.startsWith("-") ? -1 : 1, body = typed.replace(/^[-+]/, "")
  let m: RegExpMatchArray | null
  if ((m = body.match(/^(\d+):(\d{1,2})$/))) return sign * (Number(m[1]) * 60 + Number(m[2])) * 1000
  if ((m = body.match(/^(\d+(?:\.\d+)?)(?:s|秒)$/i))) return sign * Math.round(Number(m[1]) * 1000)
  if ((m = body.match(/^(\d+)(?:m|min|分|分钟)(\d+)(?:s|秒)?$/i))) return sign * (Number(m[1]) * 60 + Number(m[2])) * 1000
  if ((m = body.match(/^(\d+(?:\.\d+)?)(?:m|min|分|分钟)?$/i))) return sign * Math.round(Number(m[1]) * 60000)
  return null
}

/** The chosen run of services spaced evenly from the first, `minutes` apart, keeping each one's stops and running times */
export function respaceEdit(run: any[], minutes: number, done: () => void): Change {
  const targets: number[] = Batch().respace(run.map(o => o.service), Math.round(minutes * 60000))
  return timetable => {
    const table = timetable.tables[run[0].tableIndex]
    // Those moving later go first from the end, those moving earlier from the start, so none lands on another
    const moves = run.map((o, i) => [o.service, targets[i]]).filter(([from, to]) => from !== to)
    const later = moves.filter(([from, to]) => mod(to - from, DAY) < DAY / 2).reverse(), earlier = moves.filter(([from, to]) => mod(to - from, DAY) >= DAY / 2)
    for (const [from, to] of [...later, ...earlier]) {
      const error = retimeService(table, from, to)
      if (error) return error
    }
    done()
    return tr("{0} 班已按 {1} 分钟间隔排开", run.length, minutes)
  }
}

/** A service running through to another route's service, at a stop or at the end; it then doesn't go back to the depot */
export function setLinkEdit(s: any, fromStop: number, target: any, toStop: number): Change {
  return timetable => {
    const table = timetable.tables[s.tableIndex]
    table.links = table.links.filter((link: any) => link.service !== s.service)
    table.links.push({ service: s.service, route: target.route.id, toService: target.service, fromStop, toStop })
    table.returns = table.returns.filter((service: number) => service !== s.service)
  }
}

/** Every service out of a table, with their links, returns, orders and exceptions; the table, its stop times and days stay */
export function clearTableEdit(index: number, tableName: (table: any, index: number) => string): Change {
  return timetable => {
    const edited = timetable.tables[index]
    edited.services = []
    edited.links = []
    edited.returns = []
    edited.suspended = []
    edited.orders = []
    edited.exceptions = []
    return tr("已清空“{0}”", tableName(edited, index))
  }
}

/**
 * The services to add: from the start every interval until the end (the end included where one falls on it); with no end
 * the start alone; or the start's own pattern when nothing else is filled in. The interval in minutes, or as 04:30.
 */
export function servicesToAdd(day: Day, startText: string, endText: string, intervalText: string): number[] | null {
  if (!endText.trim() && !intervalText.trim()) return parsePattern(day, startText)
  const start = parseClock(startText.trim()), end = endText.trim() ? parseClock(endText.trim()) : start
  const text = intervalText.trim(), parts = text.split(/[:：]/)
  const step = parts.length === 2 ? (Number(parts[0]) * 60 + Number(parts[1])) * 1000 : Number(text) * 60000
  if (start === null || end === null || !(step >= 1000)) return null
  return (Batch().stepTimes(start, end, Math.round(step / 1000) * 1000) as number[]).map(time => mod(time - day.offsetMillis, DAY))
}

/** A route's timetable renumbered to its new stops: newOf[old stop] is its new number, or -1 if it was taken away */
export function renumberTimetable(tt: any, list: { from: number; pass: boolean }[], newOf: number[]) {
  const copy = JSON.parse(JSON.stringify(tt))
  copy.pass = list.map(stop => stop.pass)
  copy.allowLonger = list.map(stop => (stop.from >= 0 ? Boolean((tt.allowLonger || [])[stop.from]) : false))
  copy.tables.forEach((table: any) => {
    table.stops = list.map(stop => (stop.from >= 0 && !(stop.pass && !tt.pass[stop.from]) && table.stops[stop.from] ? table.stops[stop.from] : { arrival: -1, departure: -1 }))
    table.orders = (table.orders || []).map((order: any) => Object.assign({}, order, { stop: newOf[order.stop] })).filter((order: any) => order.stop >= 0)
    table.links = table.links.map((link: any) => Object.assign({}, link, { fromStop: link.fromStop < 0 ? link.fromStop : newOf[link.fromStop] }))
    table.exceptions = table.exceptions.map((exception: any) => ({ service: exception.service, stops: exception.stops.map((stop: any) => Object.assign({}, stop, { stop: newOf[stop.stop] })).filter((stop: any) => stop.stop >= 0) })).filter((exception: any) => exception.stops.length)
  })
  copy.tables.forEach((table: any) => table.exceptions.forEach((exception: any) => exception.stops.forEach((stop: any) => {
    const changed = list[stop.stop]
    if (changed?.pass && !tt.pass[changed.from]) { stop.run = -1; stop.dwell = -1; stop.overtake = -1 }
  })))
  return normalizeTimetable(copy)
}

/** Through services of another route into this one, renumbered to its new stops; null if none changed */
export function renumberLinksInto(tt: any, routeId: string, newOf: number[]) {
  const copy = JSON.parse(JSON.stringify(tt))
  let changed = false
  copy.tables.forEach((table: any) => table.links.forEach((link: any) => {
    if (link.route === routeId && link.toStop >= 0 && newOf[link.toStop] !== link.toStop) {
      link.toStop = newOf[link.toStop]
      changed = true
    }
  }))
  return changed ? copy : null
}

// ---------- Choosing services ----------

/** What is chosen: the service selected, the route chosen whole, a run of services (state of the old page) */
export type Choice = { selected: string | null; selectedRoute: string | null; range: { anchor: string; ids: string[]; toggled?: string } | null; slot?: unknown }

const tableRun = (s: any) => s.route.services.filter((o: any) => o.tableIndex === s.tableIndex)
export const inRange = (state: Choice, s: any) => Boolean(state.range && state.range.ids.includes(s.id))
export const rangeServices = (state: Choice, find: (id: string) => any) => (state.range ? state.range.ids.map(find).filter(Boolean) : [])

/** Chooses the services from the one chosen before to this one, if they're of one route and table @return whether it did */
export function chooseRange(state: Choice, s: any, find: (id: string) => any) {
  const anchor = state.range ? find(state.range.anchor) : state.selected && find(state.selected)
  if (!anchor || anchor.route !== s.route || anchor.tableIndex !== s.tableIndex) {
    state.range = null
    return false
  }
  state.range = { anchor: anchor.id, ids: Batch().runBetween(tableRun(s).map((o: any) => o.id), anchor.id, s.id) }
  return true
}

/**
 * Ctrl-click: a service put in or taken out of the chosen ones; with Shift, every service from the one Ctrl-clicked last
 * to this one, taken out if this one was chosen, else put in
 */
export function toggleChosen(state: Choice, s: any, span: boolean, find: (id: string) => any) {
  const run = tableRun(s).map((o: any) => o.id)
  let ids = state.range ? state.range.ids.slice() : state.selectedRoute === s.route.id ? run.slice() : state.selected ? [state.selected] : []
  const first = ids.length ? find(ids[0]) : null
  if (first && (first.route !== s.route || first.tableIndex !== s.tableIndex)) ids = []
  const fromId = (state.range && (state.range.toggled || state.range.anchor)) || state.selected
  const affected: string[] = span && fromId && run.includes(fromId) ? Batch().runBetween(run, fromId, s.id) : [s.id]
  const chosen = new Set(ids), remove = chosen.has(s.id)
  affected.forEach(id => (remove ? chosen.delete(id) : chosen.add(id)))
  const order = run.filter((id: string) => chosen.has(id))
  state.selectedRoute = null
  state.range = order.length ? { anchor: state.range && order.includes(state.range.anchor) ? state.range.anchor : order[0], ids: order, toggled: s.id } : null
  state.selected = order.length ? (order.includes(s.id) ? s.id : order[0]) : null
  return true
}

/** Chooses a service and every later one of its table @return how many */
export function chooseLater(state: Choice, s: any) {
  const ids = [s, ...laterServices(s)].map((o: any) => o.id)
  state.selectedRoute = null
  state.selected = s.id
  state.range = { anchor: s.id, ids }
  return ids.length
}

/** The services a drag moves: the chosen run or route without those edited on their own, or the one dragged */
export function servicesToMove(state: Choice, s: any, canEdit: boolean, find: (id: string) => any): string[] | null {
  if (!s || state.slot || !canEdit) return null
  const unedited = (list: any[]) => list.filter(o => o === s || !o.exceptions)
  if (inRange(state, s)) return unedited(rangeServices(state, find)).map(o => o.id)
  if (state.selectedRoute === s.route.id) return unedited(s.route.services.filter((o: any) => o.tableIndex === s.tableIndex)).map(o => o.id)
  return [s.id]
}
