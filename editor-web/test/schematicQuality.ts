// Measures how faithfully the schematic (orthogonalTracks) keeps what the real drawing (trackSchematic) shows, so a change
// to the layout can be judged on real networks rather than by eye. Used by schematicQuality.test.ts and the gallery.
import type { DrawRail, Point, TrackMap } from "../src/core/trackSchematic"

/** Tracks closer than this (drawing units, 4 per block when tracks are close) are taken as running side by side */
export const BESIDE = 28
/** A part shorter than this between two others is a step (about four blocks where tracks are close) */
export const STEP = 16

const segmentDistance = (p: Point, a: Point, b: Point) => {
  const dx = b[0] - a[0], dy = b[1] - a[1], square = dx * dx + dy * dy
  const t = square ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / square)) : 0
  return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)
}

export const polylineDistance = (p: Point, points: Point[]) => {
  let best = Infinity
  for (let i = 1; i < points.length; i++) best = Math.min(best, segmentDistance(p, points[i - 1], points[i]))
  return points.length === 1 ? Math.hypot(p[0] - points[0][0], p[1] - points[0][1]) : best
}

export const polylineLength = (points: Point[]) => points.slice(1).reduce((n, p, i) => n + Math.hypot(p[0] - points[i][0], p[1] - points[i][1]), 0)

/** Points at fractions of the way along a polyline */
export function sample(points: Point[], fractions: number[]): Point[] {
  const total = polylineLength(points)
  return fractions.map(f => {
    let remain = f * total
    for (let i = 1; i < points.length; i++) {
      const len = Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
      if (remain <= len && len > 0) {
        const t = remain / len
        return [points[i - 1][0] + (points[i][0] - points[i - 1][0]) * t, points[i - 1][1] + (points[i][1] - points[i - 1][1]) * t] as Point
      }
      remain -= len
    }
    return points[points.length - 1]
  })
}

// The middle of a rail, away from the switches at its ends where tracks meet anyway
const INNER = [0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85]

export type BesidePair = {
  rail: number
  /** The rails it runs beside in the real drawing */
  beside: number[]
  /** Its greatest distance from them in the real drawing and in the schematic */
  real: number
  schematic: number
}

export type Quality = {
  /** Rails running beside others in the real drawing */
  besidePairs: number
  /** Those drawn apart in the schematic: the "box" problem, two parallel tracks bending different ways */
  apart: BesidePair[]
  /** Pairs of rails crossing in the schematic that do not cross in the real drawing */
  newCrossings: [number, number][]
  /** Segments that are not horizontal, vertical or 45 degrees */
  offAngle: number
  /** Rails with a point that is not a finite number (they are not drawn at all) */
  broken: number[]
  /** Short parts in the middle of a rail's drawing: the little right-angle steps of a staircase */
  steps: number
}

const shareNode = (a: DrawRail, b: DrawRail) => a.nodes.some(n => b.nodes.includes(n))

