/**
 * The draft (api/draft) and how it is applied, moved from editor.js (loadDraft, tidyDraft, draftOperations, applyDraft,
 * resolveConflict, routeChanges, draftChangesDialog). The draft's shape is shared with the old page, so both must read
 * and write it the same way; test/draft.test.ts runs the old functions beside these on the same drafts.
 *
 * Functions here never change their argument: each returns a new draft, which keeps undo simple.
 */
import type { Route } from "../api/types"
import { tr } from "../i18n"
import {
  type Model, daysText, routeTitle, serviceClock, setTimeText, shortName, someServices, stopName, tableName,
} from "./model"

export type Draft = {
  routes: Record<string, any>
  created: any[]
  duties: Record<string, any>
  boundaries: Record<string, any>
  depots: Record<string, any>
  depotRules: Record<string, any>
  platformStations: Record<string, any>
  platformSpeeds: Record<string, any>
  platformStops: Record<string, any>
  networkSettings: any
  contingencies: Record<string, any>
  [other: string]: any
}

export const emptyDraft = (): Draft => ({
  routes: {}, created: [], duties: {}, boundaries: {}, depots: {}, depotRules: {}, platformStations: {}, platformSpeeds: {},
  platformStops: {}, networkSettings: null, contingencies: {},
})

const clone = <T,>(value: T): T => (value === undefined ? value : JSON.parse(JSON.stringify(value)))
const isObject = (value: unknown) => Boolean(value) && typeof value === "object"

/** The draft as saved, with every part present (loadDraft). */
export function normalizeDraft(saved: any): Draft {
  const draft = saved && saved.routes ? clone(saved) : { routes: {} }
  Object.values(draft.routes).forEach((entry: any) => {
    if (entry.conflict && !entry.error) entry.error = tr("游戏里的这条线路在草稿之后改过")
  })
  if (!Array.isArray(draft.created)) draft.created = []
  for (const part of ["duties", "boundaries", "depots", "depotRules", "platformStations", "platformSpeeds", "platformStops", "contingencies"]) {
    if (!isObject(draft[part])) draft[part] = {}
  }
  if (!isObject(draft.networkSettings)) draft.networkSettings = null
  return draft
}

/** How many things the draft changes (draftCount). */
export const draftCount = (draft: Draft) =>
  Object.keys(draft.routes).length + draft.created.length + Object.keys(draft.duties).length + Object.keys(draft.boundaries || {}).length
  + Object.keys(draft.depots || {}).length + Object.keys(draft.depotRules || {}).length + Object.keys(draft.platformStations || {}).length
  + Object.keys(draft.platformSpeeds || {}).length + Object.keys(draft.platformStops || {}).length + (draft.networkSettings ? 1 : 0)
  + Object.keys(draft.contingencies || {}).length

export const conflictCount = (draft: Draft) => Object.values(draft.routes).filter(entry => entry.conflict).length

/** A route's entry goes once nothing is left in it but where it came from (tidyDraft). */
export function tidy(draft: Draft, id: string): Draft {
  const entry = draft.routes[id]
  if (!entry || !Object.keys(entry).every(k => k === "base" || k === "force" || k === "error" || k === "conflict" || k === "conflictBy")) return draft
  const routes = { ...draft.routes }
  delete routes[id]
  return { ...draft, routes }
}

/** Who each operation is for, to put an error back on: a route ID, a created route, or { kindOf: key }. */
export type Owner = string | Record<string, any>

