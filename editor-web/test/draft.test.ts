/**
 * src/core/draft.ts: the operations a draft sends, the draft left after applying, conflicts, dropping parts, the list of
 * changes. Until step 9 of retiring the old code each was compared with the old page's own functions; the snapshots were
 * taken when they still matched.
 */
import { describe, expect, it } from "vitest"
import { fullDraft, testModel } from "./fixtures"
import {
  changeSections, draftCount, draftOperations, drop, emptyDraft, normalizeDraft, resolveConflict, settleApply, tidy, type ChangeSection,
} from "../src/core/draft"

const plain = (value: unknown) => JSON.parse(JSON.stringify(value))

describe("draftOperations", () => {
  it("gives the operations the old page sent", () => {
    const model = testModel(), draft = fullDraft(model)
    expect(plain(draftOperations(model, draft))).toMatchSnapshot()
  })
  it("sends nothing for an empty draft", () => {
    expect(draftOperations(testModel(), emptyDraft())).toEqual({ operations: [], owners: [] })
  })
})

describe("applying", () => {
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
    it(`leaves the draft the old page left: ${name}`, () => {
      const model = testModel(), draft = fullDraft(model)
      const { owners } = draftOperations(model, draft)
      const result = (makeResult as (owners: any[]) => any)(owners)
      const settled = settleApply(draft, owners, result)
      expect(plain({ draft: settled.draft, boundaryErrors: settled.boundaryErrors, ok: settled.result.ok, general: settled.result.general, applied: settled.result.applied })).toMatchSnapshot()
      // The argument is left as it was, for undo
      expect(plain(draft)).toEqual(plain(fullDraft(testModel())))
    })
  }
})

describe("conflicts and dropping", () => {
  for (const keep of [true, false]) {
    it(`settles a conflict as the old page did (${keep ? "keep" : "drop"})`, () => {
      const model = testModel()
      expect(plain(resolveConflict(model, fullDraft(model), "201", keep))).toMatchSnapshot()
    })
  }
  it("tidies an entry with nothing left in it", () => {
    const draft = { ...emptyDraft(), routes: { a: { base: 1, force: true, error: "e" }, b: { base: 1, managed: true } } }
    expect(Object.keys(tidy(tidy(draft, "a"), "b").routes)).toEqual(["b"])
  })
  it("drops one part at a time and counts as the old page did", () => {
    const model = testModel()
    let draft = fullDraft(model)
    const count = draftCount(draft)
    expect(count).toMatchSnapshot()
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
    expect(draftCount(draft)).toBe(count - 1 - 1 - 1 - 3 - 1)
  })
  it("reads a saved draft as the old page did", () => {
    expect(plain(normalizeDraft({ routes: { a: { conflict: true } }, duties: 3 }))).toMatchSnapshot()
    expect(plain(normalizeDraft(null))).toEqual(plain({ ...emptyDraft() }))
  })
})

describe("the list of changes", () => {
  const squash = (text: string) => text.replace(/\s+/g, " ").trim()
  const newSection = (section: ChangeSection) => squash([section.title, section.error || "", ...section.items.flatMap(item => (typeof item === "string" ? [item] : [item.title, ...item.lines]))].join(" "))

  it("says what the old page's dialog said, section by section", () => {
    const model = testModel(), draft = fullDraft(model)
    expect(changeSections(model, draft, { "1,2": "边界失败" }).map(newSection)).toMatchSnapshot()
  })
})
