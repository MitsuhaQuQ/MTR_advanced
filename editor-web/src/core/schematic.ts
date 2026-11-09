/**
 * A route's track diagram as a schematic: the rails along its path (api/route-layout, straightened by the server's
 * RouteLayouts into distance along the path × offset to its side) laid on parallel lanes, with the turnouts, the
 * platforms and the stations, and which routes run on each rail.
 *
 * - Lanes go by order, not by distance: the path is lane 0; the other tracks beside it, wherever they are, take the next
 *   lane out on their side (4 or 5 blocks apart, the same lane). A rail that keeps its offset is a level piece on its lane;
 *   one whose offset changes (a turnout's lead, a crossover, a track leaving the line) is a diagonal between two lanes.
 * - Along the line, the places that matter (turnouts, platform ends, stops, where a track changes lane) keep their
 *   spacing, and the long runs between them are shortened, so stations and junctions get the room.
 *
 * Pure: the page draws the result (views/TrackDiagram.tsx); test/schematic.test.ts checks it.
 */

export type RouteLayout = {
  ready?: boolean
  found: boolean
  length: number
  halfWidth?: number
  truncated?: boolean
  /** Where each stop of the route is along the path, -1 if not on it */
  stops: number[]
  /** Each piece of rail as [distance, offset, distance, offset, …] */
  rails: number[][]
  /** Each piece's rail: [node, node, isolation] with isolation 0, 1 connecting line, 2 released, 3 boundary */
  railKeys: [string, string, number][]
  turnouts: number[][]
  platforms: { id: string; name: string; station: string; stationName: string; passingTrack?: boolean; line: number[] }[]
  boundaryNodes?: number[][]
}

/** Which routes run on a rail, by its key (railKey of its two nodes). */
export type Usage = Map<string, { route: string; color: number }[]>

export type Segment = {
  /** Where it starts and ends along the shortened line, and on which lanes */
  x1: number; lane1: number; x2: number; lane2: number
  diagonal: boolean
  key: string; a: string; b: string; status: number
  /** The routes on it, the route the diagram is for first */
  routes: { route: string; color: number }[]
  ours: boolean
}
export type Schematic = {
  width: number
  lanes: { min: number; max: number }
  segments: Segment[]
  turnouts: { x: number; lane: number }[]
  platforms: { id: string; name: string; station: string; stationName: string; passingTrack: boolean; x1: number; x2: number; lane: number; ours: boolean }[]
  stations: { station: string; name: string; x: number; stop: number }[]
  stops: { stop: number; x: number }[]
  boundaries: { x: number; lane: number }[]
  /** Where a long run was shortened */
  breaks: number[]
}

/** The key of a rail by its two nodes, whichever way round (railKey in editor.js) */
export const railKey = (a: string, b: string) => (BigInt(a) < BigInt(b) ? a + "," + b : b + "," + a)

/** Offsets closer than this are one track */
const SAME_TRACK = 1.6
/** A piece whose ends' offsets differ by more than this changes lane */
const CHANGES_LANE = 1.2
/** Runs longer than this many blocks between places that matter are shortened */
const KEEP = 40

/**
 * Which routes run on each rail, from the whole network's data (api/network): each route's path as node indices, each
 * node with its key as its third value.
 */
export function usageOf(network: { nodes: any[]; routes: { id: string; color: number; nodes: number[] }[] }, first?: string): Usage {
  const usage: Usage = new Map()
  const routes = [...network.routes].sort((a, b) => (a.id === first ? -1 : b.id === first ? 1 : 0))
  for (const route of routes) {
    const seen = new Set<string>()
    for (let i = 1; i < route.nodes.length; i++) {
      const a = network.nodes[route.nodes[i - 1]], b = network.nodes[route.nodes[i]]
      if (!a || !b) continue
      const key = railKey(String(a[2]), String(b[2]))
      if (seen.has(key)) continue
      seen.add(key)
      if (!usage.has(key)) usage.set(key, [])
      usage.get(key)!.push({ route: route.id, color: route.color })
    }
  }
  return usage
}

