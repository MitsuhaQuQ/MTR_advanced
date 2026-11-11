/** Routes without stop times aren't drawn on the running diagram with made-up times: the diagram says which and why */
import { describe, expect, it } from "vitest"
import { untimedNotes } from "../src/core/model"

const route = (fields: any): any => ({ name: "S1 down快速", stops: [{ name: "犬宫" }, { name: "东北泽" }], timetable: true, hasTimes: false, ...fields })

describe("untimed routes", () => {
  it("names each route without stop times, with the server's reason when there is one", () => {
    const notes = untimedNotes([
      route({ id: "1", pathFailure: "从 田端 3 到 新郭德纲 3，不经过必经节点 -2200, 88, 2478 能走通，经过它就走不通" }),
      route({ id: "2", name: "S1 up" }),
      route({ id: "3", name: "S1 down", hasTimes: true }),
      route({ id: "4", name: "No timetable", timetable: false }),
    ])
    expect(notes).toHaveLength(2)
    expect(notes[0]).toContain("S1 down快速 → 东北泽")
    expect(notes[0]).toContain("-2200, 88, 2478")
    expect(notes[1]).toContain("S1 up")
    expect(notes[1]).toContain("正在向服务端取")
  })
})
