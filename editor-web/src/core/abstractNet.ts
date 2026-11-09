/**
 * The whole network as a schematic (全网示意配线): what api/network gives (every rail end to end) folded into stations and
 * the corridors between them, laid out like a metro map.
 *
 * 1. Each station's area: its platform rails (the nearest station's), and every node in a box around them along the
 *    platforms' axis. The rails inside running along the axis are the station's tracks, one lane per side-by-side
 *    position: a platform (with its number) or a track through.
 * 2. The rest of the rails as chains between anchors (a station's area, a junction, a dead end); turnouts close together
 *    (a crossover's two ends) are one junction.
 * 3. Chains between the same two places are one corridor, its tracks the chains; a junction with only two places beside
 *    it (a crossover out on the line) is folded into the corridor through it, and marked.
 * 4. Layout: each corridor wants its geographic direction rounded to 45° and a length growing with the square root of its
 *    real length; places too close are pushed apart. The result is in pixels.
 * 5. The routes' paths colour the tracks they run on.
 */

export type NetData = {
  nodes: [number, number, string][]
  rails: any[]
  stations?: { id: string; name: string; x: number; z: number }[]
  routes?: { id: string; color: number; lineKey: string; name: string; nodes: number[] }[]
}
export type TopoPlatform = { id: string; name: string; station: string; line?: number[]; passingTrack?: boolean }

export type Lane = { offset: number; platform: boolean; name: string; colors: string[] }
export type Place = { id: string; kind: "station" | "junction" | "end"; name: string; x: number; z: number; px: number; py: number; angle: number; lanes: Lane[] }
export type Corridor = { a: string; b: string; tracks: number; length: number; colors: string[]; crossovers: number; rails: number[] }
export type Schematic = { places: Place[]; corridors: Corridor[]; width: number; height: number }

const KIND_PLATFORM = 1
/** How far beyond its platforms a station reaches along them: its throats, where turnouts are its own */
const THROAT = 80
const SNAP = Math.PI / 4
const hex = (color: number) => "#" + (color & 0xffffff).toString(16).padStart(6, "0")