type Piece = { index: number; d1: number; d2: number; o1: number; o2: number; level: boolean; mean: number; a: string; b: string; status: number; startNode: string; endNode: string }

export function buildSchematic(layout: RouteLayout, usage: Usage, routeId: string, routePlatforms: Set<string> = new Set()): Schematic {
  // ---------- The pieces, each from its smaller distance to its larger ----------
  const pieces: Piece[] = layout.rails.map((points, index) => {
    const [a, b, status] = layout.railKeys[index] || ["0", "0", 0]
    const n = points.length / 2
    let d1 = points[0], o1 = points[1], d2 = points[2 * n - 2], o2 = points[2 * n - 1]
    // Which node is at which end isn't sent; the nearer end to a's side is a guess the lanes don't depend on
    let startNode = a, endNode = b
    if (d2 < d1) { [d1, d2] = [d2, d1]; [o1, o2] = [o2, o1]; [startNode, endNode] = [b, a] }
    let sum = 0
    for (let k = 1; k < points.length; k += 2) sum += points[k]
    return { index, d1, d2, o1, o2, level: Math.abs(o2 - o1) <= CHANGES_LANE, mean: sum / n, a, b, status: Number(status) || 0, startNode, endNode }
  })

  // ---------- Lanes by order out from the path ----------
  const level = pieces.filter(p => p.level)
  /** The lane of an offset at a place along the line: how many tracks lie between it and the path, on its side */
  const laneAt = (offset: number, from: number, to: number) => {
    if (Math.abs(offset) <= SAME_TRACK / 2) return 0
    const side = Math.sign(offset)
    const offsets = level.filter(q => q.d1 < to && q.d2 > from && Math.sign(q.mean) === side && Math.abs(q.mean) > SAME_TRACK / 2 && Math.abs(q.mean) < Math.abs(offset) - SAME_TRACK / 2)
      .map(q => Math.abs(q.mean)).sort((x, y) => x - y)
    // Tracks between it and the path, one per group of offsets closer than a track
    let count = 0, last = -Infinity
    for (const value of offsets) {
      if (value - last > SAME_TRACK) count++
      last = value
    }
    return side * (count + 1)
  }
  const laneOf = new Map<number, number>()
  level.forEach(p => laneOf.set(p.index, laneAt(p.mean, p.d1, p.d2)))

  // The lane at each node, from the level pieces touching it, for the diagonals' ends
  const nodeLane = new Map<string, number>()
  level.forEach(p => {
    const lane = laneOf.get(p.index)!
    for (const node of [p.a, p.b]) if (!nodeLane.has(node) || Math.abs(lane) < Math.abs(nodeLane.get(node)!)) nodeLane.set(node, lane)
  })
  const lanes = [0, ...laneOf.values()]
  let min = Math.min(...lanes), max = Math.max(...lanes)
  const endLane = (node: string, offset: number) => {
    if (nodeLane.has(node)) return nodeLane.get(node)!
    if (Math.abs(offset) <= SAME_TRACK / 2) return 0
    // A track leaving the line: one lane further out than any on its side
    return offset > 0 ? max + 1 : min - 1
  }
  const diagonalLanes = new Map<number, [number, number]>()
  pieces.filter(p => !p.level).forEach(p => {
    // Its ends by offset: which node is at which end isn't known, so the node whose lane matches the offset better
    const options: [number, number][] = [[endLane(p.startNode, p.o1), endLane(p.endNode, p.o2)], [endLane(p.endNode, p.o1), endLane(p.startNode, p.o2)]]
    const fit = ([l1, l2]: [number, number]) => Math.abs(Math.sign(p.o1) - Math.sign(l1)) + Math.abs(Math.sign(p.o2) - Math.sign(l2))
    const chosen = fit(options[0]) <= fit(options[1]) ? options[0] : options[1]
    diagonalLanes.set(p.index, chosen)
  })
  diagonalLanes.forEach(([l1, l2]) => { min = Math.min(min, l1, l2); max = Math.max(max, l1, l2) })

  // ---------- Places that matter, and the shortened line between them ----------
  const keys = new Set<number>([0, layout.length])
  layout.turnouts.forEach(t => keys.add(t[0]))
  layout.platforms.forEach(p => { keys.add(p.line[0]); keys.add(p.line[2]) })
  layout.stops.forEach(s => { if (s >= 0) keys.add(s) })
  pieces.forEach(p => { if (!p.level) { keys.add(p.d1); keys.add(p.d2) } })
  const sorted = [...keys].filter(Number.isFinite).sort((x, y) => x - y)
  const positions: number[] = [0]
  const breaks: number[] = []
  for (let i = 1; i < sorted.length; i++) {
    const gap = sorted[i] - sorted[i - 1]
    const shortened = gap <= KEEP ? gap : KEEP + Math.sqrt(gap - KEEP) * 3
    if (gap > KEEP * 3) breaks.push(positions[i - 1] + shortened / 2)
    positions.push(positions[i - 1] + shortened)
  }
  /** A distance along the path as a place on the shortened line, in between the places that matter by proportion */
  const xOf = (d: number) => {
    if (d <= sorted[0]) return positions[0] + (d - sorted[0])
    for (let i = 1; i < sorted.length; i++) {
      if (d <= sorted[i]) {
        const span = sorted[i] - sorted[i - 1]
        return positions[i - 1] + (span > 0 ? (d - sorted[i - 1]) / span : 0) * (positions[i] - positions[i - 1])
      }
    }
    return positions[positions.length - 1] + (d - sorted[sorted.length - 1])
  }

  // ---------- What is drawn ----------
  const segments: Segment[] = pieces.map(p => {
    const key = railKey(p.a, p.b)
    const routes = usage.get(key) || []
    const [lane1, lane2] = p.level ? [laneOf.get(p.index)!, laneOf.get(p.index)!] : diagonalLanes.get(p.index)!
    return {
      x1: xOf(p.d1), x2: xOf(p.d2), lane1, lane2, diagonal: lane1 !== lane2, key, a: p.a, b: p.b, status: p.status,
      routes, ours: routes.some(r => r.route === routeId),
    }
  })

  const turnouts = layout.turnouts.map(t => ({ x: xOf(t[0]), lane: laneAt(t[1], t[0] - 1, t[0] + 1) }))
  const boundaries = (layout.boundaryNodes || []).map(n => ({ x: xOf(n[0]), lane: laneAt(n[1], n[0] - 1, n[0] + 1) }))
  const platforms = layout.platforms.map(p => {
    const [d1, o1, d2, o2] = p.line
    const from = Math.min(d1, d2), to = Math.max(d1, d2)
    return {
      id: p.id, name: p.name, station: p.station, stationName: p.stationName, passingTrack: Boolean(p.passingTrack),
      x1: xOf(from), x2: xOf(to), lane: laneAt((o1 + o2) / 2, from, to), ours: routePlatforms.has(p.id),
    }
  })
  platforms.forEach(p => { min = Math.min(min, p.lane); max = Math.max(max, p.lane) })

  // A station's name over its platforms, once; the route's stops by where they are along the path
  const stops = layout.stops.map((s, stop) => ({ stop, x: s >= 0 ? xOf(s) : -1 })).filter(s => s.x >= 0)
  const byStation = new Map<string, { station: string; name: string; xs: number[] }>()
  platforms.forEach(p => {
    const id = p.station || "platform:" + p.id
    if (!byStation.has(id)) byStation.set(id, { station: p.station, name: p.stationName, xs: [] })
    byStation.get(id)!.xs.push((p.x1 + p.x2) / 2)
  })
  const stations = [...byStation.values()].map(s => {
    const x = s.xs.reduce((a, b) => a + b, 0) / s.xs.length
    const near = stops.reduce((best, stop) => (Math.abs(stop.x - x) < Math.abs(best.x - x) ? stop : best), { stop: -1, x: Infinity })
    return { station: s.station, name: s.name, x, stop: Math.abs(near.x - x) < 30 ? near.stop : -1 }
  }).sort((a, b) => a.x - b.x)

  return { width: positions[positions.length - 1], lanes: { min, max }, segments, turnouts, platforms, stations, stops, boundaries, breaks }
}
