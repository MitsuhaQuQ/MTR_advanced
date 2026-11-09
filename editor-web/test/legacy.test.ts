// @vitest-environment happy-dom
/**
 * Smoke test of the old code as the new page runs it (src/legacy/, generated from editor.js and media.js): the old
 * page's elements on the stage, the dictionary and helpers loaded, the example data given in. Run on every build, so a
 * change to editor.js the generator can't handle, or one the new page's bridge doesn't fit, shows here and not first in
 * the game.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { beforeAll, describe, expect, it } from "vitest"
import { mockData } from "../mock/fixture"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../common/src/main/resources/assets/mtr/editor")

let engine: typeof import("../src/legacy/engine")
beforeAll(async () => {
  // The old page's plain scripts, as globals: their top-level const become var (a strict eval would keep them to itself)
  for (const file of ["i18n.js", "batch.js", "terminal.js"]) {
    (0, eval)(fs.readFileSync(path.join(EDITOR, file), "utf8").replace(/^"use strict";/m, "").replace(/^const /gm, "var "))
  }
  // No real drawing here: any 2D context call does nothing
  const context: any = new Proxy({}, { get: (_, name) => (name === "measureText" ? () => ({ width: 10 }) : name === "getLineDash" ? () => [] : () => {}), set: () => true })
  ;(HTMLCanvasElement.prototype as any).getContext = () => context
  globalThis.fetch = (async () => new Response("", { status: 503 })) as typeof fetch
  engine = await import("../src/legacy/engine")
})

describe("the old code in the new page", () => {
  it("takes the data and works out lines, rows and services", () => {
    engine.pushData(mockData())
    const { v } = engine.legacy
    expect(v.data.lines.map((line: any) => line.key)).toEqual(["TRAIN|1号线|Line 1", "TRAIN|2号线|Line 2"])
    expect(v.data.lines[0].rows.length).toBeGreaterThan(3)
    expect(v.data.routes.every((r: any) => Array.isArray(r.services))).toBe(true)
    expect(v.data.routes.some((r: any) => r.services.length > 0)).toBe(true)
  })
  it("draws its tree, summary table and inspector into the old elements", () => {
    const { fn, v } = engine.legacy
    v.state.line = v.data.lines[0].key
    v.state.view = "table"
    v.state.split = false
    engine.renderLegacy()
    expect(document.getElementById("view-table")!.querySelectorAll("td").length).toBeGreaterThan(10)
    expect(() => fn.draw()).not.toThrow()
  })
  it("hands its draft changes to the page as one step", async () => {
    const { fn, v } = engine.legacy
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const r = v.data.routes[0]
    fn.setManaged(r, !r.editorManaged)
    fn.setConnecting(r, true)
    await new Promise(resolve => setTimeout(resolve, 10))
    expect(changes.length).toBe(1)
    expect(changes[0].routes[r.id]).toMatchObject({ managed: !r.editorManaged, connectingLine: true })
  })
  it("takes the page's draft and works out its services again", () => {
    const { v } = engine.legacy
    engine.pushDraft({ routes: {} })
    expect(v.draft.routes).toEqual({})
  })
  it("drives the old tree's handlers from the new navigator", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { Navigator } = await import("../src/views/Navigator")
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    const { v } = engine.legacy
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(Navigator, { part: 4 })))
    const line = v.data.lines[0], r = line.routes[1]
    v.state.line = line.key
    await act(async () => { engine.renderLegacy(); engine.flushLegacy() })
    // A route's row opens its views; its chip opens the summary table (onTreeClick)
    const row = document.querySelector(`#tree .node[data-route="${r.id}"]:not([data-view])`) as HTMLElement
    expect(row).toBeTruthy()
    await act(async () => { row.click(); engine.flushLegacy() })
    const chip = document.querySelector(`#tree .node[data-route="${r.id}"][data-view="table"]`) as HTMLElement
    expect(chip).toBeTruthy()
    await act(async () => { chip.click(); engine.flushLegacy() })
    expect(v.state.route).toBe(r.id)
    expect(v.state.view).toBe("table")
    // A right-click opens the old code's menu for it
    let items: any[] = []
    engine.menuHost.current = { open: (_x, _y, list) => { items = list }, close: () => {} }
    await act(async () => row.dispatchEvent(new MouseEvent("contextmenu", { bubbles: true, cancelable: true, clientX: 5, clientY: 5 })))
    expect(items.map(item => item[0])).toContain("新建时刻表…")
  })
  it("shows the selected service and the line in the new inspector", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { Inspector } = await import("../src/views/Inspector")
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    const { v } = engine.legacy
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(Inspector, { className: "" })))
    const line = v.data.lines[0], r = line.routes[0]
    v.state.line = line.key
    v.state.selected = r.services[0].id
    await act(async () => { engine.renderLegacy(); engine.flushLegacy() })
    const text = document.getElementById("inspector")!.textContent!
    expect(text).toContain("选中的班次")
    expect(text).toContain("线路")
    // Drawn again after another choice, without losing its nodes
    v.state.selected = r.services[1].id
    await act(async () => { engine.renderLegacy(); engine.flushLegacy() })
    expect(document.getElementById("inspector")!.textContent).toContain("选中的班次")
  })
  it("edits a duty on the duty board", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { DutyBoard } = await import("../src/views/DutyBoard")
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    const { v } = engine.legacy
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => { createRoot(host).render(createElement(DutyBoard)); engine.flushLegacy() })
    const train = () => [...host.querySelectorAll("button[aria-pressed]")].find(b => b.textContent!.startsWith("运用 2")) as HTMLElement
    expect(train()).toBeTruthy()
    // Choose the second train, take its first service out
    await act(async () => { train().click(); engine.flushLegacy() })
    expect(v.state.slot).toMatchObject({ depot: "9001", siding: "8001", index: 1 })
    const blocks = () => [...train().parentElement!.querySelectorAll("button[title]")].filter(b => b !== train()) as HTMLElement[]
    const before = blocks().length
    await act(async () => { blocks()[0].click(); engine.flushLegacy() })
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 10)); engine.flushLegacy() })
    expect(blocks().length).toBe(before - 1)
    await new Promise(resolve => setTimeout(resolve, 10))
    const last = changes[changes.length - 1]
    // The day edited is split off from the duty's other days; the day shown has one service fewer
    const day = last.duties["9001"].duties.sidings["8001"][1].find((each: any) => (each.days >> v.state.weekday) & 1)
    expect(day.segments.length).toBe(before - 1)
  })
  it("starts the media library", async () => {
    const media = await import("../src/legacy/media")
    expect(() => media.setMediaShown(true)).not.toThrow()
    expect(() => media.setMediaShown(false)).not.toThrow()
  })
})
