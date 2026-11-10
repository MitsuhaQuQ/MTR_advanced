/**
 * The names, times and days of core/format.ts for the views, with the operating day of the data the page shows now
 * (until step 3 of retiring the old code moves the data model over, it is the legacy engine's).
 */
import { legacy } from "../legacy/engine"
import * as F from "../core/format"

const day = (): F.Day => legacy.v.data || { dayStartMillis: 0, offsetMillis: 0 }

export const clock = (millis: number, seconds = false) => F.clock(day(), millis, seconds)
export const typedTime = (text: string, near: number) => F.typedTime(day(), text, near)
export const parsePattern = (value: string) => F.parsePattern(day(), value)
export const orderDepotName = (order: { depot: string } | null | undefined) => F.orderDepotName(legacy.v.data, order)
export const systemTitle = (system: { routes: string[] }) => F.systemTitle(legacy.v.data, system)
/** The weekdays' short names in the language shown: 一 二 三 … */
export const weekdaysShort = () => F.WEEKDAY_SHORT_ZH.map((_, i) => F.weekdayShort(i))

export const { shortName, colorOf, mod, daysText, tableName, routeTitle, stopName, durationText, parseClock, formatDuration, weekdayName, weekdayShort, trc, DAY } = F
