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
  it("changes a route's tracks and stops in the stops editor", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { StopsEditor } = await import("../src/views/StopsEditor")
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    const { fn, v } = engine.legacy
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(StopsEditor)))
    // The old code's call opens the new editor
    await act(async () => fn.stopsDialog(v.data.routeById.get("101")))
    const stop = (i: number) => host.querySelector(`li[data-stop="${i}"]`) as HTMLElement
    expect(host.textContent).toContain("中央广场")
    expect(host.textContent).not.toContain("沿轨道走不通")
    // 中央广场's passing track: fixed there, the stations around still run on track 1
    const loop = [...stop(2).querySelectorAll("button[aria-pressed]")].find(b => b.textContent!.includes("5")) as HTMLElement
    await act(async () => loop.click())
    expect(loop.getAttribute("aria-pressed")).toBe("true")
    expect(stop(2).textContent).toContain("改了股道")
    // 市政厅 passed
    const toggle = [...stop(3).querySelectorAll("button")].find(b => b.textContent === "停车") as HTMLElement
    await act(async () => toggle.click())
    expect(toggle.textContent).toBe("通过")
    const save = [...host.querySelectorAll("button")].find(b => b.textContent === "放进草稿") as HTMLElement
    await act(async () => { save.click(); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    const entry = changes[changes.length - 1].routes["101"]
    expect(entry.platforms).toEqual(["1000", "1001", "1200", "1003", "1004", "1005"])
    expect(entry.timetable.pass).toEqual([false, false, false, true, false, false])
    expect(host.textContent).toBe("")
    // A route whose rails don't run through shows where
    await act(async () => fn.stopsDialog(v.data.routeById.get("202")))
    expect(host.textContent).toContain("沿轨道走不通")
    await act(async () => ([...host.querySelectorAll("button")].find(b => b.textContent === "取消") as HTMLElement).click())
  })
  it("makes a new line group both ways in the new line editor", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { StopsEditor } = await import("../src/views/StopsEditor")
    const { fn } = engine.legacy
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(StopsEditor)))
    await act(async () => fn.newLineDialog())
    const set = async (element: HTMLInputElement | HTMLSelectElement, value: string) => {
      const proto = element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype
      await act(async () => {
        Object.getOwnPropertyDescriptor(proto, "value")!.set!.call(element, value)
        element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? "change" : "input", { bubbles: true }))
      })
    }
    const labelled = (text: string) => [...host.querySelectorAll("label")].find(l => l.textContent!.startsWith(text))!.querySelector("input, select") as HTMLInputElement
    await set(labelled("线路组名"), "3号线")
    await set(labelled("种别"), "普通")
    await set(labelled("起点"), "s1|0")
    await set(labelled("终点"), "s6|0")
    expect(host.querySelectorAll("li[data-stop]").length).toBe(6)
    expect(host.textContent).toContain("建出：3号线 普通 上行、3号线 普通 下行")
    await act(async () => { ([...host.querySelectorAll("button")].find(b => b.textContent === "新建") as HTMLElement).click(); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    const created = changes[changes.length - 1].created
    expect(created.map((c: any) => c.name)).toEqual(["3号线 普通 上行", "3号线 普通 下行"])
    expect(created[0].stops.map((s: any) => s.platformId)).toEqual(["1000", "1001", "1002", "1003", "1004", "1005"])
    expect(created[1].stops[0].platformId).toBe("1105")
    engine.pushDraft({ routes: {} })
  })
  it("paints sections and ticks logical lines in the ownership panel", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { OwnershipPanel, paint } = await import("../src/views/OwnershipPanel")
    const { fn, v } = engine.legacy
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    // Three rails in two sections, as api/network gives them
    v.net.mode = "TRAIN"
    v.net.layer = "owners"
    v.net.data = { modes: ["TRAIN"], nodes: [[0, 0, "1"], [10, 0, "2"], [20, 0, "3"], [30, 0, "4"]], rails: [[0, 1, 0, 0], [1, 2, 0, 0], [2, 3, 0, 0]], owners: { ready: true } }
    v.net.sectionRails = new Map([["a", [0, 1]], ["b", [2]]])
    v.net.ownerOf = new Map([[0, { section: "a", owners: [], manual: false }], [1, { section: "a", owners: [], manual: false }], [2, { section: "b", owners: [], manual: false }]])
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(OwnershipPanel, { className: "" })))
    // Without logical lines: the operating lines are the brushes
    expect(host.textContent).toContain("只归 1号线")
    await act(async () => { paint(1, { kind: "owner", lines: ["TRAIN|1号线|Line 1"], label: "" }); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    expect(changes[changes.length - 1].networkSettings.ownerOverrides).toEqual({ "2,3": ["TRAIN|1号线|Line 1"] })
    // A logical line, then a section painted into it: its brush counts one section
    await act(async () => { fn.draftSettingsPart("logicalLines", (drafted: any) => { drafted["77"] = { name: "干线", color: 0x3366cc, connecting: false } }); engine.flushLegacy() })
    expect(host.textContent).toContain("逻辑线路画笔")
    await act(async () => { paint(2, { kind: "logical", id: "77" }); engine.flushLegacy() })
    const row = [...host.querySelectorAll("li")].find(li => li.textContent!.includes("干线"))!
    expect(row.textContent).toContain("1")
    // Line 1 runs on it
    const box = host.querySelector('input[aria-label="1号线 走 干线"]') as HTMLInputElement
    await act(async () => { box.click(); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    const settings = changes[changes.length - 1].networkSettings
    expect(settings.railLines).toEqual({ "3,4": "77" })
    expect(settings.lineTracks).toEqual({ "TRAIN|1号线|Line 1": ["77"] })
    // Deleting it takes its sections and ticks with it
    const { removeLogicalLine } = await import("../src/views/OwnershipPanel")
    await act(async () => { removeLogicalLine("77"); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    expect(changes[changes.length - 1].networkSettings).toEqual({ ownerOverrides: { "2,3": ["TRAIN|1号线|Line 1"] } })
    engine.pushDraft({ routes: {} })
  })
  it("draws the network schematic and shows a station's tracks", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { NetworkSchematic } = await import("../src/views/NetworkSchematic")
    const { exampleTrack } = await import("../mock/track")
    const { v } = engine.legacy
    v.net.data = exampleTrack().network
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(NetworkSchematic, { onShowReal: () => {} })))
    const stations = [...host.querySelectorAll("g[role=button]")]
    expect(stations.length).toBe(8)
    const central = stations.find(g => g.getAttribute("aria-label") === "中央广场")!
    await act(async () => { central.dispatchEvent(new MouseEvent("click", { bubbles: true })) })
    expect(host.textContent).toContain("5 号站台")
    expect(host.textContent).toContain("在实际图上看")
  })
  it("starts the media library", async () => {
    const media = await import("../src/legacy/media")
    expect(() => media.setMediaShown(true)).not.toThrow()
    expect(() => media.setMediaShown(false)).not.toThrow()
  })
})