export function abstractNetwork(net: NetData, platforms: TopoPlatform[] = []): Schematic {
  const nodes = net.nodes, rails = net.rails
  const stations = (net.stations || []).filter(s => isFinite(s.x) && isFinite(s.z))
  const mid = (r: any[]) => [(nodes[r[0]][0] + nodes[r[1]][0]) / 2, (nodes[r[0]][1] + nodes[r[1]][1]) / 2]
  const lengthOf = (r: any[]) => Math.hypot(nodes[r[0]][0] - nodes[r[1]][0], nodes[r[0]][1] - nodes[r[1]][1])

  const adjacent: number[][] = nodes.map(() => [])
  rails.forEach((r, i) => { adjacent[r[0]].push(i); adjacent[r[1]].push(i) })
  const other = (rail: number, node: number) => (rails[rail][0] === node ? rails[rail][1] : rails[rail][0])

  // Which routes run on each rail, by its two nodes
  const railByPair = new Map<string, number>()
  rails.forEach((r, i) => railByPair.set(Math.min(r[0], r[1]) + "," + Math.max(r[0], r[1]), i))
  const railColors: Set<string>[] = rails.map(() => new Set())
  ;(net.routes || []).forEach(route => {
    for (let k = 1; k < route.nodes.length; k++) {
      const rail = railByPair.get(Math.min(route.nodes[k - 1], route.nodes[k]) + "," + Math.max(route.nodes[k - 1], route.nodes[k]))
      if (rail !== undefined) railColors[rail].add(hex(route.color))
    }
  })

  // ---------- 1. Station areas ----------
  const platformRails = new Map<string, number[]>()
  rails.forEach((r, i) => {
    if (r[2] !== KIND_PLATFORM || !stations.length) return
    const [x, z] = mid(r)
    let best = -1, bestDistance = 200
    stations.forEach((s, k) => { const d = Math.hypot(s.x - x, s.z - z); if (d < bestDistance) { bestDistance = d; best = k } })
    if (best < 0) return
    const id = stations[best].id
    if (!platformRails.has(id)) platformRails.set(id, [])
    platformRails.get(id)!.push(i)
  })
  const areaOf = new Map<number, string>()
  type Area = { id: string; u: number[]; n: number[]; nodes: number[] }
  const areas = new Map<string, Area>()
  // Nodes by grid cell, to find those in each box quickly
  const CELL = 64, grid = new Map<string, number[]>()
  nodes.forEach((p, i) => { const key = Math.floor(p[0] / CELL) + "," + Math.floor(p[1] / CELL); if (!grid.has(key)) grid.set(key, []); grid.get(key)!.push(i) })
  platformRails.forEach((list, id) => {
    const longest = list.reduce((a, b) => (lengthOf(rails[b]) > lengthOf(rails[a]) ? b : a))
    const r = rails[longest], length = lengthOf(r) || 1
    const u = [(nodes[r[1]][0] - nodes[r[0]][0]) / length, (nodes[r[1]][1] - nodes[r[0]][1]) / length], n = [-u[1], u[0]]
    const ends = list.flatMap(i => [rails[i][0], rails[i][1]])
    const along = ends.map(k => nodes[k][0] * u[0] + nodes[k][1] * u[1]), across = ends.map(k => nodes[k][0] * n[0] + nodes[k][1] * n[1])
    const box = [Math.min(...along) - THROAT, Math.max(...along) + THROAT, Math.min(...across) - 8, Math.max(...across) + 8]
    const xs = ends.map(k => nodes[k][0]), zs = ends.map(k => nodes[k][1])
    const reach = THROAT + 20
    const inside: number[] = []
    for (let cx = Math.floor((Math.min(...xs) - reach) / CELL); cx <= Math.floor((Math.max(...xs) + reach) / CELL); cx++) {
      for (let cz = Math.floor((Math.min(...zs) - reach) / CELL); cz <= Math.floor((Math.max(...zs) + reach) / CELL); cz++) {
        (grid.get(cx + "," + cz) || []).forEach(k => {
          const a = nodes[k][0] * u[0] + nodes[k][1] * u[1], c = nodes[k][0] * n[0] + nodes[k][1] * n[1]
          if (a >= box[0] && a <= box[1] && c >= box[2] && c <= box[3] && !areaOf.has(k)) inside.push(k)
        })
      }
    }
    inside.forEach(k => areaOf.set(k, id))
    areas.set(id, { id, u, n, nodes: inside })
  })

  // The tracks through each station, side by side
  const platformName = (rail: number) => {
    const [x, z] = mid(rails[rail])
    const found = platforms.find(p => p.line && Math.hypot((p.line[0] + p.line[2]) / 2 - x, (p.line[1] + p.line[3]) / 2 - z) < 4)
    return found ? found.name : ""
  }
  const lanesOf = (area: Area): Lane[] => {
    const lanes: { offset: number; platform: boolean; names: Set<string>; colors: Set<string> }[] = []
    rails.forEach((r, i) => {
      if (areaOf.get(r[0]) !== area.id || areaOf.get(r[1]) !== area.id) return
      const length = lengthOf(r) || 1
      const dot = Math.abs(((nodes[r[1]][0] - nodes[r[0]][0]) * area.u[0] + (nodes[r[1]][1] - nodes[r[0]][1]) * area.u[1]) / length)
      if (dot < 0.9) return
      const [x, z] = mid(r)
      const offset = x * area.n[0] + z * area.n[1]
      let lane = lanes.find(l => Math.abs(l.offset - offset) < 2.5)
      if (!lane) lanes.push(lane = { offset, platform: false, names: new Set(), colors: new Set() })
      if (r[2] === KIND_PLATFORM) { lane.platform = true; const name = platformName(i); if (name) lane.names.add(name) }
      railColors[i].forEach(c => lane!.colors.add(c))
    })
    const base = lanes.length ? Math.min(...lanes.map(l => l.offset)) : 0
    return lanes.sort((a, b) => a.offset - b.offset).map(l => ({ offset: l.offset - base, platform: l.platform, name: [...l.names].join("/"), colors: [...l.colors] }))
  }

  // ---------- 2. Chains between anchors ----------
  const isAnchor = (k: number) => areaOf.has(k) || adjacent[k].length !== 2
  // Turnouts close together are one junction
  const parent = new Map<number, number>()
  const find = (k: number): number => { const p = parent.get(k); if (p === undefined || p === k) return k; const root = find(p); parent.set(k, root); return root }
  const junctionNodes = nodes.map((_, k) => k).filter(k => !areaOf.has(k) && adjacent[k].length > 2)
  junctionNodes.forEach(k => parent.set(k, k))
  junctionNodes.forEach(k => {
    const [x, z] = nodes[k]
    for (let cx = Math.floor((x - 30) / CELL); cx <= Math.floor((x + 30) / CELL); cx++) {
      for (let cz = Math.floor((z - 30) / CELL); cz <= Math.floor((z + 30) / CELL); cz++) {
        (grid.get(cx + "," + cz) || []).forEach(j => { if (j !== k && parent.has(j) && Math.hypot(nodes[j][0] - x, nodes[j][1] - z) < 30) parent.set(find(j), find(k)) })
      }
    }
  })
  const placeOf = (k: number) => areaOf.has(k) ? "s:" + areaOf.get(k) : adjacent[k].length > 2 ? "j:" + find(k) : "e:" + k

  type Chain = { a: string; b: string; rails: number[]; length: number }
  const chains: Chain[] = []
  const walked = new Set<number>()
  nodes.forEach((_, start) => {
    if (!isAnchor(start)) return
    adjacent[start].forEach(first => {
      if (walked.has(first)) return
      // Rails inside a station are its tracks, not a chain
      if (areaOf.has(start) && areaOf.get(other(first, start)) === areaOf.get(start)) return
      const list = [first]
      walked.add(first)
      let node = other(first, start), length = lengthOf(rails[first])
      while (!isAnchor(node)) {
        const next = adjacent[node].find(rail => !walked.has(rail))
        if (next === undefined) break
        walked.add(next)
        list.push(next)
        length += lengthOf(rails[next])
        node = other(next, node)
      }
      chains.push({ a: placeOf(start), b: placeOf(node), rails: list, length })
    })
  })

  // ---------- 3. Corridors ----------
  const corridorMap = new Map<string, Corridor>()
  chains.forEach(chain => {
    if (chain.a === chain.b) return
    const [a, b] = chain.a < chain.b ? [chain.a, chain.b] : [chain.b, chain.a]
    const key = a + "|" + b
    const corridor = corridorMap.get(key)
    if (corridor) {
      corridor.tracks++
      corridor.length = Math.max(corridor.length, chain.length)
      corridor.rails.push(...chain.rails)
    } else corridorMap.set(key, { a, b, tracks: 1, length: chain.length, colors: [], crossovers: 0, rails: chain.rails.slice() })
  })
  let corridors = [...corridorMap.values()]
  // A junction between only two places is a crossover on the line: the corridor runs through it
  for (let changed = true; changed;) {
    changed = false
    const around = new Map<string, Corridor[]>()
    corridors.forEach(c => [c.a, c.b].forEach(p => { if (!around.has(p)) around.set(p, []); around.get(p)!.push(c) }))
    for (const [place, list] of around) {
      if (!place.startsWith("j:") || list.length !== 2) continue
      const [c1, c2] = list
      const a = c1.a === place ? c1.b : c1.a, b = c2.a === place ? c2.b : c2.a
      if (a === b) continue
      const [x, y] = a < b ? [a, b] : [b, a]
      const merged: Corridor = { a: x, b: y, tracks: Math.max(c1.tracks, c2.tracks), length: c1.length + c2.length, colors: [], crossovers: c1.crossovers + c2.crossovers + 1, rails: [...c1.rails, ...c2.rails] }
      corridors = corridors.filter(c => c !== c1 && c !== c2)
      const same = corridors.find(c => c.a === x && c.b === y)
      if (same) { same.tracks = Math.max(same.tracks, merged.tracks); same.rails.push(...merged.rails); same.crossovers += merged.crossovers }
      else corridors.push(merged)
      changed = true
      break
    }
  }
  corridors.forEach(c => { const colors = new Set<string>(); c.rails.forEach(r => railColors[r].forEach(color => colors.add(color))); c.colors = [...colors] })

  // ---------- 4. Places and layout ----------
  const used = new Set(corridors.flatMap(c => [c.a, c.b]))
  areas.forEach((_, id) => used.add("s:" + id))
  const places: Place[] = []
  const centre = (list: number[]) => [list.reduce((s, k) => s + nodes[k][0], 0) / list.length, list.reduce((s, k) => s + nodes[k][1], 0) / list.length]
  const stationById = new Map(stations.map(s => [s.id, s]))
  used.forEach(id => {
    if (id.startsWith("s:")) {
      const area = areas.get(id.slice(2))!, station = stationById.get(area.id)!
      places.push({ id, kind: "station", name: station.name, x: station.x, z: station.z, px: 0, py: 0, angle: Math.round(Math.atan2(area.u[1], area.u[0]) / SNAP) * SNAP, lanes: lanesOf(area) })
    } else if (id.startsWith("j:")) {
      const root = +id.slice(2)
      const [x, z] = centre(junctionNodes.filter(k => find(k) === root))
      places.push({ id, kind: "junction", name: "", x, z, px: 0, py: 0, angle: 0, lanes: [] })
    } else {
      const k = +id.slice(2)
      places.push({ id, kind: "end", name: "", x: nodes[k][0], z: nodes[k][1], px: 0, py: 0, angle: 0, lanes: [] })
    }
  })
  // A station's tracks lie across its axis; keep its box upright-ish (angle in -90…90)
  places.forEach(p => { if (p.angle > Math.PI / 2 + 1e-6) p.angle -= Math.PI; if (p.angle < -Math.PI / 2 - 1e-6) p.angle += Math.PI })
  layout(places, corridors)
  const xs = places.map(p => p.px), ys = places.map(p => p.py)
  const minX = Math.min(...xs, 0), minY = Math.min(...ys, 0)
  places.forEach(p => { p.px -= minX - 80; p.py -= minY - 80 })
  return { places, corridors, width: Math.max(...xs, 0) - minX + 160, height: Math.max(...ys, 0) - minY + 160 }
}

