/**
 * Parity of src/core/topology.ts with the old page's track solver (retiring the old code, step 1): the same network (the
 * mock's two lines, with a passing track, crossovers, one-way main tracks and a broken leg), every function asked the same
 * things, the answers compared.
 */
import { describe, expect, it } from "vitest"
import { loadOld } from "./old"
import { exampleTopology } from "../mock/fixture"
import { Topology } from "../src/core/topology"

const NAMES = ["buildTopology", "isUsable", "TRACK_DIRECTIONS", "TRACK_SHORT", "trackInfo", "trackLabel", "trackShort", "platformLabel", "stationLabel", "stationTracks", "reachFrom", "legCost", "compareTuples", "solveStops", "findCorridor", "orderByLayout", "topologyModel", "stopsSummaryOf", "shortName", "routeProblems", "platformsOf", "draftOf"]

function both() {
  const topology = exampleTopology()
  const old = loadOld<any>(NAMES, { data: { topology }, topo: null, draft: { routes: {} } })
  old.buildTopology()
  return { old, mine: new Topology(topology), topology }
}
/** Sets as sorted lists and Maps as entries, so answers compare as plain values */
const plain = (value: any): any => (value instanceof Set ? [...value].sort((a, b) => a - b) : value instanceof Map ? [...value.entries()] : Array.isArray(value) ? value.map(plain) : value && typeof value === "object" ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plain(v)])) : value)

describe("the track solver", () => {
  it("names and groups tracks as the old page does", () => {
    const { old, mine, topology } = both()
    topology.platforms.forEach((p, i) => {
      expect(mine.platformLabel(i)).toBe(old.platformLabel(i))
      expect(mine.stationLabel(i)).toBe(old.stationLabel(i))
      expect(mine.trackLabel(p.id, p.name, p.passingTrack)).toBe(old.trackLabel(p.id, p.name, p.passingTrack))
      expect(mine.trackShort(p.id, p.name, p.passingTrack)).toBe(old.trackShort(p.id, p.name, p.passingTrack))
      expect(mine.stationTracks(i)).toEqual(old.stationTracks(i))
    })
  })
  it("finds the same way and cost between every two tracks", () => {
    const { old, mine, topology } = both()
    let reached = 0
    for (let a = 0; a < topology.platforms.length; a++) {
      for (let b = 0; b < topology.platforms.length; b++) {
        const cost = mine.legCost(a, b)
        expect(cost).toBe(old.legCost(a, b))
        if (isFinite(cost)) reached++
      }
      for (const s of [a * 2, a * 2 + 1]) expect(plain(mine.reachFrom(s).dist)).toEqual(plain(old.reachFrom(s).dist))
    }
    expect(reached).toBeGreaterThan(50)
  })
  it("solves stops the same way, with and without fixed tracks", () => {
    const { old, mine, topology } = both()
    const id = (pid: string) => topology.platforms.findIndex(p => p.id === pid)
    // Line 1 north on track 1, each stop offered its station's tracks
    const tracks = ["1000", "1001", "1002", "1003", "1004", "1005"].map(id)
    const layers = mine.orderByLayout(tracks.map(p => mine.stationTracks(p)))
    expect(layers).toEqual(old.orderByLayout(tracks.map((p: number) => old.stationTracks(p))))
    const cases: [number[] | null, Map<number, number> | null][] = [
      [tracks, null], [null, null],
      [tracks, new Map([[2, id("1200")]])], // the passing track at 中央广场
      [tracks, new Map([[1, id("1101")]])], // the wrong way at 码头街
      [tracks, new Map([[0, id("1100")], [5, id("1105")]])],
    ]
    let solved = 0
    cases.forEach(([current, pins]) => {
      const a = mine.solveStops(layers, current, pins), b = old.solveStops(layers, current, pins)
      expect(plain(a)).toEqual(plain(b))
      if (a.ok) solved++
    })
    expect(solved).toBeGreaterThan(2)
  })
  it("lays out the same stations between two ends, and the same picker", () => {
    const { old, mine, topology } = both()
    const stations = [...new Set(topology.platforms.filter(p => p.station).map((_, i) => i))]
    let found = 0
    for (const a of stations) for (const b of stations) {
      if (a === b) continue
      const stages = [mine.stationTracks(a), mine.stationTracks(b)]
      const route = mine.findCorridor(stages)
      expect(route).toEqual(old.findCorridor(stages))
      if (route) {
        found++
        const model = mine.model(route, route.map(() => true), "#123456")
        expect(plain(model)).toEqual(plain(old.topologyModel(route, route.map(() => true), "#123456")))
        model.stop[1] = false
        expect(mine.stopsSummary(model)).toBe(old.stopsSummaryOf(model))
      }
    }
    expect(found).toBeGreaterThan(20)
    // Through a station between
    const via = [mine.stationTracks(0), mine.stationTracks(2), mine.stationTracks(5)]
    expect(mine.findCorridor(via)).toEqual(old.findCorridor(via))
  })
  it("finds the same problems with a route's tracks", () => {
    const { old, mine } = both()
    const route = (id: string, ids: string[]) => ({ id, stops: ids.map((pid, i) => ({ name: "站" + i + "|S", platformId: pid })) })
    for (const ids of [["1000", "1001", "1002"], ["1000", "1101", "1002"], ["2103", "2102", "2101", "2100"], ["1000", "9999"], ["1000", "2001"], ["1002", "1000"]]) {
      const r = route("r" + ids.join(), ids)
      expect(mine.routeProblems(r, ids)).toEqual(old.routeProblems(r))
    }
  })
})
