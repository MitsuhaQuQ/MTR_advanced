/**
 * Names, times and days as the editor writes and reads them (retiring the old code, step 2: the old clock,
 * durationText, parseClock, typedTime, parsePattern, formatDuration, daysText, tableName, routeTitle, stopName,
 * orderDepotName, systemTitle and the weekday names). Those that turn a time of the operating day into the clock and back
 * take the day's start and the time zone's offset (`Day`) instead of reading the old code's data. test/format.test.ts
 * runs the old ones beside these.
 */
import { tr, trc } from "../i18n"
import { DAY, mod, shortName, colorOf, daysText, tableName, routeTitle, stopName } from "./model"

export { DAY, mod, shortName, colorOf, daysText, tableName, routeTitle, stopName }

/** Where the operating day starts, and the time zone's offset from the game's clock */
export type Day = { dayStartMillis: number; offsetMillis: number }

export const WEEKDAY_NAMES_ZH = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
export const WEEKDAY_SHORT_ZH = ["一", "二", "三", "四", "五", "六", "日"]
/** The weekdays' names in the language shown, read each time */
export const weekdayName = (day: number) => tr(WEEKDAY_NAMES_ZH[day])
export const weekdayShort = (day: number) => tr(WEEKDAY_SHORT_ZH[day])

/** A time of the operating day on the clock: "07:05", or "07:05:30" with seconds when there are any */
export function clock(day: Day, millis: number, seconds = false) {
  const local = mod(millis + day.dayStartMillis + day.offsetMillis, DAY)
  const h = Math.floor(local / 3600000), m = Math.floor(local / 60000) % 60, sec = Math.floor(local / 1000) % 60
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}${seconds && sec ? ":" + String(sec).padStart(2, "0") : ""}`
}

/** "45 秒", "3 分钟", "3 分 20 秒" */
export function durationText(millis: number) {
  const seconds = Math.round(millis / 1000), minutes = Math.floor(seconds / 60), rest = seconds % 60
  return !minutes ? tr("{0} 秒", rest) : rest ? tr("{0} 分 {1} 秒", minutes, rest) : tr("{0} 分钟", minutes)
}
/** "3 分 20 秒" from a minute on, else "45 秒" */
export const formatDuration = (millis: number) => (millis >= 60000 ? tr("{0} 分 {1} 秒", Math.floor(millis / 60000), Math.round((millis % 60000) / 1000)) : tr("{0} 秒", Math.round(millis / 1000)))

/** A time typed on the clock (07:10, 7:10:30, 0710, full-width digits and colons), as millis from midnight; null if not one */
export function parseClock(text: string): number | null {
  const clean = text.replace(/[０-９]/g, d => String.fromCharCode(d.charCodeAt(0) - 0xff10 + 48)).replace(/[：．。.]/g, ":").replace(/\s+/g, "")
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(clean) || /^(\d{2})(\d{2})(\d{2})?$/.exec(clean)
  if (!m || +m[1] > 29 || +m[2] > 59 || +(m[3] || 0) > 59) return null
  return ((+m[1] * 60 + +m[2]) * 60 + +(m[3] || 0)) * 1000
}

/** A time typed for a cell, as the time of the operating day nearest the one it had */
export function typedTime(day: Day, text: string, near: number): number | null {
  const local = parseClock(text)
  if (local === null) return null
  const oldLocal = mod(near + day.dayStartMillis + day.offsetMillis, DAY)
  return near + mod(local - oldLocal + DAY / 2, DAY) - DAY / 2
}

/** "07:10" or "07:10 + 10 * 00:03" (full-width signs too) as the services' times in the game's day; null if not one */
export function parsePattern(day: Day, value: string): number[] | null {
  const text = value.replace(/[０-９]/g, d => String.fromCharCode(d.charCodeAt(0) - 0xff10 + 48)).replace(/[：]/g, ":").replace(/＋/g, "+").replace(/[＊×]/g, "*")
  const m = /^\s*([\d:]+)\s*(?:\+\s*(\d+)\s*\*\s*([\d:]+))?\s*$/.exec(text)
  const first = m && parseClock(m[1]), step = m && m[3] ? parseClock(m[3]) : 0, count = m && m[2] ? +m[2] : 0
  if (first === null || first === undefined || (m![3] && !step) || count > 1000) return null
  return Array.from({ length: count + 1 }, (_, k) => mod(first + k * (step || 0) - day.offsetMillis, DAY))
}

type Named = { id: string; name: string }
/** The depot an order to or from a depot names, or "自动选的车厂" */
export function orderDepotName(data: { depots: Named[] }, order: { depot: string } | null | undefined) {
  const depot = order && order.depot !== "0" ? data.depots.find(d => d.id === order.depot) : null
  return depot ? shortName(depot.name) : tr("自动选的车厂")
}

/** A rail system by its lines: "1号线、2号线", or "1号线、2号线、3号线 等 5 条线" */
export function systemTitle(data: { routeById: Map<string, { line: string }> }, system: { routes: string[] }) {
  const lineNames = [...new Set(system.routes.map(id => data.routeById.get(id)).filter(Boolean).map(r => shortName(r!.line)))]
  return lineNames.length > 3 ? tr("{0} 等 {1} 条线", lineNames.slice(0, 3).join("、"), lineNames.length) : lineNames.join("、")
}

export { trc }
