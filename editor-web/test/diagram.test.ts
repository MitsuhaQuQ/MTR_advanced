// @vitest-environment happy-dom
/**
 * Parity of src/core/diagram.ts with the old page drawing the same running diagram (the old code run as the new page runs
 * it, its canvas calls doing nothing): the rows, every service's line, the axis, the dwell handles, hit testing and the
 * view's limits.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { beforeAll, describe, expect, it } from "vitest"
import { mockData } from "../mock/fixture"
import * as diagram from "../src/core/diagram"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../common/src/main/resources/assets/mtr/editor")
const W = 1400, H = 640

let engine: typeof import("../src/legacy/engine")
beforeAll(async () => {
  for (const file of ["i18n.js", "batch.js", "terminal.js"]) {
    (0, eval)(fs.readFileSync(path.join(EDITOR, file), "utf8").replace(/^"use strict";/m, "").replace(/^const /gm, "var "))
  }
  const context: any = new Proxy({}, { get: (_, name) => (name === "measureText" ? () => ({ width: 10 }) : name === "getLineDash" ? () => [] : () => {}), set: () => true })
  ;(HTMLCanvasElement.prototype as any).getContext = () => context
  globalThis.fetch = (async () => new Response("", { status: 503 })) as typeof fetch
  engine = await import("../src/legacy/engine")
  engine.pushData(mockData())
  // The diagram's box at a size, as a browser would lay it out
  const wrap = document.getElementById("canvas-wrap")!
  Object.defineProperty(wrap, "clientWidth", { value: W, configurable: true })
  Object.defineProperty(wrap, "clientHeight", { value: H, configurable: true })
  const { v } = engine.legacy
  v.state.line = v.data.lines[0].key
  v.state.view = "diagram"
  v.state.split = false
  v.state.route = null
  v.state.selectedRoute = null
})

/** The old code draws the day; what it drew and the view it drew it at */
function oldDraw(change: (state: any) => void = () => {}) {
  const { fn, v } = engine.legacy
  change(v.state)
  fn.draw()
  const view: diagram.View = { viewStart: v.state.viewStart, pxPerMin: v.state.pxPerMin, scrollY: v.state.scrollY, width: W, height: H }
  return { view, layout: v.layout, drawn: v.drawn as { s: any; points: diagram.Point[] }[], handles: v.handles as any[] }
}

