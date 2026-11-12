/**
 * src/core/format.ts: names, times and days. Until step 9 of retiring the old code these were checked against the old
 * page's own functions; the snapshots were taken when they still matched, so they hold the old page's behaviour.
 */
import { describe, expect, it } from "vitest"
import * as F from "../src/core/format"
import { mockData } from "../mock/fixture"

function withDay(day: F.Day) {
  const raw = mockData()
  return { ...raw, ...day, routeById: new Map(raw.routes.map(r => [r.id, r])) }
}

describe("names, times and days", () => {
  const days: F.Day[] = [{ dayStartMillis: 4 * 3600000, offsetMillis: 8 * 3600000 }, { dayStartMillis: 0, offsetMillis: 0 }, { dayStartMillis: 3 * 3600000 + 1800000, offsetMillis: -5 * 3600000 }]
  it("writes times on the clock as the old page did", () => {
    const shown: string[] = []
    for (const day of days) {
      for (let millis = -7200000; millis < F.DAY + 7200000; millis += 37 * 60000 + 13000) shown.push(`${millis} ${F.clock(day, millis)} ${F.clock(day, millis, true)}`)
    }
    expect(shown).toMatchSnapshot()
  })
  it("reads typed times and patterns as the old page did", () => {
    const typed = ["07:10", "7:10", "0710", "071030", "７：１０", "23:59:59", "29:00", "30:00", "7:60", "abc", "", "07.10", " 07 : 10 ", "06:05:5"]
    const patterns = ["07:10", "07:10 + 10 * 00:03", "07:10＋3＊00:05", "06:00 + 2000 * 00:01", "07:10 + 3 * 00:00", "x", "05:00 + 5 * 0:30"]
    const read: unknown[] = []
    for (const day of days) {
      for (const text of typed) {
        read.push([text, F.parseClock(text), [0, 3 * 3600000, 20 * 3600000, F.DAY - 60000].map(near => F.typedTime(day, text, near))])
      }
      for (const text of patterns) read.push([text, F.parsePattern(day, text)])
    }
    expect(read).toMatchSnapshot()
  })
  it("writes durations, days and names as the old page did", () => {
    const data = withDay(days[0])
    const shown: unknown[] = []
    for (const millis of [0, 400, 1500, 45000, 59600, 60000, 125000, 180000, 3605000]) shown.push([millis, F.durationText(millis), F.formatDuration(millis)])
    for (let mask = 0; mask < 128; mask += 7) shown.push([mask, F.daysText(mask)])
    for (let day = 0; day < 7; day++) shown.push([F.weekdayName(day), F.weekdayShort(day)])
    data.routes.forEach((r: any) => {
      shown.push(F.routeTitle(r))
      r.stops.forEach((_: unknown, i: number) => shown.push(F.stopName(r, i)))
      r.tables.forEach((t: any, i: number) => shown.push([F.tableName(t, i), F.tableName({}, i)]))
    })
    for (const order of [null, { depot: "0" }, { depot: "9001" }, { depot: "404" }]) shown.push(F.orderDepotName(data, order))
    for (const routes of [["101"], ["101", "201"], ["101", "102", "201", "999"]]) shown.push(F.systemTitle(data, { routes }))
    for (const name of ["港湾|Harbour", "", null, "A||B|C", "|x"]) shown.push(F.shortName(name))
    expect(shown).toMatchSnapshot()
  })
})