/** The metro-map layout: directions rounded to 45°, lengths by the square root of the real ones, places kept apart. */
export function layout(places: Place[], corridors: Corridor[]) {
  if (!places.length) return
  const index = new Map(places.map((p, i) => [p.id, i]))
  const xs = places.map(p => p.x), zs = places.map(p => p.z)
  const span = Math.max(Math.max(...xs) - Math.min(...xs), Math.max(...zs) - Math.min(...zs), 1)
  const scale = 1200 / span
  places.forEach(p => { p.px = p.x * scale; p.py = p.z * scale })
  const wants = corridors.map(c => {
    const a = places[index.get(c.a)!], b = places[index.get(c.b)!]
    const angle = Math.round(Math.atan2(b.z - a.z, b.x - a.x) / SNAP) * SNAP
    const length = Math.max(90, Math.min(280, 40 + 5 * Math.sqrt(c.length)))
    return { a: index.get(c.a)!, b: index.get(c.b)!, dx: Math.cos(angle) * length, dy: Math.sin(angle) * length }
  })
  const near = 70
  const linked = new Set(wants.map(w => Math.min(w.a, w.b) + "," + Math.max(w.a, w.b)))
  // Then the directions alone for the last rounds, so they end up straight
  for (let iteration = 0; iteration < 600; iteration++) {
    wants.forEach(w => {
      const a = places[w.a], b = places[w.b]
      const ex = b.px - a.px - w.dx, ey = b.py - a.py - w.dy
      a.px += ex * 0.25; a.py += ey * 0.25
      b.px -= ex * 0.25; b.py -= ey * 0.25
    })
    if (iteration < 400 && places.length <= 600) {
      for (let i = 0; i < places.length; i++) {
        for (let j = i + 1; j < places.length; j++) {
          const a = places[i], b = places[j]
          const dx = b.px - a.px, dy = b.py - a.py, d = Math.hypot(dx, dy)
          if (d >= near || linked.has(i + "," + j)) continue
          const push = (near - d) / 2 / (d || 1)
          const ux = d ? dx : 1, uy = d ? dy : 0
          a.px -= ux * push; a.py -= uy * push
          b.px += ux * push; b.py += uy * push
        }
      }
    }
  }
}
