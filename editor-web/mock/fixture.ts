/**
 * Example data for `npm run dev`, in the shape of api/data: two line groups sharing a station, a short-turn route, and
 * two depots, one of them still finding its paths. Not shipped.
 */
import { LINE2_X, trackPosition } from "./track.ts"

const MIN = 60000

type StopIn = [station: string, name: string, platform: string, id: string]

function route(id: string, name: string, line: string, color: number, lineColor: number, stops: StopIn[], every: number, extra: Record<string, unknown> = {}) {
  const services: number[] = []
  for (let t = 6 * 60 * MIN; t <= 23 * 60 * MIN; t += every * MIN) services.push(t)
  // Run times as the game's fastest train gives them: 3 minutes a stop, 30 s dwell; the old page needs a full table
  const fastest = stops.map((_, k) => [k * 3 * MIN, k * 3 * MIN + (k === 0 ? 0 : 30000)])
  // Each stop as the game sends its times: [arrival, departure, overtake, passes, tooFast], millis from the first stop
  const times = fastest.map(([arrival, departure]) => [arrival, departure, 0, 0, 0])
  const table = (name: string, weekdays: number, list: number[]) => ({
    name, weekdays, marginPercent: 0, services: list, plans: fastest.map(f => [...f]), times,
    stops: stops.map(() => ({ arrival: -1, departure: -1, overtake: false })), links: [], returns: [], exceptions: [],
  })
  return {
    id, name, line, lineKey: "TRAIN|" + line, serviceType: "普通", color, lineColor, circular: false, timetable: true,
    entryPlatform: "0", editorManaged: true, connectingLine: false, revision: 1, parts: {}, editors: "",
    stops: stops.map(([station, stationName, platform, platformId]) => ({
      station, name: stationName, platformId, platform, pass: false, boundary: false, dwellOverride: 0,
    })),
    hasTimes: true, fastest,
    tables: [table("平日", 31, services), table("周末", 96, services.filter((_, i) => i % 2 === 0))],
    ...extra,
  }
}

const L1_NAMES = [["s1", "港湾|Harbour"], ["s2", "码头街|Pier St"], ["s3", "中央广场|Central Sq"], ["s4", "市政厅|City Hall"], ["s5", "西门|West Gate"], ["s6", "北湖|North Lake"]]
const L2_NAMES = [["t1", "机场|Airport"], ["t2", "会展中心|Expo"], ["s3", "中央广场|Central Sq"], ["t4", "东站|East"]]
// Line 1 north on track 1 (platforms 1000…), south on track 2 (1100…); line 2 east on 1 (2000…), west on 2 (2100…), at 中央广场 3 and 4
const L1: StopIn[] = L1_NAMES.map(([s, n], k) => [s, n, "1", String(1000 + k)])
const L1_SOUTH: StopIn[] = L1_NAMES.map(([s, n], k) => [s, n, "2", String(1100 + k)] as StopIn).reverse()
const L2: StopIn[] = L2_NAMES.map(([s, n], k) => [s, n, s === "s3" ? "3" : "1", String(2000 + k)])
const L2_WEST: StopIn[] = L2_NAMES.map(([s, n], k) => [s, n, s === "s3" ? "4" : "2", String(2100 + k)] as StopIn).reverse()

/**
 * The platforms on the rails as api/data's topology gives them: each with its two ways along (a state is platform × 2 +
 * way), and from each way the states it reaches without turning back, with the length. Line 1 is double track, each
 * track one way, with a
 * crossover at each end and a passing track (1200, track 5) at 中央广场 both ways can use; line 2 is double track too,
 * on rails of its own, with its westbound rail from 中央广场 to 会展中心 missing (route 202's path failure).
 */
export function exampleTopology() {
  type Platform = { id: string; name: string; station: string; stationName: string; network: number; mode: string; line: number[]; ways: { usable: boolean; edges: number[] }[]; track: { direction: string; kind: string }; passingTrack?: boolean }
  const platforms: Platform[] = []
  const add = (id: string, name: string, [station, stationName]: string[], network: number, line: number[], direction: string, kind = "main") => {
    platforms.push({ id, name, station, stationName, network, mode: "TRAIN", line, ways: [{ usable: true, edges: [] }, { usable: true, edges: [] }], track: { direction, kind }, ...(kind === "passing" ? { passingTrack: true } : {}) })
    return platforms.length - 1
  }
  // Where the mock's network map (track.ts) has them: line 1 eastward along a curve, line 2 north from 中央广场
  const place = (d: number, offset: number, back = false) => { const a = trackPosition(d - 20, offset), b = trackPosition(d + 20, offset); return back ? [...b, ...a] : [...a, ...b] }
  const STOPS = [100, 600, 1100, 1600, 2100, 2600]
  const up = L1_NAMES.map((s, k) => add(String(1000 + k), "1", s, 0, place(STOPS[k], 0), "up"))
  const down = L1_NAMES.map((s, k) => add(String(1100 + k), "2", s, 0, place(STOPS[k], -4, true), "down"))
  const loop = add("1200", "5", L1_NAMES[2], 0, place(1100, 4), "both", "passing")
  const north = [-940, -440, 0, 2000]
  const east = L2_NAMES.map((s, k) => add(String(2000 + k), s[0] === "s3" ? "3" : "1", s, 1, [LINE2_X, north[k] + 20, LINE2_X, north[k] - 20], "up"))
  const west = L2_NAMES.map((s, k) => add(String(2100 + k), s[0] === "s3" ? "4" : "2", s, 1, [LINE2_X + 4, north[k] - 20, LINE2_X + 4, north[k] + 20], "down"))
  // From platform a leaving on way wa to platform b arriving on way wb; the main tracks one way, the crossovers both ways
  const link = (a: number, wa: number, b: number, wb: number, length = 200, back = false) => {
    platforms[a].ways[wa].edges.push(b * 2 + wb, length)
    if (back) platforms[b].ways[1 - wb].edges.push(a * 2 + 1 - wa, length)
  }
  const along = (track: number[], skip = -1) => track.slice(1).forEach((p, k) => { if (k !== skip) link(track[k], 0, p, 0) })
  along([...up.slice(0, 2)])
  along(up.slice(3))
  link(up[1], 0, up[2], 0)
  link(up[2], 0, up[3], 0)
  along([...down].reverse().slice(0, 3))
  along([...down].reverse().slice(3))
  link(down[3], 0, down[2], 0)
  // The passing track at 中央广场, from and to both main tracks
  link(up[1], 0, loop, 0, 210)
  link(loop, 0, up[3], 0, 210)
  link(down[3], 0, loop, 1, 210)
  link(loop, 1, down[1], 0, 210)
  // Crossovers at both ends, for turning back
  link(down[1], 0, up[0], 1, 220, true)
  link(up[4], 0, down[5], 1, 220, true)
  along(east)
  along([...west].reverse(), 1)
  return { networkCount: 2, platforms }
}

