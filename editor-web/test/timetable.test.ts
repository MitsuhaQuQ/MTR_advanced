/**
 * src/core/timetable.ts, the timetable rules: a change on the mock's data, the whole draft (and what is chosen) after.
 * Until step 9 of retiring the old code the same change was made by the old page's functions too and the two compared;
 * the snapshots were taken when they still matched.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { beforeAll, describe, expect, it } from "vitest"
import * as T from "../src/core/timetable"
import * as S from "../src/core/services"
import { clock } from "../src/core/format"
import { mockData } from "../mock/fixture"
import { fingerprint } from "./fingerprint"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../common/src/main/resources/assets/mtr/editor")
beforeAll(() => { (0, eval)(fs.readFileSync(path.join(EDITOR, "batch.js"), "utf8").replace(/^"use strict";/m, "").replace(/^const /gm, "var ")) })

type Side = { data: any; draft: any; state: any }
function sides(weekday = 0) {
  const make = (): Side => {
    const data: any = mockData()
    data.routeById = new Map(data.routes.map((r: any) => [r.id, r]))
    return { data, draft: { routes: {} }, state: { weekday, selected: null, selectedRoute: null, range: null, slot: null, pxPerMin: 4 } }
  }
  const mine = make()
  const build = () => mine.data.routes.forEach((r: any) => { r.services = S.routeServices(r, T.timetableOf(mine.draft, r), S.gameSource(r), weekday, mine.data.dayStartMillis) })
  build()
  const find = (id: string) => S.findService(mine.data.routeById, id)
  const apply = (r: any, change: T.Change) => { const message = T.applyEdit(mine.draft, r, change); build(); return message }
  /** The whole draft and what is chosen, as the old page left them after the same change */
  const same = () => {
    expect(fingerprint({ draft: mine.draft, state: mine.state })).toMatchSnapshot()
  }
  const service = (side: Side, r: number, k: number) => side.data.routes[r].services[k]
  return { mine, find, apply, same, service, build }
}

