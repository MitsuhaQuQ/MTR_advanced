/**
 * A station's own track diagram (the old code's stationDiagramHtml, loadDiagram and turn platforms; replaces the old
 * stops dialog's station part): the rails around the station as the game has them (api/layout, EditorLayout), turned so
 * the train runs left to right along the track picked, the view set by the station's own tracks, moved and zoomed. And
 * the platforms a route turns back at or would rather start from, in the draft.
 */
import { draftEntry } from "./timetable"

/** api/layout's answer: the rails as sampled points, turnouts, and the platforms in the box, all in world x, z */
export type LayoutData = {
  ready?: boolean; box: number[]; rails: number[][]; turnouts: number[][]; truncated?: boolean
  platforms: { id: string; name: string; station: string; stationName: string; passingTrack?: boolean; line: number[] }[]
}
export type Point = [number, number]
export type StationView = {
  /** minX, minY, width, height in turned world units */
  viewBox: [number, number, number, number]
  width: number; height: number
  /** World units per pixel at the drawn size */
  unit: number
  rails: Point[][]
  turnouts: Point[]
  platforms: { platform: LayoutData["platforms"][number]; a: Point; b: Point; ours: boolean; start: Point }[]
}

/**
 * The view of a station's diagram
 *
 * @param station the station's ID
 * @param tracks the IDs of the station's tracks on the route's rails, the ones that can be picked
 * @param pickedId the track picked, whose direction the view is turned to
 * @param forwards whether the train runs along the picked track's line from its first end (arriving, or leaving at the first stop)
 * @param margin the blocks shown beyond the station's tracks
 * @param pan the view moved by, in turned world units
 * @param frame what sets the view: the stop's tracks on the route's rails (the new page), or every platform of the station
 * as the old dialog had it, where a platform of another line far off at the same station made the route's tracks small
 */
export function stationView(data: LayoutData, station: string, tracks: Set<string>, pickedId: string, forwards: boolean, margin: number, pan: Point, frame: "tracks" | "station" = "tracks"): StationView {
  const picked = data.platforms.find(p => p.id === pickedId)
  let dx = 1, dz = 0
  const own = data.platforms.filter(p => p.station === station)
  if (picked) {
    dx = (picked.line[2] - picked.line[0]) * (forwards ? 1 : -1)
    dz = (picked.line[3] - picked.line[1]) * (forwards ? 1 : -1)
  } else if (own.length) {
    dx = own[0].line[2] - own[0].line[0]
    dz = own[0].line[3] - own[0].line[1]
  }
  const length = Math.hypot(dx, dz) || 1, cos = dx / length, sin = dz / length
  const cx = (data.box[0] + data.box[2]) / 2, cz = (data.box[1] + data.box[3]) / 2
  const turn = (x: number, z: number): Point => [(x - cx) * cos + (z - cz) * sin, -(x - cx) * sin + (z - cz) * cos]
  // The station's own tracks set the view, so the rails around it don't push it into a corner
  const framing = frame === "tracks" && data.platforms.some(p => tracks.has(p.id)) ? data.platforms.filter(p => tracks.has(p.id)) : own
  const ownPoints = framing.flatMap(p => [turn(p.line[0], p.line[1]), turn(p.line[2], p.line[3])])
  const fallback = [[data.box[0], data.box[1]], [data.box[2], data.box[3]]].map(([x, z]) => turn(x, z))
  const points = ownPoints.length ? ownPoints : fallback
  const minX = Math.min(...points.map(p => p[0])) - margin + pan[0], maxX = Math.max(...points.map(p => p[0])) + margin + pan[0]
  const minY = Math.min(...points.map(p => p[1])) - margin / 2 + pan[1], maxY = Math.max(...points.map(p => p[1])) + margin / 2 + pan[1]
  const viewW = maxX - minX, viewH = Math.max(maxY - minY, viewW * 0.3)
  const width = 560, height = Math.round(Math.min(360, Math.max(160, (width * viewH) / viewW)))
  const unit = Math.max(viewW / width, viewH / height)
  const rails = data.rails.map(r => {
    const line: Point[] = []
    for (let k = 0; k + 1 < r.length; k += 2) line.push(turn(r[k], r[k + 1]))
    return line
  })
  const platforms = data.platforms.map(platform => {
    const a = turn(platform.line[0], platform.line[1]), b = turn(platform.line[2], platform.line[3])
    // The name goes before the left end, where tracks side by side don't crowd it
    return { platform, a, b, ours: tracks.has(platform.id), start: a[0] <= b[0] ? a : b }
  })
  return { viewBox: [minX, minY, viewW, viewH], width, height, unit, rails, turnouts: data.turnouts.map(t => turn(t[0], t[1])), platforms }
}

/** The blocks shown beyond a station's tracks after zooming in or out */
export const zoomMargin = (margin: number, zoomIn: boolean) => Math.max(16, Math.min(256, Math.round(margin * (zoomIn ? 0.6 : 1.6))))

export type TurnKind = "terminalPlatforms" | "startPlatforms"
/** A route's platforms to turn back at (last stop) or start from (first stop), as drafted or as the game has them */
export const turnOf = (draft: any, r: any, kind: TurnKind): string[] => (draft.routes[r.id] && draft.routes[r.id][kind] ? draft.routes[r.id][kind] : r[kind] || [])

/** Adds a platform to the end of one of these lists, or takes it out, in the draft (the caller tidies and saves) */
export function toggleTurnPlatform(draft: any, r: any, kind: TurnKind, platformId: string) {
  const entry = draftEntry(draft, r)
  const list = turnOf(draft, r, kind).filter(id => id !== platformId)
  if (list.length === turnOf(draft, r, kind).length) list.push(platformId)
  const original: string[] = r[kind] || []
  if (list.length === original.length && list.every((id, k) => id === original[k])) delete entry[kind]
  else entry[kind] = list
  if (!entry.conflict) delete entry.error
}