/**
 * Duties of the north depot by its diagram: six trains, each going north on route 101 and back south on 102 three times
 * in the morning, then home. The rest of the day's services have no train (the board's "没车" row).
 */
function exampleDuties() {
  const slot = (k: number) => [{
    days: 127, stay: k === 5, next: -1, duty: String(k + 1), returnSiding: "0", waits: [], outLead: -1, exceptions: k === 2 ? 1 : 0,
    segments: [0, 1, 2].flatMap(lap => [
      { route: "101", service: (6 * 60 + k * 6 + lap * 48) * MIN, from: 0, to: -1 },
      { route: "102", service: (6 * 60 + 24 + k * 6 + lap * 48) * MIN, from: 0, to: -1 },
    ]),
  }]
  return { mode: "diagram", sidings: { "8001": [slot(0), slot(1), slot(2)], "8002": [slot(3), slot(4)], "8003": [slot(5)] } }
}

export function mockData() {
  return {
    dayStartMillis: 4 * 60 * MIN, offsetMillis: 8 * 60 * MIN, canEdit: true, simulation: false, language: "zh_cn", regionCount: 3,
    networkSettings: { minReverseMillis: 2 * MIN, earlyThresholdMillis: 30000 },
    topology: exampleTopology(),
    routes: [
      route("101", "1号线 全程 北行|Line 1", "1号线|Line 1", 0xc8102e, 0xc8102e, L1, 6),
      route("102", "1号线 全程 南行|Line 1", "1号线|Line 1", 0xc8102e, 0xc8102e, L1_SOUTH, 6),
      route("103", "1号线 区间 北行|Line 1 short", "1号线|Line 1", 0xe8758a, 0xc8102e, L1.slice(0, 4), 12, { editors: "rail_fan" }),
      route("201", "2号线 东行|Line 2", "2号线|Line 2", 0x0072ce, 0x0072ce, L2, 10),
      route("202", "2号线 西行|Line 2", "2号线|Line 2", 0x0072ce, 0x0072ce, L2_WEST, 10, { pathFailure: "会展中心到中央广场之间没有连通的轨道" }),
    ],
    depots: [
      {
        id: "9001", name: "北湖车厂|North Lake Depot", routes: ["101", "102", "103"],
        sidings: [{ id: "8001", name: "1 道", maxTrains: 3, fleet: 3, routes: [], legs: { entrance: "s1", out: { "1000": 150000, "1105": 150000 }, back: { "1005": 200000, "1100": 200000 } } }, { id: "8002", name: "2 道", maxTrains: 3, fleet: 3, routes: [], legs: { entrance: "s1", out: { "1000": 150000, "1105": 150000 }, back: { "1005": 200000, "1100": 200000 } } }, { id: "8003", name: "3 道", maxTrains: 2, fleet: 2, routes: ["101", "102"], legs: { entrance: "s1", out: { "1000": 150000, "1105": 150000 }, back: { "1005": 200000, "1100": 200000 } } }],
        pools: [{ key: "a6", vehicle: "A 型 6 节", fleet: 9, sum: 8, sidings: ["8001", "8002", "8003"] }],
        path: { state: "found", text: "", failedDuties: 0 }, duties: exampleDuties(), shortfall: { uncovered: 4, lines: "1号线", needed: 10, available: 9, missing: 1 },
      },
      {
        id: "9002", name: "机场车厂|Airport Depot", routes: ["201", "202"],
        sidings: [{ id: "8101", name: "1 道", maxTrains: 4, fleet: -1, routes: [] }],
        pools: [{ key: "b4", vehicle: "B 型 4 节", fleet: null, sum: -1, sidings: ["8101"] }],
        path: { state: "generating", text: "", failedDuties: 0 }, duties: { mode: "auto", sidings: {} },
      },
    ],
  }
}