describe("timetable rules", () => {
  it("changes times as the old page did: moving the first stop, a dwell, a running time, and the later ones too", () => {
    for (const [stop, kind, delta, toLater] of [[0, "dep", 120000, false], [0, "dep", -90000, true], [2, "dep", 45000, false], [3, "arr", 60000, true], [1, "arr", -20000, false]] as [number, string, number, boolean][]) {
      const { mine, apply, same, service } = sides()
      const b = service(mine, 0, 4)
      const times = S.timesOf(b), time = (kind === "dep" ? times[stop][1] : times[stop][0]) + delta
      const services = toLater ? [b, ...T.laterServices(b)] : [b]
      apply(b.route, T.changeTimeEdit(b, times, stop, kind, time, services))
      if (stop === 0) T.selectMoved(mine.state, mine.data.routeById.get(b.route.id), b, time - times[0][1])
      same()
    }
  })
  it("moves services by dragging as the old page did, and refuses a move onto a service that stays", () => {
    for (const shift of [300000, -180000, 360000]) {
      const { mine, apply, same, service } = sides()
      const ids = [service(mine, 0, 2).id, service(mine, 0, 3).id]
      const moving = ids.map(id => S.findService(mine.data.routeById, id).service)
      apply(mine.data.routeById.get("101"), T.moveServicesEdit(0, moving, shift, at => clock(mine.data, (at - mine.data.dayStartMillis + 86400000) % 86400000), () => {}))
      same()
    }
  })
  it("shifts the later services, spaces a run, links, clears as the old page did", () => {
    {
      const { mine, apply, same, service } = sides(0)
      apply(mine.data.routeById.get("101"), T.shiftLaterEdit(service(mine, 0, 10), T.parseShift("-1:30")!))
      same()
    }
    {
      const { mine, apply, same, service, find } = sides(0)
      const ids = [5, 6, 7, 8].map(k => service(mine, 0, k).id)
      mine.state.range = { anchor: ids[0], ids: ids.slice() }
      const run = T.rangeServices(mine.state, find)
      apply(run[0].route, T.respaceEdit(run, 7, () => { mine.state.range = null }))
      same()
    }
    {
      const { mine, apply, same, service } = sides()
      apply(mine.data.routeById.get("101"), T.setLinkEdit(service(mine, 0, 3), -1, service(mine, 1, 6), 0))
      same()
    }
    {
      const { mine, apply, same } = sides()
      mine.state.range = null
      apply(mine.data.routeById.get("102"), T.clearTableEdit(1, (t, i) => t.name || "表 " + (i + 1)))
      same()
    }
  })
  it("edits a table's services as the old page did", () => {
    const { mine } = sides()
    const table = () => JSON.parse(JSON.stringify(T.gameTimetable(mine.data.routeById.get("101")).tables[0]))
    const s = table().services
    const pairs: [string, (t: any) => unknown][] = [
      ["setSuspended", t => T.setSuspended(t, [s[1], s[2], 999], true)],
      ["removeService", t => T.removeService(t, s[3])],
      ["setServiceOrder", t => { T.setServiceOrder(t, s[4], "deploy", 2, "9001"); T.setServiceOrder(t, s[5], "return", 4, 0) }],
      ["setStopException", t => { T.setStopException(t, s[6], 2, { dwell: 40 }); T.setStopException(t, s[6], 3, { run: 200 }); T.setStopException(t, s[6], 3, { run: -1 }) }],
      ["retimeService", t => T.retimeService(t, s[7], s[7] + 60000)],
    ]
    for (const [name, mineDo] of pairs) {
      const b = table()
      mineDo(b)
      expect(b, name).toMatchSnapshot()
    }
    // Exceptions copied to others
    const b = table()
    T.setStopException(b, s[1], 2, { dwell: 50 })
    expect(T.syncExceptions(b, s[1], [s[1], s[2], s[3]])).toMatchSnapshot()
    expect(b).toMatchSnapshot()
    // A taken service time refused
    expect(T.retimeService(table(), s[1], s[2])).toMatchSnapshot()
  })
  it("reads shifts and services to add, and renumbers to new stops, as the old page did", () => {
    const { mine } = sides()
    expect(["5", "-2.5", "5m", "5分", "90s", "90秒", "1:30", "-0:45", "1m30s", "1分30秒", "−3", "x", "", null].map(text => [text, T.parseShift(text as any)])).toMatchSnapshot()
    expect([["07:00", "08:00", "10"], ["07:00", "08:05", "04:30"], ["07:10 + 3 * 00:05", "", ""], ["07:00", "", "5"], ["x", "08:00", "5"], ["07:00", "06:00", "15"]]
      .map(([start, end, interval]) => [start, end, interval, T.servicesToAdd(mine.data, start, end, interval)])).toMatchSnapshot()
    const tt = T.gameTimetable(mine.data.routeById.get("101"))
    tt.tables[0].links = [{ service: tt.tables[0].services[0], route: "102", toService: 1, fromStop: 3, toStop: 0 }]
    tt.tables[0].orders = [{ service: tt.tables[0].services[1], kind: "deploy", stop: 2, depot: "0" }]
    tt.tables[0].exceptions = [{ service: tt.tables[0].services[2], stops: [{ stop: 4, run: 10, dwell: -1, pass: -1, overtake: -1 }, { stop: 1, run: -1, dwell: 9, pass: -1, overtake: -1 }] }]
    const list = [{ from: 0, pass: false }, { from: 2, pass: true }, { from: -1, pass: false }, { from: 3, pass: false }, { from: 5, pass: false }]
    const newOf = [0, -1, 1, 3, -1, 4]
    expect(T.renumberTimetable(tt, list, newOf)).toMatchSnapshot()
    const other = JSON.parse(JSON.stringify(tt))
    other.tables[0].links = [{ service: 1, route: "101", toService: 2, fromStop: -1, toStop: 3 }]
    expect(T.renumberLinksInto(other, "101", newOf)).toMatchSnapshot()
    expect(T.renumberLinksInto(other, "999", newOf)).toMatchSnapshot()
  })
  it("chooses services as the old page did", () => {
    const steps: [string, number, boolean][] = [["toggle", 3, false], ["toggle", 6, true], ["range", 9, false], ["toggle", 7, false], ["later", 12, false], ["toggle", 13, true], ["range", 2, false]]
    const { mine, same, service, find } = sides()
    mine.state.selected = service(mine, 0, 1).id
    for (const [what, k, span] of steps) {
      const b = service(mine, 0, k)
      if (what === "toggle") expect(T.toggleChosen(mine.state, b, span, find)).toMatchSnapshot()
      if (what === "range") expect(T.chooseRange(mine.state, b, find)).toMatchSnapshot()
      if (what === "later") T.chooseLater(mine.state, b)
      same()
      expect(T.servicesToMove(mine.state, b, true, find)).toMatchSnapshot()
    }
  })
})

it("changing a stop to passing clears old service timing but later explicit timing is kept", () => {
  const table = { exceptions: [{ service: 0, stops: [{ stop: 1, run: 0, dwell: 30, pass: -1, overtake: -1 }] }] }
  T.setStopException(table, 0, 1, { pass: 1, dwell: -1 })
  expect(table.exceptions[0].stops[0].run).toBe(-1)
  T.setStopException(table, 0, 1, { run: 60 })
  expect(table.exceptions[0].stops[0].run).toBe(60)
})
