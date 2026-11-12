/**
 * src/core/dispatch.ts, the simulated day, the dispatcher's console and loops: answers from the game, the state and what
 * is shown after each. Until step 9 of retiring the old code the same answers went to the old page's functions too and
 * the two were compared; the snapshots were taken when they still matched.
 */
import { describe, expect, it, vi } from "vitest"
import { createRequire } from "node:module"
import * as P from "../src/core/dispatch"
import * as S from "../src/core/services"
import * as T from "../src/core/timetable"
import * as D from "../src/core/duties"
import { mockData } from "../mock/fixture"
import { fingerprint } from "./fingerprint"

const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value, (_k, v) => (v instanceof Map ? [...v] : v instanceof Set ? [...v] : v)))
const shown = (sim: any) => { const { timer, ...rest } = sim; return copy(rest) }

/** The answers the game gives, one after the other */
function game(answers: { post: any[]; get: any[] }) {
  let post = 0, get = 0
  return {
    sendJson: async (url: string, _method: string, body: any) => {
      if (url === "api/apply") return { body }
      const answer = answers.post[post++]
      if (answer instanceof Error) throw answer
      return answer
    },
    fetchJson: async () => {
      const answer = answers.get[get++]
      if (answer instanceof Error) throw answer
      return answer
    },
  }
}

function sides(change: (data: any) => void = () => {}) {
  const make = () => {
    const data: any = mockData()
    change(data)
    data.routeById = new Map(data.routes.map((r: any) => [r.id, r]))
    return { data, draft: { routes: {}, duties: {} } as any, state: { weekday: 2, line: "TRAIN|1号线", selected: null } }
  }
  return { mine: make() }
}

const RESULT = {
  from: 0,
  trips: [{ route: "101", service: 21600000, stops: [[0, 21600000, 21630000, 21600000, 21600000, 0], [1, 21700000, 21760000, 21690000, 21720000, 0]] }, { route: "102", service: 23040000, stops: [[0, 23040000, 23040000, 23040000, 23040000, 0]] }],
  problems: [
    { kind: "NOT_RUN", route: "101", service: 22000000, stop: -1, time: 22000000, duration: 0 },
    { kind: "LATE", route: "101", service: 21600000, stop: 1, time: 21760000, duration: 40000 },
    { kind: "LATE", route: "201", service: 30000000, stop: 2, time: 30000000, duration: 90000 },
    { kind: "DEADLOCK", route: "", service: 0, stop: -1, time: 31000000, duration: 0, detail: "a/b" },
    { kind: "WAITING_FOR_PLATFORM", route: "103", service: 25000000, stop: 0, time: 25000000, duration: 120000 },
  ],
}

describe("simulated day", () => {
  it("asks, waits and takes the answer as the old page did, through each way it can go", async () => {
    const runs: { post: any[]; get: any[]; checkDepot?: string; fromNow?: boolean }[] = [
      { post: [{ request: 4 }], get: [{ request: 3, state: "done" }, { request: 4, state: "running" }, { request: 4, state: "done", result: RESULT, notSimulated: 2, ignored: 1, missingPaths: 3, scopeDepots: 1, allDepots: 2 }] },
      { post: [{ request: 4 }], get: [{ request: 5, state: "done" }] },
      { post: [{ error: "busy" }], get: [] },
      { post: [new Error("closed")], get: [] },
      { post: [{ request: 7 }], get: [{ request: 7, state: "running" }, new Error("closed")] },
      { post: [{ request: 8 }], get: [{ request: 8, state: "busy", notSimulated: 0, ignored: 0, scopeDepots: 3, allDepots: 9 }], checkDepot: "9001", fromNow: true },
      { post: [{ request: 9 }], get: [{ request: 9, state: "done", result: RESULT, notSimulated: 0, ignored: 0 }], checkDepot: "9002" },
    ]
    // A fixed time now (a Wednesday, the mock's weekday 2), so "from now" is taken the same way every day the tests run
    const now = vi.spyOn(Date, "now").mockReturnValue(Date.UTC(2026, 10, 11, 4))
    for (const run of runs) {
      const { mine } = sides()
      const io = game(run)
      const sent: any[] = []
      const sim = Object.assign(P.newSim(), { on: true, checkDepot: run.checkDepot || null, fromNow: Boolean(run.fromNow) })
      const line = { routes: mine.data.routes.filter((r: any) => r.id.startsWith("10")) }
      const checkDepot = run.checkDepot ? mine.data.depots.find((d: any) => d.id === run.checkDepot) : null
      const myIo: P.SimIO = { send: body => { sent.push(body); return io.sendJson("api/simulate", "POST", body) }, fetch: io.fetchJson, wait: async () => {} }
      await P.startSimulation(sim, myIo, { line: mine.state.line, weekday: mine.state.weekday, fromNow: P.simFromNow(sim, mine.data, mine.state.weekday), routes: P.simRoutes(line, checkDepot), operations: [{ type: "x" }] })
      await P.pollSimulation(sim, myIo)
      // When it was made: the time now
      if (sim.madeAt) sim.madeAt = 1
      expect(P.simStatusText(sim)).toBeTypeOf("string")
      expect({ sent: copy(sent), sim: fingerprint(shown(sim)), checks: mine.data.depots.map((depot: any) => P.dutyCheckText(sim, depot)) }).toMatchSnapshot()
    }
    now.mockRestore()
  })
  it("tells services, depots and problems from a result as the old page did", () => {
    for (const checkDepot of [null, "9001", "9002"]) {
      const { mine } = sides()
      mine.data.routes.forEach((r: any) => { r.services = S.routeServices(r, T.timetableOf(mine.draft, r), S.gameSource(r), 2, mine.data.dayStartMillis) })
      const sim = Object.assign(P.newSim(), { on: true, checkDepot, notSimulated: 1, ignored: 2, missingPaths: 1, scopeDepots: 1, allDepots: 3 })
      P.setSimResult(sim, RESULT, 1)
      // The problems listed and the notes the old panel had
      const line = { routes: mine.data.routes.filter((r: any) => r.id.startsWith("10")) }
      const { problems, notes } = P.simProblems(sim, mine.data, line, checkDepot ? mine.data.depots.find((d: any) => d.id === checkDepot) : null)
      expect(fingerprint({
        sim: shown(sim),
        services: mine.data.routes.map((r: any) => r.services.slice(0, 30).map((s: any) => P.simServiceText(sim, s))),
        checks: mine.data.depots.map((depot: any) => P.dutyCheckText(sim, depot)),
        problems: copy(problems), notes,
      })).toMatchSnapshot()
    }
    expect([[0, -1, -1, 5, 5, 0], [0, 100, 200, 50, 220, 0], [0, 100, 300, 120, 200, 0], [0, -1, 400, -1, 100, 1]].map(stop => P.simLate(stop))).toMatchSnapshot()
  })
})

