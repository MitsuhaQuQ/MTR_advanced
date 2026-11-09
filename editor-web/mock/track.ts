/**
 * Example track for the mock's api/route-layout and api/network: line 1 as double track along its six stations, with a
 * crossover at the second, a passing loop and the junction to line 2 at the third, a turn back track beyond the last.
 * Distances along route 101's path; offsets to its left positive, as RouteLayouts sends them.
 */
type Rail = [a: number, b: number, d1: number, o1: number, d2: number, o2: number]

const STATIONS = [100, 600, 1100, 1600, 2100, 2600]
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

  // The network: every node with its key; each route's path as node indices
  const nodes: (number | string)[][] = []
  const index = new Map<number, number>()
  const indexOf = (n: number) => { if (!index.has(n)) { index.set(n, nodes.length); nodes.push([0, 0, String(n)]) } return index.get(n)! }
  const path = (offset: number, ds: number[]) => ds.map(d => indexOf(nodeAt.get(d + "," + offset)!))
  const upPath = path(0, up)
  const network = {
    version: 1, mode: "TRAIN", modes: ["TRAIN"], rails: [], stations: [], boundaryNodes: [], owners: { ready: false, names: [], rails: [] },
    nodes,
    routes: [
      { id: "101", name: "1号线 全程 北行", color: 0xc8102e, lineKey: "TRAIN|1号线|Line 1", nodes: upPath, stopNodes: [] },
      { id: "102", name: "1号线 全程 南行", color: 0xc8102e, lineKey: "TRAIN|1号线|Line 1", nodes: path(-4, down).reverse(), stopNodes: [] },
      { id: "103", name: "1号线 区间 北行", color: 0xe8758a, lineKey: "TRAIN|1号线|Line 1", nodes: path(0, up.filter(d => d <= 1620)), stopNodes: [] },
      { id: "201", name: "2号线 东行", color: 0x0072ce, lineKey: "TRAIN|2号线|Line 2", nodes: [...path(0, [1120, 1160, 1180]), indexOf(branch[1])], stopNodes: [] },
    ],
  }
  return { layout, network }
}
