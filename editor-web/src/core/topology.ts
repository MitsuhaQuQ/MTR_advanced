/**
 * The platforms on the rails and the ways between them (retiring the old code, step 1: the old buildTopology, isUsable,
 * trackInfo / trackLabel / trackShort, stationTracks, reachFrom, legCost, solveStops, findCorridor, orderByLayout,
 * topologyModel, stopsSummaryOf, routeProblems): api/data's topology, a state being a platform and the way the train runs
 * along it (platform × 2 + way). test/topology.test.ts runs the old functions beside these on the same network.
 */
import { tr } from "../i18n"
import { shortName } from "./model"

export type Way = { usable: boolean; edges: number[] }
export type TopoPlatform = {
  id: string; name: string; station: string; stationName: string; network: number; mode?: string
  line?: number[]; ways: Way[]; track?: { direction?: string; kind?: string } | null; passingTrack?: boolean
}
export type TopologySource = { platforms: TopoPlatform[]; networkCount?: number; stations?: unknown[]; overlapping?: unknown[] }
/** The picker's model: each stop's tracks, the ones picked, stop or pass, fixed tracks, the colour, a conflict to tell */
export type StopsModel = { layers: number[][]; picks: number[]; stop: boolean[]; pins: Map<number, number>; color: string; conflict: string }
export type Solution = { ok: true; picks: number[]; reversals: number[]; legs: number[]; feasible: Set<number>[]; arrive: number[]; depart: number[] } | { ok: false; feasible: Set<number>[] }
export type Problem = { stop: number; level: "error" | "warn"; text: string }

export const TRACK_DIRECTIONS: Record<string, string> = { up: "上行", down: "下行", both: "双向" }
export const TRACK_SHORT: Record<string, string> = { up: "上", down: "下", both: "双" }

const compareTuples = (a: number[], b: number[]) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2]

export class Topology {
  readonly platforms: TopoPlatform[]
  readonly index: Map<string, number>
  readonly byStation = new Map<string, number[]>()
  readonly networkCount: number
  readonly stations: unknown[]
  readonly overlapping: unknown[]
  private readonly reach = new Map<number, { dist: Map<number, number>; prev: Map<number, number> }>()
  private readonly problems = new Map<string, { key: string; problems: Problem[] }>()

  constructor(source: TopologySource | null | undefined) {
    const s = source || { platforms: [], networkCount: 0 }
    this.platforms = s.platforms
    s.platforms.forEach((p, i) => {
      if (!p.station) return
      const key = p.station + "|" + p.network
      if (!this.byStation.has(key)) this.byStation.set(key, [])
      this.byStation.get(key)!.push(i)
    })
    this.index = new Map(s.platforms.map((p, i) => [p.id, i]))
    this.networkCount = s.networkCount || 0
    this.stations = s.stations || []
    this.overlapping = s.overlapping || []
  }

  isUsable = (s: number) => Boolean(this.platforms[s >> 1] && this.platforms[s >> 1].ways[s & 1].usable)
  platformLabel = (i: number) => this.platforms[i].name || "?"
  stationLabel = (i: number) => shortName(this.platforms[i].stationName) || tr("（不属于车站）")

  /** A station's track as the game works it out: up or down, main line or passing track, the hand-set flag first */
  trackInfo(id: string, passingTrack?: boolean) {
    const index = this.index.get(id)
    const track = index !== undefined && this.platforms[index] ? this.platforms[index].track : null
    const kind = passingTrack ? "passing" : track && track.kind ? track.kind : ""
    return { direction: track && track.direction ? track.direction : "", kind }
  }
  /** As "上行 3道 正线" */
  trackLabel(id: string, name: string, passingTrack?: boolean) {
    const { direction, kind } = this.trackInfo(id, passingTrack)
    return [direction ? tr(TRACK_DIRECTIONS[direction]) : "", tr("{0}道", name || "?"), kind === "main" ? tr("正线") : kind === "passing" ? tr("待避线") : ""].filter(Boolean).join(" ")
  }
  /** As "上 3 待避", for the narrow cells of the stop picker */
  trackShort(id: string, name: string, passingTrack?: boolean) {
    const { direction, kind } = this.trackInfo(id, passingTrack)
    return [direction ? tr(TRACK_SHORT[direction]) : "", name || "?", kind === "main" ? tr("正") : kind === "passing" ? tr("待避") : ""].filter(Boolean).join(" ")
  }

