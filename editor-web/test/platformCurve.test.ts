/**
 * src/core/sync.ts, platforms on curves too tight for the cars stopping there (0.88.0): the check list tells the gap and
 * the smallest radius.
 */
import { describe, expect, it } from "vitest"
import { testData } from "./fixtures"
import { buildModel } from "../src/core/model"
import { syncProblems } from "../src/core/sync"
import { emptyDraft } from "../src/core/draft"

describe("platforms on curves", () => {
  it("tells a platform whose gap grows by more than half a block, not one whose gap stays small", () => {
    const data = testData()
    const platforms = data.topology.platforms
    platforms[0].curve = { gap: 1.31, car: 25, smallestRadius: 156, wide: true }
    platforms[1].curve = { gap: 0.62, car: 25, smallestRadius: 156, wide: false }
    const texts = syncProblems("ok", buildModel(data), emptyDraft()).map(problem => problem.text).filter(text => text.includes("156"))
    expect(texts).toHaveLength(1)
    expect(texts[0]).toContain("1.31")
    expect(texts[0]).toContain("25")
  })
})
