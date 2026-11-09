/**
 * The game's data as the pages use it: api/data plus the lookups the old page's setData builds (routeById, lines).
 * Logic moved from editor.js takes a Model where the old code read its global `data`.
 */
import type { Depot, EditorData, Route } from "../api/types"
import { tr, trc } from "../i18n"

export type Line = { key: string; name: string; color: number; routes: Route[] }
export type Model = EditorData & {
  routeById: Map<string, Route>
  depotById: (id: string) => Depot | undefined
  lines: Line[]
  // The rest of api/data, untyped until the layer that reads it is moved
  [other: string]: any
}

export function buildModel(data: EditorData): Model {
  const routeById = new Map(data.routes.map(r => [r.id, r]))
  const lines = new Map<string, Line>()
  data.routes.forEach(r => {
    if (!lines.has(r.lineKey)) lines.set(r.lineKey, { key: r.lineKey, name: r.line, color: r.lineColor, routes: [] })
    lines.get(r.lineKey)!.routes.push(r)
  })
  return {
    ...data,
    routeById,
    depotById: id => data.depots.find(depot => depot.id === id),
    lines: [...lines.values()].sort((a, b) => a.name.localeCompare(b.name)),
  }
}

export const DAY = 24 * 60 * 60 * 1000
export const mod = (a: number, b: number) => ((a % b) + b) % b
/** Names written as "中文|English" show their first part. */
export const shortName = (name: unknown) => String(name || "").split("||")[0].split("|")[0] || tr("（未命名）")
export const colorOf = (value: number) => "#" + (value & 0xffffff).toString(16).padStart(6, "0")

const WEEKDAY_SHORT = ["一", "二", "三", "四", "五", "六", "日"]

/** A time in the operating day as the local time of day (clock in editor.js). */
export const clock = (model: Model, millis: number, seconds = false) => {
  const local = mod(millis + model.dayStartMillis + model.offsetMillis, DAY)
  const h = Math.floor(local / 3600000), m = Math.floor(local / 60000) % 60, sec = Math.floor(local / 1000) % 60
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}${seconds && sec ? ":" + String(sec).padStart(2, "0") : ""}`
}
export const serviceClock = (model: Model, service: number) => clock(model, mod(service - model.dayStartMillis, DAY), true)
/** A few times, and how many more. */
export const someServices = (model: Model, services: number[]) => {
  const shown = services.slice(0, 12).map(s => serviceClock(model, s)).join(tr("、"))
  return services.length > 12 ? tr("{0} 等 {1} 班", shown, services.length) : shown
}
export const daysText = (mask: number) => (mask ? WEEKDAY_SHORT.filter((_, i) => (mask >> i) & 1).map(day => tr(day)).join(trc("weekday separator", "")) : tr("不运行"))
export const tableName = (table: { name?: string }, index: number) => table.name || tr("表 ") + (index + 1)
export const setTimeText = (seconds: number) => (seconds < 0 ? tr("跟前一站") : `+${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`)
export const routeTitle = (r: Route) => {
  const last = r.stops.length ? shortName(r.stops[r.stops.length - 1].name) : ""
  return shortName(r.name) + (last ? " → " + last : "")
}
export const stopName = (r: Route, stop: number) => shortName(r.stops[stop].name)
