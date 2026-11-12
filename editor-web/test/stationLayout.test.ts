/**
 * src/core/stationLayout.ts, a station's own track diagram: the mock's stations (every station, margins, moved, both ways
 * along the track picked) and the turn platforms ticked in turn. Until step 9 of retiring the old code the numbers were
 * compared with the SVG the old stops dialog drew (stationDiagramHtml) and its toggleTurnPlatform; the snapshots were
 * taken when they still matched.
 */
import { describe, expect, it } from "vitest"
import * as L from "../src/core/stationLayout"
import * as T from "../src/core/timetable"
import { mockData } from "../mock/fixture"
import { stationLayout } from "../mock/track"
import { fingerprint } from "./fingerprint"


describe("a station's own track diagram", () => {
  it("turns, frames and draws the rails and platforms as the old dialog did", () => {
    const data: any = mockData()
    const platforms = data.topology.platforms
    const views: unknown[] = []
    let drawn = 0
    for (const station of ["s1", "s2", "s3", "s4", "s6"]) for (const margin of [48, 28, 77]) for (const pan of [[0, 0], [12, -5]] as L.Point[]) for (const state of [0, 1]) {
      const layout = stationLayout(platforms, station, margin) as L.LayoutData
      const layer = platforms.map((p: any, k: number) => (p.station === station ? k : -1)).filter((k: number) => k >= 0)
      const picks = [layer[0], layer[layer.length - 1]], i = 1
      const tracks = new Set<string>(layer.map((k: number) => platforms[k].id))
      const view = L.stationView(layout, station, tracks, platforms[picks[i]].id, (state & 1) === 0, margin, pan, "station")
      views.push({ station, margin, pan, state, viewBox: view.viewBox, width: view.width, height: view.height, unit: view.unit, rails: view.rails, turnouts: view.turnouts,
        platforms: view.platforms.map(p => [p.ours, p.a, p.b, p.start]) })
      drawn += view.platforms.length + view.rails.length
    }
    expect(drawn).toBeGreaterThan(200)
    expect(fingerprint(views)).toMatchSnapshot()
    // Framed by the stop's own tracks: 中央广场's line 2 platform, far off, no longer widens the view
    const layout = stationLayout(platforms, "s3", 48) as L.LayoutData
    const tracks = new Set(["1002", "1102", "1200"])
    const byStation = L.stationView(layout, "s3", tracks, "1002", true, 48, [0, 0], "station")
    const byTracks = L.stationView(layout, "s3", tracks, "1002", true, 48, [0, 0])
    expect(byTracks.viewBox[2]).toBeLessThan(byStation.viewBox[2] / 2)
    expect(byTracks.rails).toEqual(byStation.rails)
  })
  it("ticks the platforms to start from and turn back at in turn as the old dialog did", () => {
    const make = () => {
      const data: any = mockData()
      data.routeById = new Map(data.routes.map((r: any) => [r.id, r]))
      data.routes[0].terminalPlatforms = ["3005"]
      return { data, draft: { routes: {} } as any }
    }
    const mine = make()
    const steps: [L.TurnKind, string][] = [["terminalPlatforms", "1200"], ["terminalPlatforms", "3005"], ["startPlatforms", "1100"], ["terminalPlatforms", "3005"], ["terminalPlatforms", "1200"], ["startPlatforms", "1100"]]
    for (const [kind, platform] of steps) {
      L.toggleTurnPlatform(mine.draft, mine.data.routes[0], kind, platform)
      expect([JSON.parse(JSON.stringify(mine.draft)), L.turnOf(mine.draft, mine.data.routes[0], kind)]).toMatchSnapshot()
    }
    expect(T.draftOf(mine.draft, mine.data.routes[0])).toBeTruthy()
  })
})
