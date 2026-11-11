/** The whole network folded into stations and corridors (src/core/abstractNet.ts), on the mock's network. */
import { describe, expect, it } from "vitest"
import { abstractNetwork } from "../src/core/abstractNet"
import { exampleTrack } from "../mock/track"
import { exampleTopology } from "../mock/fixture"

const schematic = () => abstractNetwork(exampleTrack().network as any, exampleTopology().platforms)
const name = (id: string, s = schematic()) => s.places.find(p => p.id === id)!.name.split("|")[0]

describe("abstractNetwork", () => {
  it("makes a place of each station, with its tracks side by side", () => {
    const s = schematic()
    const stations = s.places.filter(p => p.kind === "station")
    expect(stations.map(p => p.name.split("|")[0]).sort()).toEqual(["中央广场", "北湖", "会展中心", "市政厅", "机场", "港湾", "码头街", "西门"].sort())
    const central = stations.find(p => p.name.startsWith("中央广场"))!
    // The passing loop, the up track and the down track, all platforms, numbered
    expect(central.lanes.map(l => l.name).sort()).toEqual(["1", "2", "5"])
    expect(central.lanes.every(l => l.platform)).toBe(true)
    // Line 1 and line 2 stop on its up track
    expect(central.lanes.find(l => l.name === "1")!.colors.sort()).toEqual(["#0072ce", "#c8102e", "#e8758a"])
    // 北湖's turn back tracks are its own, not platforms
    const lake = stations.find(p => p.name.startsWith("北湖"))!
    expect(lake.lanes.filter(l => l.platform).length).toBe(2)
    // The passing loop's turnouts in its throats, at both ends
    expect(new Set(central.turnouts.map(t => t.end))).toEqual(new Set([-1, 1]))
    const expo = stations.find(p => p.name.startsWith("会展中心"))!
    expect(expo.lanes.map(l => l.name).sort()).toEqual(["1", "2"])
  })
  it("joins neighbouring stations with corridors of as many tracks as run between them", () => {
    const s = schematic()
    const between = (a: string, b: string) => s.corridors.find(c => [name(c.a, s), name(c.b, s)].sort().join() === [a, b].sort().join())
    expect(between("港湾", "码头街")!.tracks).toBe(2)
    expect(between("中央广场", "市政厅")!.tracks).toBe(2)
    expect(between("中央广场", "会展中心")!.tracks).toBe(1)
    expect(between("会展中心", "机场")!.tracks).toBe(1)
    // Line 2 colours its corridor
    expect(between("会展中心", "机场")!.colors).toEqual(["#0072ce"])
    // Each track meets its own lane at both ends: the two tracks of a double-track section don't share one
    const pier = between("港湾", "码头街")!
    expect(new Set(pier.lines.map(t => t.aLane)).size).toBe(2)
    expect(new Set(pier.lines.map(t => t.bLane)).size).toBe(2)
    expect(pier.lines.every(t => t.aLane >= 0 && t.bLane >= 0)).toBe(true)
    // Nothing but stations and the corridors between them: no junctions left over
    expect(s.places.filter(p => p.kind === "junction")).toEqual([])
  })
  it("lays it out like a metro map: every corridor at a multiple of 45°", () => {
    const s = schematic()
    const at = new Map(s.places.map(p => [p.id, p]))
    s.corridors.forEach(c => {
      const a = at.get(c.a)!, b = at.get(c.b)!
      const angle = Math.atan2(b.py - a.py, b.px - a.px) / (Math.PI / 4)
      expect(Math.abs(angle - Math.round(angle))).toBeLessThan(0.08)
    })
    // Stations kept apart enough for their boxes
    const stations = s.places.filter(p => p.kind === "station")
    for (let i = 0; i < stations.length; i++) for (let j = i + 1; j < stations.length; j++) {
      expect(Math.hypot(stations[i].px - stations[j].px, stations[i].py - stations[j].py)).toBeGreaterThan(55)
    }
  })

  it("keeps the four real connections of a scissors crossover and a depot spur", () => {
    const nodes: [number, number, string][] = []
    const at = (x: number, z: number) => { nodes.push([x, z, String(nodes.length)]); return nodes.length - 1 }
    const a0 = at(0, 0), a1 = at(40, 0), j00 = at(160, 0), j10 = at(200, 0), b0 = at(360, 0), b1 = at(400, 0)
    const c0 = at(0, 10), c1 = at(40, 10), j01 = at(160, 10), j11 = at(200, 10), d0 = at(360, 10), d1 = at(400, 10)
    const depot = at(260, 70)
    const rails = [
      [a0, a1, 1], [a1, j00, 0], [j00, j10, 0], [j10, b0, 0], [b0, b1, 1],
      [c0, c1, 1], [c1, j01, 0], [j01, j11, 0], [j11, d0, 0], [d0, d1, 1],
      [j00, j11, 0], [j01, j10, 0], [j11, depot, 2],
    ]
    const s = abstractNetwork({ nodes, rails, stations: [{ id: "a", name: "A", x: 20, z: 5 }, { id: "b", name: "B", x: 380, z: 5 }] })
    const junctions = s.places.filter(p => p.kind === "junction")
    expect(junctions).toHaveLength(2)
    expect(junctions.map(p => p.ports)).toEqual([2, 2])
    const crossing = s.corridors.find(c => c.a.startsWith("j:") && c.b.startsWith("j:"))!
    expect(crossing.lines.map(t => `${t.aLane}-${t.bLane}`).sort()).toEqual(["0-0", "0-1", "1-0", "1-1"])
    const spur = s.corridors.find(c => c.lines.some(t => t.kind === 2))!
    expect(spur.tracks).toBe(1)
    expect(s.places.some(p => p.kind === "end" && (p.id === spur.a || p.id === spur.b))).toBe(true)
  })

  it("does not invent a through track for a centre turn-back road", () => {
    const nodes: [number, number, string][] = []
    const at = (x: number, z: number) => { nodes.push([x, z, String(nodes.length)]); return nodes.length - 1 }
    const lm = at(-150, 0), m0 = at(-40, 0), m1 = at(40, 0), turnout = at(70, 0), rm = at(150, 0)
    const lt = at(-150, 10), t0 = at(-40, 10), t1 = at(40, 10)
    const rails = [[lm, m0, 0], [m0, m1, 1], [m1, turnout, 0], [turnout, rm, 0], [lt, t0, 0], [t0, t1, 1], [t1, turnout, 0]]
    const platforms = [{ id: "main", name: "1", station: "s", line: [-40, 0, 40, 0] }, { id: "turn", name: "2", station: "s", line: [-40, 10, 40, 10] }]
    const s = abstractNetwork({ nodes, rails, stations: [{ id: "s", name: "Terminus", x: 0, z: 5 }] }, platforms)
    const station = s.places.find(p => p.kind === "station")!
    const main = station.lanes.find(l => l.name === "1")!, turn = station.lanes.find(l => l.name === "2")!
    expect([main.negative, main.positive]).toEqual([true, true])
    expect([turn.negative, turn.positive]).toEqual([true, false])
    expect(station.turnouts).toContainEqual({ from: 1, to: 0, end: 1 })
  })
})
