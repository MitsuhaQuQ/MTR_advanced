/**
 * Parity of src/core/draft.ts with editor.js: the same drafts through the old functions (cut out of editor.js) and the
 * new ones must give the same operations, the same draft after applying, and the same list of changes.
 */
import { describe, expect, it } from "vitest"
import { loadOld } from "./old"
import { fullDraft, testModel } from "./fixtures"
import {
  changeSections, draftCount, draftOperations, drop, emptyDraft, normalizeDraft, resolveConflict, settleApply, tidy, type ChangeSection,
} from "../src/core/draft"

const plain = (value: unknown) => JSON.parse(JSON.stringify(value))

describe("draftOperations", () => {
  it("gives the operations the old page sends", () => {
    const model = testModel(), draft = fullDraft(model)
    const old = loadOld<{ draftOperations: () => any }>(["draftOperations"], { draft: fullDraft(testModel()), data: testModel() })
    expect(plain(draftOperations(model, draft))).toEqual(plain(old.draftOperations()))
  })
  it("sends nothing for an empty draft", () => {
    expect(draftOperations(testModel(), emptyDraft())).toEqual({ operations: [], owners: [] })
  })
})

describe("applying", () => {
  /** The old applyDraft with the game answering `result`; the draft and what it shows afterwards. */
  async function oldApply(result: any) {
    const old = loadOld<{ applyDraft: () => Promise<void> }>(["applyDraft", "draftOperations", "draftCount"], {
      draft: fullDraft(testModel()), data: testModel(), applying: false, boundaryErrors: {}, pendingOpen: null, applyResult: null, revision: 1,
      sendJson: async () => result, renderDraftState() {}, saveDraft() {}, poll() {}, renderAll() {},
    })
    await old.applyDraft()
    return old.sandbox
  }

  const errorsOf = (owners: any[]) => {
    // One error for each kind of owner, and a general one
    const at = (match: (o: any) => boolean, message: string) => ({ index: owners.findIndex(match), message })
    return [
      at(o => o === "101", "CONFLICT"),
      at(o => o === "102", "站台被占"),
      at(o => o && o.depotOf, "车厂不对"),
      at(o => o && o.dutiesOf, "运用不对"),
      at(o => o && o.boundaryOf === "5,6", "边界不对"),
      at(o => o && o.platformStationOf === "1001", "车站不对"),
      at(o => o && o.key === "c2", "CONFLICT"),
      at(o => o && o.platformSpeedOf, "限速不对"),
      { index: -1, message: "整体" },
    ]
  }

  for (const [name, makeResult] of [
    ["everything taken", () => ({ ok: true, errors: [], created: [{ id: "7" }] })],
    ["some refused", (owners: any[]) => ({ ok: false, errors: errorsOf(owners), created: [] })],
  ] as const) {
    it(`leaves the same draft as the old page: ${name}`, async () => {
      const model = testModel(), draft = fullDraft(model)
      const { owners } = draftOperations(model, draft)
      const result = (makeResult as (owners: any[]) => any)(owners)
      const old = await oldApply(result)
      const settled = settleApply(draft, owners, result)
      expect(plain(settled.draft)).toEqual(plain(old.draft))
      expect(settled.boundaryErrors).toEqual(plain(old.boundaryErrors))
      expect(settled.result.ok).toBe(old.applyResult.ok)
      expect(settled.result.general).toEqual(plain(old.applyResult.general))
      expect(settled.result.applied).toBe(old.applyResult.applied)
      // The argument is left as it was, for undo
      expect(plain(draft)).toEqual(plain(fullDraft(testModel())))
    })
  }
})

