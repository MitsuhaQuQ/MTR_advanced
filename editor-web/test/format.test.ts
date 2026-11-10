/** Parity of src/core/format.ts with the old page's names, times and days (retiring the old code, step 2). */
import { describe, expect, it } from "vitest"
import { loadOld } from "./old"
import * as F from "../src/core/format"
import { mockData } from "../mock/fixture"

const NAMES = ["DAY", "mod", "clock", "durationText", "parseClock", "typedTime", "parsePattern", "formatDuration", "daysText", "tableName", "routeTitle", "stopName", "orderDepotName", "depotById", "systemTitle", "shortName", "WEEKDAY_NAMES", "WEEKDAY_SHORT"]

function both(day: F.Day) {
  const raw = mockData()
  const data = { ...raw, ...day, routeById: new Map(raw.routes.map(r => [r.id, r])) }
  return { old: loadOld<any>(NAMES, { data }), data }
}

describe("names, times and days", () => {
  const days: F.Day[] = [{ dayStartMillis: 4 * 3600000, offsetMillis: 8 * 3600000 }, { dayStartMillis: 0, offsetMillis: 0 }, { dayStartMillis: 3 * 3600000 + 1800000, offsetMillis: -5 * 3600000 }]
  it("writes times on the clock as the old page does", () => {
    for (const day of days) {
      const { old } = both(day)
      for (let millis = -7200000; millis < F.DAY + 7200000; millis += 37 * 60000 + 13000) {
        expect(F.clock(day, millis)).toBe(old.clock(millis))
        expect(F.clock(day, millis, true)).toBe(old.clock(millis, true))
      }
    }
  })
  it("reads typed times and patterns as the old page does", () => {
    const typed = ["07:10", "7:10", "0710", "071030", "７：１０", "23:59:59", "29:00", "30:00", "7:60", "abc", "", "07.10", " 07 : 10 ", "06:05:5"]
    const patterns = ["07:10", "07:10 + 10 * 00:03", "07:10＋3＊00:05", "06:00 + 2000 * 00:01", "07:10 + 3 * 00:00", "x", "05:00 + 5 * 0:30"]
    for (const day of days) {
      const { old } = both(day)
      for (const text of typed) {
        expect(F.parseClock(text)).toBe(old.parseClock(text))
        for (const near of [0, 3 * 3600000, 20 * 3600000, F.DAY - 60000]) expect(F.typedTime(day, text, near)).toBe(old.typedTime(text, near))
      }
      for (const text of patterns) expect(F.parsePattern(day, text)).toEqual(old.parsePattern(text))
    }
  })
  it("writes durations, days and names as the old page does", () => {
    const { old, data } = both(days[0])
    for (const millis of [0, 400, 1500, 45000, 59600, 60000, 125000, 180000, 3605000]) {
      expect(F.durationText(millis)).toBe(old.durationText(millis))
      expect(F.formatDuration(millis)).toBe(old.formatDuration(millis))
    }
    for (let mask = 0; mask < 128; mask += 7) expect(F.daysText(mask)).toBe(old.daysText(mask))
    for (let day = 0; day < 7; day++) {
      expect(F.weekdayName(day)).toBe(old.WEEKDAY_NAMES[day])
      expect(F.weekdayShort(day)).toBe(old.WEEKDAY_SHORT[day])
    }
    data.routes.forEach((r: any) => {
      expect(F.routeTitle(r)).toBe(old.routeTitle(r))
      r.stops.forEach((_: unknown, i: number) => expect(F.stopName(r, i)).toBe(old.stopName(r, i)))
      r.tables.forEach((t: any, i: number) => { expect(F.tableName(t, i)).toBe(old.tableName(t, i)); expect(F.tableName({}, i)).toBe(old.tableName({}, i)) })
    })
    for (const order of [null, { depot: "0" }, { depot: "9001" }, { depot: "404" }]) expect(F.orderDepotName(data, order)).toBe(old.orderDepotName(order))
    for (const routes of [["101"], ["101", "201"], ["101", "102", "201", "999"]]) expect(F.systemTitle(data, { routes })).toBe(old.systemTitle({ routes }))
    for (const name of ["港湾|Harbour", "", null, "A||B|C", "|x"]) expect(F.shortName(name)).toBe(old.shortName(name))
  })
})
