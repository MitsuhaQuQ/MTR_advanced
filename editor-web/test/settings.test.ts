/**
 * src/core/settings.ts, the network settings: steps on saved settings, the whole draft and what is shown after each.
 * Until step 9 of retiring the old code the same steps were taken by the old page's functions too and the two compared;
 * the snapshots were taken when they still matched.
 */
import { describe, expect, it } from "vitest"
import * as X from "../src/core/settings"
import { mockData } from "../mock/fixture"

const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value))

/** Rails 0–9 in sections of three ({0,1,2} {3,4,5} {6,7,8} {9}), keyed by node positions as the server writes them */
const KEYS = ["-5,3", "3,17", "17,40", "100,-4", "-4,-90", "-90,7", "12,13", "13,14", "14,15", "9007199254740993,1"]
const SECTIONS = [[0, 1, 2], [3, 4, 5], [6, 7, 8], [9]]
const sectionOf = (index: number) => SECTIONS.find(section => section.includes(index))!

function sides() {
  const make = () => {
    const data: any = mockData()
    data.networkSettings = {
      ...data.networkSettings,
      logicalLines: [{ id: "L1", name: "京沪线", color: 0xff0000, connecting: false }, { id: "L2", name: "联络", color: 0x00ff00, connecting: true }],
      railLines: { [KEYS[1]]: "L1", [KEYS[4]]: "L2", [KEYS[5]]: "L1" },
      lineTracks: { "TRAIN|1号线": ["L1"], "TRAIN|2号线": ["L1", "L2"] },
      ownerOverrides: { [KEYS[0]]: ["TRAIN|1号线"], [KEYS[7]]: ["TRAIN|2号线"] },
      waitThresholdMillis: 240000,
    }
    data.depots[0].rules = { rules: [{ line: "TRAIN|1号线", station: "s1" }], paths: [] }
    data.depots[0].pools[0].vehicleSettings = { set: true, acceleration: 0.01, deceleration: 0.012, manual: false, maxSpeed: 80 }
    const draft: any = { routes: {}, created: [], duties: {}, boundaries: {}, depots: {}, depotRules: {}, platformStations: {}, platformSpeeds: {}, platformStops: {}, networkSettings: null, contingencies: {} }
    return { data, draft }
  }
  const mine = make()
  const ownerOf = new Map([[0, { section: 0, owners: ["TRAIN|1号线"], manual: true }], [3, { section: 1, owners: ["TRAIN|2号线"] }], [9, { section: 3, owners: [] }]])
  const platforms = [{ id: "1000", x: 0, z: 0, passSpeed: 120, stopPosition: 5, stationSet: "s2" }, { id: "1001", x: 400, z: 30 }]
  const topo = { stations: Array.from({ length: 12 }, (_, i) => ({ id: "s" + i, name: "站" + i + "|Station " + i, x: i * 90, z: (i % 3) * 40 })) }
  const keys = (index: number) => sectionOf(index).map(i => KEYS[i])
  /** The whole draft, as the old page left it after the same step */
  const same = () => expect(copy(mine.draft)).toMatchSnapshot()
  return { mine, keys, same, ownerOf, platforms, topo }
}

