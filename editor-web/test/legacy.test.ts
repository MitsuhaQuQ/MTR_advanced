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
  it("makes a new type of a line both ways in the new type editor", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { StopsEditor } = await import("../src/views/StopsEditor")
    const { fn, v } = engine.legacy
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(StopsEditor)))
    await act(async () => fn.newTypeDialog("TRAIN|1号线|Line 1", v.data.routeById.get("101")))
    expect(host.textContent).toContain("新建种别")
    // The ends of route 101 to start with, both ways
    expect(host.querySelectorAll("li[data-stop]").length).toBe(6)
    const type = [...host.querySelectorAll("label")].find(l => l.textContent!.startsWith("种别"))!.querySelector("input") as HTMLInputElement
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(type, "急行")
      type.dispatchEvent(new Event("input", { bubbles: true }))
    })
    await act(async () => { ([...host.querySelectorAll("button")].find(b => b.textContent === "新建") as HTMLElement).click(); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    const created = changes[changes.length - 1].created
    expect(created.map((c: any) => [c.serviceType, c.source])).toEqual([["急行", "101"], ["急行", "102"]])
    // Each way on the tracks its route uses there
    expect(created[0].stops.map((s: any) => s.platformId)).toEqual(["1000", "1001", "1002", "1003", "1004", "1005"])
    expect(created[1].stops.map((s: any) => s.platformId)).toEqual(["1105", "1104", "1103", "1102", "1101", "1100"])
    expect(created[0].depot).toBe("9001")
    engine.pushDraft({ routes: {} })
  })
  it("edits speed limits, through services and contingency plans in their panels", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { LinePanels } = await import("../src/views/LinePanels")
    const { fn, v } = engine.legacy
    engine.pushData(mockData())
    engine.pushDraft({ routes: {} })
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(LinePanels)))
    const button = (text: string) => [...host.querySelectorAll("button")].find(b => b.textContent!.startsWith(text)) as HTMLElement
    const type = async (input: HTMLInputElement, value: string) => act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, value)
      input.dispatchEvent(new Event("input", { bubbles: true }))
    })
    const settle = async () => { engine.flushLegacy(); await new Promise(resolve => setTimeout(resolve, 10)) }

    // Speed limits: above the fastest rail is refused as typed, a good value goes in the draft
    await act(async () => fn.speedLimitDialog([v.data.routeById.get("101")]))
    const speed = host.querySelector('[data-speed-route="101"] input') as HTMLInputElement
    await type(speed, "150")
    expect(host.textContent).toContain("不能高于轨道限速 120 km/h")
    await type(speed, "90")
    await act(async () => { button("放进草稿").click(); await settle() })
    await settle()
    expect(changes[changes.length - 1].routes["101"].speedLimit).toBe(90)

    // Through services: route 102 ends at 港湾, where 101 starts; leave the first pair out
    await act(async () => fn.throughDialog(v.data.routeById.get("102")))
    const boxes = [...host.querySelectorAll("tbody input[type=checkbox]")] as HTMLInputElement[]
    expect(boxes.length).toBeGreaterThan(10)
    await act(async () => boxes[0].click())
    expect(button("新建 ").textContent).toBe(`新建 ${boxes.length - 1} 对`)
    await act(async () => { button("新建 ").click(); await settle() })
    await settle()
    const links = changes[changes.length - 1].routes["102"].timetable.tables.flatMap((t: any) => t.links)
    expect(links.length).toBe(boxes.length - 1)
    expect(links.every((l: any) => l.route === "101")).toBe(true)

    // Contingency plans: a new one, turning back at a chosen station
    await act(async () => fn.contingencyDialog(v.data.contingencySystems[0]))
    await act(async () => button("新建预案").click())
    const plan = host.querySelector("[data-plan='1']") as HTMLElement
    await type(plan.querySelector("input[aria-label=名称]") as HTMLInputElement, "中断")
    const turn = [...plan.querySelectorAll("select")].find(s => [...s.options].some(o => o.value === "station")) as HTMLSelectElement
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value")!.set!.call(turn, "station")
      turn.dispatchEvent(new Event("change", { bubbles: true }))
    })
    // No station chosen yet
    await act(async () => button("放进草稿").click())
    expect(host.textContent).toContain("选了指定车站折返，但没选车站")
    const station = [...plan.querySelectorAll("select")].find(s => [...s.options].some(o => o.value === "s3")) as HTMLSelectElement
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value")!.set!.call(station, "s3")
      station.dispatchEvent(new Event("change", { bubbles: true }))
    })
    await act(async () => { button("放进草稿").click(); await settle() })
    await settle()
    expect(changes[changes.length - 1].contingencies.a1).toMatchObject([{ id: "1", name: "中断", turn: "station", turnStation: "s3", lateMinutes: 5 }])
    engine.pushDraft({ routes: {} })
  })
  it("makes a new table for a line's routes in the table panel", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { LinePanels } = await import("../src/views/LinePanels")
    const { fn, v } = engine.legacy
    engine.pushDraft({ routes: {} })
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(LinePanels)))
    await act(async () => fn.tableDialog(v.data.routeById.get("101"), -1))
    // Sunday belongs to 周末 now
    const sunday = [...host.querySelectorAll("button[aria-pressed]")].find(b => b.textContent!.startsWith("周日")) as HTMLElement
    expect(sunday.textContent).toContain("周末")
    const name = [...host.querySelectorAll("label")].find(l => l.textContent!.startsWith("表名"))!.querySelector("input") as HTMLInputElement
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(name, "周日")
      name.dispatchEvent(new Event("input", { bubbles: true }))
    })
    await act(async () => sunday.click())
    const copy = [...host.querySelectorAll("select")].find(s => [...s.options].some(o => o.textContent!.includes("周末"))) as HTMLSelectElement
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value")!.set!.call(copy, "1")
      copy.dispatchEvent(new Event("change", { bubbles: true }))
    })
    await act(async () => { ([...host.querySelectorAll("button")].find(b => b.textContent === "新建") as HTMLElement).click(); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    const routes = changes[changes.length - 1].routes
    for (const id of ["101", "102", "103"]) {
      const tables = routes[id].timetable.tables
      expect(tables.map((t: any) => [t.name, t.weekdays])).toEqual([["平日", 31], ["周末", 32], ["周日", 64]])
      expect(tables[2].services).toEqual(tables[1].services)
    }
    engine.pushDraft({ routes: {} })
  })
  it("edits a depot's routes and sidings in the depot panel", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { LinePanels } = await import("../src/views/LinePanels")
    const { fn } = engine.legacy
    // Something else drafted in the network settings, which the depot panel must keep
    engine.pushDraft({ routes: {}, networkSettings: { logicalLines: { "77": { name: "干线", color: 1, connecting: false } } } })
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(LinePanels)))
    await act(async () => fn.depotDialog(fn.depotById("9001")))
    const columns = () => host.querySelectorAll("tr[data-siding='8001'] input[type=checkbox]").length
    expect(columns()).toBe(3)
    // The depot stops running route 103: the sidings' table loses its column at once
    const route103 = [...host.querySelectorAll("button[aria-pressed]")].find(b => b.textContent!.includes("区间")) as HTMLElement
    await act(async () => route103.click())
    expect(columns()).toBe(2)
    const box = host.querySelector("input[aria-label='1 道 跑 1号线 全程 南行 → 港湾']") as HTMLInputElement
    await act(async () => box.click())
    await act(async () => { ([...host.querySelectorAll("button")].find(b => b.textContent === "放进草稿") as HTMLElement).click(); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    const last = changes[changes.length - 1]
    expect(last.depots["9001"].routes.sort()).toEqual(["101", "102"])
    expect(last.depots["9001"].sidings["8001"].routes).toEqual(["102"])
    expect(last.depots["9001"].sidings["8003"].routes.sort()).toEqual(["101", "102"])
    expect(last.networkSettings.logicalLines).toEqual({ "77": { name: "干线", color: 1, connecting: false } })
    engine.pushDraft({ routes: {} })
  })
  it("edits a depot's rules, keeping them across choosing a way on the map", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { LinePanels } = await import("../src/views/LinePanels")
    const { fn, v } = engine.legacy
    engine.pushDraft({ routes: {} })
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(LinePanels)))
    await act(async () => fn.depotRulesDialog(fn.depotById("9001")))
    const button = (text: string) => [...host.querySelectorAll("button")].find(b => b.textContent === text) as HTMLElement
    await act(async () => button("新增规则").click())
    await act(async () => ([...host.querySelectorAll("[data-rule='0'] button[aria-pressed]")].find(b => b.textContent === "1号线") as HTMLElement).click())
    await act(async () => ([...host.querySelectorAll("[data-rule='0'] button[aria-pressed]")].find(b => b.textContent === "六") as HTMLElement).click())
    // Off to the map and back without choosing: the panel opens again with the rule as it was
    await act(async () => ([...host.querySelectorAll("[data-rule='0'] button")].find(b => b.textContent === "在图上点轨道…") as HTMLElement).click())
    expect(v.wayPick).toBeTruthy()
    expect(host.textContent).toBe("")
    await act(async () => fn.finishWayPick(false))
    expect(v.wayPick).toBe(null)
    expect(host.querySelector("[data-rule='0']")).toBeTruthy()
    await act(async () => { button("放进草稿").click(); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    const rule = changes[changes.length - 1].depotRules["9001"].rules.rules[0]
    expect(rule).toMatchObject({ lines: ["TRAIN|1号线|Line 1"], weekdays: 32 })
    v.state.view = "diagram"
    engine.pushDraft({ routes: {} })
  })
  it("edits a vehicle and shows the release plan and duty groups in their panels", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { LinePanels } = await import("../src/views/LinePanels")
    const { fn } = engine.legacy
    engine.pushDraft({ routes: {} })
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(LinePanels)))
    const depot = fn.depotById("9001")
    const button = (text: string) => [...host.querySelectorAll("button")].find(b => b.textContent === text) as HTMLButtonElement
    const input = (text: string) => [...host.querySelectorAll("label")].find(l => l.textContent!.trim().startsWith(text))!.querySelector("input") as HTMLInputElement
    const type = async (element: HTMLInputElement, value: string) => act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(element, value)
      element.dispatchEvent(new Event("input", { bubbles: true }))
    })

    // A vehicle's settings: a wrong acceleration is refused as typed
    await act(async () => fn.vehicleDialog(depot, depot.pools[0]))
    await act(async () => input("统一设置").click())
    await type(input("加速度"), "0")
    expect(host.textContent).toContain("加速度写正数")
    expect(button("放进草稿").disabled).toBe(true)
    await type(input("加速度"), "1.2")
    await act(async () => { button("放进草稿").click(); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    expect(changes[changes.length - 1].depots["9001"].vehicleSettings.a6.acceleration).toBeCloseTo(1.2 / 400)

    // The release plan: six trains, on any day chosen in the panel
    await act(async () => fn.releasePlanDialog(depot))
    expect(host.querySelectorAll("tbody tr").length).toBe(6)
    await act(async () => ([...host.querySelectorAll("button[aria-pressed]")].find(b => b.textContent === "周六") as HTMLElement).click())
    expect(host.textContent).toContain("周六")
    expect(host.querySelectorAll("tbody tr").length).toBe(6)
    await act(async () => button("关闭").click())

    // Duty groups: none planned yet
    await act(async () => fn.dutyGroupsDialog(depot))
    expect(host.textContent).toContain("还没有按组排过")
    await act(async () => button("关闭").click())
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
    // The tracks drawing: platforms beside their tracks, turnouts as diagonals in 中央广场
    expect(central.querySelectorAll("rect title").length).toBe(3)
    expect(central.querySelectorAll("g > line").length).toBeGreaterThan(3)
  })
  it("paints a whole section on the logical map with a brush", async () => {
    const { act, createElement } = await import("react")
    const { createRoot } = await import("react-dom/client")
    const { NetworkSchematic } = await import("../src/views/NetworkSchematic")
    const { setBrushNow } = await import("../src/views/OwnershipPanel")
    const { exampleTrack } = await import("../mock/track")
    const { v } = engine.legacy
    engine.pushDraft({ routes: {} })
    v.net.data = exampleTrack().network
    v.net.ownerOf = new Map()
    v.net.sectionRails = new Map()
    v.net.layer = "owners"
    const changes: any[] = []
    engine.onLegacyDraft(draft => changes.push(draft))
    const host = document.createElement("div")
    document.body.appendChild(host)
    await act(async () => createRoot(host).render(createElement(NetworkSchematic, { onShowReal: () => {}, mode: "logical" })))
    expect(host.querySelector("svg")!.getAttribute("aria-label")).toBe("全网逻辑图")
    await act(async () => setBrushNow({ kind: "owner", lines: ["TRAIN|2号线|Line 2"], label: "" }))
    // The section from 会展中心 to 机场, line 2's single track
    const sections = [...host.querySelectorAll("g.cursor-crosshair")]
    expect(sections.length).toBeGreaterThan(5)
    await act(async () => { sections[sections.length - 1].dispatchEvent(new MouseEvent("click", { bubbles: true })); engine.flushLegacy() })
    await new Promise(resolve => setTimeout(resolve, 10))
    const overrides = changes[changes.length - 1].networkSettings.ownerOverrides
    expect(Object.values(overrides).length).toBeGreaterThan(0)
    expect(Object.values(overrides).every((lines: any) => lines[0] === "TRAIN|2号线|Line 2")).toBe(true)
    await act(async () => setBrushNow(null))
    engine.pushDraft({ routes: {} })
  })
  it("starts the media library", async () => {
    const media = await import("../src/legacy/media")
    expect(() => media.setMediaShown(true)).not.toThrow()
    expect(() => media.setMediaShown(false)).not.toThrow()
  })
})
