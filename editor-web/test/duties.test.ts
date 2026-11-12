/**
 * src/core/duties.ts, the duty rules: on the mock's duties, and on them made wrong in each way a duty can be, every
 * weekday, the coverage, terminal pairs, problems, numbers, edits and stop renumbering. Until step 9 of retiring the old
 * code they were compared with the old page's own functions; the snapshots were taken when they still matched.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { beforeAll, describe, expect, it } from "vitest"
import * as D from "../src/core/duties"
import * as T from "../src/core/timetable"
import * as S from "../src/core/services"
import { mockData } from "../mock/fixture"
import { fingerprint } from "./fingerprint"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../common/src/main/resources/assets/mtr/editor")
const script = (file: string) => fs.readFileSync(path.join(EDITOR, file), "utf8").replace(/^"use strict";/m, "").replace(/^const /gm, "var ")
beforeAll(() => { (0, eval)(script("batch.js")); (0, eval)(script("terminal.js")) })

const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
type Side = { data: any; draft: any; state: any }

/** The mock's data, changed by @param change before the services are built */
function sides(weekday: number, change: (data: any) => void = () => {}) {
  const data: any = mockData()
  change(data)
  data.routeById = new Map(data.routes.map((r: any) => [r.id, r]))
  const mine: Side = { data, draft: { routes: {}, duties: {} }, state: { weekday, selected: null, slot: null } }
  mine.data.routes.forEach((r: any) => { r.services = S.routeServices(r, T.timetableOf(mine.draft, r), S.gameSource(r), weekday, mine.data.dayStartMillis) })
  return { mine }
}

const sets = (c: D.Coverage) => ({ weekday: c.weekday, routes: [...c.routes].sort(), noTrain: [...c.noTrain].sort() })
const slotsOf = (data: any) => data.depots.flatMap((depot: any) => Object.entries(D.dutiesOf({ duties: {} } as any, depot).sidings).flatMap(([siding, slots]: any) => slots.map((_: unknown, index: number) => ({ depot, siding, index }))))

/**
 * The mock's duties made wrong in each way: a service taken twice and overlapping, one from another station, short
 * turns, a changed platform with no way and one too short, overnight to a duty gone or not starting where it stays,
 * two staying overnight for one train
 */
function wrong(data: any) {
  const depot = data.depots[0], sidings = depot.duties.sidings
  const MIN = 60000
  const s = (route: string, minutes: number, from = 0, to = -1) => ({ route, service: minutes * MIN, from, to })
  sidings["8001"][0][0].segments.splice(1, 0, s("101", 6 * 60 + 12))
  sidings["8001"][1][0].segments.push(s("201", 9 * 60))
  sidings["8001"][2][0].segments.push(s("101", 22 * 60), s("102", 22 * 60 + 23), s("103", 23 * 60, 1))
  sidings["8002"][0][0].stay = true
  sidings["8002"][0][0].nextDuty = "99"
  sidings["8002"][1][0].stay = true
  sidings["8002"][1][0].nextDuty = "6"
  sidings["8003"][0][0].next = 0
  sidings["8003"].push([{ days: 96, stay: true, next: 0, duty: "7", segments: [s("101", 6 * 60), s("102", 7 * 60 + 2), s("101", 7 * 60 + 25, 2)] }])
  // From the north end of 101 to 102 takes ten minutes; from the south end of 102 back to 101 there is no way
  depot.path = { ...depot.path, turnTimesMeasured: true, turnTimes: { "1005": { "1105": 400000 } }, holdIns: {}, holdCounts: {} }
  // A suspended service in a duty, and a loop
  data.routes[1].tables[0].suspended = [data.routes[1].tables[0].services[3]]
  data.routes.forEach((r: any) => { r.tables[1].suspended = [...r.tables[1].services.slice(0, 3)] })
}

/** Route 103 made a loop back to its first platform, a duty going round it, and changing ends taking an hour */
function loop(data: any) {
  const r = data.routes[2]
  r.circular = true
  // 101 and 102 marked loops, 102 leaving from where 101 comes round: going round from one to the other
  data.routes[0].circular = data.routes[1].circular = true
  data.routes[1].stops[0] = { ...data.routes[1].stops[0], platformId: data.routes[0].stops[5].platformId }
  r.stops[r.stops.length - 1] = { ...r.stops[0] }
  data.networkSettings.minReverseMillis = 3600000
  const services = r.tables[0].services
  data.depots[0].duties.sidings["8003"].push([{ days: 127, stay: false, next: -1, duty: "8", segments: services.slice(10, 14).map((service: number) => ({ route: "103", service, from: 0, to: -1 })) }])
}
const VARIANTS = [() => {}, wrong, loop]

