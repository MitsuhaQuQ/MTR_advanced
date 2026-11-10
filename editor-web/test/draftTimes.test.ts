/** The draft's times asked of the game (retiring the old code, step 4): asked once per change, kept until the draft changes */
import { describe, expect, it, vi } from "vitest"
import { DraftTimes } from "../src/core/draftTimes"

describe("draft times", () => {
  it("asks the game once a moment after a change, shows the game's own times meanwhile, and the answer after", async () => {
    vi.useFakeTimers()
    const route: any = { id: "101", tables: [{ times: [[0, 0]], exceptions: [] }], hasTimes: false }
    let timetable: any = null
    const send = vi.fn(async (routes: any[]) => ({ routes: routes.map(r => ({ id: r.id, tables: [{ times: [[0, 30000]], exceptions: [{ service: 5, times: [[0, 1]] }] }] })) }))
    const answered = vi.fn()
    const times = new DraftTimes(send, () => timetable, () => route, answered)
    expect(times.source(route)[0].times).toEqual([[0, 0]])
    timetable = { tables: [] }
    expect(times.source(route)[0].times).toEqual([[0, 0]])
    times.source(route)
    await vi.advanceTimersByTimeAsync(200)
    expect(send).toHaveBeenCalledTimes(1)
    expect(answered).toHaveBeenCalledTimes(1)
    expect(times.source(route)[0].times).toEqual([[0, 30000]])
    expect(times.source(route)[0].exceptions.get(5)).toEqual([[0, 1]])
    await vi.advanceTimersByTimeAsync(200)
    expect(send).toHaveBeenCalledTimes(1)
    // A failed answer is asked again with the next change
    send.mockRejectedValueOnce(new Error("offline"))
    timetable = { tables: [1] }
    times.source(route)
    await vi.advanceTimersByTimeAsync(200)
    expect(times.requested.has("101")).toBe(false)
    timetable = { tables: [2] }
    times.source(route)
    await vi.advanceTimersByTimeAsync(200)
    expect(send).toHaveBeenCalledTimes(3)
    vi.useRealTimers()
  })
})
