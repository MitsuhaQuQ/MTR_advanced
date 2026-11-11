import type { NetData, TopoPlatform } from "./abstractNet"

export type Point = [number, number]
export type DrawRail = { rail: number; nodes: [number, number]; points: Point[]; kind: number; colors: string[]; station?: string; name: string }
export type TrackMap = { rails: DrawRail[]; stations: { id: string; name: string; x: number; y: number; vertical: boolean }[]; ends: Point[]; junctions: Point[]; width: number; height: number }

/** One strictly increasing projection for the whole network: nearby tracks move together. */
export function compressedAxis(values: number[]) {
  let knots = [...new Set(values)].sort((a, b) => a - b)
  if (!knots.length) knots.push(0)
  let mapped = [60]
  for (let i = 1; i < knots.length; i++) {
    const gap = knots[i] - knots[i - 1]
    mapped.push(mapped[i - 1] + (gap <= 8 ? gap * 4 : gap <= 24 ? 32 + gap - 8 : 48 + 12 * Math.sqrt(gap - 24)))
  }
  // Dense sections have one constant scale; only changes of scale need subdivision later.
  const keep = knots.map((_, i) => i).filter(i => i === 0 || i === knots.length - 1 || Math.abs((mapped[i] - mapped[i - 1]) / (knots[i] - knots[i - 1]) - (mapped[i + 1] - mapped[i]) / (knots[i + 1] - knots[i])) > 1e-9)
  knots = keep.map(i => knots[i]); mapped = keep.map(i => mapped[i])
  const project = (value: number) => {
    let lo = 0, hi = knots.length - 1
    while (lo < hi) { const mid = Math.ceil((lo + hi) / 2); if (knots[mid] <= value) lo = mid; else hi = mid - 1 }
    if (lo === knots.length - 1) return mapped[lo] + (value - knots[lo]) * 2
    return mapped[lo] + (value - knots[lo]) / (knots[lo + 1] - knots[lo]) * (mapped[lo + 1] - mapped[lo])
  }
  return { knots, project }
}

/** Preserve every rail and shared node, including the two diagonals of a scissors crossover.
 * Subdivide at projection knots so the piecewise transform cannot introduce or remove crossings.
 * Station glyphs decorate real platform rails; they never replace the station's switchwork.
 */
