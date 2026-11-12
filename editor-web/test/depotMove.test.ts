/**
 * Moving work between depots (0.69.2): duty groups put on another vehicle, a line's duties taken out of one depot and put
 * into another, and the depots' routes and sidings changed alike (Q world, 2026-11-12: each depot runs its own vehicle).
 */
import { describe, expect, it } from "vitest"
import { applyDutyGroup, dutyGroupsOf, entryWith, entryWithout, hasDutiesOf, putTaken, takeRoutes, vehiclesOf } from "../src/core/depotMove"

const seg = (route: string, service: number) => ({ route, service, from: 0, to: -1 })
const day = (days: number, ...segments: any[]) => ({ days, stay: false, next: -1, duty: "", start: "prefer", nextDuty: "0", returnSiding: "0", fromOvernight: false, segments })

const tabata = { id: "T", sidings: [{ id: "t1", vehicle: "br_423" }, { id: "t2", vehicle: "br_423" }] }
const inumiya = { id: "I", sidings: [{ id: "i1", vehicle: "class_377" }] }

describe("duty groups on another vehicle", () => {
  it("lists a depot's groups by vehicle and days, and puts one on another vehicle", () => {
    const source = { mode: "diagram", sidings: { t1: [[day(0b11111, seg("L", 1), seg("L", 2))], [day(0b11111, seg("L", 3))]], t2: [[day(0b1100000, seg("L", 4))]] } }
    expect([...dutyGroupsOf(tabata, source).entries()].sort()).toEqual([["br_423|31", 2], ["br_423|96", 1]])
    expect(dutyGroupsOf(tabata, { mode: "online", sidings: source.sidings }).size).toBe(0)

    const target: any = { mode: "online", sidings: { i1: [[day(0b11111, seg("X", 9))], [day(0b1100000, seg("X", 8))]] } }
    expect(applyDutyGroup(target, inumiya, tabata, source, "br_423", 0b11111, "class_377")).toBe(2)
    expect(target.mode).toBe("diagram")
    // Its own weekday duty gives way, the weekend one stays; the two put each get a slot free on their days
    const days = target.sidings.i1.flatMap((slot: any[]) => slot.map(each => [each.days, each.segments.map((s: any) => s.service)]))
    expect(days).toEqual([[0b11111, [1, 2]], [0b1100000, [8]], [0b11111, [3]]])
    expect(target.sidings.i1.flatMap((slot: any[]) => slot).filter((each: any) => each.days === 0b11111).every((each: any) => each.duty)).toBe(true)
    expect(applyDutyGroup(target, inumiya, tabata, source, "br_423", 0b11111, "nothing")).toBe(-1)
  })
})

describe("a line moved to another depot", () => {
  it("takes the line's services out, keeping the rest of each duty and the next-slot links", () => {
    const duties: any = {
      mode: "diagram", sidings: {
        t1: [[day(0b1111111, seg("S1", 1), seg("S1", 2))], [Object.assign(day(0b1111111, seg("S1", 3), seg("S2", 4)), { stay: true })], [Object.assign(day(0b1111111, seg("S2", 5)), { next: 2 })]],
        t2: [],
      },
    }
    expect(hasDutiesOf(duties, ["S1"])).toBe(true)
    const taken = takeRoutes(duties, tabata, ["S1"])
    expect(hasDutiesOf(duties, ["S1"])).toBe(false)
    // The first slot had only S1 and goes; the second keeps S2; the third's link to itself follows it to index 1
    expect(duties.sidings.t1.map((slot: any[]) => slot.map(each => each.segments.map((s: any) => s.service)))).toEqual([[[4]], [[5]]])
    expect(duties.sidings.t1[0][0].stay).toBe(true)
    expect(duties.sidings.t1[1][0].next).toBe(1)
    const perSiding = taken.get("br_423")!
    expect(perSiding[0].map(each => each.segments.map((s: any) => s.service))).toEqual([[1, 2], [3]])
    // A day split in two doesn't keep the train overnight in the moved part
    expect(perSiding[0][1].stay).toBe(false)

    const target: any = { mode: "online", sidings: {} }
    expect(putTaken(target, inumiya, taken, "class_377")).toBe(2)
    expect(target.mode).toBe("diagram")
    expect(target.sidings.i1.length).toBe(2)
    expect(putTaken({ mode: "online", sidings: {} }, inumiya, taken, "br_423")).toBe(-1)
  })

  it("changes the depots' routes and sidings", () => {
    const from = { routes: ["S1a", "S1b", "S2"], sidings: { t1: { fleet: -1, routes: ["S1a", "S1b"] }, t2: { fleet: 3, routes: [] } } }
    entryWithout(from, ["S1a", "S1b"])
    expect(from.routes).toEqual(["S2"])
    expect(from.sidings.t1.routes).toEqual([])
    const depot = { sidings: [{ id: "i1", vehicle: "class_377" }, { id: "i2", vehicle: "class_377" }, { id: "i3", vehicle: "other" }] }
    const to = { routes: ["X"], sidings: { i1: { fleet: -1, routes: ["X"] }, i2: { fleet: -1, routes: [] }, i3: { fleet: -1, routes: ["X"] } } }
    entryWith(to, depot, ["S1a", "S1a"], "class_377")
    expect(to.routes).toEqual(["X", "S1a"])
    // A siding running only some routes gets the new ones; one running all runs them anyway; another vehicle's doesn't
    expect(to.sidings.i1.routes).toEqual(["X", "S1a"])
    expect(to.sidings.i2.routes).toEqual([])
    expect(to.sidings.i3.routes).toEqual(["X"])
    expect(vehiclesOf(depot)).toEqual(["class_377", "other"])
  })
})
