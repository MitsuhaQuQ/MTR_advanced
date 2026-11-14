/**
 * src/core/sync.ts on data with every kind of problem in it. Until step 9 of retiring the old code the list was compared
 * with the old page's syncProblems; the snapshots were taken when they still matched.
 */
import { describe, expect, it } from "vitest"
import { fullDraft, testData } from "./fixtures"
import { buildModel } from "../src/core/model"
import { syncProblems } from "../src/core/sync"
import { emptyDraft, settleApply } from "../src/core/draft"

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
    it(`lists what the old page listed (${connection})`, () => {
      const model = buildModel(troubledData())
      const draft = fullDraft(model)
      draft.routes["102"].error = "站台被占"
      draft.duties["9001"].error = "运用不对"
      draft.depots["9001"].error = "车厂不对"
      draft.created[1].error = "建不成"
      const found = JSON.parse(JSON.stringify(syncProblems(connection, model, draft)))
      // Every kind of problem in the data shows, so the snapshot means something
      expect(found.length).toBeGreaterThan(25)
      expect(found).toMatchSnapshot()
    })
  }
  it("adds why a siding has no way out or back, where the server knows (0.65.26)", () => {
    const data = troubledData()
    data.depots[0].sidings[0].noWayOutWhy = { "1000": "找到的路只能逆着本线站台 地税湖 3 走" }
    const model = buildModel(data)
    const texts = syncProblems("ok", model, fullDraft(model)).map(p => p.text)
    expect(texts.some(text => text.includes("没有出库的路") && text.includes("（原因：找到的路只能逆着本线站台 地税湖 3 走）"))).toBe(true)
    expect(texts.some(text => text.includes("回不到") && text.includes("原因"))).toBe(false)
  })
  it("says a siding no train can leave keeps the trains taken back into it (2026-11-13)", () => {
    const data = troubledData()
    const siding = data.depots[0].sidings[0]
    siding.noWayOut = ["1000"]
    siding.measuredOut = []
    const model = buildModel(data)
    const texts = syncProblems("ok", model, fullDraft(model)).map(p => p.text)
    expect(texts.some(text => text.includes("到哪个首站都没有出库的路") && text.includes("调车线"))).toBe(true)
    siding.measuredOut = ["1001"]
    const measured = buildModel(data)
    expect(syncProblems("ok", measured, fullDraft(measured)).some(p => p.text.includes("到哪个首站都没有出库的路"))).toBe(false)
  })
  it("tells a route of the draft changed in the game since, and who is editing it (2026-11-14)", () => {
    const data = troubledData()
    const model = buildModel(data)
    const draft = fullDraft(model)
    const r: any = model.routeById.get("101")
    expect(syncProblems("ok", model, draft).some(p => p.text.includes("在你的草稿之后改过"))).toBe(false)
    // Someone changed its timetable in the game, and is still editing it
    const changed = troubledData()
    const route = changed.routes.find((each: any) => String(each.id) === "101")
    route.parts = { ...r.parts, timetable_services: r.parts.timetable_services + 1 }
    route.editors = "Steve"
    const later = buildModel(changed)
    const texts = syncProblems("ok", later, draft).map(p => p.text)
    expect(texts.some(text => text.includes("在你的草稿之后改过") && text.includes("Steve 正在编辑"))).toBe(true)
  })
  it("says whose edits a route turned down by the game conflicts with (2026-11-14)", () => {
    const model = buildModel(troubledData())
    const draft = fullDraft(model)
    const { draft: after } = settleApply(draft, ["101"], { ok: false, errors: [{ index: 0, route: "101", message: "CONFLICT:Alex" }], applied: [] })
    expect(after.routes["101"].conflict).toBe(true)
    expect(after.routes["101"].error).toContain("Alex")
    expect(syncProblems("ok", model, after).some(p => p.text.includes("和 Alex 的修改冲突"))).toBe(true)
  })
  it("says only the connection without data", () => {
    expect(syncProblems("key", null, emptyDraft()).map(p => p.text)).toEqual(["页面的链接已经失效"])
  })
})