describe("duty rules", () => {
  it("covers, pairs and finds problems as the old page did, every weekday, on the mock's duties and on wrong ones", () => {
    for (const change of VARIANTS) for (let weekday = 0; weekday < 7; weekday++) {
      const { mine } = sides(weekday, change)
      const found = slotsOf(mine.data).map(({ depot, siding, index }: any) => D.dutyProblems(mine.data, mine.draft, depot, siding, index, weekday))
      if (change === wrong && weekday === 0) expect(found.flat().length).toBeGreaterThan(5)
      expect(fingerprint({
        coverage: sets(D.coverage(mine.data, mine.draft, weekday)),
        assignments: [...D.dutyAssignments(mine.data, mine.draft, weekday)],
        terminal: D.terminalPairs(mine.data, mine.draft, weekday),
        problems: found,
        numbers: slotsOf(mine.data).map(({ depot, siding, index }: any) => D.dutyNumber(mine.draft, depot, siding, index)),
        dayGroups: mine.data.depots.map((depot: any) => D.dayGroup(mine.data, mine.draft, depot, weekday)),
      }), `${VARIANTS.indexOf(change)} ${weekday}`).toMatchSnapshot()
    }
  })
  it("edits duties as the old page did: split off a day group, remove and reorder segments, stay overnight, add a train, back again", () => {
    type Step = (duties: any, forEditing: (slot: any[]) => any) => unknown
    const steps: [number, Step][] = [
      [5, (duties, forEditing) => { forEditing(duties.sidings["8001"][0]).segments.pop(); return "去掉一班" }],
      [5, (duties, forEditing) => { const day = forEditing(duties.sidings["8001"][1]); day.segments.unshift(day.segments.pop()) }],
      [2, (duties, forEditing) => { const day = forEditing(duties.sidings["8002"][0]); day.stay = true; day.nextDuty = "6" }],
      [2, (duties, forEditing) => { duties.sidings["8003"].push([]); forEditing(duties.sidings["8003"][1]).segments.push({ route: "103", service: 9 * 3600000, from: 0, to: -1 }) }],
      [2, (duties, forEditing) => { duties.sidings["8003"].splice(1, 1); const day = forEditing(duties.sidings["8002"][0]); day.stay = false; day.nextDuty = "0" }],
    ]
    const { mine } = sides(0)
    for (const [weekday, step] of steps) {
      mine.state.weekday = weekday
      const b = mine.data.depots[0]
      const message = D.applyDutyEdit(mine.data, mine.draft, b, duties => step(duties, slot => D.dutyForEditing(mine.data, mine.draft, b, slot, weekday)))
      // A new train's number is random
      const ids = (draft: any) => JSON.stringify(draft).replace(/"duty":"\d{16}"/g, '"duty":"new"')
      expect([message, fingerprint(JSON.parse(ids(mine.draft)))]).toMatchSnapshot()
    }
    expect(D.findDutySlot(D.dutiesOf(mine.draft, mine.data.depots[0]), "3")).toMatchSnapshot()
    expect(D.poolSidings(mine.data.depots[0], "8002")).toMatchSnapshot()
  })
  it("finds what keeps a stop and renumbers to new stops as the old page did", () => {
    const { mine } = sides(0)
    const link = (data: any, gameTimetable: (r: any) => any) => {
      const tt = gameTimetable(data.routeById.get("101"))
      tt.tables[0].links.push({ service: tt.tables[0].services[1], route: "102", toService: 1, fromStop: 3, toStop: 2 })
      tt.tables[0].orders = [{ service: tt.tables[0].services[2], kind: "deploy", stop: 2, depot: "0" }]
      data.depots[0].duties.sidings["8001"][0][0].segments[0].to = 4
      data.depots[0].duties.sidings["8001"][1][0].segments[0].from = 2
    }
    link(mine.data, T.gameTimetable)
    expect(copy(([["101", 0], ["101", 2], ["101", 3], ["101", 4], ["102", 2], ["103", 1]] as [string, number][])
      .map(([route, stop]) => D.stopHangers(mine.data, mine.draft, mine.data.routeById.get(route), stop)))).toMatchSnapshot()
    const list = [{ platformId: "n", from: -1, pass: false }, { platformId: "a", from: 0, pass: false }, { platformId: "b", from: 2, pass: true }, { platformId: "c", from: -1, pass: false }, { platformId: "d", from: 3, pass: false }, { platformId: "e", from: 4, pass: false }, { platformId: "f", from: 5, pass: false }]
    T.draftEntry(mine.draft, mine.data.routeById.get("101")).timetable = copy(T.gameTimetable(mine.data.routeById.get("101")))
    mine.draft.duties["9001"] = { duties: copy(mine.data.depots[0].duties) }
    const change = D.stopListChange(mine.data, mine.draft, mine.data.routeById.get("101"), list)
    expect(copy(change)).toMatchSnapshot()
    expect(fingerprint(mine.draft)).toMatchSnapshot()
    expect(D.stopListChange(mine.data, mine.draft, mine.data.routeById.get("101"), list.slice(0, 1))).toMatchSnapshot()
  })
})