/** The draft as the changes to send to the game, and what each change is for (draftOperations). */
export function draftOperations(model: Model, draft: Draft): { operations: any[]; owners: Owner[] } {
  const operations: any[] = [], owners: Owner[] = []
  Object.entries(draft.routes).forEach(([id, entry]) => {
    // A route with a conflict stays in the draft and isn't sent; it doesn't hold up the other routes
    if (entry.conflict) return
    let base = entry.base
    const parts = entry.parts
    // Nothing else of a route being deleted is worth sending
    if (entry.deleted) {
      operations.push({ type: "deleteRoute", route: id, base, parts, force: Boolean(entry.force) })
      owners.push(id)
      return
    }
    if (entry.platforms) {
      operations.push({ type: "platforms", route: id, base, parts, force: Boolean(entry.force), platforms: entry.platforms })
      owners.push(id)
      base = undefined
    }
    if (entry.timetable) {
      operations.push({ type: "timetable", route: id, base, parts, force: Boolean(entry.force), timetable: entry.timetable })
      owners.push(id)
      // The timetable changes the revision, so what follows for the route doesn't check it again
      base = undefined
    }
    if ("managed" in entry) {
      operations.push({ type: "managed", route: id, base, parts, force: Boolean(entry.force), managed: entry.managed })
      owners.push(id)
    }
    if ("connectingLine" in entry) {
      operations.push({ type: "connectingLine", route: id, base, parts, force: Boolean(entry.force), connectingLine: entry.connectingLine })
      owners.push(id)
    }
    if ("speedLimit" in entry) {
      operations.push({ type: "speedLimit", route: id, base, parts, force: Boolean(entry.force), speedLimit: entry.speedLimit })
      owners.push(id)
    }
    if (entry.connectingReleased) {
      operations.push({ type: "connectingRelease", route: id, base, parts, force: Boolean(entry.force), rails: entry.connectingReleased })
      owners.push(id)
    }
    if (entry.terminalPlatforms || entry.startPlatforms) {
      const r: any = model.routeById.get(id) || {}
      operations.push({ type: "turnPlatforms", route: id, base, parts, force: Boolean(entry.force), terminal: entry.terminalPlatforms || r.terminalPlatforms || [], start: entry.startPlatforms || r.startPlatforms || [] })
      owners.push(id)
    }
  })
  // What a depot runs goes first, so its duties are applied to the routes and sidings as drafted
  Object.entries(draft.depots || {}).forEach(([depotId, entry]) => {
    operations.push({ type: "depot", depot: depotId, base: entry.base, force: Boolean(entry.force), routes: entry.routes, cycleRoutes: entry.cycleRoutes, sidings: entry.sidings, poolFleets: entry.poolFleets || null, vehicleSettings: entry.vehicleSettings || null, serviceTypes: entry.serviceTypes || null, clearBuffers: Boolean(entry.clearBuffers), addedBuffers: entry.addedBuffers || null })
    owners.push({ depotOf: depotId })
  })
  // How a depot's trains go out and back, before its duties, which are planned by them
  Object.entries(draft.depotRules || {}).forEach(([depotId, entry]) => {
    operations.push({ type: "depotRules", depot: depotId, rules: entry.rules })
    owners.push({ depotRulesOf: depotId })
  })
  Object.entries(draft.duties).forEach(([depotId, entry]) => {
    operations.push({ type: "duties", depot: depotId, duties: entry.duties })
    owners.push({ dutiesOf: depotId })
  })
  Object.entries(draft.boundaries || {}).forEach(([key, boundary]) => {
    const [a, b] = key.split(",")
    operations.push({ type: "pathBoundary", a, b, boundary })
    owners.push({ boundaryOf: key })
  })
  // A rail system's contingency plans, all of them in place of the system's
  Object.entries(draft.contingencies || {}).forEach(([anchor, plans]) => {
    operations.push({ type: "contingencies", system: anchor, plans })
    owners.push({ contingenciesOf: anchor })
  })
  Object.entries(draft.platformSpeeds || {}).forEach(([platform, speed]) => {
    operations.push({ type: "platformSpeed", platform, speed })
    owners.push({ platformSpeedOf: platform })
  })
  Object.entries(draft.platformStops || {}).forEach(([platform, position]) => {
    operations.push({ type: "platformStop", platform, position })
    owners.push({ platformStopOf: platform })
  })
  if (draft.networkSettings) {
    operations.push(Object.assign({ type: "networkSettings" }, draft.networkSettings))
    owners.push({ networkSettingsOf: true })
  }
  Object.entries(draft.platformStations || {}).forEach(([platform, station]) => {
    operations.push({ type: "platformStation", platform, station })
    owners.push({ platformStationOf: platform })
  })
  draft.created.forEach(made => {
    operations.push({ type: "newRoute", route: made.source, mode: made.mode || "", line: made.lineName || "", name: made.name, serviceType: made.serviceType, color: made.color, stops: made.stops, services: made.services, table: made.table, depot: made.depot || "" })
    owners.push(made)
  })
  return { operations, owners }
}

export type ApplyResult = { ok: boolean; general: string[]; applied: number; created: string[] }

/**
 * What applying did to the draft (the part of applyDraft after the game answers): what the game took leaves the draft,
 * what it didn't stays with the reason. Errors on boundaries and platform stations are returned apart, as the old page
 * keeps them outside the draft.
 */
