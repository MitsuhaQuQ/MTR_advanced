/** The track diagram's schematic (src/core/schematic.ts) on made-up layouts of the kinds lines are built of. */
import { describe, expect, it } from "vitest"
import { buildSchematic, railKey, usageOf, type RouteLayout } from "../src/core/schematic"

/** A layout from rails given as [from node, to node, d1, o1, d2, o2], straight between their ends. */
function layout(rails: [string, string, number, number, number, number][], extra: Partial<RouteLayout> = {}): RouteLayout {
  const length = Math.max(...rails.map(r => Math.max(r[2], r[4])))
  return {
    found: true, length, stops: [], turnouts: [], platforms: [], ...extra,
    rails: rails.map(([, , d1, o1, d2, o2]) => [d1, o1, (d1 + d2) / 2, (o1 + o2) / 2, d2, o2]),
    railKeys: rails.map(([a, b]) => [a, b, 0]),
  }
}
const lanesOf = (schematic: ReturnType<typeof buildSchematic>) => schematic.segments.map(s => [s.lane1, s.lane2])

describe("lanes", () => {
  it("puts the path on lane 0 and the track beside it on the next lane", () => {
    const s = buildSchematic(layout([["1", "2", 0, 0, 100, 0], ["3", "4", 0, -4, 100, -4]]), new Map(), "r")
    expect(lanesOf(s)).toEqual([[0, 0], [-1, -1]])
    expect(s.lanes).toEqual({ min: -1, max: 0 })
  })
  it("draws a crossover as a diagonal between the lanes it joins", () => {
    const s = buildSchematic(layout([
      ["1", "2", 0, 0, 100, 0], ["2", "3", 100, 0, 200, 0],
      ["4", "5", 0, -4, 110, -4], ["5", "6", 110, -4, 200, -4],
      ["2", "5", 100, 0, 110, -4],
    ], { turnouts: [[100, 0], [110, -4]] }), new Map(), "r")
    const diagonal = s.segments.find(seg => seg.key === railKey("2", "5"))!
    expect(diagonal.diagonal).toBe(true)
    expect([diagonal.lane1, diagonal.lane2].sort()).toEqual([-1, 0])
    expect(s.turnouts.map(t => t.lane).sort()).toEqual([-1, 0])
  })
  it("gives tracks 4 and 5 blocks out the same lane, one lane per track by order", () => {
    const s = buildSchematic(layout([
      ["1", "2", 0, 0, 1000, 0],
      ["3", "4", 100, 4, 200, 4], ["5", "6", 600, 5, 700, 5],
      ["7", "8", 600, 10, 700, 10],
    ]), new Map(), "r")
    expect(lanesOf(s)).toEqual([[0, 0], [1, 1], [1, 1], [2, 2]])
  })
  it("sends a track leaving the line one lane further out than the rest", () => {
    const s = buildSchematic(layout([
      ["1", "2", 0, 0, 100, 0], ["2", "3", 100, 0, 300, 0],
      ["9", "10", 0, -4, 300, -4],
      ["2", "7", 100, 0, 140, 20],
    ]), new Map(), "r")
    const branch = s.segments.find(seg => seg.key === railKey("2", "7"))!
    expect([branch.lane1, branch.lane2]).toEqual([0, 1])
  })
})

describe("stations and platforms", () => {
  it("puts each platform on its track's lane, and the station's name over them, by the nearest stop", () => {
    const s = buildSchematic(layout([
      ["1", "2", 0, 0, 500, 0], ["3", "4", 200, 4, 300, 4],
    ], {
      stops: [250],
      platforms: [
        { id: "p1", name: "1", station: "s", stationName: "中央广场", line: [220, 0, 280, 0] },
        { id: "p2", name: "2", station: "s", stationName: "中央广场", line: [220, 4, 280, 4], passingTrack: true },
      ],
    }), new Map(), "r", new Set(["p1"]))
    expect(s.platforms.map(p => [p.id, p.lane, p.ours])).toEqual([["p1", 0, true], ["p2", 1, false]])
    expect(s.stations).toHaveLength(1)
    expect(s.stations[0]).toMatchObject({ name: "中央广场", stop: 0 })
  })
})

describe("the line shortened between the places that matter", () => {
  it("keeps stations' spacing and shortens a long run, marking it", () => {
    const s = buildSchematic(layout([["1", "2", 0, 0, 3000, 0]], {
      stops: [100, 2900],
      platforms: [
        { id: "a", name: "1", station: "s1", stationName: "A", line: [80, 0, 120, 0] },
        { id: "b", name: "1", station: "s2", stationName: "B", line: [2880, 0, 2920, 0] },
      ],
    }), new Map(), "r")
    const [a, b] = s.platforms
    // A platform keeps its length
    expect(a.x2 - a.x1).toBeCloseTo(40, 5)
    expect(b.x2 - b.x1).toBeCloseTo(40, 5)
    // The 2760 blocks between them are far shorter, and marked
    expect(b.x1 - a.x2).toBeLessThan(250)
    expect(s.breaks.length).toBe(1)
    expect(s.width).toBeLessThan(500)
  })
  it("keeps the order of everything along the line", () => {
    const s = buildSchematic(layout([["1", "2", 0, 0, 1000, 0], ["2", "3", 1000, 0, 5000, 0]], { stops: [10, 1000, 4990] }), new Map(), "r")
    const xs = s.stops.map(stop => stop.x)
    expect([...xs].sort((x, y) => x - y)).toEqual(xs)
  })
})

describe("which routes run where", () => {
  const network = {
    nodes: [[0, 0, "1"], [0, 0, "2"], [0, 0, "3"], [0, 0, "4"]],
    routes: [
      { id: "other", color: 0x0072ce, nodes: [1, 2, 3] },
      { id: "r", color: 0xc8102e, nodes: [0, 1, 2] },
    ],
  }
  it("finds the routes on each rail from their paths, the diagram's route first", () => {
    const usage = usageOf(network, "r")
    expect(usage.get(railKey("1", "2"))!.map(u => u.route)).toEqual(["r"])
    expect(usage.get(railKey("2", "3"))!.map(u => u.route)).toEqual(["r", "other"])
    expect(usage.get(railKey("3", "4"))!.map(u => u.route)).toEqual(["other"])
  })
  it("marks the rails the route runs on", () => {
    const s = buildSchematic(layout([["1", "2", 0, 0, 100, 0], ["2", "3", 100, 0, 200, 0], ["3", "4", 200, 0, 300, 0]]), usageOf(network, "r"), "r")
    expect(s.segments.map(seg => seg.ours)).toEqual([true, true, false])
    expect(s.segments[2].routes.map(u => u.route)).toEqual(["other"])
  })
})