  /** A station's tracks on the same rails, by their names */
  stationTracks(i: number): number[] {
    const p = this.platforms[i]
    const tracks = p.station ? this.byStation.get(p.station + "|" + p.network) || [i] : [i]
    return (tracks.includes(i) ? tracks : [i, ...tracks]).slice().sort((a, b) => this.platformLabel(a).localeCompare(this.platformLabel(b), undefined, { numeric: true }) || a - b)
  }

  /** Costs from a state to every state reachable from it without turning back, passing platforms on the way */
  reachFrom(start: number) {
    const cached = this.reach.get(start)
    if (cached) return cached
    const dist = new Map<number, number>([[start, 0]]), prev = new Map<number, number>(), done = new Set<number>()
    const heap: [number, number][] = [[0, start]]
    const push = (item: [number, number]) => {
      heap.push(item)
      for (let i = heap.length - 1; i > 0;) {
        const parent = (i - 1) >> 1
        if (heap[parent][0] <= heap[i][0]) break
        ;[heap[parent], heap[i]] = [heap[i], heap[parent]]
        i = parent
      }
    }
    const pop = () => {
      const top = heap[0], last = heap.pop()!
      if (heap.length) {
        heap[0] = last
        for (let i = 0; ;) {
          const l = i * 2 + 1, r = l + 1
          let m = i
          if (l < heap.length && heap[l][0] < heap[m][0]) m = l
          if (r < heap.length && heap[r][0] < heap[m][0]) m = r
          if (m === i) break
          ;[heap[m], heap[i]] = [heap[i], heap[m]]
          i = m
        }
      }
      return top
    }
    while (heap.length && done.size < 20000) {
      const [d, s] = pop()
      if (done.has(s)) continue
      done.add(s)
      const edges = this.isUsable(s) ? this.platforms[s >> 1].ways[s & 1].edges : []
      for (let k = 0; k < edges.length; k += 2) {
        const t = edges[k], nd = d + edges[k + 1]
        if (t !== start && nd < (dist.has(t) ? dist.get(t)! : Infinity)) {
          dist.set(t, nd)
          prev.set(t, s)
          push([nd, t])
        }
      }
    }
    dist.delete(start)
    const result = { dist, prev }
    this.reach.set(start, result)
    return result
  }

  /** The cheapest way from any state of one platform to any state of another, or Infinity */
  legCost(from: number, to: number) {
    let best = Infinity
    ;[from * 2, from * 2 + 1].filter(this.isUsable).forEach(s => [to * 2, to * 2 + 1].forEach(t => {
      const d = this.reachFrom(s).dist.get(t)
      if (d !== undefined && d < best) best = d
    }))
    return best
  }

  /**
   * The best tracks for a run of stations, by layers: fixed stops keep their track; then as few tracks as possible differ
   * from `current`, then as few turn backs as possible, then the cheapest. Turning back is leaving a stop the other way.
   */
  solveStops(layers: number[][], current: number[] | null, pins: Map<number, number> | null): Solution {
    const n = layers.length
    const allowed = layers.map((tracks, i) => (pins && pins.has(i) ? tracks.filter(p => p === pins.get(i)) : tracks))
    const change = (i: number, p: number) => (current && current[i] !== undefined && current[i] !== p ? 1 : 0)
    type Step = { t: number[]; from: number | null }
    const departures: Map<number, Step>[] = [new Map()], arrivals: (Map<number, Step> | null)[] = [null]
    allowed[0].forEach(p => [p * 2, p * 2 + 1].filter(this.isUsable).forEach(s => departures[0].set(s, { t: [change(0, p), 0, 0], from: null })))
    for (let i = 1; i < n; i++) {
      const arrive = new Map<number, Step>(), targets = allowed[i].flatMap(p => [p * 2, p * 2 + 1])
      departures[i - 1].forEach((v, s) => {
        const { dist } = this.reachFrom(s)
        targets.forEach(t => {
          const d = dist.get(t)
          if (d === undefined) return
          const tuple = [v.t[0] + change(i, t >> 1), v.t[1], v.t[2] + d]
          const old = arrive.get(t)
          if (!old || compareTuples(tuple, old.t) < 0) arrive.set(t, { t: tuple, from: s })
        })
      })
      arrivals.push(arrive)
      const depart = new Map<number, Step>()
      if (i < n - 1) arrive.forEach((v, t) => [t, t ^ 1].filter(this.isUsable).forEach(s => {
        const tuple = s === t ? v.t : [v.t[0], v.t[1] + 1, v.t[2]]
        const old = depart.get(s)
        if (!old || compareTuples(tuple, old.t) < 0) depart.set(s, { t: tuple, from: t })
      }))
      departures.push(depart)
    }

    // Which tracks some way through all the stops can use, from both ends
    const feasible = layers.map(() => new Set<number>())
    let good = n > 1 ? new Set(arrivals[n - 1]!.keys()) : new Set(departures[0].keys())
    good.forEach(t => feasible[n - 1].add(t >> 1))
    for (let i = n - 2; i >= 0; i--) {
      const goodDepartures = new Set([...departures[i].keys()].filter(s => [...good].some(t => this.reachFrom(s).dist.has(t))))
      good = i === 0 ? goodDepartures : new Set([...arrivals[i]!.keys()].filter(t => goodDepartures.has(t) || goodDepartures.has(t ^ 1)))
      good.forEach(s => feasible[i].add(s >> 1))
    }

    const last = n > 1 ? arrivals[n - 1]! : departures[0]
    let best: [number, Step] | null = null
    last.forEach((v, t) => { if (!best || compareTuples(v.t, best[1].t) < 0) best = [t, v] })
    if (!best) return { ok: false, feasible }
    const picks = new Array<number>(n), arrive = new Array<number>(n), depart = new Array<number>(n), legs = new Array<number>(n).fill(0)
    let t = (best as [number, Step])[0]
    for (let i = n - 1; i >= 1; i--) {
      const entry = arrivals[i]!.get(t)!
      arrive[i] = t
      picks[i] = t >> 1
      const s = entry.from!
      legs[i - 1] = this.reachFrom(s).dist.get(t)!
      depart[i - 1] = s
      t = i - 1 > 0 ? departures[i - 1].get(s)!.from! : s
    }
    picks[0] = (n > 1 ? depart[0] : (best as [number, Step])[0]) >> 1
    const reversals: number[] = []
    for (let i = 1; i < n - 1; i++) if (depart[i] !== arrive[i]) reversals.push(i)
    return { ok: true, picks, reversals, legs, feasible, arrive, depart }
  }