export function settleApply(draft: Draft, owners: Owner[], result: any): { draft: Draft; result: ApplyResult; boundaryErrors: Record<string, string> } {
  const next = clone(draft)
  // The owners point into the draft sent; match created routes by their place in the list
  const createdIndex = new Map<Owner, number>(draft.created.map((made, i) => [made, i]))
  const boundaryErrors: Record<string, string> = {}
  const failed = new Set<Owner>()
  ;(result.errors || []).forEach((error: any) => {
    if (error.index < 0) return
    const id = owners[error.index]
    failed.add(id)
    if (typeof id === "object") {
      if (id.depotOf) {
        if (next.depots[id.depotOf]) next.depots[id.depotOf].error = error.message
      } else if (id.dutiesOf) {
        if (next.duties[id.dutiesOf]) next.duties[id.dutiesOf].error = error.message
      } else if (id.boundaryOf) {
        boundaryErrors[id.boundaryOf] = error.message
      } else if (id.platformStationOf) {
        boundaryErrors["station:" + id.platformStationOf] = error.message
      } else if (createdIndex.has(id)) {
        // Changed in the game since the draft was made: said as that, not as the server's code
        next.created[createdIndex.get(id)!].error = isConflict(error.message) ? tr("游戏里的这一项在草稿之后改过（可能是自动排了新的运用），草稿没有写入；看一下游戏里现在的，不需要就放弃这项草稿") : error.message
      }
      return
    }
    const entry = next.routes[id]
    if (!entry) return
    entry.conflict = isConflict(error.message)
    if (conflictBy(error.message)) entry.conflictBy = conflictBy(error.message)
    entry.error = entry.conflict ? (entry.conflictBy ? tr("游戏里的这条线路在草稿之后被 {0} 改过", entry.conflictBy) : tr("游戏里的这条线路在草稿之后改过")) : error.message
  })
  owners.forEach(id => { if (!failed.has(id) && typeof id === "string") delete next.routes[id] })
  next.created = next.created.filter((_, i) => failed.has(draft.created[i]))
  const settled = (key: string) => owners.filter(owner => owner && typeof owner === "object" && owner[key] !== undefined && !failed.has(owner)) as Record<string, any>[]
  settled("dutiesOf").forEach(owner => delete next.duties[owner.dutiesOf])
  settled("depotOf").forEach(owner => delete next.depots[owner.depotOf])
  settled("depotRulesOf").forEach(owner => delete next.depotRules[owner.depotRulesOf])
  settled("platformStationOf").forEach(owner => delete next.platformStations[owner.platformStationOf])
  settled("platformSpeedOf").forEach(owner => delete next.platformSpeeds[owner.platformSpeedOf])
  settled("platformStopOf").forEach(owner => delete next.platformStops[owner.platformStopOf])
  if (settled("networkSettingsOf").length) next.networkSettings = null
  settled("contingenciesOf").forEach(owner => delete next.contingencies[owner.contingenciesOf])
  settled("boundaryOf").forEach(owner => delete next.boundaries[owner.boundaryOf])
  return {
    draft: next,
    boundaryErrors,
    result: {
      ok: Boolean(result.ok),
      general: (result.errors || []).filter((error: any) => error.index < 0).map((error: any) => error.message),
      applied: owners.filter(id => !failed.has(id)).length,
      created: (result.created || []).map((made: any) => made.id),
    },
  }
}

/** The game's answer for a change made in the game since the draft: "CONFLICT", or "CONFLICT:" and who made it (2026-11-14). */
export const isConflict = (message: string) => message === "CONFLICT" || (typeof message === "string" && message.startsWith("CONFLICT:"))
export const conflictBy = (message: string) => typeof message === "string" && message.startsWith("CONFLICT:") ? message.slice("CONFLICT:".length) : ""

/** The parts of a route the game checks the draft's changes against (EditorActions.EDITOR_PARTS). */
export const EDITOR_PARTS = ["timetable_services", "platform_ids", "is_light_rail_route"]

/**
 * Whether the game's route changed since the draft was made, in a part the game checks the draft's changes against: they
 * would be turned down if sent now. Told on the sync panel before they are (2026-11-14: tell whose edits it conflicts with).
 */
export function changedSince(entry: { parts?: Record<string, number> }, r: { parts?: Record<string, number> } | undefined): boolean {
  if (!r || !entry.parts || !r.parts) return false
  return EDITOR_PARTS.some(part => part in entry.parts! && entry.parts![part] !== (r.parts![part] || 0))
}

/** Keeps the draft over what changed in the game, or drops the draft for the game's version (resolveConflict). */
export function resolveConflict(model: Model | null, draft: Draft, id: string, keep: boolean): Draft {
  const entry = draft.routes[id], r = model && model.routeById.get(id)
  if (!entry) return draft
  const routes = { ...draft.routes }
  if (keep) {
    const kept = { ...entry, force: true }
    if (r) {
      kept.base = r.revision
      kept.parts = Object.assign({}, r.parts || {})
    }
    delete kept.conflict
    delete kept.conflictBy
    delete kept.error
    routes[id] = kept
  } else {
    delete routes[id]
  }
  return { ...draft, routes }
}

