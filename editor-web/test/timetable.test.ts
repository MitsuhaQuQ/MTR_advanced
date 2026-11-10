/**
 * Parity of src/core/timetable.ts with the old page's timetable rules (retiring the old code, step 4): the same change on
 * the same data by the old functions and the new, the whole draft (and what is chosen) compared after.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { beforeAll, describe, expect, it } from "vitest"
import { loadOld } from "./old"
import * as T from "../src/core/timetable"
import * as S from "../src/core/services"
import { clock } from "../src/core/format"
import { mockData } from "../mock/fixture"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../common/src/main/resources/assets/mtr/editor")
beforeAll(() => { (0, eval)(fs.readFileSync(path.join(EDITOR, "batch.js"), "utf8").replace(/^"use strict";/m, "").replace(/^const /gm, "var ")) })

const OLD = ["editTimetable", "draftEntry", "draftOf", "tidyDraft", "gameTimetable", "fromGame", "normalize", "timetableOf", "setStopException", "syncExceptions", "retimeService", "setSuspended", "removeService", "setServiceOrder",
  "changeTime", "laterServices", "moveServices", "shiftLaterDialog", "parseShift", "respaceRange", "setLink", "clearTable", "servicesToAdd", "renumberTimetable", "renumberLinksInto",
  "inRange", "rangeServices", "tableRun", "Batch", "chooseRange", "toggleChosen", "chooseLater", "servicesToMove",
  "findService", "timesOf", "buildServices", "timesSource", "gameSource", "plansFor", "toPlans", "mod", "DAY", "clock", "durationText", "parseClock", "parsePattern", "tableName"]

type Side = { data: any; draft: any; state: any }
function sides(weekday = 0, answer = "5") {
  const make = (): Side => {
    const data: any = mockData()
    data.routeById = new Map(data.routes.map((r: any) => [r.id, r]))
    return { data, draft: { routes: {} }, state: { weekday, selected: null, selectedRoute: null, range: null, slot: null, pxPerMin: 4 } }
  }
  const theirs = make(), mine = make()
  const old = loadOld<any>(OLD, {
    data: theirs.data, draft: theirs.draft, state: theirs.state, window: { TimetableBatch: (globalThis as any).TimetableBatch },
    saveDraft() {}, renderAll() {}, renderTable() {}, tableMessage: "", confirm: () => true, prompt: () => answer,
    // Asking the game for a draft's times: never answered here, so the game's own times stay, as on the new side
    draftTimes: new Map(), requestedTimes: new Map(), timesTimer: 0, sendTimes() {}, setTimeout: () => 0, clearTimeout() {},
  })
  old.buildServices()
  const build = () => mine.data.routes.forEach((r: any) => { r.services = S.routeServices(r, T.timetableOf(mine.draft, r), S.gameSource(r), weekday, mine.data.dayStartMillis) })
  build()
  const find = (id: string) => S.findService(mine.data.routeById, id)
  const apply = (r: any, change: T.Change) => { const message = T.applyEdit(mine.draft, r, change); build(); return message }
  const same = () => {
    expect(JSON.parse(JSON.stringify(mine.draft))).toEqual(JSON.parse(JSON.stringify(theirs.draft)))
    expect({ ...mine.state }).toEqual({ ...theirs.state })
  }
  const service = (side: Side, r: number, k: number) => side.data.routes[r].services[k]
  return { old, theirs, mine, find, apply, same, service, build }
}

describe("timetable rules", () => {
  it("changes times as the old page does: moving the first stop, a dwell, a running time, and the later ones too", () => {
    for (const [stop, kind, delta, toLater] of [[0, "dep", 120000, false], [0, "dep", -90000, true], [2, "dep", 45000, false], [3, "arr", 60000, true], [1, "arr", -20000, false]] as [number, string, number, boolean][]) {
      const { old, theirs, mine, apply, same, service } = sides()
      const a = service(theirs, 0, 4), b = service(mine, 0, 4)
      const times = S.timesOf(b), time = (kind === "dep" ? times[stop][1] : times[stop][0]) + delta
      old.changeTime(a, stop, kind, time, toLater)
      const services = toLater ? [b, ...T.laterServices(b)] : [b]
      apply(b.route, T.changeTimeEdit(b, times, stop, kind, time, services))
      if (stop === 0) T.selectMoved(mine.state, mine.data.routeById.get(b.route.id), b, time - times[0][1])
      same()
    }
  })
  it("moves services by dragging as the old page does, and refuses a move onto a service that stays", () => {
    for (const shift of [300000, -180000, 360000]) {
      const { old, theirs, mine, apply, same, service } = sides()
      const ids = [service(theirs, 0, 2).id, service(theirs, 0, 3).id]
      old.moveServices(ids, shift)
      const moving = ids.map(id => S.findService(mine.data.routeById, id).service)
      apply(mine.data.routeById.get("101"), T.moveServicesEdit(0, moving, shift, at => clock(mine.data, (at - mine.data.dayStartMillis + 86400000) % 86400000), () => {}))
      same()
    }
  })
  it("shifts the later services, spaces a run, links, clears as the old page does", () => {
    {
      const { old, theirs, mine, apply, same, service } = sides(0, "-1:30")
      old.shiftLaterDialog(service(theirs, 0, 10))
      apply(mine.data.routeById.get("101"), T.shiftLaterEdit(service(mine, 0, 10), T.parseShift("-1:30")!))
      same()
    }
    {
      const { old, theirs, mine, apply, same, service, find } = sides(0, "7")
      const ids = [5, 6, 7, 8].map(k => service(theirs, 0, k).id)
      theirs.state.range = { anchor: ids[0], ids }
      mine.state.range = { anchor: ids[0], ids: ids.slice() }
      old.respaceRange()
      const run = T.rangeServices(mine.state, find)
      apply(run[0].route, T.respaceEdit(run, 7, () => { mine.state.range = null }))
      same()
    }
    {
      const { old, theirs, mine, apply, same, service } = sides()
      old.setLink(service(theirs, 0, 3), -1, service(theirs, 1, 6), 0)
      apply(mine.data.routeById.get("101"), T.setLinkEdit(service(mine, 0, 3), -1, service(mine, 1, 6), 0))
      same()
    }
    {
      const { old, theirs, mine, apply, same } = sides()
      old.clearTable(theirs.data.routeById.get("102"), 1)
      mine.state.range = null
      apply(mine.data.routeById.get("102"), T.clearTableEdit(1, (t, i) => t.name || "表 " + (i + 1)))
      same()
    }
  })
  it("edits a table's services as the old page does", () => {
    const { old, mine } = sides()
    const table = () => JSON.parse(JSON.stringify(T.gameTimetable(mine.data.routeById.get("101")).tables[0]))
    const s = table().services
    const pairs: [string, (t: any) => unknown][] = [
      ["setSuspended", t => [T.setSuspended(t, [s[1], s[2], 999], true), old.setSuspended]],
      ["removeService", t => T.removeService(t, s[3])],
      ["setServiceOrder", t => { T.setServiceOrder(t, s[4], "deploy", 2, "9001"); T.setServiceOrder(t, s[5], "return", 4, 0) }],
      ["setStopException", t => { T.setStopException(t, s[6], 2, { dwell: 40 }); T.setStopException(t, s[6], 3, { run: 200 }); T.setStopException(t, s[6], 3, { run: -1 }) }],
      ["retimeService", t => T.retimeService(t, s[7], s[7] + 60000)],
    ]
    for (const [name, mineDo] of pairs) {
      const a = table(), b = table()
      mineDo(b)
      if (name === "setSuspended") old.setSuspended(a, [s[1], s[2], 999], true)
      if (name === "removeService") old.removeService(a, s[3])
      if (name === "setServiceOrder") { old.setServiceOrder(a, s[4], "deploy", 2, "9001"); old.setServiceOrder(a, s[5], "return", 4, 0) }
      if (name === "setStopException") { old.setStopException(a, s[6], 2, { dwell: 40 }); old.setStopException(a, s[6], 3, { run: 200 }); old.setStopException(a, s[6], 3, { run: -1 }) }
      if (name === "retimeService") old.retimeService(a, s[7], s[7] + 60000)
      expect(b, name).toEqual(a)
    }
    // Exceptions copied to others
    const a = table(), b = table()
    old.setStopException(a, s[1], 2, { dwell: 50 }); T.setStopException(b, s[1], 2, { dwell: 50 })
    expect(T.syncExceptions(b, s[1], [s[1], s[2], s[3]])).toBe(old.syncExceptions(a, s[1], [s[1], s[2], s[3]]))
    expect(b).toEqual(a)
    // A taken service time refused alike
    expect(T.retimeService(table(), s[1], s[2])).toBe(old.retimeService(table(), s[1], s[2]))
  })
  it("reads shifts and services to add, and renumbers to new stops, as the old page does", () => {
    const { old, mine } = sides()
    for (const text of ["5", "-2.5", "5m", "5分", "90s", "90秒", "1:30", "-0:45", "1m30s", "1分30秒", "−3", "x", "", null]) expect(T.parseShift(text as any)).toBe(old.parseShift(text))
    for (const [start, end, interval] of [["07:00", "08:00", "10"], ["07:00", "08:05", "04:30"], ["07:10 + 3 * 00:05", "", ""], ["07:00", "", "5"], ["x", "08:00", "5"], ["07:00", "06:00", "15"]]) {
      expect(T.servicesToAdd(mine.data, start, end, interval)).toEqual(old.servicesToAdd(start, end, interval))
    }
    const tt = T.gameTimetable(mine.data.routeById.get("101"))
    tt.tables[0].links = [{ service: tt.tables[0].services[0], route: "102", toService: 1, fromStop: 3, toStop: 0 }]
    tt.tables[0].orders = [{ service: tt.tables[0].services[1], kind: "deploy", stop: 2, depot: "0" }]
    tt.tables[0].exceptions = [{ service: tt.tables[0].services[2], stops: [{ stop: 4, run: 10, dwell: -1, pass: -1, overtake: -1 }, { stop: 1, run: -1, dwell: 9, pass: -1, overtake: -1 }] }]
    const list = [{ from: 0, pass: false }, { from: 2, pass: true }, { from: -1, pass: false }, { from: 3, pass: false }, { from: 5, pass: false }]
    const newOf = [0, -1, 1, 3, -1, 4]
    expect(T.renumberTimetable(tt, list, newOf)).toEqual(old.renumberTimetable(tt, list, newOf))
    const other = JSON.parse(JSON.stringify(tt))
    other.tables[0].links = [{ service: 1, route: "101", toService: 2, fromStop: -1, toStop: 3 }]
    expect(T.renumberLinksInto(other, "101", newOf)).toEqual(old.renumberLinksInto(other, "101", newOf))
    expect(T.renumberLinksInto(other, "999", newOf)).toEqual(old.renumberLinksInto(other, "999", newOf))
  })
  it("chooses services as the old page does", () => {
    const steps: [string, number, boolean][] = [["toggle", 3, false], ["toggle", 6, true], ["range", 9, false], ["toggle", 7, false], ["later", 12, false], ["toggle", 13, true], ["range", 2, false]]
    const { old, theirs, mine, same, service, find } = sides()
    theirs.state.selected = mine.state.selected = service(theirs, 0, 1).id
    for (const [what, k, span] of steps) {
      const a = service(theirs, 0, k), b = service(mine, 0, k)
      if (what === "toggle") expect(T.toggleChosen(mine.state, b, span, find)).toBe(old.toggleChosen(a, span))
      if (what === "range") expect(T.chooseRange(mine.state, b, find)).toBe(old.chooseRange(a))
      if (what === "later") { old.chooseLater(a); T.chooseLater(mine.state, b) }
      same()
      expect(T.servicesToMove(mine.state, b, true, find)).toEqual(old.servicesToMove(a))
    }
  })
})