describe("dispatcher's console", () => {
  it("lists, names and tells trains and sends commands as the old page did", () => {
    const { mine } = sides()
    const trains = [
      { id: "a", route: "101", slot: 0, sidingName: "1 道|Track 1", late: 400, waiting: 90, dispatch: 1 | 8, skipping: true },
      { id: "b", route: "201", slot: -1, sidingName: "", late: 30, waiting: 0, dispatch: 2 | 4 | 16, turn: 2, takeover: 2, outOfService: true },
      { id: "c", route: "102", slot: 4, sidingName: "2 道", late: 0, dispatch: 0, noDuty: true },
    ]
    for (const system of ["", "a1", "none"]) {
      const state: P.ConsoleState = { open: true, data: { available: true, trains }, timer: null, system, selected: "" }
      expect(copy(P.consoleTrains(mine.data, state))).toMatchSnapshot()
    }
    expect(trains.map(train => [P.consoleTrainName(train), P.consoleTrainState(train)])).toMatchSnapshot()
    expect([{ train: "a", command: "hold" }, { train: "b", command: "extend", seconds: 60 }, { train: "c", command: "turn", station: "s3" }].map(operation => P.dispatchOperation(operation))).toMatchSnapshot()
  })
})

describe("loops", () => {
  it("goes round as the old page did, with and without a depot keeping trains on the line, and with an entry stop", () => {
    const loop = (data: any) => {
      const r = data.routes[2]
      r.stops[r.stops.length - 1] = { ...r.stops[0] }
      r.tables[0].links = [{ service: r.tables[0].services[5], route: "101", toService: 0, fromStop: -1, toStop: 0 }]
      r.tables[0].returns = [r.tables[0].services[9]]
    }
    for (const [cycle, entry, passed] of [[false, null, false], [true, "1001", false], [false, "1002", false], [false, "1002", true]] as [boolean, string | null, boolean][]) {
      const { mine } = sides(data => { loop(data); data.depots[0].cycleRoutes = cycle })
      const terminalPairs = createRequire(import.meta.url)("../../common/src/main/resources/assets/mtr/editor/terminal.js")
      ;(globalThis as any).TerminalPairs = terminalPairs
      const pairs = () => D.terminalPairs(mine.data, mine.draft, 2)
      mine.data.routes.forEach((r: any) => { r.services = S.routeServices(r, T.timetableOf(mine.draft, r), S.gameSource(r), 2, mine.data.dayStartMillis) })
      if (entry) {
        // A stop passed can't be where trains join
        const pass = (tt: any) => tt.pass.map((p: boolean, i: number) => (passed && i === 2 ? true : p))
        T.draftEntry(mine.draft, mine.data.routes[2]).timetable = { ...T.gameTimetable(mine.data.routes[2]), entryPlatform: entry, pass: pass(T.gameTimetable(mine.data.routes[2])) }
      }
      const ids = (runs: any) => ({ next: [...runs.next].map(([id, s]: any) => [id, s.id]), trains: runs.trains.map((laps: any[]) => laps.map(s => s.id)), maxWait: runs.maxWait })
      expect(mine.data.routes.map((b: any) => [P.isLoop(b), P.loopEntryStop(mine.draft, b), fingerprint(ids(P.loopRuns(mine.data, b, pairs().next)))])).toMatchSnapshot()
    }
  })
})
