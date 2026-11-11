/** A new table takes the route's stops (0.65.27): the stops every service passes by its exception become the route's own */
import { describe, expect, it } from "vitest"
import { liftCommonPasses } from "../src/core/timetable"

const passAt = (service: number, stops: number[], extra: any[] = []) => ({ service, stops: [...stops.map(stop => ({ stop, run: -1, dwell: -1, pass: 1, overtake: -1 })), ...extra].sort((a, b) => a.stop - b.stop) })
const stopsOf = (count: number) => Array.from({ length: count }, () => ({ arrival: 10, departure: 20 }))

describe("liftCommonPasses", () => {
  it("makes the stops every service passes the route's passes and drops those exceptions", () => {
    const tt: any = {
      pass: [false, false, false, false, false, false],
      tables: [{ services: [0, 300000], stops: stopsOf(6), exceptions: [passAt(0, [1, 3]), passAt(300000, [1, 3, 4], [{ stop: 2, run: -1, dwell: 30, pass: -1, overtake: -1 }])] }],
    }
    expect(liftCommonPasses(tt)).toEqual([1, 3])
    expect(tt.pass).toEqual([false, true, false, true, false, false])
    // What else the exceptions said stays: stop 4 passed by one service only, stop 2's own stop time
    expect(tt.tables[0].exceptions).toEqual([{ service: 300000, stops: [{ stop: 2, run: -1, dwell: 30, pass: -1, overtake: -1 }, { stop: 4, run: -1, dwell: -1, pass: 1, overtake: -1 }] }])
    expect(tt.tables[0].stops[1]).toEqual({ arrival: -1, departure: -1 })
    expect(tt.tables[0].stops[2]).toEqual({ arrival: 10, departure: 20 })
  })

  it("leaves a stop some service of any table stops at, the ends, and tables with no services", () => {
    const tt: any = {
      pass: [false, false, false, false],
      tables: [
        { services: [0], stops: stopsOf(4), exceptions: [passAt(0, [0, 1, 2, 3])] },
        { services: [60000], stops: stopsOf(4), exceptions: [passAt(60000, [0, 2, 3])] },
        { services: [], stops: stopsOf(4), exceptions: [] },
      ],
    }
    expect(liftCommonPasses(tt)).toEqual([2])
    expect(tt.pass).toEqual([false, false, true, false])
    expect(liftCommonPasses({ pass: [false, false, false], tables: [{ services: [], stops: stopsOf(3), exceptions: [] }] })).toEqual([])
  })
})
