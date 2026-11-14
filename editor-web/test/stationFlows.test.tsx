// @vitest-environment happy-dom
/**
 * A station's track use (0.80.0): line 11 of the test lines in small. Station S has an east platform (nodes 1-2, x 400-460,
 * z 0) and a west one (3-4, z 8); a dead-end turn-back track T (6-7) east of it joined from both main tracks at x 490
 * (nodes 2-5 east, 4-8 west, 5-6 and 8-6 the links). Route A runs east into S; its duties turn through T out to the west
 * platform. Route B runs west into S, and its duties turn on the west platform, through T and out to the east track.
 */
import { afterEach, beforeAll, describe, expect, it } from "vitest"
import { act, createElement } from "react"
import { createRoot, type Root } from "react-dom/client"
import { stationFlows, type FlowNet } from "../src/core/stationFlows"

const net: FlowNet = {
  nodes: [[200, 0, "n0"], [400, 0, "n1"], [460, 0, "n2"], [460, 8, "n3"], [400, 8, "n4"], [490, 0, "n5"], [530, 4, "n6"], [580, 4, "n7"], [490, 8, "n8"], [700, 8, "n9"]],
  rails: [
    [0, 1, 0, 0], // 0: west of the east platform
    [1, 2, 1, 0], // 1: east platform
    [3, 4, 1, 0], // 2: west platform, its lower node at the east end
    [2, 5, 0, 0], // 3: east track on
    [5, 6, 0, 0], // 4: link from the east track
    [6, 7, 0, 0], // 5: T
    [3, 8, 0, 0], // 6: west track east of the platform
    [6, 8, 0, 0], // 7: link to the west track
    [8, 9, 0, 0], // 8: west track further east
  ],
  stations: [{ id: "S", name: "三站|Station 3", x: 430, z: 4 }],
  routes: [
    { id: "1", name: "A上行|A Up", color: 0xff0000, lineKey: "TRAIN|A", nodes: [0, 1, 2] },
    { id: "2", name: "B下行|B Down", color: 0x0000ff, lineKey: "TRAIN|B", nodes: [9, 8, 3, 4] },
  ],
  dutyUses: [
    // A: the service, then into T and out to the west platform
    [0, 1, "1", "d"], [1, 2, "1", "d"], [2, 5, "1", "d"], [5, 6, "1", "d"], [6, 7, "1", "d"], [7, 6, "1", "d"], [6, 8, "1", "d"], [8, 3, "1", "d"], [3, 4, "1", "d"],
    // B: the service, turning on the west platform, east into T, out to the east track
    [9, 8, "2", "d"], [8, 3, "2", "d"], [3, 4, "2", "d"], [4, 3, "2", "d"], [3, 8, "2", "d"], [8, 6, "2", "d"], [6, 7, "2", "d"], [7, 6, "2", "d"], [6, 5, "2", "d"], [5, 2, "2", "d"],
  ],
}

describe("a station's track use", () => {
  it("tells services from the duties' moves, each way, and finds the tracks run both ways", () => {
    const view = stationFlows(net, "S")!
    const ways = (rail: number) => view.flows.filter(f => f.rail === rail).map(f => `${f.lineKey.slice(6)}${f.forward ? ">" : "<"}${f.move ? "m" : "s"}`).sort()
    // The east platform: A's service only (B comes out of T only as far as its east end)
    expect(ways(1)).toEqual(["A>s"])
    // The west platform: B's service, A coming out of T onto it the same way, and B turning back on it
    expect(ways(2)).toEqual(["A>m", "B<m", "B>s"].sort())
    // The east track on: A's way into T, B's way out of it, against each other
    expect(ways(3)).toEqual(["A>m", "B<m"].sort())
    // Everything between the platforms and T runs both ways: the conflicting flows
    const both = view.rails.filter(r => r.both).map(r => r.rail).sort((a, b) => a - b)
    expect(both).toEqual([2, 3, 4, 5, 6, 7])
    expect(view.lines.map(l => l.name)).toEqual(["A上行", "B下行"])
  })

  it("takes only the tracks near the station", () => {
    const view = stationFlows(net, "S", 50)!
    expect(view.rails.map(r => r.rail)).not.toContain(8)
    expect(view.rails.map(r => r.rail)).not.toContain(5)
    expect(stationFlows(net, "nowhere")).toBeNull()
  })
})

let root: Root | undefined
let StationFlows: typeof import("../src/views/StationFlows").StationFlows
beforeAll(async () => {
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  StationFlows = (await import("../src/views/StationFlows")).StationFlows
})
afterEach(async () => { if (root) await act(async () => root?.unmount()); root = undefined; document.body.innerHTML = "" })

describe("the popup", () => {
  it("draws a flowing line for each use and shows one line alone from the legend", async () => {
    const div = document.createElement("div")
    document.body.appendChild(div)
    root = createRoot(div)
    let closed = false
    await act(async () => root!.render(createElement(StationFlows, { net, stationId: "S", name: "三站", onClose: () => { closed = true }, onShowReal: () => {} })))
    const flows = () => div.querySelectorAll("polyline[data-flow]")
    const all = flows().length
    expect(all).toBe(stationFlows(net, "S")!.flows.length)
    expect(div.querySelectorAll("polyline[data-flow] animate").length).toBe(all)
    const legend = [...div.querySelectorAll("button[aria-pressed]")] as HTMLButtonElement[]
    await act(async () => legend[0].click())
    expect(flows().length).toBeLessThan(all)
    expect([...flows()].every(f => f.getAttribute("stroke") === "#ff0000")).toBe(true)
    await act(async () => { window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })) })
    expect(closed).toBe(true)
  })
})