describe("network settings", () => {
  it("edits logical lines and sections as the old page did, and shows them alike", () => {
    const { mine, keys, same } = sides()
    const shown = () => {
      expect(copy({
        lines: [...X.logicalLinesShown(mine.data, mine.draft)],
        rails: X.railLinesShown(mine.data, mine.draft),
        uses: X.usesLogicalLines(mine.data, mine.draft),
        sections: KEYS.map((_, i) => [X.sectionLogicalLine(mine.data, mine.draft, keys(i)), X.sectionLogicalDrafted(mine.draft, keys(i))]),
      })).toMatchSnapshot()
    }
    shown()
    const steps: (() => void)[] = [
      () => X.setSectionLogicalLine(mine.data, mine.draft, KEYS[0], keys(0), "L2"),
      () => X.setSectionLogicalLine(mine.data, mine.draft, KEYS[4], keys(4), null),
      () => X.setSectionLogicalLine(mine.data, mine.draft, KEYS[9], keys(9), "L1"),
      () => X.setLogicalLine(mine.data, mine.draft, "L3", { name: "新线", color: 255, connecting: false }),
      () => X.setSectionLogicalLine(mine.data, mine.draft, KEYS[7], keys(7), "L3"),
      () => X.setLineTracks(mine.data, mine.draft, "TRAIN|1号线", ["L1", "L3"]),
      () => X.deleteLogicalLine(mine.data, mine.draft, "L1"),
      () => X.setLogicalLine(mine.data, mine.draft, "L1", { name: "京沪线", color: 0xff0000, connecting: false }),
      () => X.deleteLogicalLine(mine.data, mine.draft, "L3"),
      () => X.setSectionLogicalLine(mine.data, mine.draft, KEYS[4], keys(4), "L2"),
    ]
    for (const ours of steps) {
      ours(); same(); shown()
    }
    expect(["9007199254740993,1", "-5,3", "3,17", "-5,2"].sort(X.compareRailKeys)).toMatchSnapshot()
  })
  it("sets section owners by hand as the old page did", () => {
    const { mine, keys, same, ownerOf } = sides()
    const shown = () => { expect(copy(KEYS.map((_, i) => X.sectionOwnersShown(mine.data, mine.draft, keys(i), ownerOf.get(i))))).toMatchSnapshot() }
    const steps: [number, string[] | null][] = [[1, ["TRAIN|2号线"]], [3, []], [7, null], [8, ["TRAIN|1号线", "TRAIN|2号线"]], [1, null], [0, ["TRAIN|1号线"]], [3, null], [8, null]]
    shown()
    for (const [index, lines] of steps) {
      X.setSectionOwners(mine.data, mine.draft, KEYS[index], keys(index), lines)
      same(); shown()
    }
    expect(X.draftOwnerOverrides(mine.draft)).toMatchSnapshot()
    expect(X.savedOwnerOverrides(mine.data)).toMatchSnapshot()
  })
  it("sets waits, platforms, depot entries, rules and boundaries as the old page did", () => {
    const { mine, same, platforms, topo } = sides()
    expect(X.waitThresholdMillis(mine.data, mine.draft)).toMatchSnapshot()
    mine.draft.networkSettings = { waitThresholdMillis: 90000 }
    expect(X.waitThresholdMillis(mine.data, mine.draft)).toMatchSnapshot()
    // The platform menus: each choice of each, with what is ticked
    const menus: [X.PlatformPart, (p: any) => (number | string)[]][] = [
      ["platformSpeeds", () => X.PASS_SPEEDS],
      ["platformStops", () => X.STOP_POSITIONS],
      ["platformStations", p => ["0", ...topo.stations.slice().sort((a, b) => Math.hypot(a.x - p.x, a.z - p.z) - Math.hypot(b.x - p.x, b.z - p.z)).slice(0, 8).map(s => s.id)]],
    ]
    for (const [part, choices] of menus) for (const p of platforms) choices(p).forEach(value => {
      X.setPlatformSetting(mine.draft, part, p, value)
      same()
      // What the menu ticks
      expect(choices(p).map(v => X.platformSetting(mine.draft, part, p) === v)).toMatchSnapshot()
    })
    // A depot's entry changed and stored, then changed back
    const b = mine.data.depots[0]
    expect(X.depotEntryOf(mine.draft, b)).toMatchSnapshot()
    const changes: ((entry: any) => void)[] = [
      entry => { entry.poolFleets.a6 = 12 },
      entry => { entry.sidings["8002"].routes = ["101"]; entry.vehicleSettings.a6 = { ...entry.vehicleSettings.a6, maxSpeed: 100 } },
      entry => { entry.poolFleets.a6 = 9; entry.sidings["8002"].routes = []; entry.vehicleSettings.a6 = { ...entry.vehicleSettings.a6, maxSpeed: 80 } },
      entry => { entry.clearBuffers = true },
      entry => { entry.clearBuffers = false },
      entry => { entry.vehicleSettings.a6 = null },
    ]
    for (const change of changes) {
      const mineEntry = X.depotEntryOf(mine.draft, b)
      expect(mineEntry).toMatchSnapshot()
      change(mineEntry)
      X.storeDepotEntry(mine.draft, b, mineEntry)
      same()
    }
    expect(X.rulesOf(mine.draft, b)).toMatchSnapshot()
    mine.draft.depotRules["9001"] = { rules: { rules: [], paths: [{ name: "p" }] } }
    expect(X.rulesOf(mine.draft, b)).toMatchSnapshot()
    for (const [x, y, boundary, was] of [["1", "2", true, false], ["3", "4", false, true], ["1", "2", false, false], ["5", "6", true, true]] as [string, string, boolean, boolean][]) {
      X.setBoundary(mine.draft, x + "," + y, boundary, was)
      same()
    }
  })
})