  /**
   * The way along the rails from one station to another, through stations in between in order: the shortest at each
   * part, turning back at a station on the way only when there is no other way.
   *
   * @param stages lists of tracks: where to start, each station to go through, and where to end
   * @return the tracks of every station passed, in order, or null if there is no way
   */
  findCorridor(stages: number[][]): number[] | null {
    let sources = new Map<number, { cost: number; states: number[] }>()
    stages[0].forEach(p => [p * 2, p * 2 + 1].filter(this.isUsable).forEach(s => sources.set(s, { cost: 0, states: [s] })))
    for (let k = 1; k < stages.length; k++) {
      const targets = stages[k].flatMap(p => [p * 2, p * 2 + 1])
      let found: { cost: number; states: number[]; end: number } | null = null
      sources.forEach((v, s) => {
        const { dist, prev } = this.reachFrom(s)
        targets.forEach(t => {
          const d = dist.get(t)
          if (d === undefined || (found && v.cost + d >= found.cost)) return
          const between: number[] = []
          for (let x: number | undefined = t; x !== s && x !== undefined; x = prev.get(x)) between.unshift(x)
          found = { cost: v.cost + d, states: [...v.states, ...between], end: t }
        })
      })
      if (!found) return null
      const f = found as { cost: number; states: number[]; end: number }
      sources = new Map([[f.end, f]])
      // Turning back at a station on the way costs a lot, so it is only taken when nothing else gets there
      if (k < stages.length - 1 && this.isUsable(f.end ^ 1)) sources.set(f.end ^ 1, { cost: f.cost + 1e7, states: f.states })
    }
    const way = [...sources.values()][0].states
    const tracks: number[] = []
    way.forEach(s => {
      const p = s >> 1
      if (!this.platforms[p].station) return
      if (tracks.length && this.platforms[tracks[tracks.length - 1]].station === this.platforms[p].station) return
      tracks.push(p)
    })
    return tracks
  }