export function trackSchematic(net: NetData, platforms: TopoPlatform[] = []): TrackMap {
  const used = new Set<number>()
  net.rails.forEach(r => { used.add(r[0]); used.add(r[1]) })
  const nodes = [...used].map(k => net.nodes[k])
  const ax = compressedAxis(nodes.map(p => p[0])), ay = compressedAxis(nodes.map(p => p[1]))
  const project = (p: Point): Point => [ax.project(p[0]), ay.project(p[1])]
  const pair = (a: number, b: number) => a < b ? `${a},${b}` : `${b},${a}`
  const colors = new Map<string, Set<string>>()
  for (const route of net.routes || []) for (let i = 1; i < route.nodes.length; i++) {
    const key = pair(route.nodes[i - 1], route.nodes[i])
    if (!colors.has(key)) colors.set(key, new Set())
    colors.get(key)!.add("#" + (route.color & 0xffffff).toString(16).padStart(6, "0"))
  }
  const degree = new Map<number, number>()
  const rails: DrawRail[] = net.rails.map((r, rail) => {
    const a = net.nodes[r[0]], b = net.nodes[r[1]]
    degree.set(r[0], (degree.get(r[0]) || 0) + 1); degree.set(r[1], (degree.get(r[1]) || 0) + 1)
    const source: Point[] = [[a[0], a[1]]]
    for (let i = 4; i + 1 < r.length; i += 2) if (Number.isFinite(r[i]) && Number.isFinite(r[i + 1])) source.push([r[i], r[i + 1]])
    source.push([b[0], b[1]])
    const points: Point[] = [project(source[0])]
    for (let i = 1; i < source.length; i++) {
      const p = source[i - 1], q = source[i], ts = [1]
      for (const [axis, knots] of [[0, ax.knots], [1, ay.knots]] as const) {
        if (p[axis] === q[axis]) continue
        for (const knot of knots) { const t = (knot - p[axis]) / (q[axis] - p[axis]); if (t > 0 && t < 1) ts.push(t) }
      }
      for (const t of [...new Set(ts)].sort((x, y) => x - y)) points.push(project([p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t]))
    }
    const x = (a[0] + b[0]) / 2, z = (a[1] + b[1]) / 2
    const platform = r[2] === 1 ? platforms.find(p => p.line && Math.hypot((p.line[0] + p.line[2]) / 2 - x, (p.line[1] + p.line[3]) / 2 - z) < 4) : undefined
    const station = r[2] === 1 ? (net.stations || []).filter(s => Math.hypot(s.x - x, s.z - z) < 200)
      .sort((s, t) => Number(t.id === platform?.station) - Number(s.id === platform?.station) || Math.hypot(s.x - x, s.z - z) - Math.hypot(t.x - x, t.z - z))[0] : undefined
    return { rail, nodes: [r[0], r[1]], points, kind: r[2], colors: [...(colors.get(pair(r[0], r[1])) || [])], station: station ? "s:" + station.id : undefined, name: platform?.name || "" }
  })
  const stations: TrackMap["stations"] = []
  for (const s of net.stations || []) {
    const tracks = rails.filter(r => r.station === "s:" + s.id)
    if (!tracks.length) continue
    const points = tracks.flatMap(r => r.points), xs = points.map(p => p[0]), ys = points.map(p => p[1])
    const first = tracks[0].points, a = first[0], b = first[first.length - 1]
    const vertical = Math.abs(b[1] - a[1]) > Math.abs(b[0] - a[0])
    stations.push({ id: "s:" + s.id, name: s.name, vertical, x: vertical ? Math.max(...xs) + 18 : (Math.min(...xs) + Math.max(...xs)) / 2, y: vertical ? (Math.min(...ys) + Math.max(...ys)) / 2 : Math.max(...ys) + 22 })
  }
  const all = rails.flatMap(r => r.points)
  const pointsOfDegree = (matches: (degree: number) => boolean) => [...degree].filter(([, d]) => matches(d)).map(([k]) => project([net.nodes[k][0], net.nodes[k][1]]))
  const ends = pointsOfDegree(d => d === 1), junctions = pointsOfDegree(d => d > 2)
  // Pack remote disconnected test tracks below the main network, rather than letting an empty geographic gap
  // shrink every station label. Nearby disconnected tracks retain their place beside the main network.
  const parent = net.nodes.map((_, i) => i)
  const find = (i: number): number => {
    let root = i
    while (parent[root] !== root) root = parent[root]
    while (parent[i] !== i) { const next = parent[i]; parent[i] = root; i = next }
    return root
  }
  rails.forEach(r => { parent[find(r.nodes[0])] = find(r.nodes[1]) })
  const groups = new Map<number, DrawRail[]>()
  rails.forEach(r => { const id = find(r.nodes[0]); if (!groups.has(id)) groups.set(id, []); groups.get(id)!.push(r) })
  const components = [...groups.values()].sort((a, b) => b.length - a.length)
  const bounds = (points: Point[]) => points.reduce((b, p) => [Math.min(b[0], p[0]), Math.min(b[1], p[1]), Math.max(b[2], p[0]), Math.max(b[3], p[1])], [Infinity, Infinity, -Infinity, -Infinity])
  const main = components[0]
  if (main) {
    const rawBounds = (group: DrawRail[]) => bounds(group.flatMap(r => r.nodes.map(k => [net.nodes[k][0], net.nodes[k][1]] as Point)))
    const base = rawBounds(main)
    const remote = components.filter(g => { const b = rawBounds(g); return Math.max(base[0] - b[2], b[0] - base[2], base[1] - b[3], b[1] - base[3]) > 200 })
    const local = rails.filter(r => !remote.some(g => g.includes(r)))
    const localBounds = bounds(local.flatMap(r => r.points))
    let left = localBounds[0]
    for (const group of remote) {
      const b = bounds(group.flatMap(r => r.points)), dx = left - b[0], dy = localBounds[3] + 100 - b[1]
      // End and switch coordinates are separate arrays, so move them before the rail coordinates.
      for (const p of [...ends, ...junctions]) if (group.some(r => r.points.some(q => q[0] === p[0] && q[1] === p[1]))) { p[0] += dx; p[1] += dy }
      for (const s of stations) if (group.some(r => r.station === s.id)) { s.x += dx; s.y += dy }
      group.forEach(r => r.points.forEach(p => { p[0] += dx; p[1] += dy }))
      left += b[2] - b[0] + 100
    }
  }
  const b = bounds(all), dx = Number.isFinite(b[0]) ? 60 - b[0] : 0, dy = Number.isFinite(b[1]) ? 60 - b[1] : 0
  for (const p of [...all, ...ends, ...junctions]) { p[0] += dx; p[1] += dy }
  for (const s of stations) { s.x += dx; s.y += dy }
  return { rails, stations, ends, junctions, width: (all.length ? b[2] - b[0] : 0) + 220, height: (all.length ? b[3] - b[1] : 0) + 140 }
}

export function railMidpoint(points: Point[]): Point {
  const lengths = points.slice(1).map((p, i) => Math.hypot(p[0] - points[i][0], p[1] - points[i][1]))
  let remaining = lengths.reduce((a, b) => a + b, 0) / 2
  for (let i = 0; i < lengths.length; i++) {
    if (remaining <= lengths[i]) { const t = lengths[i] ? remaining / lengths[i] : 0; return [points[i][0] + t * (points[i + 1][0] - points[i][0]), points[i][1] + t * (points[i + 1][1] - points[i][1])] }
    remaining -= lengths[i]
  }
  return points[0]
}
