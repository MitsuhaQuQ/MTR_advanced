/** Parity of src/core/sync.ts with the old page's syncProblems, on data with every kind of problem in it. */
import { describe, expect, it } from "vitest"
import { loadOld } from "./old"
import { fullDraft, testData } from "./fixtures"
import { buildModel } from "../src/core/model"
import { syncProblems } from "../src/core/sync"
import { emptyDraft } from "../src/core/draft"

function troubledData() {
  const data = testData()
  const [north, airport] = data.depots
  north.path = {
    state: "failed", text: "", failedDuties: 2, failedDutyKeys: ["a", "b", "c", "d"], turnTimesMeasured: true, turnTimes: { "1005": { "1000": 45000 } },
    detours: [{ from: "西门|W", to: "港湾", length: 300, apart: 20 }],
  }
  north.held = [{ siding: "8001", slot: 0, platform: "港湾 1", reason: "OTHER_PLATFORM" }, { siding: "8999", slot: 2, platform: "北湖", reason: "x" }]
  north.alarms = [
    { train: "t1", siding: "8001", slot: 1, route: "101", system: "a1", plan: "p1", handled: false, reason: "LATE", late: 400, turn: 1 },
    { train: "t2", siding: "8002", slot: 0, route: "999", system: "a1", plan: "0", handled: true, reason: "WAITING", waiting: 200, takeover: 3, dispatch: true },
    { train: "t3", siding: "8003", slot: 0, route: "102", system: "a9", plan: "p9", handled: true, reason: "DEADLOCK", skipping: true },
  ]
  north.returnMisses = [{ depot: "9002" }, { depot: "123" }]
  north.duties = { mode: "diagram", sidings: { "8001": [[{ days: 127, segments: [{ route: "101" }, { route: "gone" }] }]] }, unplaced: { "a6/x": 2, "/": 1 }, moves: [{ from: "8001", to: "8002" }, { from: "8001", to: "8002" }, { from: "77", to: "88" }, { from: "0", to: "8002" }] }
  north.shortfall = { uncovered: 4, lines: "1号线", needed: 9, available: 8, unrunnable: 2, entrancesUnset: ["TRAIN|1号线|Line 1", "TRAIN|9号线|L9"], suggested: 10, pools: [{ label: "A", needed: -1, available: 3, uncovered: 2 }, { label: "B", needed: 5, available: 4, uncovered: 1 }] }
  north.sidings[0].noWayOut = ["1000"]
  north.sidings[0].noWayBack = ["1003"]
  north.sidings[1].measuredOut = ["1000"]
  airport.path = { state: "stale", text: "" }
  airport.autoDuties = { mode: "diagram", sidings: { "8101": [] } }
  airport.shortfall = { uncovered: 1, lines: "2号线", needed: -1, available: 2, replan: "minReverse:30000:60000;buffers;ways;other" }
  data.depots.push({ id: "9003", name: "", routes: [], sidings: [], path: { state: "generating" } })
  data.contingencySystems[0].plans = [{ id: "p1", name: "中断", active: true, manual: true }]
  data.contingencySystems[0].adhoc = { t1: {}, t9: {} }
  data.contingencySystems[0].others[0].plans = [{ id: "p9", name: "", active: true }]
  data.topology.overlapping = [{ platform: "1000", station: "s1", byIdAlone: true }, { platform: "1001", station: "0" }]
  data.routes[3].hasTimes = false
  return data
}

describe("syncProblems", () => {
  for (const connection of ["ok", "world", "offline"] as const) {
    it(`lists what the old page lists (${connection})`, () => {
      const model = buildModel(troubledData())
      const draft = fullDraft(model)
      draft.routes["102"].error = "站台被占"
      draft.duties["9001"].error = "运用不对"
      draft.depots["9001"].error = "车厂不对"
      draft.created[1].error = "建不成"
      const data = buildModel(troubledData())
      const old = loadOld<{ syncProblems: () => any[] }>([
        "syncProblems", "shortName", "routeTitle", "depotById", "systemTitle", "plansByAnchor", "alarmReasonText", "turnPairsOf", "turnTimeOf",
        "stopLabel", "dutiesOf", "depotLines", "shortfallText", "replanText", "platformText", "platformById", "stationNameOf",
      ], {
        connection, data, draft: JSON.parse(JSON.stringify(draft)),
        topo: { platforms: data.topology.platforms, index: new Map(data.topology.platforms.map((p: any, i: number) => [p.id, i])), stations: data.topology.stations, overlapping: data.topology.overlapping },
        terminalNow: () => ({ warnings: [] }),
      })
      const expected = JSON.parse(JSON.stringify(old.syncProblems()))
      // Every kind of problem in the data shows, so the comparison means something
      expect(expected.length).toBeGreaterThan(30)
      expect(JSON.parse(JSON.stringify(syncProblems(connection, model, draft)))).toEqual(expected)
    })
  }
  it("says only the connection without data", () => {
    expect(syncProblems("key", null, emptyDraft()).map(p => p.text)).toEqual(["页面的链接已经失效"])
  })
})