/** A part of the draft that can be dropped on its own, from the list of changes. */
export type DropTarget =
  | { kind: "route"; id: string }
  | { kind: "created"; index: number }
  | { kind: "networkSettings" }
  | { kind: "boundaries" }
  | { kind: "duties" | "depots" | "depotRules" | "contingencies"; key: string }
  | { kind: "platformSpeeds" | "platformStops" | "platformStations" }

export function drop(draft: Draft, target: DropTarget): Draft {
  const next = { ...draft }
  switch (target.kind) {
    case "route": next.routes = { ...draft.routes }; delete next.routes[target.id]; break
    case "created": next.created = draft.created.filter((_, i) => i !== target.index); break
    case "networkSettings": next.networkSettings = null; break
    case "boundaries": case "platformSpeeds": case "platformStops": case "platformStations": next[target.kind] = {}; break
    default: next[target.kind] = { ...draft[target.kind] }; delete next[target.kind][target.key]
  }
  return next
}

// ---------- What the draft changes, to read ----------

/** A route's timetable as the game has it, in the form the editor sends it back (fromGame, normalize). */
export function normalizeTimetable(timetable: any) {
  timetable.tables.forEach((table: any) => {
    table.services.sort((a: number, b: number) => a - b)
    table.returns.sort((a: number, b: number) => a - b)
    table.suspended = (table.suspended || []).slice().sort((a: number, b: number) => a - b)
    table.orders = table.orders || []
    table.orders.sort((a: any, b: any) => a.service - b.service || (a.kind < b.kind ? -1 : a.kind > b.kind ? 1 : 0))
    table.links.sort((a: any, b: any) => a.service - b.service)
    table.exceptions.sort((a: any, b: any) => a.service - b.service)
    table.exceptions.forEach((exception: any) => exception.stops.sort((a: any, b: any) => a.stop - b.stop))
  })
  return timetable
}

export function gameTimetable(r: any) {
  return normalizeTimetable({
    enabled: r.timetable, entryPlatform: r.entryPlatform, pass: r.stops.map((stop: any) => stop.pass), allowLonger: r.stops.map((stop: any) => Boolean(stop.allowLonger)),
    tables: r.tables.map((table: any) => ({
      name: table.name, weekdays: table.weekdays, marginPercent: table.marginPercent,
      minAcceleration: table.minAcceleration || 0, minDeceleration: table.minDeceleration || 0, minMaxSpeed: table.minMaxSpeed || 0,
      services: [...table.services], returns: [...table.returns], suspended: [...(table.suspended || [])],
      orders: (table.orders || []).map((order: any) => ({ ...order })),
      links: table.links.map((link: any) => ({ ...link })),
      stops: table.stops.map((stop: any) => ({ ...stop })),
      exceptions: (table.exceptions || []).map((exception: any) => ({ service: exception.service, stops: exception.stops.map((stop: any) => ({ stop: stop.stop, run: stop.run, dwell: stop.dwell, pass: stop.pass, overtake: stop.overtake })) })),
    })),
  })
}

/** One change, or a timetable with its changes under it. */
export type ChangeItem = string | { title: string; lines: string[] }

const orderDepotName = (model: Model, order: any) => {
  const depot = order && order.depot !== "0" ? model.depotById(order.depot) : null
  return depot ? shortName(depot.name) : tr("自动选的车厂")
}

