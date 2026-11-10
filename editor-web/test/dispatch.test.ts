/**
 * Parity of src/core/dispatch.ts with the old page's simulated day, dispatcher's console and loops (retiring the old code,
 * step 7): the same answers from the game to the old functions cut from editor.js and to the new module, the state and
 * what is shown compared after each.
 */
import { describe, expect, it } from "vitest"
import { loadOld } from "./old"
import * as P from "../src/core/dispatch"
import * as S from "../src/core/services"
import * as T from "../src/core/timetable"
import { mockData } from "../mock/fixture"

const OLD = ["DAY", "sim", "SIM_LATE_MILLIS", "SIM_KINDS", "FIRST_DAY_WEEKDAY", "simFromNow", "nowDay", "simulate", "pollSimulation", "setSimResult", "simLate", "formatDuration",
  "simTooltip", "dutyCheckText", "simPanel", "consoleState", "consoleTrains", "consoleTrainName", "consoleTrainState", "consoleCommand", "systemByAnchor", "systemOfRoute",
  "MAX_LAYOVER_MILLIS", "isLoop", "loopEntryStop", "loopRuns", "timetableOf", "draftOf", "gameTimetable", "fromGame", "normalize", "timesOf",
  "buildServices", "timesSource", "gameSource", "plansFor", "toPlans", "findService", "mod", "clock", "routeTitle", "shortName", "escapeHtml"]

const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value, (_k, v) => (v instanceof Map ? [...v] : v instanceof Set ? [...v] : v)))
const shown = (sim: any) => { const { timer, ...rest } = sim; return copy(rest) }

/** The answers the game gives, one after the other, to both sides */
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
  const theirs = make(), mine = make()
  return { theirs, mine }
}

