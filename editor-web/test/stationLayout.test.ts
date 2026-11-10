/**
 * Parity of src/core/stationLayout.ts with the old stops dialog's station part: the old stationDiagramHtml cut from
 * editor.js draws the mock's stations (every station, margins, moved, both ways along the track picked) and the numbers in
 * its SVG are compared with the new view's; the turn platforms ticked in turn as the old toggleTurnPlatform does.
 */
import { describe, expect, it } from "vitest"
import { loadOld } from "./old"
import * as L from "../src/core/stationLayout"
import * as T from "../src/core/timetable"
import { mockData } from "../mock/fixture"
import { stationLayout } from "../mock/track"

const nums = (text: string) => text.trim().split(/[\s,]+/).map(Number)
const attr = (tag: string, name: string) => { const m = new RegExp(`\\b${name}="([^"]*)"`).exec(tag); return m ? m[1] : null }

describe("a station's own track diagram", () => {
  it("turns, frames and draws the rails and platforms as the old dialog does", () => {
    const data: any = mockData()
    const platforms = data.topology.platforms
    const index = new Map<string, number>(platforms.map((p: any, k: number) => [p.id, k]))
    const old = loadOld<any>(["stationDiagramHtml"], {
      topo: { platforms, index },
      stationLabel: (p: number) => platforms[p].stationName, trackShort: (id: string, name: string) => name + "#" + id, trackLabel: (id: string, name: string) => name + "@" + id,
      shortName: (name: string) => (name || "").split("|")[0], escapeHtml: (text: string) => String(text), throatHtml: () => "", turnHtml: () => "",
    })
    let compared = 0
    for (const station of ["s1", "s2", "s3", "s4", "s6"]) for (const margin of [48, 28, 77]) for (const pan of [[0, 0], [12, -5]] as L.Point[]) for (const state of [0, 1]) {
      const layout = stationLayout(platforms, station, margin) as L.LayoutData
      const layer = platforms.map((p: any, k: number) => (p.station === station ? k : -1)).filter((k: number) => k >= 0)
      const picks = [layer[0], layer[layer.length - 1]], i = 1
      const model = {
        layers: [[0], layer], picks, pins: new Map([[1, layer[layer.length - 1]]]), color: "#c8102e",
        diagram: { stop: i, margin, pan, data: layout },
        solution: { ok: true, arrive: [0, state], depart: [state, 0], feasible: [new Set([0]), new Set(layer.slice(1))] },
      }
      const html: string = old.stationDiagramHtml(model)
      const tracks = new Set<string>(layer.map((k: number) => platforms[k].id))
      const view = L.stationView(layout, station, tracks, platforms[picks[i]].id, (state & 1) === 0, margin, pan, "station")
      // The frame
      const svg = /<svg[^>]*>/.exec(html)![0]
      expect(nums(attr(svg, "viewBox")!)).toEqual(view.viewBox)
      expect(attr(svg, "style")).toBe(`aspect-ratio:${view.width} / ${view.height}`)
      expect(Number(attr(svg, "data-unit"))).toBe(view.unit)
      // The rails, point for point
      const polylines = [...html.matchAll(/<polyline [^>]*>/g)].map(m => nums(attr(m[0], "points")!))
      expect(polylines).toEqual(view.rails.map(line => line.flat()))
      const circles = [...html.matchAll(/<circle [^>]*>/g)].map(m => [Number(attr(m[0], "cx")), Number(attr(m[0], "cy"))])
      expect(circles).toEqual(view.turnouts)
      // The platforms: their ends, which are this stop's, and where the name goes
      const lines = [...html.matchAll(/<g class="platform ([^"]*)"[^>]*>.*?<line ([^>]*)\/><text x="([^"]*)" y="([^"]*)"/g)]
      expect(lines.length).toBe(view.platforms.length)
      lines.forEach((m, k) => {
        const p = view.platforms[k]
        expect(m[1].split(" ")[0] === "ours").toBe(p.ours)
        expect(["x1", "y1", "x2", "y2"].map(name => Number(attr(m[2], name)))).toEqual([...p.a, ...p.b])
        expect([Number(m[3]), Number(m[4])]).toEqual([p.start[0] - view.unit * 6, p.start[1] + view.unit * 4])
      })
      compared += lines.length + polylines.length
    }
    expect(compared).toBeGreaterThan(200)
    // Framed by the stop's own tracks: 中央广场's line 2 platform, far off, no longer widens the view
    const layout = stationLayout(platforms, "s3", 48) as L.LayoutData
    const tracks = new Set(["1002", "1102", "1200"])
    const byStation = L.stationView(layout, "s3", tracks, "1002", true, 48, [0, 0], "station")
    const byTracks = L.stationView(layout, "s3", tracks, "1002", true, 48, [0, 0])
    expect(byTracks.viewBox[2]).toBeLessThan(byStation.viewBox[2] / 2)
    expect(byTracks.rails).toEqual(byStation.rails)
  })
  it("ticks the platforms to start from and turn back at in turn as the old dialog does", () => {
    const make = () => {
      const data: any = mockData()
      data.routeById = new Map(data.routes.map((r: any) => [r.id, r]))
      data.routes[0].terminalPlatforms = ["3005"]
      return { data, draft: { routes: {} } as any }
    }
    const theirs = make(), mine = make()
    const old = loadOld<any>(["turnOf", "toggleTurnPlatform", "draftOf", "draftEntry"], { data: theirs.data, draft: theirs.draft, tidyDraft() {}, saveDraft() {} })
    const steps: [L.TurnKind, string][] = [["terminalPlatforms", "1200"], ["terminalPlatforms", "3005"], ["startPlatforms", "1100"], ["terminalPlatforms", "3005"], ["terminalPlatforms", "1200"], ["startPlatforms", "1100"]]
    for (const [kind, platform] of steps) {
      old.toggleTurnPlatform(theirs.data.routes[0], kind, platform)
      L.toggleTurnPlatform(mine.draft, mine.data.routes[0], kind, platform)
      expect(JSON.parse(JSON.stringify(mine.draft))).toEqual(JSON.parse(JSON.stringify(theirs.draft)))
      expect(L.turnOf(mine.draft, mine.data.routes[0], kind)).toEqual(old.turnOf(theirs.data.routes[0], kind))
    }
    expect(T.draftOf(mine.draft, mine.data.routes[0])).toBeTruthy()
  })
})