describe("the running diagram's geometry", () => {
  it("lays the rows out as the old page does", () => {
    const { v } = engine.legacy
    for (const showDepots of [false, true]) {
      v.showDepots = showDepots
      const { layout } = oldDraw()
      const line = v.data.lines.find((l: any) => l.key === v.state.line)
      const depotRoom = showDepots && v.data.depots.some((depot: any) => engine.legacy.fn.dutiesOf(depot).mode === "diagram" && depot.sidings.some((siding: any) => siding.legs)) ? diagram.DEPOT_ROW_ROOM : 0
      const mine = diagram.layoutRows(line.rows.map((row: any) => row.gap), H, depotRoom)
      expect(mine.ys).toEqual(layout.ys)
      expect(mine.contentHeight).toBe(layout.contentHeight)
    }
  })
  it("draws every service through the same points, at several zooms and scrolls", () => {
    const { fn } = engine.legacy
    for (const [pxPerMin, viewStart, scrollY] of [[4, 2 * 3600000, 0], [12, 3 * 3600000, 0], [1.2, 0, 0], [30, 4 * 3600000 + 600000, 40]]) {
      const { view, layout, drawn } = oldDraw(state => { state.pxPerMin = pxPerMin; state.viewStart = viewStart; state.scrollY = scrollY })
      expect(drawn.length).toBeGreaterThan(5)
      const x = (millis: number) => diagram.xOf(view, millis), y = (row: number) => layout.ys[row] - view.scrollY
      drawn.forEach(d => {
        const times = fn.timesOf(d.s)
        expect(diagram.inView(times, x, W)).toBe(true)
        expect(diagram.servicePath(d.s, times, x, y)).toEqual(d.points)
      })
      // And none drawn that the new code would leave out, or the other way round
      const all = engine.legacy.v.data.lines[0].routes.flatMap((r: any) => r.services)
      expect(all.filter((s: any) => diagram.inView(fn.timesOf(s), x, W) && diagram.servicePath(s, fn.timesOf(s), x, y).length).length).toBe(drawn.length)
    }
  })
  it("steps the time axis as the old page does", () => {
    const { fn, v } = engine.legacy
    for (const pxPerMin of [0.3, 1, 2.5, 6, 20, 80, 300]) {
      v.state.pxPerMin = pxPerMin
      expect(diagram.axisSteps(pxPerMin)).toEqual(fn.axisSteps())
    }
    const view: diagram.View = { viewStart: 3600000, pxPerMin: 4, scrollY: 0, width: W, height: H }
    const ticks = diagram.axisTicks(view, v.data.dayStartMillis + v.data.offsetMillis)
    expect(ticks.filter(t => t.hour).length).toBeGreaterThan(3)
    expect(ticks.every(t => t.x >= diagram.LABEL_W && t.x < W)).toBe(true)
  })
  it("finds the service under the mouse as the old page does", () => {
    const { fn } = engine.legacy
    const { drawn } = oldDraw(state => { state.pxPerMin = 8; state.viewStart = 2 * 3600000; state.scrollY = 0 })
    let hits = 0
    for (let x = diagram.LABEL_W; x < W; x += 37) {
      for (let y = diagram.AXIS_H; y < H; y += 11) {
        const old = fn.hitService(x, y), mine = diagram.hitPath(drawn, x, y)
        expect(mine && (mine as any).id).toBe(old && old.id)
        if (old) hits++
      }
    }
    expect(hits).toBeGreaterThan(20)
  })
  it("puts a selected service's dwell handles where the old page does", () => {
    const { fn, v } = engine.legacy
    const s = v.data.lines[0].routes[0].services.find((each: any) => each.start > 2 * 3600000 + 1800000)
    const { view, layout, handles } = oldDraw(state => { state.pxPerMin = 8; state.viewStart = 2 * 3600000; state.selected = s.id })
    const mine = diagram.dwellHandles(s, fn.timesOf(s), (millis: number) => diagram.xOf(view, millis), (row: number) => layout.ys[row] - view.scrollY)
    expect(mine.map(h => [h.x, h.y, h.i])).toEqual(handles.map(h => [h.x, h.y, h.i]))
    expect(mine.length).toBeGreaterThan(2)
    expect(diagram.hitHandle(mine, mine[1].x + 3, mine[1].y - 2)!.i).toBe(mine[1].i)
    v.state.selected = null
  })
  it("keeps the view on the day as the old page does", () => {
    const { fn, v } = engine.legacy
    for (const [viewStart, scrollY] of [[-9e6, -5], [2e8, 1e4], [3e6, 15]]) {
      v.state.viewStart = viewStart
      v.state.scrollY = scrollY
      fn.clampView()
      const mine = diagram.clampView({ viewStart, pxPerMin: v.state.pxPerMin, scrollY, width: W, height: H }, v.layout.contentHeight)
      expect([mine.viewStart, mine.scrollY]).toEqual([v.state.viewStart, v.state.scrollY])
    }
  })
  it("draws services as the old page does: colour, width and strength", () => {
    expect(diagram.serviceLook({ selected: false, dim: false, noTrain: false, covered: true, hasTimes: true, suspended: false, notRun: false, showNoTrain: false })).toEqual({ color: "route", width: 2, alpha: 1 })
    expect(diagram.serviceLook({ selected: false, dim: false, noTrain: true, covered: false, hasTimes: true, suspended: false, notRun: false, showNoTrain: true })).toEqual({ color: "warn", width: 1, alpha: 1 })
    expect(diagram.serviceLook({ selected: false, dim: true, noTrain: true, covered: false, hasTimes: true, suspended: false, notRun: false, showNoTrain: false })).toEqual({ color: "muted", width: 1, alpha: 0.25 })
    expect(diagram.serviceLook({ selected: true, dim: false, noTrain: false, covered: false, hasTimes: false, suspended: true, notRun: false, showNoTrain: false })).toEqual({ color: "muted", width: 2.5, alpha: 0.45 })
  })
})
