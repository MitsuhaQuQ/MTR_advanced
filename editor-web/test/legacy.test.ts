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
    expect(document.getElementById("tree")!.textContent).toContain("1号线")
    expect(document.getElementById("view-table")!.querySelectorAll("td").length).toBeGreaterThan(10)
    expect(document.getElementById("inspector")!.textContent!.length).toBeGreaterThan(0)
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
  it("starts the media library", async () => {
    const media = await import("../src/legacy/media")
    expect(() => media.setMediaShown(true)).not.toThrow()
    expect(() => media.setMediaShown(false)).not.toThrow()
  })
})
