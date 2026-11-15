/**
 * Choosing many rails at once on the ownership layer (0.83.13): the way between two rails, along one to a turnout, a box,
 * and a stroke of the mouse.
 */
import { describe, expect, it } from "vitest"
import { railPath, railsAlong, railsInBox, railsNearSegment } from "../src/core/railPick"

// A line along z = 0 with a turnout at node 1 branching off to node 4, and a long loop from node 3 round to node 5:
//
//   0 ---r0--- 1 ---r1--- 2 ---r2--- 3 ----.
//               `--r3-- 4 ---r4--- 5 -r5--'
const nodes = [[0, 0], [10, 0], [20, 0], [30, 0], [20, 10], [30, 10]]
const rails = [[0, 1], [1, 2], [2, 3], [1, 4], [4, 5], [3, 5]]
const between: number[][] = [[], [], [], [15, 2], [], [40, 0, 40, 10]]
const points = rails.map(([a, b], i) => [...nodes[a], ...between[i], ...nodes[b]])

describe("the way between two rails", () => {
  it("goes through the turnout the way it came", () => {
    expect(railPath(rails, points, 0, 4)).toEqual([0, 3, 4])
    expect(railPath(rails, points, 4, 0)).toEqual([4, 3, 0])
  })
  it("doesn't turn back at a turnout while there is another way, however long", () => {
    // Back through the turnout at node 1 would be 2, 1, 3, 4
    expect(railPath(rails, points, 2, 4)).toEqual([2, 5, 4])
  })
  it("takes any way when only turning back gets there", () => {
    const short = rails.slice(0, 5), shortPoints = points.slice(0, 5)
    expect(railPath(short, shortPoints, 2, 4)).toEqual([2, 1, 3, 4])
  })
  it("is the rail alone from a rail to itself, and nothing without a way", () => {
    expect(railPath(rails, points, 1, 1)).toEqual([1])
    const apart = [[0, 1], [2, 3]]
    expect(railPath(apart, [[0, 0, 1, 0], [5, 0, 6, 0]], 0, 1)).toEqual([])
  })
})

describe("along a rail to a turnout", () => {
  it("stops at nodes with more than two rails and at the end of the track", () => {
    expect(railsAlong(rails, 0)).toEqual([0])
    expect(railsAlong(rails.slice(0, 5), 2)).toEqual([2, 1])
  })
  it("goes round a loop once", () => {
    expect([...railsAlong(rails, 1)].sort()).toEqual([1, 2, 3, 4, 5])
  })
})

describe("rails in a box", () => {
  it("takes the rails inside and those crossing an edge", () => {
    expect(railsInBox(points, 12, -1, 18, 1)).toEqual([1, 3])
    expect(railsInBox(points, 11, 1, -1, -1)).toEqual([0, 1, 3])
    expect(railsInBox(points, 100, 100, 110, 110)).toEqual([])
  })
})

describe("rails under a stroke of the mouse", () => {
  it("takes the rails the stroke comes within reach of", () => {
    expect(railsNearSegment(points, 5, -5, 5, 5, 0.5)).toEqual([0])
    expect(railsNearSegment(points, 25, -3, 25, -1, 0.5)).toEqual([])
    expect(railsNearSegment(points, 25, -3, 25, -1, 1.5)).toEqual([2])
    // Along the loop's far side
    expect(railsNearSegment(points, 41, 2, 41, 8, 1.5)).toEqual([5])
  })
})