  /**
   * Puts each station's tracks in the order they lie side by side, across the way from the station before to the station
   * after, the leftmost first. Platform names are whatever players typed, so they don't say where a track is.
   */
  orderByLayout(layers: number[][]) {
    const middle = (p: number) => { const line = this.platforms[p].line; return line ? [(line[0] + line[2]) / 2, (line[1] + line[3]) / 2] : null }
    const centres = layers.map(layer => {
      const points = layer.map(middle).filter(Boolean) as number[][]
      return points.length ? [points.reduce((a, p) => a + p[0], 0) / points.length, points.reduce((a, p) => a + p[1], 0) / points.length] : null
    })
    return layers.map((layer, i) => {
      const centre = centres[i]
      if (!centre || layer.length < 2) return layer
      const before = centres.slice(0, i).reverse().find(Boolean) || centre, after = centres.slice(i + 1).find(Boolean) || centre
      let dx = after[0] - before[0], dz = after[1] - before[1]
      if (Math.hypot(dx, dz) < 1e-6) {
        const line = this.platforms[layer[0]].line
        if (!line) return layer
        dx = line[2] - line[0]
        dz = line[3] - line[1]
      }
      // With x east and z south, left of the way along has a negative cross product
      const left = (p: number) => { const point = middle(p); return point ? -(dx * (point[1] - centre[1]) - dz * (point[0] - centre[0])) : 0 }
      return layer.slice().sort((a, b) => left(b) - left(a) || this.platformLabel(a).localeCompare(this.platformLabel(b), undefined, { numeric: true }))
    })
  }

  /** A picker model for stations given as tracks, offering the other tracks of each station on the same rails */
  model(tracks: number[], stop: boolean[], color: string): StopsModel {
    return { layers: this.orderByLayout(tracks.map(p => this.stationTracks(p))), picks: tracks.slice(), stop: stop.slice(), pins: new Map(), color, conflict: "" }
  }

  /** "6 站，停 5 站，通过 1 站：…" */
  stopsSummary(model: StopsModel) {
    const n = model.layers.length
    const passed = model.layers.filter((_, i) => !model.stop[i] && i > 0 && i < n - 1).map(tracks => this.stationLabel(tracks[0]))
    return tr("{0} 站，停 {1} 站{2}", n, n - passed.length, passed.length ? tr("，通过 {0} 站：{1}", passed.length, passed.join("、")) : tr("，全部停车"))
  }

  /**
   * What is wrong with a route's tracks: a stop off the rails, not on the first stop's rails, no way between two stops,
   * turning back on the way, or a detour another track would save. Cached by the platforms.
   *
   * @param ids the platform of each stop, as the draft has them
   */
  routeProblems(r: { id: string; stops: { name: string }[] }, ids: string[]): Problem[] {
    const key = ids.join(",")
    const cached = this.problems.get(r.id)
    if (cached && cached.key === key) return cached.problems
    const problems: Problem[] = []
    const tracks = ids.map(id => (this.index.has(id) ? this.index.get(id)! : -1))
    const name = (i: number) => shortName(r.stops[i].name) || tr("第 {0} 站", i + 1)
    tracks.forEach((p, i) => { if (p < 0) problems.push({ stop: i, level: "error", text: tr("{0}的站台没有轨道", name(i)) }) })
    if (!problems.length && tracks.length > 1) {
      const network = this.platforms[tracks[0]].network
      tracks.forEach((p, i) => { if (this.platforms[p].network !== network) problems.push({ stop: i, level: "error", text: tr("{0}的站台和首站不在同一个线网里（轨道没有连起来）", name(i)) }) })
    }
    if (!problems.length && tracks.length > 1) {
      const solution = this.solveStops(tracks.map(p => [p]), null, null)
      if (!solution.ok) {
        for (let i = 0; i + 1 < tracks.length; i++) {
          if (this.legCost(tracks[i], tracks[i + 1]) === Infinity) problems.push({ stop: i + 1, level: "error", text: tr("{0}到{1}沿轨道走不通", name(i), name(i + 1)) })
        }
        if (!problems.length) problems.push({ stop: 0, level: "error", text: tr("各站单独走得通，连起来要在中途反复掉头才行") })
      } else {
        solution.reversals.forEach(i => problems.push({ stop: i, level: "warn", text: tr("在{0}掉头", name(i)) }))
        for (let i = 0; i + 1 < tracks.length; i++) {
          const best = Math.min(...this.stationTracks(tracks[i]).flatMap(p => this.stationTracks(tracks[i + 1]).map(q => this.legCost(p, q))))
          if (isFinite(best) && solution.legs[i] > best * 1.5 + 50) problems.push({ stop: i + 1, level: "warn", text: tr("{0}到{1}绕路：换股可以近得多", name(i), name(i + 1)) })
        }
      }
    }
    this.problems.set(r.id, { key, problems })
    return problems
  }
}

// One per topology the game sent: its caches live as long as it does
const made = new WeakMap<object, Topology>()
export function topologyOf(source: TopologySource | null | undefined): Topology {
  if (!source) return new Topology(null)
  let topology = made.get(source)
  if (!topology) made.set(source, (topology = new Topology(source)))
  return topology
}