/** What a route's draft changes from the game's version (routeChanges). */
export function routeChanges(model: Model, r: Route, entry: any): ChangeItem[] {
  const items: ChangeItem[] = []
  if (entry.deleted) items.push(tr("删除这个种别"))
  if ("managed" in entry) items.push(entry.managed ? tr("改由高级编辑器管理") : tr("交回游戏内编辑"))
  if ("connectingLine" in entry) items.push(entry.connectingLine ? tr("设为联络线") : tr("取消联络线"))
  if ("speedLimit" in entry) items.push(entry.speedLimit > 0 ? tr("限速 {0} km/h", entry.speedLimit) : tr("取消限速"))
  if (entry.connectingReleased) items.push(tr("联络线放行 {0} 段", entry.connectingReleased.length))
  if (entry.terminalPlatforms) items.push(tr("轮用站台 {0} 个", entry.terminalPlatforms.length))
  if (entry.startPlatforms) items.push(tr("优先股道 {0} 个", entry.startPlatforms.length))
  if (entry.platforms) {
    // Each platform by its station and number, from the stops that use it or could
    const names = new Map<string, string>()
    const platformName = (stop: any, platform: string) => `${shortName(stop.name)} ${tr("站台 {0}", platform || "?")}`
    model.routes.forEach(route => route.stops.forEach((stop: any) => {
      (stop.candidates || []).forEach((candidate: any) => names.set(candidate.id, platformName(stop, candidate.name)))
      if (stop.platform || !names.has(stop.platformId)) names.set(stop.platformId, platformName(stop, stop.platform))
    }))
    entry.platforms.forEach((id: string, i: number) => {
      const old = r.stops[i]
      if (old && old.platformId !== id) items.push(tr("第 {0} 站换股道：{1} → {2}", i + 1, names.get(old.platformId) || "?", names.get(id) || "?"))
    })
  }
  if (!entry.timetable) return items
  const before = gameTimetable(r), after = entry.timetable
  if (before.enabled !== after.enabled) items.push(after.enabled ? tr("启用时刻表") : tr("停用时刻表"))
  const passed = (after.pass || []).map((pass: boolean, i: number) => (pass !== Boolean((before.pass || [])[i]) ? tr("{0}改为{1}", stopName(r, i), pass ? tr("通过") : tr("停车")) : null)).filter(Boolean)
  if (passed.length) items.push(tr("停站：{0}", passed.join(tr("；"))))
  if (before.entryPlatform !== after.entryPlatform) items.push(tr("出库接入站改了"))
  const count = Math.max(before.tables.length, after.tables.length)
  for (let t = 0; t < count; t++) {
    const old = before.tables[t], now = after.tables[t]
    if (!now) {
      items.push(tr("删掉{0}（{1}，{2} 班）", tableName(old, t), daysText(old.weekdays), old.services.length))
      continue
    }
    const name = tableName(now, t)
    if (!old) {
      items.push(tr("新建{0}（{1}，{2} 班）", name, daysText(now.weekdays), now.services.length))
      continue
    }
    const lines: string[] = []
    if (old.name !== now.name) lines.push(tr("改名为“{0}”", now.name || tableName(now, t)))
    if (old.weekdays !== now.weekdays) lines.push(tr("星期 {0} → {1}", daysText(old.weekdays), daysText(now.weekdays)))
    if (old.marginPercent !== now.marginPercent) lines.push(tr("余裕 {0}% → {1}%", old.marginPercent, now.marginPercent))
    const oldServices = new Set(old.services), newServices = new Set(now.services)
    const added = now.services.filter((service: number) => !oldServices.has(service)), removed = old.services.filter((service: number) => !newServices.has(service))
    if (added.length) lines.push(tr("增加 {0} 班：{1}", added.length, someServices(model, added)))
    if (removed.length) lines.push(tr("删掉 {0} 班：{1}", removed.length, someServices(model, removed)))
    now.stops.forEach((stop: any, i: number) => {
      const was = old.stops[i] || { arrival: -1, departure: -1, overtake: false }
      const parts = []
      if (was.arrival !== stop.arrival) parts.push(tr("到 {0} → {1}", setTimeText(was.arrival), setTimeText(stop.arrival)))
      if (was.departure !== stop.departure) parts.push(tr("发 {0} → {1}", setTimeText(was.departure), setTimeText(stop.departure)))
      if (Boolean(was.overtake) !== Boolean(stop.overtake)) parts.push(stop.overtake ? tr("待避") : tr("不待避"))
      if (parts.length && i < r.stops.length) lines.push(tr("{0}：{1}", stopName(r, i), parts.join(tr("，"))))
    })
    const linkText = (link: any) => { const target = model.routeById.get(link.route); return tr("{0} 直通 {1}", serviceClock(model, link.service), target ? shortName(target.name) : "?") }
    const oldLinks = new Map(old.links.map((link: any) => [link.service, JSON.stringify(link)])), newLinks = new Map(now.links.map((link: any) => [link.service, JSON.stringify(link)]))
    now.links.filter((link: any) => oldLinks.get(link.service) !== JSON.stringify(link)).forEach((link: any) => lines.push(tr("设直通：{0}", linkText(link))))
    old.links.filter((link: any) => !newLinks.has(link.service)).forEach((link: any) => lines.push(tr("取消直通：{0}", linkText(link))))
    const oldReturns = new Set(old.returns), newReturns = new Set(now.returns)
    const returned = now.returns.filter((service: number) => !oldReturns.has(service)), unreturned = old.returns.filter((service: number) => !newReturns.has(service))
    if (returned.length) lines.push(tr("回库：{0}", someServices(model, returned)))
    if (unreturned.length) lines.push(tr("不再回库：{0}", someServices(model, unreturned)))
    const oldSuspended = new Set(old.suspended || []), newSuspended = new Set(now.suspended || [])
    const suspended = (now.suspended || []).filter((service: number) => !oldSuspended.has(service)), resumed = (old.suspended || []).filter((service: number) => !newSuspended.has(service))
    if (suspended.length) lines.push(tr("停运：{0}", someServices(model, suspended)))
    if (resumed.length) lines.push(tr("恢复运行：{0}", someServices(model, resumed)))
    const orderText = (order: any) => tr(order.kind === "deploy" ? "{0} 从{1}出车（{2}）" : "{0} 在{1}转回送（{2}）", serviceClock(model, order.service), stopName(r, order.stop), orderDepotName(model, order))
    const oldOrders = new Map((old.orders || []).map((order: any) => [order.service + order.kind, JSON.stringify(order)])), newOrders = new Map((now.orders || []).map((order: any) => [order.service + order.kind, JSON.stringify(order)]))
    ;(now.orders || []).filter((order: any) => oldOrders.get(order.service + order.kind) !== JSON.stringify(order)).forEach((order: any) => lines.push(tr("设{0}", orderText(order))))
    ;(old.orders || []).filter((order: any) => !newOrders.has(order.service + order.kind)).forEach((order: any) => lines.push(tr("取消{0}", orderText(order))))
    const oldExceptions = new Map(old.exceptions.map((exception: any) => [exception.service, JSON.stringify(exception)]))
    const newExceptions = new Map(now.exceptions.map((exception: any) => [exception.service, JSON.stringify(exception)]))
    now.exceptions.filter((exception: any) => oldExceptions.get(exception.service) !== JSON.stringify(exception)).forEach((exception: any) => {
      const stops = exception.stops.map((stop: any) => {
        const parts = []
        if (stop.run >= 0) parts.push(tr("运行 {0} 秒", stop.run))
        if (stop.dwell >= 0) parts.push(tr("停 {0} 秒", stop.dwell))
        if (stop.pass >= 0) parts.push(stop.pass ? tr("通过") : tr("停车"))
        if (stop.overtake) parts.push(tr("待避"))
        return parts.length && stop.stop < r.stops.length ? `${stopName(r, stop.stop)} ${parts.join(tr("，"))}` : null
      }).filter(Boolean)
      lines.push(tr("{0} 这一班：{1}", serviceClock(model, exception.service), stops.length ? stops.join(tr("；")) : tr("例外")))
    })
    old.exceptions.filter((exception: any) => !newExceptions.has(exception.service)).forEach((exception: any) => lines.push(tr("{0} 这一班的例外去掉", serviceClock(model, exception.service))))
    if (lines.length) items.push({ title: name, lines })
  }
  return items
}