function oldSide(side: any, answers: { post: any[]; get: any[] }, extra: Record<string, unknown> = {}) {
  const io = game(answers)
  const old = loadOld<any>(OLD, {
    data: side.data, draft: side.draft, state: side.state, ...io, setTimeout: (f: () => void) => { f(); return 0 }, clearTimeout() {},
    currentLine: () => side.data.lines?.find((l: any) => l.key === side.state.line) || { routes: side.data.routes.filter((r: any) => r.id.startsWith("10")) },
    depotById: (id: string) => side.data.depots.find((d: any) => d.id === id), draftOperations: () => ({ operations: [{ type: "x" }] }),
    renderSimStatus() {}, draw() {}, renderInspector() {}, renderBanner() {}, loadConsole() {},
    draftTimes: new Map(), requestedTimes: new Map(), timesTimer: 0, sendTimes() {}, ...extra,
  })
  return old
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
  it("asks, waits and takes the answer as the old page does, through each way it can go", async () => {
    const runs: { post: any[]; get: any[]; checkDepot?: string; fromNow?: boolean }[] = [
      { post: [{ request: 4 }], get: [{ request: 3, state: "done" }, { request: 4, state: "running" }, { request: 4, state: "done", result: RESULT, notSimulated: 2, ignored: 1, missingPaths: 3, scopeDepots: 1, allDepots: 2 }] },
      { post: [{ request: 4 }], get: [{ request: 5, state: "done" }] },
      { post: [{ error: "busy" }], get: [] },
      { post: [new Error("closed")], get: [] },
      { post: [{ request: 7 }], get: [{ request: 7, state: "running" }, new Error("closed")] },
      { post: [{ request: 8 }], get: [{ request: 8, state: "busy", notSimulated: 0, ignored: 0, scopeDepots: 3, allDepots: 9 }], checkDepot: "9001", fromNow: true },
      { post: [{ request: 9 }], get: [{ request: 9, state: "done", result: RESULT, notSimulated: 0, ignored: 0 }], checkDepot: "9002" },
    ]
    for (const run of runs) {
      const { theirs, mine } = sides()
      const old = oldSide(theirs, run)
      const io = game(run)
      const sent: any[] = []
      Object.assign(old.sim, { on: true, checkDepot: run.checkDepot || null, fromNow: Boolean(run.fromNow) })
      const sim = Object.assign(P.newSim(), { on: true, checkDepot: run.checkDepot || null, fromNow: Boolean(run.fromNow) })
      const oldSent: any[] = []
      const send = old.sandbox.sendJson
      old.sandbox.sendJson = (url: string, method: string, body: any) => { oldSent.push(body); return send(url, method, body) }
      await old.simulate()
      await new Promise(resolve => setTimeout(resolve, 0))
      const line = { routes: mine.data.routes.filter((r: any) => r.id.startsWith("10")) }
      const checkDepot = run.checkDepot ? mine.data.depots.find((d: any) => d.id === run.checkDepot) : null
      const myIo: P.SimIO = { send: body => { sent.push(body); return io.sendJson("api/simulate", "POST", body) }, fetch: io.fetchJson, wait: async () => {} }
      await P.startSimulation(sim, myIo, { line: mine.state.line, weekday: mine.state.weekday, fromNow: P.simFromNow(sim, mine.data, mine.state.weekday), routes: P.simRoutes(line, checkDepot), operations: [{ type: "x" }] })
      await P.pollSimulation(sim, myIo)
      sim.madeAt = old.sim.madeAt
      expect(copy(sent)).toEqual(copy(oldSent))
      expect(shown(sim)).toEqual(shown(old.sim))
      expect(P.simStatusText(sim)).toBeTypeOf("string")
      for (const depot of mine.data.depots) expect(P.dutyCheckText(sim, depot)).toBe(old.dutyCheckText(theirs.data.depots.find((d: any) => d.id === depot.id)))
    }
  })
  it("tells services, depots and problems from a result as the old page does", () => {
    for (const checkDepot of [null, "9001", "9002"]) {
      const { theirs, mine } = sides()
      const old = oldSide(theirs, { post: [], get: [] })
      old.buildServices()
      mine.data.routes.forEach((r: any) => { r.services = S.routeServices(r, T.timetableOf(mine.draft, r), S.gameSource(r), 2, mine.data.dayStartMillis) })
      const sim = Object.assign(P.newSim(), { on: true, checkDepot, notSimulated: 1, ignored: 2, missingPaths: 1, scopeDepots: 1, allDepots: 3 })
      Object.assign(old.sim, { on: true, checkDepot, notSimulated: 1, ignored: 2, missingPaths: 1, scopeDepots: 1, allDepots: 3 })
      old.setSimResult(RESULT)
      P.setSimResult(sim, RESULT, old.sim.madeAt)
      expect(shown(sim)).toEqual(shown(old.sim))
      for (const r of mine.data.routes) r.services.slice(0, 30).forEach((s: any, k: number) => {
        const theirsService = theirs.data.routeById.get(r.id).services[k]
        expect(`<div class="hint">${P.simServiceText(sim, s)}</div>`).toBe(old.simTooltip(theirsService))
      })
      for (const depot of mine.data.depots) expect(P.dutyCheckText(sim, depot)).toBe(old.dutyCheckText(theirs.data.depots.find((d: any) => d.id === depot.id)))
      // The problems listed and the notes as the old panel has them
      const line = { routes: mine.data.routes.filter((r: any) => r.id.startsWith("10")) }
      const html = old.simPanel({ routes: theirs.data.routes.filter((r: any) => r.id.startsWith("10")) })
      const { problems, notes } = P.simProblems(sim, mine.data, line, checkDepot ? mine.data.depots.find((d: any) => d.id === checkDepot) : null)
      expect(copy(problems)).toEqual(copy(old.sim.listed))
      notes.forEach(note => expect(html).toContain(note))
      expect(html.match(/<p class="hint">/g)!.length).toBe(notes.length + 1)
    }
    for (const stop of [[0, -1, -1, 5, 5, 0], [0, 100, 200, 50, 220, 0], [0, 100, 300, 120, 200, 0], [0, -1, 400, -1, 100, 1]]) expect(P.simLate(stop)).toBe(oldSide(sides().theirs, { post: [], get: [] }).simLate(stop))
  })
})

