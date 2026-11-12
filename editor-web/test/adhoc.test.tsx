// @vitest-environment happy-dom
/**
 * An ad hoc measure for one train from the sync panel (adhocDialog of the old page, moved in when the old page went in
 * step 9 of retiring it): the form is filled from what the train has now, refuses what can't be sent, and sends one
 * operation at once, not through the draft.
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest"
import { act, createElement } from "react"
import { createRoot, type Root } from "react-dom/client"
import { mockData } from "../mock/fixture"
import { buildModel } from "../src/core/model"
import { emptyDraft } from "../src/core/draft"
import type { Store } from "../src/state/store"

const EDITOR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../common/src/main/resources/assets/mtr/editor")
let StoreContext: typeof import("../src/state/store").StoreContext
let SyncPanel: typeof import("../src/shell/Sync").SyncPanel
beforeAll(async () => {
  // The page's plain scripts, as globals, before the store (which starts what is left of the old code)
  for (const file of ["i18n.js", "batch.js", "terminal.js"]) {
    (0, eval)(fs.readFileSync(path.join(EDITOR, file), "utf8").replace(/^"use strict";/m, "").replace(/^const /gm, "var "))
  }
  ;(HTMLCanvasElement.prototype as any).getContext = () => new Proxy({}, { get: () => () => {} })
  globalThis.fetch = (async () => new Response("", { status: 503 })) as typeof fetch
  ;(await import("../src/i18n")).setLanguage("zh_cn")
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  StoreContext = (await import("../src/state/store")).StoreContext
  SyncPanel = (await import("../src/shell/Sync")).SyncPanel
})

let root: Root | undefined
afterEach(async () => { if (root) await act(async () => root?.unmount()); root = undefined; document.body.innerHTML = "" })

function storeWith(sendNow: Store["sendNow"]): Store {
  const data: any = mockData()
  data.depots[0].alarms = [{ train: "t1", siding: data.depots[0].sidings[0].id, slot: 0, route: "101", system: "a1", plan: "", handled: false, reason: "LATE", late: 400 }]
  data.contingencySystems[0].adhoc = { t1: { turn: "station", turnStation: data.contingencySystems[0].stations[2].id, afterTurn: "deadhead", skipStations: [], takeover: false } }
  return {
    model: buildModel(data), connection: "ok", loadedAt: 0, language: "", draft: emptyDraft(), draftLoaded: true, edit() {}, undo() {}, redo() {},
    canUndo: false, canRedo: false, applying: false, applyResult: null, boundaryErrors: {}, apply: async () => {}, dismissResult() {}, sendNow, refresh() {},
  }
}

const select = async (element: HTMLSelectElement, value: string) => act(async () => {
  Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value")!.set!.call(element, value)
  element.dispatchEvent(new Event("change", { bubbles: true }))
})
const click = async (element: Element) => act(async () => { (element as HTMLElement).click() })
const button = (text: string) => [...document.querySelectorAll("button")].find(b => b.textContent === text)!

describe("an ad hoc measure from the sync panel", () => {
  it("fills in the train's measure, refuses an empty one, and sends it at once", async () => {
    const sendNow = vi.fn(async () => true)
    const host = document.createElement("div")
    document.body.appendChild(host)
    root = createRoot(host)
    await act(async () => root!.render(createElement(StoreContext.Provider, { value: storeWith(sendNow) }, createElement(SyncPanel, { open: true, onClose() {} }))))
    await click(button("临时处置…"))
    const form = document.querySelector("[data-adhoc=t1]")!
    expect(form).toBeTruthy()
    const [turn, station, after] = [...form.querySelectorAll("select")] as HTMLSelectElement[]
    // What the train has now
    expect([turn.value, after.value]).toEqual(["station", "deadhead"])
    expect(station.value).not.toBe("0")
    // Nothing chosen: refused, nothing sent
    await select(turn, "none")
    await click(button("发送"))
    expect(form.querySelector("[role=alert]")!.textContent).toBe("什么处置都没选")
    expect(sendNow).not.toHaveBeenCalled()
    // A station to pass, a spare train taking over: sent as one operation
    const boxes = [...form.querySelectorAll("input[type=checkbox]")] as HTMLInputElement[]
    await click(boxes[1])
    await click(boxes[boxes.length - 1])
    await click(button("发送"))
    expect(sendNow).toHaveBeenCalledOnce()
    const [[operations]] = sendNow.mock.calls as unknown as [[any[]]]
    expect(operations).toEqual([{ type: "contingency", system: "a1", action: "adhoc", train: "t1", setting: {
      turn: "none", turnStation: station.value, afterTurn: "deadhead", skipStations: [mockData().contingencySystems[0].stations[1].id], takeover: true,
    } }])
    expect(document.querySelector("[data-adhoc=t1]")).toBeNull()
    expect(document.body.textContent).toContain("已发送")
  })
})
