/**
 * src/core/topology.ts, the track solver, on the mock's network (two lines, with a passing track, crossovers, one-way
 * main tracks and a broken leg), every function asked the same things. Until step 9 of retiring the old code the answers
 * were compared with the old page's own solver; the snapshots were taken when they still matched.
 */
import { describe, expect, it } from "vitest"
import { exampleTopology } from "../mock/fixture"
import { Topology } from "../src/core/topology"

function both() {
  const topology = exampleTopology()
  const got: unknown[] = []
  return { mine: new Topology(topology), topology, got, keep: (value: unknown) => { got.push(plain(value)) } }
}
/** Sets as sorted lists and Maps as entries, so answers compare as plain values */
const plain = (value: any): any => (value instanceof Set ? [...value].sort((a, b) => a - b) : value instanceof Map ? [...value.entries()] : Array.isArray(value) ? value.map(plain) : value && typeof value === "object" ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plain(v)])) : value)

describe("the track solver", () => {
  it("names and groups tracks as the old page did", () => {
    const { mine, topology, got, keep } = both()
    topology.platforms.forEach((p, i) => {
      keep([mine.platformLabel(i), mine.stationLabel(i), mine.trackLabel(p.id, p.name, p.passingTrack), mine.trackShort(p.id, p.name, p.passingTrack), mine.stationTracks(i)])
    })
    expect(got).toMatchSnapshot()
  })
  it("finds the same way and cost between every two tracks as the old page did", () => {
    const { mine, topology, got, keep } = both()
    let reached = 0
    for (let a = 0; a < topology.platforms.length; a++) {
      const costs = []
      for (let b = 0; b < topology.platforms.length; b++) {
        const cost = mine.legCost(a, b)
        costs.push(cost)
        if (isFinite(cost)) reached++
      }
      keep(costs)
      for (const s of [a * 2, a * 2 + 1]) keep(mine.reachFrom(s).dist)
    }
    expect(reached).toBeGreaterThan(50)
    expect(got).toMatchSnapshot()
  })
  it("solves stops as the old page did, with and without fixed tracks", () => {
    const { mine, topology, got, keep } = both()
    const id = (pid: string) => topology.platforms.findIndex(p => p.id === pid)
    // Line 1 north on track 1, each stop offered its station's tracks
    const tracks = ["1000", "1001", "1002", "1003", "1004", "1005"].map(id)
    const layers = mine.orderByLayout(tracks.map(p => mine.stationTracks(p)))
    keep(layers)
    const cases: [number[] | null, Map<number, number> | null][] = [
      [tracks, null], [null, null],
      [tracks, new Map([[2, id("1200")]])], // the passing track at 中央广场
      [tracks, new Map([[1, id("1101")]])], // the wrong way at 码头街
      [tracks, new Map([[0, id("1100")], [5, id("1105")]])],
    ]
    let solved = 0
    cases.forEach(([current, pins]) => {
      const a = mine.solveStops(layers, current, pins)
      keep(a)
      if (a.ok) solved++
    })
    expect(solved).toBeGreaterThan(2)
    expect(got).toMatchSnapshot()
  })
  it("lays out the stations between two ends, and the picker, as the old page did", () => {
    const { mine, topology, got, keep } = both()
    const stations = [...new Set(topology.platforms.filter(p => p.station).map((_, i) => i))]
    let found = 0
    for (const a of stations) for (const b of stations) {
      if (a === b) continue
      const stages = [mine.stationTracks(a), mine.stationTracks(b)]
      const route = mine.findCorridor(stages)
      keep(route)
      if (route) {
        found++
        const model = mine.model(route, route.map(() => true), "#123456")
        keep(model)
        model.stop[1] = false
        keep(mine.stopsSummary(model))
      }
    }
    expect(found).toBeGreaterThan(20)
    // Through a station between
    const via = [mine.stationTracks(0), mine.stationTracks(2), mine.stationTracks(5)]
    keep(mine.findCorridor(via))
    expect(got).toMatchSnapshot()
  })
  it("finds the problems with a route's tracks the old page found", () => {
    const { mine, got, keep } = both()
    const route = (id: string, ids: string[]) => ({ id, stops: ids.map((pid, i) => ({ name: "站" + i + "|S", platformId: pid })) })
    for (const ids of [["1000", "1001", "1002"], ["1000", "1101", "1002"], ["2103", "2102", "2101", "2100"], ["1000", "9999"], ["1000", "2001"], ["1002", "1000"]]) {
      const r = route("r" + ids.join(), ids)
      keep(mine.routeProblems(r, ids))
    }
    expect(got).toMatchSnapshot()
  })
})