/** Whether two polylines cross away from their ends */
function crosses(p: Point[], q: Point[]) {
  const cross = (a: Point, b: Point, c: Point) => (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
  for (let i = 1; i < p.length; i++) for (let j = 1; j < q.length; j++) {
    const a = p[i - 1], b = p[i], c = q[j - 1], d = q[j]
    const d1 = cross(c, d, a), d2 = cross(c, d, b), d3 = cross(a, b, c), d4 = cross(a, b, d)
    if (((d1 > 1e-6 && d2 < -1e-6) || (d1 < -1e-6 && d2 > 1e-6)) && ((d3 > 1e-6 && d4 < -1e-6) || (d3 < -1e-6 && d4 > 1e-6))) return true
  }
  return false
}

export function measure(real: TrackMap, schematic: TrackMap): Quality {
  const lines = real.rails.map((r, i) => ({ i, r })).filter(({ r }) => r.kind !== 1)
  const apart: BesidePair[] = []
  let besidePairs = 0
  for (const { i, r } of lines) {
    if (polylineLength(r.points) < 60) continue
    const samples = sample(r.points, INNER)
    const beside = lines.filter(o => o.i !== i && !shareNode(r, o.r) && samples.some(p => polylineDistance(p, o.r.points) < BESIDE)).map(o => o.i)
    if (!beside.length) continue
    const nearest = (p: Point, map: TrackMap) => Math.min(...beside.map(j => polylineDistance(p, map.rails[j].points)))
    const realDistances = samples.map(p => nearest(p, real))
    if (realDistances.some(d => d >= BESIDE)) continue
    besidePairs++
    const realMax = Math.max(...realDistances)
    const schematicMax = Math.max(...sample(schematic.rails[i].points, INNER).map(p => nearest(p, schematic)))
    if (schematicMax > Math.max(realMax * 2.5, 40)) apart.push({ rail: i, beside, real: Math.round(realMax), schematic: Math.round(schematicMax) })
  }
  const newCrossings: [number, number][] = []
  for (let i = 0; i < real.rails.length; i++) for (let j = i + 1; j < real.rails.length; j++) {
    const a = real.rails[i], b = real.rails[j]
    if (shareNode(a, b)) continue
    if (crosses(schematic.rails[i].points, schematic.rails[j].points) && !crosses(a.points, b.points)) newCrossings.push([i, j])
  }
  let offAngle = 0
  for (const r of schematic.rails) for (let k = 1; k < r.points.length; k++) {
    const dx = Math.abs(r.points[k][0] - r.points[k - 1][0]), dy = Math.abs(r.points[k][1] - r.points[k - 1][1])
    if (Math.min(dx, dy, Math.abs(dx - dy)) > 1e-6) offAngle++
  }
  const broken = schematic.rails.flatMap((r, i) => r.points.every(p => Number.isFinite(p[0]) && Number.isFinite(p[1])) ? [] : [i])
  let steps = 0
  for (const r of schematic.rails) for (let k = 2; k < r.points.length - 1; k++) {
    if (Math.hypot(r.points[k][0] - r.points[k - 1][0], r.points[k][1] - r.points[k - 1][1]) < STEP) steps++
  }
  return { besidePairs, apart, newCrossings, offAngle, broken, steps }
}

/** An SVG of a drawing, rails in their first route colour (grey without), platforms thicker, problem rails marked red. */
export function svgOf(map: TrackMap, title: string, marked: Set<number> = new Set()): string {
  const parts = [`<svg xmlns="http://www.w3.org/2000/svg" width="${Math.ceil(map.width)}" height="${Math.ceil(map.height) + 30}" viewBox="0 -30 ${Math.ceil(map.width)} ${Math.ceil(map.height) + 30}" style="background:#fff;font-family:sans-serif">`,
    `<text x="10" y="-10" font-size="16">${title}</text>`]
  map.rails.forEach((r, i) => {
    const color = marked.has(i) ? "#e00000" : r.colors[0] || "#9e9e9e"
    const width = r.kind === 1 ? 6 : marked.has(i) ? 3.5 : 2.5
    parts.push(`<polyline points="${r.points.map(p => p.map(v => v.toFixed(1)).join(",")).join(" ")}" fill="none" stroke="${color}" stroke-width="${width}" stroke-linejoin="round"${r.kind === 2 || r.kind === 3 ? ' stroke-dasharray="4 3"' : ""}/>`)
  })
  map.junctions.forEach(p => parts.push(`<circle cx="${p[0].toFixed(1)}" cy="${p[1].toFixed(1)}" r="2.5" fill="#222"/>`))
  map.stations.forEach(s => parts.push(`<text x="${s.x.toFixed(1)}" y="${s.y.toFixed(1)}" font-size="12" text-anchor="${s.vertical ? "start" : "middle"}">${s.name.split("|")[0]}</text>`))
  parts.push("</svg>")
  return parts.join("")
}
