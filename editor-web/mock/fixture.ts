/**
 * Example data for `npm run dev`, in the shape of api/data: two line groups sharing a station, a short-turn route, and
 * two depots, one of them still finding its paths. Not shipped.
 */
const MIN = 60000

type StopIn = [station: string, name: string, platform: string]

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
    stops: stops.map(([station, stationName, platform], i) => ({
      station, name: stationName, platformId: String(1000 + i), platform, pass: false, boundary: false, dwellOverride: 0,
    })),
    hasTimes: true, fastest,
    tables: [table("平日", 31, services), table("周末", 96, services.filter((_, i) => i % 2 === 0))],
    ...extra,
  }
}

const L1: StopIn[] = [["s1", "港湾|Harbour", "1"], ["s2", "码头街|Pier St", "1"], ["s3", "中央广场|Central Sq", "1"], ["s4", "市政厅|City Hall", "1"], ["s5", "西门|West Gate", "1"], ["s6", "北湖|North Lake", "1"]]
const L2: StopIn[] = [["t1", "机场|Airport", "1"], ["t2", "会展中心|Expo", "1"], ["s3", "中央广场|Central Sq", "3"], ["t4", "东站|East", "1"]]

export function mockData() {
  return {
    dayStartMillis: 4 * 60 * MIN, offsetMillis: 8 * 60 * MIN, canEdit: true, simulation: false, language: "zh_cn", regionCount: 3,
    networkSettings: { minReverseMillis: 2 * MIN, earlyThresholdMillis: 30000 },
    topology: { networkCount: 1, platforms: [] },
    routes: [
      route("101", "1号线 全程 北行|Line 1", "1号线|Line 1", 0xc8102e, 0xc8102e, L1, 6),
      route("102", "1号线 全程 南行|Line 1", "1号线|Line 1", 0xc8102e, 0xc8102e, [...L1].reverse(), 6),
      route("103", "1号线 区间 北行|Line 1 short", "1号线|Line 1", 0xe8758a, 0xc8102e, L1.slice(0, 4), 12, { editors: "rail_fan" }),
      route("201", "2号线 东行|Line 2", "2号线|Line 2", 0x0072ce, 0x0072ce, L2, 10),
      route("202", "2号线 西行|Line 2", "2号线|Line 2", 0x0072ce, 0x0072ce, [...L2].reverse(), 10, { pathFailure: "会展中心到中央广场之间没有连通的轨道" }),
    ],
    depots: [
      {
        id: "9001", name: "北湖车厂|North Lake Depot", routes: ["101", "102", "103"],
        sidings: [{ id: "8001", name: "1 道", maxTrains: 3, fleet: 3, routes: [] }, { id: "8002", name: "2 道", maxTrains: 3, fleet: 3, routes: [] }, { id: "8003", name: "3 道", maxTrains: 2, fleet: 2, routes: ["101", "102"] }],
        pools: [{ key: "a6", vehicle: "A 型 6 节", fleet: 9, sum: 8, sidings: ["8001", "8002", "8003"] }],
        path: { state: "found", text: "", failedDuties: 0 }, duties: { mode: "auto", sidings: {} }, shortfall: { uncovered: 4, lines: "1号线", needed: 10, available: 9, missing: 1 },
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
