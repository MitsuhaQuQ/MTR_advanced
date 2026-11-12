/**
 * src/core/services.ts, the rows and services: line shapes made for it (a branch, another way between two stations, a
 * loop, a Q back to the same platform and to another, routes both ways, going back the way it came), and the mock's
 * routes with exceptions, suspended services, orders and links added, every weekday. Until step 9 of retiring the old
 * code they were compared with the old page's own; the snapshots were taken when they still matched.
 */
import { describe, expect, it } from "vitest"
import * as S from "../src/core/services"
import { gameTimetable } from "../src/core/timetable"
import { mockData } from "../mock/fixture"
import { fingerprint } from "./fingerprint"

const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value))

/** A route through stations given as "A", or "A:2" for platform 2 of A; running times of 120 s, dwell 30 s */
function route(id: string, stations: string[]) {
  const stops = stations.map(s => { const [station, platform = "1"] = s.split(":"); return { station, name: station + "站", platformId: station + platform } })
  const plans = stops.map((_, k) => [k * 150000, k * 150000 + (k ? 30000 : 0)])
  return { id, stops, hasTimes: true, tables: [{ plans }] }
}
const SHAPES: Record<string, string[][]> = {
  straight: [["A", "B", "C", "D"], ["D", "C", "B", "A"]],
  branch: [["A", "B", "C", "D"], ["A", "B", "E", "F"], ["F", "E", "B", "A"]],
  another: [["A", "B", "C", "D", "E"], ["A", "B", "X", "D", "E"]],
  loop: [["A", "B", "C", "D", "A"]],
  qSame: [["A", "B", "C", "D", "E", "C"]],
  qOther: [["A", "B", "C", "D", "E", "C:2", "B:2", "A:2"]],
  retrace: [["A", "B", "C", "B", "A"]],
  short: [["A", "B", "C", "D", "E"], ["B", "C", "D"], ["E", "D", "C"]],
  mixed: [["A", "B", "C", "D"], ["C", "Y", "Z"], ["A", "B", "C", "D", "A"], ["Z", "Y", "C", "B"]],
}

describe("rows and services", () => {
  it("lays every line shape out in the rows the old page did", () => {
    const got: unknown[] = []
    for (const [name, routes] of Object.entries(SHAPES)) {
      const line: any = { key: name, routes: routes.map((stations, i) => route(name + i, stations)) }
      const mine = copy(line)
      S.buildStations(mine)
      got.push([name, mine.rows, mine.routes.map((r: any) => [r.rows, r.leaveRows])])
    }
    // And the mock's lines
    const data = mockData()
    S.groupLines(copy(data.routes)).forEach(line => {
      const mine = copy(line)
      S.buildStations(mine)
      got.push([line.key, mine.rows])
    })
    expect(fingerprint(got)).toMatchSnapshot()
  })

  it("groups lines as the old page does", () => {
    const data = mockData()
    const grouped = S.groupLines(data.routes)
    expect(grouped.map(l => [l.key, l.name, l.routes.map((r: any) => r.id)])).toEqual([["TRAIN|1号线|Line 1", "1号线|Line 1", ["101", "102", "103"]], ["TRAIN|2号线|Line 2", "2号线|Line 2", ["201", "202"]]])
  })

  it("makes the services the old page did every weekday, exceptions, suspensions, orders and links included", () => {
    const data: any = mockData()
    // Route 101: one service with times of its own, one suspended, an order from a depot, a link on to route 102
    const r = data.routes[0], table = r.tables[0], service = table.services[3]
    const own = r.stops.map((_: unknown, k: number) => [k * 200000, k * 200000 + 45000, 0, k === 2 ? 1 : 0, 0])
    r.tables[0].exceptions = [{ service, stops: [{ stop: 2, run: -1, dwell: 45, pass: 1, overtake: -1 }], times: own }]
    table.suspended = [table.services[5]]
    table.orders = [{ service: table.services[6], kind: "deploy", stop: 1, depot: "9001" }]
    table.links = [{ service: table.services[7], route: "102", toService: data.routes[1].tables[0].services[9], fromStop: -1, toStop: 0 }]
    table.returns = [table.services[8]]
    data.routeById = new Map(data.routes.map((each: any) => [each.id, each]))
    const strip = (s: any) => ({ ...s, route: s.route.id, table: s.tableIndex, exceptions: s.exceptions ? [...s.exceptions.entries()] : null })
    const got: unknown[] = []
    for (let weekday = 0; weekday < 7; weekday++) {
      const mine = copy(data)
      mine.routeById = new Map(mine.routes.map((each: any) => [each.id, each]))
      mine.routes.forEach((each: any) => { each.services = S.routeServices(each, gameTimetable(each), S.gameSource(each), weekday, mine.dayStartMillis) })
      mine.routes.forEach((each: any) => {
        got.push([weekday, each.id, each.services.map(strip)])
        each.services.forEach((s: any) => expect(S.timesOf(s)).toEqual(s.plans.map((p: any) => [s.start + p.arrival, s.start + p.departure])))
      })
      // Found by id
      const found = mine.routes[0].services[2]
      expect(S.findService(mine.routeById, found.id).id).toBe(found.id)
      expect(S.findService(mine.routeById, "101@1")).toBe(null)
    }
    expect(fingerprint(got)).toMatchSnapshot()
  })
})