describe("dispatcher's console", () => {
  it("lists, names and tells trains and sends commands as the old page does", async () => {
    const { theirs, mine } = sides()
    const trains = [
      { id: "a", route: "101", slot: 0, sidingName: "1 道|Track 1", late: 400, waiting: 90, dispatch: 1 | 8, skipping: true },
      { id: "b", route: "201", slot: -1, sidingName: "", late: 30, waiting: 0, dispatch: 2 | 4 | 16, turn: 2, takeover: 2, outOfService: true },
      { id: "c", route: "102", slot: 4, sidingName: "2 道", late: 0, dispatch: 0, noDuty: true },
    ]
    const sent: any[] = []
    const old = oldSide(theirs, { post: [], get: [] }, { sendJson: async (_u: string, _m: string, body: any) => { sent.push(copy(body)) }, setTimeout: () => 0 })
    for (const system of ["", "a1", "none"]) {
      old.consoleState.data = { available: true, trains }
      old.consoleState.system = system
      const state: P.ConsoleState = { open: true, data: { available: true, trains }, timer: null, system, selected: "" }
      expect(P.consoleTrains(mine.data, state)).toEqual(old.consoleTrains())
    }
    trains.forEach(train => {
      expect(P.consoleTrainName(train)).toBe(old.consoleTrainName(train))
      expect(P.consoleTrainState(train)).toBe(old.consoleTrainState(train))
    })
    for (const operation of [{ train: "a", command: "hold" }, { train: "b", command: "extend", seconds: 60 }, { train: "c", command: "turn", station: "s3" }]) {
      await old.consoleCommand(null, operation)
      expect(P.dispatchOperation(operation)).toEqual(sent[sent.length - 1])
    }
  })
})

describe("loops", () => {
  it("goes round as the old page does, with and without a depot keeping trains on the line, and with an entry stop", () => {
    const loop = (data: any) => {
      const r = data.routes[2]
      r.stops[r.stops.length - 1] = { ...r.stops[0] }
      r.tables[0].links = [{ service: r.tables[0].services[5], route: "101", toService: 0, fromStop: -1, toStop: 0 }]
      r.tables[0].returns = [r.tables[0].services[9]]
    }
    for (const [cycle, entry, passed] of [[false, null, false], [true, "1001", false], [false, "1002", false], [false, "1002", true]] as [boolean, string | null, boolean][]) {
      const { theirs, mine } = sides(data => { loop(data); data.depots[0].cycleRoutes = cycle })
      const old = oldSide(theirs, { post: [], get: [] })
      old.buildServices()
      mine.data.routes.forEach((r: any) => { r.services = S.routeServices(r, T.timetableOf(mine.draft, r), S.gameSource(r), 2, mine.data.dayStartMillis) })
      if (entry) {
        // A stop passed can't be where trains join
        const pass = (tt: any) => tt.pass.map((p: boolean, i: number) => (passed && i === 2 ? true : p))
        T.draftEntry(mine.draft, mine.data.routes[2]).timetable = { ...T.gameTimetable(mine.data.routes[2]), entryPlatform: entry, pass: pass(T.gameTimetable(mine.data.routes[2])) }
        theirs.draft.routes["103"] = { timetable: { ...old.gameTimetable(theirs.data.routes[2]), entryPlatform: entry, pass: pass(old.gameTimetable(theirs.data.routes[2])) } }
      }
      for (let k = 0; k < mine.data.routes.length; k++) {
        const a = theirs.data.routes[k], b = mine.data.routes[k]
        expect(P.isLoop(b)).toBe(old.isLoop(a))
        expect(P.loopEntryStop(mine.draft, b)).toBe(old.loopEntryStop(a))
        const ids = (runs: any) => ({ next: [...runs.next].map(([id, s]: any) => [id, s.id]), trains: runs.trains.map((laps: any[]) => laps.map(s => s.id)), maxWait: runs.maxWait })
        expect(ids(P.loopRuns(mine.data, b))).toEqual(ids(old.loopRuns(a)))
      }
    }
  })
})
