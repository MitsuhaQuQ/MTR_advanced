/**
 * Parity of src/core/duties.ts with the old page's duty rules (retiring the old code, step 5): on the mock's duties, and
 * on them made wrong in each way a duty can be, every weekday, the old functions cut from editor.js and the new module
 * give the same coverage, terminal pairs, problems, numbers, edits and stop renumbering.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { beforeAll, describe, expect, it } from "vitest"
import { loadOld } from "./old"
import * as D from "../src/core/duties"
import * as T from "../src/core/timetable"
import * as S from "../src/core/services"
import { mockData } from "../mock/fixture"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../common/src/main/resources/assets/mtr/editor")
const script = (file: string) => fs.readFileSync(path.join(EDITOR, file), "utf8").replace(/^"use strict";/m, "").replace(/^const /gm, "var ")
beforeAll(() => { (0, eval)(script("batch.js")); (0, eval)(script("terminal.js")) })

const OLD = ["sortedObject", "canonDuties", "dutiesOf", "routeIsLoop", "dutyOn", "depotById", "segmentKey", "startOf", "newDutyId", "dutyIdOf", "findDutySlot", "dutyNumber", "relinkOvernight",
  "poolSidings", "editDuties", "dayGroup", "dutyForEditing", "segmentService", "lastStopOf", "segmentStart", "segmentEnd", "coverageNow", "hasNoTrain", "terminalNow", "dutyAssignments",
  "dutyProblems", "nextSlotOf", "dutyEndPlatform", "dutyStartPlatform", "minReverseMillis", "turnTimeOf", "stopLabel", "stopHangers", "renumberDuties", "sendStopList",
  "renumberTimetable", "renumberLinksInto", "draftOf", "draftEntry", "gameTimetable", "fromGame", "normalize", "timetableOf", "servedStops", "orderDepotName",
  "findService", "timesOf", "buildServices", "timesSource", "gameSource", "plansFor", "toPlans", "mod", "DAY", "clock", "durationText", "formatDuration", "routeTitle", "shortName", "WEEKDAY_NAMES", "WEEKDAY_SHORT",
  "isLoop", "loopWaitMillis", "MAX_LAYOVER_MILLIS", "MAX_STAY_MILLIS"]

const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
type Side = { data: any; draft: any; state: any }

/** The same data on both sides, changed alike by @param change before the services are built */
function sides(weekday: number, change: (data: any) => void = () => {}) {
  const make = (): Side => {
    const data: any = mockData()
    change(data)
    data.routeById = new Map(data.routes.map((r: any) => [r.id, r]))
    return { data, draft: { routes: {}, duties: {} }, state: { weekday, selected: null, slot: null } }
  }
  const theirs = make(), mine = make()
  const sent: any[] = []
  const old = loadOld<any>(OLD, {
    data: theirs.data, draft: theirs.draft, state: theirs.state, coverage: null, terminal: null, dutyNotice: "", revision: 1,
    TerminalPairs: (globalThis as any).TerminalPairs,
    saveDraft() {}, renderAll() {}, renderBanner() {}, poll() {},
    sendJson: (_url: string, _method: string, body: any) => { sent.push(copy(body)); return new Promise(() => {}) },
    draftTimes: new Map(), requestedTimes: new Map(), timesTimer: 0, sendTimes() {}, setTimeout: () => 0, clearTimeout() {},
  })
  old.buildServices()
  mine.data.routes.forEach((r: any) => { r.services = S.routeServices(r, T.timetableOf(mine.draft, r), S.gameSource(r), weekday, mine.data.dayStartMillis) })
  return { old, theirs, mine, sent }
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
  it("covers, pairs and finds problems as the old page does, every weekday, on the mock's duties and on wrong ones", () => {
    for (const change of VARIANTS) for (let weekday = 0; weekday < 7; weekday++) {
      const { old, mine, theirs } = sides(weekday, change)
      expect(sets(D.coverage(mine.data, mine.draft, weekday))).toEqual(sets(old.coverageNow()))
      expect([...D.dutyAssignments(mine.data, mine.draft, weekday)]).toEqual([...old.dutyAssignments()])
      expect(copy(D.terminalPairs(mine.data, mine.draft, weekday))).toEqual(copy(old.terminalNow()))
      const found = slotsOf(mine.data).map(({ depot, siding, index }: any) => D.dutyProblems(mine.data, mine.draft, depot, siding, index, weekday))
      expect(found).toEqual(slotsOf(theirs.data).map(({ depot, siding, index }: any) => old.dutyProblems(depot, siding, index, weekday)))
      if (change === wrong && weekday === 0) expect(found.flat().length).toBeGreaterThan(5)
      slotsOf(mine.data).forEach(({ depot, siding, index }: any) => {
        expect(D.dutyNumber(mine.draft, depot, siding, index)).toBe(old.dutyNumber(theirs.data.depots.find((d: any) => d.id === depot.id), siding, index))
      })
      for (const depot of mine.data.depots) expect(D.dayGroup(mine.data, mine.draft, depot, weekday)).toBe(old.dayGroup(theirs.data.depots.find((d: any) => d.id === depot.id), weekday))
    }
  })
  it("edits duties as the old page does: split off a day group, remove and reorder segments, stay overnight, add a train, back again", () => {
    type Step = (duties: any, forEditing: (slot: any[]) => any) => unknown
    const steps: [number, Step][] = [
      [5, (duties, forEditing) => { forEditing(duties.sidings["8001"][0]).segments.pop(); return "去掉一班" }],
      [5, (duties, forEditing) => { const day = forEditing(duties.sidings["8001"][1]); day.segments.unshift(day.segments.pop()) }],
      [2, (duties, forEditing) => { const day = forEditing(duties.sidings["8002"][0]); day.stay = true; day.nextDuty = "6" }],
      [2, (duties, forEditing) => { duties.sidings["8003"].push([]); forEditing(duties.sidings["8003"][1]).segments.push({ route: "103", service: 9 * 3600000, from: 0, to: -1 }) }],
      [2, (duties, forEditing) => { duties.sidings["8003"].splice(1, 1); const day = forEditing(duties.sidings["8002"][0]); day.stay = false; day.nextDuty = "0" }],
    ]
    const { old, mine, theirs } = sides(0)
    for (const [weekday, step] of steps) {
      theirs.state.weekday = mine.state.weekday = weekday
      const a = theirs.data.depots[0], b = mine.data.depots[0]
      old.editDuties(a, (duties: any) => step(duties, slot => old.dutyForEditing(a, slot, weekday)))
      const message = D.applyDutyEdit(mine.data, mine.draft, b, duties => step(duties, slot => D.dutyForEditing(mine.data, mine.draft, b, slot, weekday)))
      expect(message).toBe(old.sandbox.dutyNotice)
      // A new train's number is random on each side
      const ids = (draft: any) => JSON.stringify(draft).replace(/"duty":"\d{16}"/g, '"duty":"new"')
      expect(ids(mine.draft)).toBe(ids(theirs.draft))
    }
    expect(D.findDutySlot(D.dutiesOf(mine.draft, mine.data.depots[0]), "3")).toEqual(old.findDutySlot(old.dutiesOf(theirs.data.depots[0]), "3"))
    expect(D.poolSidings(mine.data.depots[0], "8002")).toEqual(old.poolSidings(theirs.data.depots[0], "8002"))
  })
  it("finds what keeps a stop and renumbers to new stops as the old page does", () => {
    const { old, mine, theirs, sent } = sides(0)
    const link = (data: any, gameTimetable: (r: any) => any) => {
      const tt = gameTimetable(data.routeById.get("101"))
      tt.tables[0].links.push({ service: tt.tables[0].services[1], route: "102", toService: 1, fromStop: 3, toStop: 2 })
      tt.tables[0].orders = [{ service: tt.tables[0].services[2], kind: "deploy", stop: 2, depot: "0" }]
      data.depots[0].duties.sidings["8001"][0][0].segments[0].to = 4
      data.depots[0].duties.sidings["8001"][1][0].segments[0].from = 2
    }
    link(mine.data, T.gameTimetable); link(theirs.data, old.gameTimetable)
    for (const [route, stop] of [["101", 0], ["101", 2], ["101", 3], ["101", 4], ["102", 2], ["103", 1]] as [string, number][]) {
      expect(D.stopHangers(mine.data, mine.draft, mine.data.routeById.get(route), stop)).toEqual(old.stopHangers(theirs.data.routeById.get(route), stop))
    }
    const list = [{ platformId: "n", from: -1, pass: false }, { platformId: "a", from: 0, pass: false }, { platformId: "b", from: 2, pass: true }, { platformId: "c", from: -1, pass: false }, { platformId: "d", from: 3, pass: false }, { platformId: "e", from: 4, pass: false }, { platformId: "f", from: 5, pass: false }]
    T.draftEntry(mine.draft, mine.data.routeById.get("101")).timetable = copy(T.gameTimetable(mine.data.routeById.get("101")))
    old.draftEntry(theirs.data.routeById.get("101")).timetable = copy(old.gameTimetable(theirs.data.routeById.get("101")))
    mine.draft.duties["9001"] = { duties: copy(mine.data.depots[0].duties) }
    theirs.draft.duties["9001"] = { duties: copy(theirs.data.depots[0].duties) }
    const change = D.stopListChange(mine.data, mine.draft, mine.data.routeById.get("101"), list)
    expect(old.sendStopList(theirs.data.routeById.get("101"), list)).toBeUndefined()
    expect(copy(change)).toEqual({ operations: sent[0].operations })
    expect(copy(mine.draft)).toEqual(copy(theirs.draft))
    expect(D.stopListChange(mine.data, mine.draft, mine.data.routeById.get("101"), list.slice(0, 1))).toBe(old.sendStopList(theirs.data.routeById.get("101"), list.slice(0, 1)))
  })
})