describe("conflicts and dropping", () => {
  for (const keep of [true, false]) {
    it(`settles a conflict as the old page (${keep ? "keep" : "drop"})`, () => {
      const model = testModel()
      const old = loadOld<{ resolveConflict: (id: string, keep: boolean) => void }>(["resolveConflict"], { draft: fullDraft(testModel()), data: testModel(), saveDraft() {}, renderAll() {} })
      old.resolveConflict("201", keep)
      expect(plain(resolveConflict(model, fullDraft(model), "201", keep))).toEqual(plain(old.sandbox.draft))
    })
  }
  it("tidies an entry with nothing left in it", () => {
    const draft = { ...emptyDraft(), routes: { a: { base: 1, force: true, error: "e" }, b: { base: 1, managed: true } } }
    expect(Object.keys(tidy(tidy(draft, "a"), "b").routes)).toEqual(["b"])
  })
  it("drops one part at a time and counts as the old page", () => {
    const model = testModel()
    let draft = fullDraft(model)
    const old = loadOld<{ draftCount: () => number }>(["draftCount"], { draft: fullDraft(testModel()) })
    expect(draftCount(draft)).toBe(old.draftCount())
    draft = drop(draft, { kind: "created", index: 0 })
    draft = drop(draft, { kind: "networkSettings" })
    draft = drop(draft, { kind: "duties", key: "9001" })
    draft = drop(draft, { kind: "platformStops" })
    draft = drop(draft, { kind: "route", id: "101" })
    expect(draft.created.map(made => made.key)).toEqual(["c2"])
    expect(draft.networkSettings).toBeNull()
    expect(draft.duties).toEqual({})
    expect(draft.platformStops).toEqual({})
    expect(Object.keys(draft.routes)).not.toContain("101")
    expect(draftCount(draft)).toBe(old.draftCount() - 1 - 1 - 1 - 3 - 1)
  })
  it("reads a saved draft as the old page does", () => {
    const old = loadOld<{ loadDraft: () => Promise<void> }>(["loadDraft"], { fetchJson: async () => ({ routes: { a: { conflict: true } }, duties: 3 }), draft: null, draftLoaded: false })
    return old.loadDraft().then(() => {
      expect(plain(normalizeDraft({ routes: { a: { conflict: true } }, duties: 3 }))).toEqual(plain(old.sandbox.draft))
      expect(plain(normalizeDraft(null))).toEqual(plain({ ...emptyDraft() }))
    })
  })
})

describe("the list of changes", () => {
  const decode = (text: string) => text.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&")
  const squash = (text: string) => text.replace(/\s+/g, " ").trim()
  /** Each old section as its text, without its buttons. */
  function oldSections(html: string) {
    return html.split('<section class="draft-change">').slice(1).map(part => squash(decode(part.split("</section>")[0]
      .replace(/<div class="draft-actions">[\s\S]*?<\/div>/g, " ").replace(/<[^>]+>/g, " "))))
  }
  const newSection = (section: ChangeSection) => squash([section.title, section.error || "", ...section.items.flatMap(item => (typeof item === "string" ? [item] : [item.title, ...item.lines]))].join(" "))

  it("says the same as the old page's dialog, section by section", () => {
    const model = testModel(), draft = fullDraft(model)
    const data = testModel()
    const topology = data.topology
    let html = ""
    const old = loadOld<{ draftChangesDialog: () => void }>([
      "draftChangesDialog", "routeChanges", "draftCount", "gameTimetable", "fromGame", "normalize", "stopName", "tableName", "daysText",
      "someServices", "setTimeText", "serviceClock", "clock", "mod", "DAY", "escapeHtml", "shortName", "orderDepotName", "depotById", "routeTitle",
      "plansByAnchor", "systemTitle", "poolFleetText", "platformText", "platformById", "stationNameOf", "savedSettings", "draftSettings",
      "logicalLinesShown", "ownerText", "stopPositionText", "WEEKDAY_SHORT",
    ], {
      draft: fullDraft(testModel()), data, boundaryErrors: { "1,2": "边界失败" },
      topo: { platforms: topology.platforms, index: new Map(topology.platforms.map((p: any, i: number) => [p.id, i])), stations: topology.stations },
      openDialog: (_title: string, body: string) => { html = body },
    })
    old.draftChangesDialog()
    const expected = oldSections(html)
    const actual = changeSections(model, draft, { "1,2": "边界失败" }).map(newSection)
    expect(actual).toEqual(expected)
  })
})