// ---------- The list of changes (draftChangesDialog) ----------

export type ChangeSection = {
  key: string
  title: string
  error?: string
  conflict?: boolean
  items: ChangeItem[]
  /** A route to open, to see the change */
  route?: string
  drop?: DropTarget
}

const poolFleetText = (fleet: any, sum: number) => {
  if (fleet === null || fleet === undefined) return sum < 0 ? tr("配属按需要（存车线相加）") : tr("配属 {0} 列（存车线相加）", sum)
  return fleet < 0 ? tr("配属按需要") : tr("配属 {0} 列", fleet)
}
const stopPositionText = (position: number) => (position === 0 ? tr("停在出站端") : position < 0 ? tr("按车长居中停车") : tr("离出站端 {0} 格停车", position))

const topologyOf = (model: Model) => model.topology || { platforms: [], stations: [] }
export const platformText = (model: Model, platformId: string) => {
  // As the old page's index by ID: of platforms listed twice, the last one counts
  const platform = [...(topologyOf(model).platforms || [])].reverse().find((p: any) => String(p.id) === String(platformId))
  return platform ? `${shortName(platform.stationName) || ""} ${platform.name || ""}`.trim() : String(platformId)
}
export const stationNameOf = (model: Model, stationId: string) => {
  if (!stationId || stationId === "0") return tr("自动（按车站范围和最近的站台）")
  const station = (topologyOf(model).stations || []).find((each: any) => each.id === stationId)
  return station ? shortName(station.name) || tr("（未命名车站）") : tr("已删除的车站")
}
export function ownerText(model: Model, owner: string) {
  if (owner.startsWith("connecting:")) {
    const r = model.routeById.get(owner.slice("connecting:".length))
    return tr("联络线 {0}", r ? routeTitle(r) : owner.slice("connecting:".length))
  }
  const line = model.lines.find(l => l.key === owner)
  return line ? shortName(line.name) : owner.split("|").slice(1).join("|") || owner
}
function logicalLinesShown(model: Model, draft: Draft) {
  const lines = new Map<string, any>(((model.networkSettings as any).logicalLines || []).map((line: any) => [line.id, Object.assign({}, line)]))
  Object.entries((draft.networkSettings || {}).logicalLines || {}).forEach(([id, line]: [string, any]) => {
    if (line === null) lines.delete(id)
    else lines.set(id, Object.assign({ id }, line))
  })
  return lines
}
function systemTitle(model: Model, system: any) {
  const lineNames = [...new Set(system.routes.map((id: string) => model.routeById.get(id)).filter(Boolean).map((r: Route) => shortName(r.line)))]
  return lineNames.length > 3 ? tr("{0} 等 {1} 条线", lineNames.slice(0, 3).join("、"), lineNames.length) : lineNames.join("、")
}
function plansByAnchor(model: Model, anchor: string) {
  for (const system of model.contingencySystems || []) {
    if (system.anchor === anchor) return { anchor, plans: system.plans, system }
    const other = (system.others || []).find((each: any) => each.anchor === anchor)
    if (other) return { anchor, plans: other.plans, system }
  }
  return null
}

