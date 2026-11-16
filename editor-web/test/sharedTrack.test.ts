/**
 * src/core/sync.ts, the track run on by depots planned apart (2026-11-16): every area shared has to be one running
 * diagram, the player choosing which line joins which.
 */
import { describe, expect, it } from "vitest"
import { testData } from "./fixtures"
import { buildModel } from "../src/core/model"
import { sharedTrackProblems, syncProblems } from "../src/core/sync"
import { emptyDraft } from "../src/core/draft"

const LINE1 = "TRAIN|1号线|Line 1"
const LINE2 = "TRAIN|2号线|Line 2"

function withArea(joins: Record<string, string> = {}) {
  const data = testData()
  data.networkSettings.diagramJoins = joins
  data.sharedAreas = [{ key: "a", units: [{ depots: ["9001"], lines: [LINE1] }, { depots: ["9002"], lines: [LINE2] }], stations: ["s1"], length: 812, start: "1, 64, 2" }]
  return buildModel(data)
}

describe("shared track", () => {
  it("tells the area and offers each line into the other's diagram", () => {
    const problems = sharedTrackProblems(withArea())
    expect(problems).toHaveLength(1)
    expect(problems[0].level).toBe("warn")
    expect(problems[0].text).toContain("812")
    expect(problems[0].actions!.map(action => action.op)).toEqual([
      { type: "networkSettings", diagramJoins: { [LINE1]: LINE2 } },
      { type: "networkSettings", diagramJoins: { [LINE2]: LINE1 } },
    ])
  })

  it("offers no join the game refuses, and tells the joins made with a way to take them out", () => {
    // Line 2 is joined into line 3 already: it can't be joined again, nor line 1 into it
    const problems = sharedTrackProblems(withArea({ [LINE2]: "TRAIN|3号线" }))
    expect(problems[0].actions).toEqual([])
    expect(problems[1].level).toBe("hint")
    expect(problems[1].actions![0].op).toEqual({ type: "networkSettings", diagramJoins: { [LINE2]: null } })
  })

  it("tells a route's services on shared track at once with another line's (0.86.0)", () => {
    const data = testData()
    data.routes[0].sharedConflicts = [{ after: "北湖|North Lake", service: 0, otherRoute: "201", otherService: 0 }, { after: "港湾", service: 0, otherRoute: "202", otherService: 0 }]
    const problems = syncProblems("ok", buildModel(data), emptyDraft())
    const shared = problems.filter(problem => problem.text.includes("共用轨道上"))
    expect(shared).toHaveLength(1)
    expect(shared[0].route).toBe(String(data.routes[0].id))
    expect(shared[0].text).toContain("有 2 班")
  })

  it("is nothing with no shared track", () => {
    const data = testData()
    expect(sharedTrackProblems(buildModel(data))).toEqual([])
  })
})
