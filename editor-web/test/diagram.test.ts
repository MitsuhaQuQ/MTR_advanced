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
  it("draws the whole day as the old page does: services, runs out and back, other lines on its track", async () => {
    const { draw } = await import("../src/views/RunningDiagram")
    const { v } = engine.legacy
    v.showDepots = true
    v.showAccess = true
    const { drawn } = oldDraw(state => { state.pxPerMin = 4; state.viewStart = 2 * 3600000; state.scrollY = 0 })
    const g = document.createElement("canvas").getContext("2d") as CanvasRenderingContext2D
    const mine = draw(g, W, H, null, null)
    expect(mine.drawn.map(d => [d.s.id, d.points])).toEqual(drawn.map(d => [d.s.id, d.points]))
    expect(mine.legs.map(l => [l.kind, l.a, l.b, l.text])).toEqual(v.legsDrawn.map((l: any) => [l.kind, l.a, l.b, l.text]))
    expect(mine.legs.length).toBeGreaterThan(5)
    expect(mine.access.map(d => [d.s.id, d.points])).toEqual(v.accessDrawn.map((d: any) => [d.s.id, d.points]))
    // Services being dragged are drawn where they would go
    const moved = drawn[3].s
    const shifted = draw(g, W, H, { ids: [moved.id], shift: 600000 }, null)
    const before = drawn[3].points[0][0], after = shifted.drawn.find(d => d.s.id === moved.id)!.points[0][0]
    expect(after - before).toBeCloseTo(10 * v.state.pxPerMin)
  })
  it("chooses, moves and changes a dwell by the pointer, through the old rules", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { RunningDiagram } = await import("../src/views/RunningDiagram")
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    const { v } = engine.legacy
    engine.pushDraft({ routes: {} })
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    v.state.pxPerMin = 4
    v.state.viewStart = 2 * 3600000
    v.state.scrollY = 0
    v.state.selected = null
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(RunningDiagram)))
    const box = host.querySelector("[data-running-diagram]") as HTMLElement, canvas = box.querySelector("canvas") as HTMLCanvasElement
    Object.defineProperty(box, "clientWidth", { value: W })
    Object.defineProperty(box, "clientHeight", { value: H })
    canvas.getBoundingClientRect = () => ({ left: 0, top: 0, right: W, bottom: H, width: W, height: H, x: 0, y: 0, toJSON() {} })
    canvas.setPointerCapture = () => {}
    await act(async () => { engine.flushLegacy() })
    const { draw } = await import("../src/views/RunningDiagram")
    const g = document.createElement("canvas").getContext("2d") as CanvasRenderingContext2D
    const target = draw(g, W, H, null, null).drawn[4]
    const [x, y] = target.points[1]
    const fire = async (type: string, px: number, py: number) => act(async () => { canvas.dispatchEvent(new PointerEvent(type, { bubbles: true, clientX: px, clientY: py, pointerId: 1, button: 0 })) })
    // A click chooses it
    await fire("pointerdown", x, y)
    await fire("pointerup", x, y)
    expect(v.state.selected).toBe(target.s.id)
    // Dragged 40 px right: moved later, into the draft
    await fire("pointerdown", x, y)
    await fire("pointermove", x + 20, y)
    await fire("pointermove", x + 40, y)
    await fire("pointerup", x + 40, y)
    await new Promise(resolve => setTimeout(resolve, 20))
    expect(changes.length).toBeGreaterThan(0)
    expect(changes[changes.length - 1].routes[target.s.route.id].timetable).toBeTruthy()
    engine.pushDraft({ routes: {} })
    v.state.selected = null
  })
  it("lays out the summary timetable cell for cell as the old page does", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { SummaryTable } = await import("../src/views/SummaryTable")
    const { fn, v } = engine.legacy
    engine.pushDraft({ routes: {} })
    v.state.view = "table"
    v.state.selected = null
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(SummaryTable, { className: "" })))
    const cells = (root: Element) => [...root.querySelectorAll("table.tt td, table.tt th")].map(c => [c.tagName, c.className, c.textContent!.replace(/\s+/g, " ").trim(), (c as HTMLElement).dataset.svc || "", (c as HTMLElement).dataset.stop || "", (c as HTMLElement).dataset.kind || ""])
    for (const route of [null, "101", "103"]) {
      v.state.route = route
      fn.renderTable()
      await act(async () => { engine.flushLegacy() })
      const old = cells(document.getElementById("view-table")!), mine = cells(host)
      expect(mine.length).toBeGreaterThan(50)
      expect(mine).toEqual(old)
    }
    // Editing a time: typed, Enter, into the draft through the old changeTime
    v.state.route = "101"
    await act(async () => { engine.flushLegacy() })
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const cell = host.querySelector('td[data-kind="dep"]:not(.unserved):not(.derived)') as HTMLElement
    await act(async () => { cell.dispatchEvent(new MouseEvent("click", { bubbles: true })) })
    let input = host.querySelector("td input") as HTMLInputElement
    expect(input).toBeTruthy()
    const time = fn.clock(fn.timesOf(fn.findService(cell.dataset.svc))[Number(cell.dataset.stop)][1] + 60000, true)
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, time)
      input.dispatchEvent(new Event("input", { bubbles: true }))
    })
    input = host.querySelector("td input") as HTMLInputElement
    await act(async () => { input.dispatchEvent(new KeyboardEvent("keydown", { key: "Tab", bubbles: true })) })
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); engine.flushLegacy() })
    expect(changes[changes.length - 1].routes["101"].timetable).toBeTruthy()
    // Tab went on to the next cell
    expect(host.querySelector("td input")).toBeTruthy()
    // Adding services from a start to an end at an interval: fitted around those already there (TimetableBatch.fitSpan)
    engine.pushDraft({ routes: {} })
    await act(async () => { engine.flushLegacy() })
    const add = [...host.querySelectorAll("button")].find(b => b.textContent === "添加") as HTMLElement
    const fields = [...add.parentElement!.querySelectorAll("input")] as HTMLInputElement[]
    for (const [field, value] of [[fields[0], "03:00"], [fields[1], "03:20"], [fields[2], "5"]] as [HTMLInputElement, string][]) {
      await act(async () => {
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(field, value)
        field.dispatchEvent(new Event("input", { bubbles: true }))
      })
    }
    await act(async () => { add.click() })
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); engine.flushLegacy() })
    const added = Number(/新增 (\d+) 班/.exec(v.tableMessage)![1])
    expect(v.tableMessage).toMatch(/^03:00–03:20：保留/)
    expect(added).toBeGreaterThan(0)
    expect(changes[changes.length - 1].routes["101"].timetable.tables[0].services.length).toBe(fn.gameTimetable(v.data.routeById.get("101")).tables[0].services.length + added)
    engine.pushDraft({ routes: {} })
    v.state.view = "diagram"
    v.state.route = null
  })
  it("draws services as the old page does: colour, width and strength", () => {
    expect(diagram.serviceLook({ selected: false, dim: false, noTrain: false, covered: true, hasTimes: true, suspended: false, notRun: false, showNoTrain: false })).toEqual({ color: "route", width: 2, alpha: 1 })
    expect(diagram.serviceLook({ selected: false, dim: false, noTrain: true, covered: false, hasTimes: true, suspended: false, notRun: false, showNoTrain: true })).toEqual({ color: "warn", width: 1, alpha: 1 })
    expect(diagram.serviceLook({ selected: false, dim: true, noTrain: true, covered: false, hasTimes: true, suspended: false, notRun: false, showNoTrain: false })).toEqual({ color: "muted", width: 1, alpha: 0.25 })
    expect(diagram.serviceLook({ selected: true, dim: false, noTrain: false, covered: false, hasTimes: false, suspended: true, notRun: false, showNoTrain: false })).toEqual({ color: "muted", width: 2.5, alpha: 0.45 })
  })
  it("connects the last and first rows at the same departure coordinate for a loop", async () => {
    const { draw } = await import("../src/views/RunningDiagram")
    const data = mockData()
    const route = data.routes[0]
    route.circular = true
    route.stops[route.stops.length - 1] = { ...route.stops[0] }
    route.tables[0].services = [21600000, 22620000]
    data.routes = [route]
    const depot = data.depots[0]
    depot.routes = [route.id]
    depot.duties = { mode: "diagram", sidings: { "8001": [[{
      days: 127, stay: false, next: -1, duty: "1", segments: route.tables[0].services.map(service => ({ route: route.id, service, from: 0, to: -1 })),
    }]] } }
    data.depots = [depot]
    data.dayStartMillis = 0
    const { v } = engine.legacy
    v.state.weekday = 0
    engine.pushData(data)
    v.state.line = v.data.lines[0].key
    v.state.viewStart = 21600000
    v.state.pxPerMin = 8
    v.state.scrollY = 0
    v.state.slot = { depot: depot.id, siding: "8001", index: 0 }
    v.showDepots = false
    let dash: number[] = [], point: number[] = []
    const strokes: { from: number[]; to: number[]; dash: number[] }[] = []
    const calls: Record<string, (...args: any[]) => unknown> = {
      moveTo: (x, y) => { point = [x, y] },
      lineTo: (x, y) => { strokes.push({ from: point, to: [x, y], dash }); point = [x, y] },
      setLineDash: value => { dash = value },
      getLineDash: () => dash,
      measureText: () => ({ width: 10 }),
    }
    const g = new Proxy({}, { get: (_, name) => calls[String(name)] || (() => {}) }) as CanvasRenderingContext2D
    const result = draw(g, W, H, null, null)
    const [firstLap, nextLap] = [...result.drawn].sort((a, b) => a.s.start - b.s.start)
    const [lastX, lastY] = firstLap.points.at(-1)!
    const [nextX, nextY] = nextLap.points[0]
    expect(lastX).toBeLessThan(nextX)
    expect(lastY).not.toBe(nextY)
    expect(strokes).toContainEqual({ from: [lastX, lastY], to: [nextX, lastY], dash: [] })
    expect(strokes).toContainEqual({ from: [nextX, lastY], to: [nextX, nextY], dash: [5, 4] })
    expect(strokes).not.toContainEqual({ from: [lastX, lastY], to: [nextX, nextY], dash: [3, 3] })
  })
})