/** The draft's changes, part by part, compared with the game's version now. */
export function changeSections(model: Model, draft: Draft, boundaryErrors: Record<string, string> = {}): ChangeSection[] {
  const sections: ChangeSection[] = []
  Object.entries(draft.routes).forEach(([id, entry]) => {
    const r = model.routeById.get(id)
    if (!r) {
      sections.push({ key: "route:" + id, title: tr("已删除的线路"), items: [], drop: { kind: "route", id } })
      return
    }
    const items = routeChanges(model, r, entry)
    sections.push({
      key: "route:" + id, title: routeTitle(r), conflict: Boolean(entry.conflict),
      error: entry.conflict ? entry.error || tr("游戏里的这条线路在草稿之后改过") : entry.error,
      items: items.length ? items : [tr("没有和游戏不同的地方")], route: id,
      drop: entry.conflict ? undefined : { kind: "route", id },
    })
  })
  draft.created.forEach((made, index) => sections.push({
    key: "created:" + index, title: "＋ " + shortName(made.name), error: made.error,
    items: [made.source ? tr("新建种别：{0} 站，{1} 班", made.stops.length, (made.services || []).length) : tr("新建线路：{0} 站", made.stops.length)],
    drop: { kind: "created", index },
  }))
  Object.entries(draft.contingencies || {}).forEach(([anchor, plans]: [string, any]) => {
    const owner = plansByAnchor(model, anchor)
    sections.push({
      key: "contingencies:" + anchor,
      title: tr("路网{0}的故障预案", owner ? systemTitle(model, owner.system) : anchor) + (owner && owner.system.anchor !== anchor ? tr("（原来接进来的一套）") : ""),
      items: plans.length ? plans.map((plan: any) => tr("预案 {0}「{1}」：{2}", plan.id, plan.name, plan.manual ? tr("手动启用") : tr("自动启用"))) : [tr("删除全部预案")],
      drop: { kind: "contingencies", key: anchor },
    })
  })
  Object.entries(draft.depots || {}).forEach(([depotId, entry]) => {
    const depot: any = model.depotById(depotId)
    const routeName = (id: string) => (model.routeById.get(id) ? routeTitle(model.routeById.get(id)!) : tr("已删除的线路"))
    const lines = [tr("负责的线路：{0}", entry.routes.map(routeName).join("、") || tr("无")), entry.cycleRoutes ? tr("允许长时间停在线上") : tr("不在线上久等")]
    ;(depot ? depot.sidings : []).forEach((siding: any) => {
      const value = entry.sidings[siding.id]
      if (value) lines.push(tr("{0}：{1}，{2}", shortName(siding.name) || tr("存车线"), value.fleet < 0 ? tr("停车不限") : tr("最多停 {0} 列", value.fleet), value.routes.length ? tr("只跑：{0}", value.routes.map(routeName).join("、")) : tr("跑车厂的全部线路")))
    })
    ;(depot ? depot.pools || [] : []).forEach((pool: any) => {
      const fleet = entry.poolFleets ? entry.poolFleets[pool.key] : undefined
      if (fleet !== undefined) lines.push(tr("车型 {0}：{1}", pool.vehicle || pool.key, poolFleetText(fleet, pool.sum)))
    })
    Object.entries(entry.serviceTypes || {}).forEach(([vehicle, text]) => lines.push(tr("车型 {0} 的种别：{1}", vehicle, text || tr("不限"))))
    sections.push({ key: "depots:" + depotId, title: shortName(depot ? depot.name : "?"), error: entry.error, items: lines, drop: { kind: "depots", key: depotId } })
  })
  Object.entries(draft.duties).forEach(([depotId, entry]) => {
    const depot = model.depotById(depotId)
    const slots = Object.values(entry.duties.sidings || {}).reduce((a: number, list: any) => a + list.length, 0)
    sections.push({ key: "duties:" + depotId, title: shortName(depot ? depot.name : "?"), error: entry.error, items: [tr("运用改了：{0}，{1} 个运用", entry.duties.mode === "diagram" ? tr("按运用图") : tr("自动排班"), slots)], drop: { kind: "duties", key: depotId } })
  })
  const boundaryKeys = Object.keys(draft.boundaries || {})
  if (boundaryKeys.length) {
    const added = boundaryKeys.filter(key => draft.boundaries[key]).length
    const errors = boundaryKeys.map(key => boundaryErrors[key]).filter(Boolean)
    const items = []
    if (added) items.push(tr("设为寻路边界 {0} 段", added))
    if (boundaryKeys.length - added) items.push(tr("取消寻路边界 {0} 段", boundaryKeys.length - added))
    sections.push({ key: "boundaries", title: tr("隔离"), error: errors[0], items, drop: { kind: "boundaries" } })
  }
  const platformSpeedKeys = Object.keys(draft.platformSpeeds || {})
  if (platformSpeedKeys.length) {
    sections.push({ key: "platformSpeeds", title: tr("过站限速"), items: platformSpeedKeys.map(platformId => tr("{0} 过站限速 {1} km/h", platformText(model, platformId), draft.platformSpeeds[platformId])), drop: { kind: "platformSpeeds" } })
  }
  if (draft.networkSettings) {
    const settings = draft.networkSettings, saved: any = model.networkSettings || {}
    const items: string[] = []
    if (Number.isFinite(settings.minReverseMillis)) items.push(tr("换端最短时间 {0} 秒", Math.round(settings.minReverseMillis / 1000)))
    if (Number.isFinite(settings.waitThresholdMillis)) items.push(tr("等候分界 {0} 秒", Math.round(settings.waitThresholdMillis / 1000)))
    Object.entries(settings.stationWaitThresholds || {}).forEach(([station, millis]: [string, any]) => items.push(millis === null ? tr("{0}：等候分界改回全网的", stationNameOf(model, station)) : tr("{0}：等候分界 {1} 秒", stationNameOf(model, station), Math.round(millis / 1000))))
    Object.entries(settings.logicalLines || {}).forEach(([id, line]: [string, any]) => {
      const was = (saved.logicalLines || []).find((each: any) => each.id === id)
      items.push(line === null ? tr("删除逻辑线路 {0}", was ? was.name : id) : was ? tr("逻辑线路 {0}：改为 {1}{2}", was.name, line.name, line.connecting ? tr("（联络线）") : "") : tr("新建逻辑线路 {0}{1}", line.name, line.connecting ? tr("（联络线）") : ""))
    })
    const railLineCount = Object.keys(settings.railLines || {}).length
    if (railLineCount) items.push(tr("{0} 段轨道改了所属的逻辑线路", railLineCount))
    const shown = logicalLinesShown(model, draft)
    Object.entries(settings.lineTracks || {}).forEach(([lineKey, ids]: [string, any]) => {
      const line = model.lines.find(each => each.key === lineKey)
      const name = line ? shortName(line.name) : lineKey
      items.push(ids === null ? tr("{0}：按站台所在的逻辑线路走", name) : tr("{0}：走逻辑线路 {1}", name, ids.map((id: string) => (shown.get(id) || { name: id }).name).join(tr("、"))))
    })
    Object.entries(settings.ownerOverrides || {}).forEach(([key, lines]: [string, any]) => items.push(lines === null ? tr("轨道 {0}：区段归属恢复自动", key) : lines.length ? tr("轨道 {0}：区段归属 {1}", key, lines.map((owner: string) => ownerText(model, owner)).join(tr("、"))) : tr("轨道 {0}：区段对所有线路开放", key)))
    sections.push({ key: "networkSettings", title: tr("全网设置"), items, drop: { kind: "networkSettings" } })
  }
  const platformStopKeys = Object.keys(draft.platformStops || {})
  if (platformStopKeys.length) {
    sections.push({ key: "platformStops", title: tr("停车位置"), items: platformStopKeys.map(platformId => platformText(model, platformId) + " " + stopPositionText(draft.platformStops[platformId])), drop: { kind: "platformStops" } })
  }
  const platformStationKeys = Object.keys(draft.platformStations || {})
  if (platformStationKeys.length) {
    sections.push({ key: "platformStations", title: tr("站台归属"), items: platformStationKeys.map(platformId => tr("{0} 归到 {1}", platformText(model, platformId), stationNameOf(model, draft.platformStations[platformId]))), drop: { kind: "platformStations" } })
  }
  return sections
}
