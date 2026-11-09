/** Data and drafts for the parity tests: the dev fixture, with the parts of api/data the tests reach filled in. */
import { mockData } from "../mock/fixture"
import { buildModel, type Model } from "../src/core/model"
import { emptyDraft, type Draft } from "../src/core/draft"

const MIN = 60000

export function testData(): any {
  const data: any = mockData()
  data.routes.forEach((r: any, k: number) => {
    r.parts = { timetable_services: 3 + k, platform_ids: 1, is_light_rail_route: 0 }
    r.terminalPlatforms = []
    r.startPlatforms = []
    r.speedLimit = k === 1 ? 80 : 0
    r.stops.forEach((stop: any, i: number) => {
      stop.candidates = [{ id: stop.platformId, name: stop.platform }, { id: String(5000 + i), name: "3" }]
    })
    r.tables[0].links = [{ service: r.tables[0].services[2], route: "102", toService: r.tables[0].services[2] + 20 * MIN, fromStop: r.stops.length - 1, toStop: 0 }]
    r.tables[0].returns = [r.tables[0].services[r.tables[0].services.length - 1]]
    r.tables[0].orders = [{ service: r.tables[0].services[1], kind: "deploy", stop: 1, depot: "9001" }]
    r.tables[0].exceptions = [{ service: r.tables[0].services[3], stops: [{ stop: 1, run: -1, dwell: 60, pass: -1, overtake: false }] }]
    r.tables[0].stops = r.stops.map(() => ({ arrival: -1, departure: -1, overtake: false }))
  })
  data.topology = {
    networkCount: 1,
    platforms: data.routes.flatMap((r: any) => r.stops.map((stop: any) => ({ id: stop.platformId, name: stop.platform, station: stop.station, stationName: stop.name }))),
    stations: [{ id: "s1", name: "港湾|Harbour" }, { id: "s3", name: "中央广场|Central Sq" }],
  }
  data.networkSettings.logicalLines = [{ id: "ll1", name: "一号线正线", color: 0xc8102e, connecting: false }]
  data.contingencySystems = [{ anchor: "a1", routes: ["101", "102", "103"], plans: [], adhoc: [], others: [{ anchor: "a9", plans: [] }] }]
  return data
}

export const testModel = (): Model => buildModel(testData())

/** A draft touching every part, as the old page would have saved it. */
export function fullDraft(model: Model): Draft {
  const r = model.routeById.get("101")!
  const t = JSON.parse(JSON.stringify(r.tables))
  const timetable = {
    enabled: true, entryPlatform: "0", pass: r.stops.map((_, i) => i === 2), allowLonger: r.stops.map(() => false),
    tables: [
      {
        ...t[0], name: "平日改", weekdays: 15, marginPercent: 5,
        services: [...t[0].services.slice(1), 23 * 60 * MIN + 30 * MIN],
        returns: [t[0].services[5]], suspended: [t[0].services[4]],
        orders: [{ service: t[0].services[6], kind: "recall", stop: 2, depot: "0" }],
        links: [{ service: t[0].services[7], route: "201", toService: t[0].services[7] + 30 * MIN, fromStop: 3, toStop: 2 }],
        stops: t[0].stops.map((stop: any, i: number) => (i === 1 ? { arrival: 150, departure: 200, overtake: true } : stop)),
        exceptions: [{ service: t[0].services[8], stops: [{ stop: 2, run: 200, dwell: -1, pass: 1, overtake: true }] }],
      },
    ],
  }
  return {
    ...emptyDraft(),
    routes: {
      "101": { base: r.revision, parts: { ...r.parts }, timetable, platforms: r.stops.map((s, i) => (i === 1 ? "5001" : s.platformId)), managed: false, speedLimit: 90, terminalPlatforms: ["1003"] },
      "102": { base: 1, parts: {}, connectingLine: true, connectingReleased: ["1,2", "3,4"], startPlatforms: ["1005"] },
      "103": { base: 1, deleted: true, force: true },
      "201": { base: 1, conflict: true, error: "x", speedLimit: 0 },
      "999": { base: 1, managed: true },
    },
    created: [
      { key: "c1", source: "101", name: "1号线 快车|Rapid", mode: "TRAIN", lineName: "1号线", serviceType: "快速", color: 0xff0000, stops: [{}, {}, {}], services: [1, 2], table: { name: "平日" }, depot: "9001" },
      { key: "c2", name: "3号线|Line 3", mode: "TRAIN", lineName: "3号线", serviceType: "普通", color: 0x00ff00, stops: [{}, {}] },
    ],
    duties: { "9001": { duties: { mode: "diagram", sidings: { "8001": [[], []], "8002": [[]] } } } },
    boundaries: { "1,2": true, "5,6": false, "7,8": true },
    depots: { "9001": { base: 1, routes: ["101", "102", "404"], cycleRoutes: true, sidings: { "8001": { fleet: 2, routes: ["101"] }, "8002": { fleet: -1, routes: [] } }, poolFleets: { a6: 7 }, serviceTypes: { "A 型 6 节": "普通,快速" } } },
    depotRules: { "9002": { rules: [{ line: "TRAIN|2号线|Line 2" }] } },
    platformStations: { "1000": "s3", "1001": "0" },
    platformSpeeds: { "1002": 120 },
    platformStops: { "1003": 0, "1004": -1, "1005": 4 },
    networkSettings: {
      minReverseMillis: 90000, waitThresholdMillis: 300000, stationWaitThresholds: { s1: 60000, s3: null },
      logicalLines: { ll1: { name: "一号线", color: 1, connecting: true }, ll2: { name: "支线", color: 2, connecting: false }, ll3: null },
      railLines: { "1,2": "ll1", "3,4": null },
      lineTracks: { "TRAIN|1号线|Line 1": ["ll1", "ll2"], "TRAIN|2号线|Line 2": null },
      ownerOverrides: { "9,10": ["TRAIN|1号线|Line 1", "connecting:102"], "11,12": [], "13,14": null },
    },
    contingencies: { a1: [{ id: 1, name: "中断", manual: true }, { id: 2, name: "站台", manual: false }], a9: [] },
  }
}
