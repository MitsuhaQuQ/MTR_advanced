/**
 * Example track for the mock's api/route-layout and api/network: line 1 as double track along its six stations, with a
 * crossover at the second, a passing loop and the junction to line 2 at the third, a turn back track beyond the last.
 * Distances along route 101's path; offsets to its left positive, as RouteLayouts sends them.
 */
type Rail = [a: number, b: number, d1: number, o1: number, d2: number, o2: number]

const STATIONS = [100, 600, 1100, 1600, 2100, 2600]

/** Where a point along route 101's path lies in the world: x east along it, a gentle curve, offsets to the left north */
export const trackPosition = (d: number, offset: number): [number, number] => [d, Math.round((260 * Math.sin(d / 800) - offset) * 10) / 10]
/** Line 2's single track north from the junction, with a passing loop at 会展中心 and two tracks at 机场 */
export const LINE2_X = 1300
const NAMES = ["港湾|Harbour", "码头街|Pier St", "中央广场|Central Sq", "市政厅|City Hall", "西门|West Gate", "北湖|North Lake"]

export function exampleTrack() {
  let next = 100
  const node = () => next++
  const rails: Rail[] = []
  const nodeAt = new Map<string, number>()
  /** A track along one offset through the given distances, a node at each */
  const along = (offset: number, ds: number[]) => {
    const nodes = ds.map(d => { const key = d + "," + offset; if (!nodeAt.has(key)) nodeAt.set(key, node()); return nodeAt.get(key)! })
    for (let i = 1; i < ds.length; i++) rails.push([nodes[i - 1], nodes[i], ds[i - 1], offset, ds[i], offset])
    return nodes
  }
  const link = (d1: number, o1: number, d2: number, o2: number) => {
    const a = nodeAt.get(d1 + "," + o1) ?? (nodeAt.set(d1 + "," + o1, node()), nodeAt.get(d1 + "," + o1)!)
    const b = nodeAt.get(d2 + "," + o2) ?? (nodeAt.set(d2 + "," + o2, node()), nodeAt.get(d2 + "," + o2)!)
    rails.push([a, b, d1, o1, d2, o2])
    return [a, b]
  }
  // Up track (route 101's path) and down track, split where something joins them
  const up = [0, 80, 120, 580, 620, 640, 1040, 1080, 1120, 1160, 1180, 1580, 1620, 2080, 2120, 2560, 2580, 2620, 2700]
  const down = [0, 80, 120, 580, 620, 650, 1080, 1120, 1580, 1620, 2080, 2120, 2570, 2580, 2620, 2700]
  along(0, up)
  along(-4, down)
  link(640, 0, 650, -4) // crossover at 码头街
  along(4, [1060, 1080, 1120, 1140]) // passing loop at 中央广场
  link(1040, 0, 1060, 4)
  link(1140, 4, 1160, 0)
  const branch = link(1180, 0, 1230, 22) // towards line 2
  link(2560, 0, 2570, -4) // crossover before 北湖, for trains turning back
  const turnouts = [[640, 0], [650, -4], [1040, 0], [1160, 0], [1180, 0], [2560, 0], [2570, -4]]
  const platforms = [
    ...STATIONS.map((d, i) => ({ id: String(1000 + i), name: "1", station: "s" + (i + 1), stationName: NAMES[i], line: [d - 20, 0, d + 20, 0] })),
    ...STATIONS.map((d, i) => ({ id: String(3000 + i), name: "2", station: "s" + (i + 1), stationName: NAMES[i], line: [d - 20, -4, d + 20, -4] })),
    { id: "3099", name: "3", station: "s3", stationName: NAMES[2], line: [1080, 4, 1120, 4], passingTrack: true },
  ]
  const layout = {
    ready: true, found: true, length: 2700, halfWidth: 24, truncated: false,
    stops: STATIONS.map(d => d + 20),
    rails: rails.map(([, , d1, o1, d2, o2]) => [d1, o1, (d1 + d2) / 2, (o1 + o2) / 2, d2, o2]),
    railKeys: rails.map(([a, b], i) => [String(a), String(b), i === 3 ? 3 : 0]),
    turnouts, platforms, boundaryNodes: [],
  }

  // The network: every node with its key and place; rails with their kind (1 platform, 2 siding); each route's path
  const nodes: (number | string)[][] = []
  const index = new Map<number, number>()
  const placeOf = new Map<number, [number, number]>()
  nodeAt.forEach((n, key) => { const [d, o] = key.split(",").map(Number); placeOf.set(n, trackPosition(d, o)) })
  const indexOf = (n: number) => { if (!index.has(n)) { index.set(n, nodes.length); const [x, z] = placeOf.get(n) || [0, 0]; nodes.push([x, z, String(n)]) } return index.get(n)! }
  const path = (offset: number, ds: number[]) => ds.map(d => indexOf(nodeAt.get(d + "," + offset)!))
  const upPath = path(0, up)
  const kindOf = ([, , d1, o1, d2, o2]: Rail) => d1 >= 2620 ? 2
    : platforms.some(p => p.line[1] === o1 && o1 === o2 && d1 >= p.line[0] - 0.1 && d2 <= p.line[2] + 0.1) ? 1 : 0
  const networkRails: (number | string)[][] = rails.map(rail => [indexOf(rail[0]), indexOf(rail[1]), kindOf(rail), 0])
  // Line 2 on its own coordinates: north from the junction, a loop at 会展中心, two tracks at 机场
  const extra = (x: number, z: number) => { const n = node(); placeOf.set(n, [x, z]); return indexOf(n) }
  const join = (a: number, b: number, kind = 0) => networkRails.push([a, b, kind, 0])
  const X = LINE2_X, fork = indexOf(branch[1])
  const p1 = extra(X, -150), loopIn = extra(X, -400), e1 = extra(X, -420), e2 = extra(X, -460), w1 = extra(X + 4, -420), w2 = extra(X + 4, -460), loopOut = extra(X, -480)
  const p2 = extra(X, -880), apIn = extra(X, -900), a1 = extra(X, -920), a2 = extra(X, -960), b1 = extra(X + 4, -920), b2 = extra(X + 4, -960)
  join(fork, p1); join(p1, loopIn); join(loopIn, e1); join(e1, e2, 1); join(e2, loopOut); join(loopIn, w1); join(w1, w2, 1); join(w2, loopOut)
  join(loopOut, p2); join(p2, apIn); join(apIn, a1); join(a1, a2, 1); join(apIn, b1); join(b1, b2, 1)
  const stationAt = (id: string, name: string, d: number) => { const [x, z] = trackPosition(d, -2); return { id, name, x, z } }
  const network = {
    version: 1, mode: "TRAIN", modes: ["TRAIN"], rails: networkRails, boundaryNodes: [],
    stations: [...STATIONS.map((d, i) => stationAt("s" + (i + 1), NAMES[i], d)), { id: "t2", name: "会展中心|Expo", x: X + 2, z: -440 }, { id: "t1", name: "机场|Airport", x: X + 2, z: -940 }],
    // Each rail its own section, owned by the line running on it
    owners: { ready: true, names: ["TRAIN|1号线|Line 1", "TRAIN|2号线|Line 2"], rails: networkRails.map((_, i) => [i, 0, "r" + i, "", i < rails.length ? 0 : 1]) },
    nodes,
    routes: [
      { id: "101", name: "1号线 全程 北行", color: 0xc8102e, lineKey: "TRAIN|1号线|Line 1", nodes: upPath, stopNodes: [] },
      { id: "102", name: "1号线 全程 南行", color: 0xc8102e, lineKey: "TRAIN|1号线|Line 1", nodes: path(-4, down).reverse(), stopNodes: [] },
      { id: "103", name: "1号线 区间 北行", color: 0xe8758a, lineKey: "TRAIN|1号线|Line 1", nodes: path(0, up.filter(d => d <= 1620)), stopNodes: [] },
      { id: "201", name: "2号线 东行", color: 0x0072ce, lineKey: "TRAIN|2号线|Line 2", nodes: [...path(0, [1120, 1160, 1180]), fork, p1, loopIn, e1, e2, loopOut, p2, apIn, a1, a2], stopNodes: [] },
    ],
  }
  // Two trains for the dispatcher's console (api/console): one between 港湾 and 码头街, one 6 minutes late at 中央广场
  const key = (offset: number, d: number) => String(nodeAt.get(d + "," + offset))
  const consoleData = {
    available: true, blocks: [],
    trains: [
      { id: "t1", route: "101", slot: 0, sidingName: "1 道", late: 30, waiting: 0, dispatch: 0, head: [key(0, 120), key(0, 580)], next: "码头街|Pier St", block: "" },
      { id: "t2", route: "101", slot: 1, sidingName: "1 道", late: 360, waiting: 0, dispatch: 1, head: [key(0, 1080), key(0, 1120)], next: "市政厅|City Hall", block: "" },
    ],
  }
  return { layout, network, console: consoleData }
}
