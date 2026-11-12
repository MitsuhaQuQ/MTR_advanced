import { describe, expect, it } from "vitest"
import { sameLine } from "../src/core/depots"

// A depot rule's lines as the server matches them (DepotRules.sameLine), for the rules panel
describe("sameLine", () => {
  it("takes a line by any one of its names", () => {
    expect(sameLine("TRAIN|s1", "TRAIN|s1|s1")).toBe(true)
    expect(sameLine("TRAIN|一号线|line 1", "TRAIN|1号线|line 1")).toBe(true)
  })
  it("not another line or mode", () => {
    expect(sameLine("TRAIN|s1", "TRAIN|s2|s2")).toBe(false)
    expect(sameLine("TRAIN|s1", "BOAT|s1")).toBe(false)
    expect(sameLine("TRAIN||x", "TRAIN||y")).toBe(false)
  })
})
