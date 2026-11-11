import { describe, expect, it } from "vitest"
import { createElement } from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { compressedAxis, trackSchematic, type Point } from "../src/core/trackSchematic"
import { TrackMapDrawing } from "../src/views/TrackMapDrawing"
import { qNetwork, qPlatforms } from "./fixtures/qNetwork"

describe("track schematic connectivity", () => {
  it("draws all 96 real rails, including both scissors diagonals and both depot tracks", () => {
    const map = trackSchematic(qNetwork, qPlatforms)
    expect(map.rails).toHaveLength(96)
    expect(map.rails.filter(r => r.kind === 2)).toHaveLength(2)
    expect(map.stations.map(s => s.name.split("|")[0])).toContain("Twelve")
    const endpoints = new Map<number, Point>()
    for (const r of map.rails) for (const [i, p] of [[r.nodes[0], r.points[0]], [r.nodes[1], r.points[r.points.length - 1]]] as const) {
      if (endpoints.has(i)) expect(p).toEqual(endpoints.get(i))
      endpoints.set(i, p)
    }
    // The two real scissors assemblies have all four connections, not an invented generic X glyph.
    const at = (x: number, z: number) => qNetwork.nodes.findIndex(p => p[0] === x + 0.5 && p[1] === z + 0.5)
    for (const [x1, x2, z1, z2] of [[-2027, -1987, 2000, 2003], [-2020, -1976, 2474, 2478]]) {
      const left = [at(x1, z1), at(x1, z2)], right = [at(x2, z1), at(x2, z2)]
      expect([...left, ...right].every(n => n >= 0)).toBe(true)
      expect(map.rails.filter(r => (left.includes(r.nodes[0]) && right.includes(r.nodes[1])) || (right.includes(r.nodes[0]) && left.includes(r.nodes[1])))).toHaveLength(4)
    }
    const html = renderToStaticMarkup(createElement("svg", {}, createElement(TrackMapDrawing, { map, chosen: null, onChoose: () => {} })))
    expect(html.match(/data-rail=/g)).toHaveLength(96)
    expect(html).toContain("存车线")
    expect(html).toContain("Twelve")
  })

  it("keeps a bypass beside the main line when only the main line has an intermediate turnout", () => {
    const map = trackSchematic({ nodes: [[0, 0, "a"], [200, 0, "j"], [400, 0, "b"], [0, 8, "c"], [400, 8, "d"], [250, -10, "e"]], rails: [[0, 1, 0, 0], [1, 2, 0, 0], [3, 4, 0, 0], [1, 5, 2, 0]] })
    expect(new Set(map.rails[0].points.map(p => p[1])).size).toBe(1)
    expect(new Set(map.rails[2].points.map(p => p[1])).size).toBe(1)
    expect(map.rails[2].points[0][1] - map.rails[0].points[0][1]).toBe(32)
    expect(map.rails[0].points.at(-1)).toEqual(map.rails[3].points[0])
    expect(map.junctions).toHaveLength(1)
  })

  it("keeps both crossing rails inside a station instead of deduplicating the lane pair", () => {
    const map = trackSchematic({ nodes: [[0, 0, "a"], [40, 0, "b"], [0, 8, "c"], [40, 8, "d"]], rails: [[0, 1, 1, 0], [2, 3, 1, 0], [0, 3, 0, 0], [2, 1, 0, 0]], stations: [{ id: "s", name: "S", x: 20, z: 4 }] })
    expect(map.rails).toHaveLength(4)
    expect(map.junctions).toHaveLength(0)
    expect(map.rails[2].points[0][1]).toBeLessThan(map.rails[2].points.at(-1)![1])
    expect(map.rails[3].points[0][1]).toBeGreaterThan(map.rails[3].points.at(-1)![1])
  })

  it("compresses empty distances without reversing or collapsing track order", () => {
    const { project } = compressedAxis([0, 4, 12, 1000, 1004])
    expect(project(4) - project(0)).toBe(16)
    expect(project(1004) - project(1000)).toBe(16)
    expect(project(1000) - project(12)).toBeLessThan(988)
    for (let x = 1; x <= 1004; x++) expect(project(x)).toBeGreaterThan(project(x - 1))
    expect(trackSchematic({ nodes: [], rails: [] }).width).toBeGreaterThan(0)
  })
})
