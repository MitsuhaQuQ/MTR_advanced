/**
 * A station's track use (0.80.0, user 2026-11-14): which way each line's trains run each track around one station, for
 * finding the conflicting flows of the duties. Two kinds of use:
 * - service: a route's own path between its stops (network.routes, the order of its nodes is the way it runs);
 * - move: what the depots' duty paths run besides (network.dutyUses): turning back, changing platforms, out and back.
 *   A duty path's rails that a route of the same line runs the same way are its services, not moves.
 * A track used both ways is a place trains meet against each other: turn-back tracks by design, anything else worth a
 * look.
 */
import type { NetData } from "./abstractNet"

export type Point = [number, number]
export type FlowRail = { rail: number; points: Point[]; kind: number; both: boolean }
export type Flow = { rail: number; forward: boolean; color: string; lineKey: string; route: string; move: boolean }
export type FlowLine = { lineKey: string; name: string; color: string; services: number; moves: number }
export type StationFlowView = { rails: FlowRail[]; flows: Flow[]; lines: FlowLine[]; box: [number, number, number, number] }
export type FlowNet = NetData & { dutyUses?: [number, number, string, string][] }

const hex = (color: number) => "#" + ((color ?? 0) & 0xffffff).toString(16).padStart(6, "0")

/**
 * @param stationId the station's id, without the "s:" of the drawings
 * @param radius    how far from the station's middle (blocks) its tracks are taken: any of a rail's points in it
 */
export function stationFlows(net: FlowNet, stationId: string, radius = 160): StationFlowView | null {
  const station = (net.stations || []).find(s => s.id === stationId)
  if (!station || !net.rails) return null
  const near = (x: number, z: number) => Math.hypot(x - station.x, z - station.z) <= radius
  const railOf = new Map<string, number>()
  const rails: FlowRail[] = []
  const shown = new Set<number>()
  net.rails.forEach((r: any[], rail: number) => {
    railOf.set(`${r[0]},${r[1]}`, rail)
    const a = net.nodes[r[0]], b = net.nodes[r[1]]
    if (!a || !b) return
    const points: Point[] = [[a[0], a[1]]]
    for (let i = 4; i + 1 < r.length; i += 2) if (Number.isFinite(r[i]) && Number.isFinite(r[i + 1])) points.push([r[i], r[i + 1]])
    points.push([b[0], b[1]])
    if (points.some(p => near(p[0], p[1]))) {
      shown.add(rail)
      rails.push({ rail, points, kind: r[2], both: false })
    }
  })
  /** The rail between two nodes and whether going from the first to the second runs it from its lower node */
  const find = (from: number, to: number): [number, boolean] | null => {
    const forward = railOf.get(`${from},${to}`)
    if (forward !== undefined) return [forward, true]
    const backward = railOf.get(`${to},${from}`)
    return backward !== undefined ? [backward, false] : null
  }
  const routes = new Map((net.routes || []).map(route => [String(route.id), route]))
  const flows: Flow[] = []
  const seen = new Set<string>()
  const serviceWays = new Set<string>()
  const add = (flow: Flow) => {
    const key = `${flow.rail}|${flow.forward}|${flow.lineKey}|${flow.move}|${flow.color}`
    if (seen.has(key)) return
    seen.add(key)
    flows.push(flow)
  }
  for (const route of net.routes || []) {
    for (let i = 1; i < route.nodes.length; i++) {
      const found = find(route.nodes[i - 1], route.nodes[i])
      if (!found || !shown.has(found[0])) continue
      serviceWays.add(`${found[0]}|${found[1]}|${route.lineKey}`)
      add({ rail: found[0], forward: found[1], color: hex(route.color), lineKey: route.lineKey, route: String(route.id), move: false })
    }
  }
  for (const [from, to, routeId] of net.dutyUses || []) {
    const found = find(from, to)
    if (!found || !shown.has(found[0])) continue
    const route = routes.get(String(routeId))
    const lineKey = route ? route.lineKey : "?" + routeId
    if (serviceWays.has(`${found[0]}|${found[1]}|${lineKey}`)) continue
    add({ rail: found[0], forward: found[1], color: route ? hex(route.color) : "#888888", lineKey, route: String(routeId), move: true })
  }
  const ways = new Map<number, Set<boolean>>()
  flows.forEach(flow => { if (!ways.has(flow.rail)) ways.set(flow.rail, new Set()); ways.get(flow.rail)!.add(flow.forward) })
  rails.forEach(rail => { rail.both = (ways.get(rail.rail)?.size ?? 0) > 1 })
  const lines = new Map<string, FlowLine>()
  flows.forEach(flow => {
    if (!lines.has(flow.lineKey)) {
      const route = routes.get(flow.route)
      lines.set(flow.lineKey, { lineKey: flow.lineKey, name: route ? route.name.split("|")[0] : flow.route, color: flow.color, services: 0, moves: 0 })
    }
    const line = lines.get(flow.lineKey)!
    if (flow.move) line.moves++; else line.services++
  })
  const all = rails.flatMap(r => r.points)
  const box: [number, number, number, number] = all.length
    ? [Math.min(...all.map(p => p[0])), Math.min(...all.map(p => p[1])), Math.max(...all.map(p => p[0])), Math.max(...all.map(p => p[1]))]
    : [station.x - radius, station.z - radius, station.x + radius, station.z + radius]
  return { rails, flows, lines: [...lines.values()], box }
}
