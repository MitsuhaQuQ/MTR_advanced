/**
 * The running diagram's geometry, apart from drawing it (from the old page's computeLayout, axisSteps, clampView, draw,
 * hitService and hitHandle): where each station's row lies, the time axis, each service's line through its stops, how it
 * is drawn (colour, width, strength), where its dwell handles are, and which service or handle is under the mouse.
 * test/diagram.test.ts checks it against the old code drawing the same day.
 */

export const LABEL_W = 120, AXIS_H = 28, MIN_ROW_GAP = 22, PAD_BOTTOM = 30, DEPOT_ROW_ROOM = 26
export const DAY = 86400000
const mod = (a: number, b: number) => ((a % b) + b) % b

export type View = { viewStart: number; pxPerMin: number; scrollY: number; width: number; height: number }

/** The x of a time, and the time at an x */
export const xOf = (view: View, millis: number) => LABEL_W + (millis - view.viewStart) / 60000 * view.pxPerMin
export const millisAt = (view: View, x: number) => view.viewStart + (x - LABEL_W) / view.pxPerMin * 60000

/** Each row's y on the whole diagram (before scrolling), spread over the height by the rows' gaps */
export function layoutRows(gaps: number[], height: number, depotRoom: number) {
  const totalGap = gaps.reduce((a, gap) => a + gap, 0) || 1
  const unit = Math.max(MIN_ROW_GAP, (height - AXIS_H - PAD_BOTTOM - 12 - depotRoom * 2) / totalGap)
  let y = AXIS_H + 12 + depotRoom
  const ys = gaps.map(gap => (y += gap * unit))
  return { ys, contentHeight: y + PAD_BOTTOM + depotRoom }
}

/** The time axis at a zoom: lines as close as ten pixels apart, times at least 44 pixels apart, both on round times */
export function axisSteps(pxPerMin: number) {
  const px = (millis: number) => millis / 60000 * pxPerMin
  const grid = [10000, 30000, 60000, 300000, 600000, 1800000, 3600000].find(step => px(step) >= 10) || 3600000
  const label = [10000, 30000, 60000, 300000, 600000, 1800000, 3600000, 7200000, 10800000].find(step => step >= grid && px(step) >= 44) || 10800000
  return { grid, label }
}

/** The axis lines in view: where, whether on the hour (by the clock: the day's start and offset added), whether labelled */
export function axisTicks(view: View, clockOffset: number) {
  const axis = axisSteps(view.pxPerMin)
  const ticks: { millis: number; x: number; hour: boolean; labelled: boolean; seconds: boolean }[] = []
  for (let millis = Math.floor(view.viewStart / axis.grid) * axis.grid; xOf(view, millis) < view.width; millis += axis.grid) {
    const x = xOf(view, millis)
    if (x < LABEL_W) continue
    ticks.push({ millis, x, hour: mod(millis + clockOffset, DAY) % 3600000 === 0, labelled: mod(millis + clockOffset, axis.label) === 0, seconds: axis.label < 60000 })
  }
  return ticks
}

/** The view kept on the day (an hour either side) and the rows */
export function clampView(view: View, contentHeight: number | null): View {
  const visible = (view.width - LABEL_W) / view.pxPerMin * 60000
  return {
    ...view,
    viewStart: Math.min(Math.max(view.viewStart, -3600000), DAY + 6 * 3600000 - visible),
    scrollY: Math.min(Math.max(view.scrollY, 0), contentHeight !== null ? Math.max(0, contentHeight - view.height) : 0),
  }
}

export type Point = [number, number] | [number, number, boolean]
type RouteRows = { rows: number[]; leaveRows: number[]; stops: unknown[] }
type ServiceIn = { route: RouteRows; deployOrder?: { stop: number } | null; returnOrder?: { stop: number } | null }

/** The stops a service runs between: from where it comes out of a depot, to where it goes back */
export const servedStops = (s: ServiceIn) => [s.deployOrder ? s.deployOrder.stop : 0, s.returnOrder ? Math.min(s.returnOrder.stop, s.route.stops.length - 1) : s.route.stops.length - 1]

/**
 * A service's line: arriving at each stop's row and leaving it after its dwell; a stop going on in another section of
 * the diagram jumps straight across to the row there (the point marked true, drawn dashed).
 *
 * @param times [arrival, departure] at each stop
 * @param yOfRow the y of a row on screen
 */
export function servicePath(s: ServiceIn, times: number[][], x: (millis: number) => number, yOfRow: (row: number) => number): Point[] {
  const points: Point[] = []
  const [firstServed, lastServed] = servedStops(s)
  s.route.rows.forEach((row, i) => {
    if (i < firstServed || i > lastServed) return
    const leaveRow = s.route.leaveRows[i]
    points.push([x(times[i][0]), yOfRow(row)])
    if (leaveRow !== row) points.push([x(times[i][0]), yOfRow(leaveRow), true])
    if (times[i][1] !== times[i][0] && i < lastServed) points.push([x(times[i][1]), yOfRow(leaveRow)])
  })
  return points
}

/** Whether a service's times put it anywhere near the view */
export const inView = (times: number[][], x: (millis: number) => number, width: number) => !(x(times[times.length - 1][1]) < LABEL_W - 40 || x(times[0][0]) > width + 40)

export type Look = { color: "route" | "muted" | "warn"; width: number; alpha: number }
/**
 * How a service is drawn: in its route's colour, a little wider when a train runs it; thin and grey (red when picked
 * out) when no train of the depots running it by duties does; faint without times; faint and grey when not running;
 * grey when no train ran it in the simulated day; faded when another route is chosen. By colour and strength, not dashes.
 */
export function serviceLook(f: { selected: boolean; dim: boolean; noTrain: boolean; covered: boolean; hasTimes: boolean; suspended: boolean; notRun: boolean; showNoTrain: boolean }): Look {
  let alpha = f.dim ? 0.25 : 1
  let color: Look["color"] = f.notRun ? "muted" : f.noTrain ? (f.showNoTrain ? "warn" : "muted") : "route"
  const width = f.selected ? 2.5 : f.noTrain ? 1 : f.covered ? 2 : 1.5
  if (!f.hasTimes) alpha = Math.min(alpha, 0.5)
  if (f.suspended) { color = "muted"; alpha = Math.min(alpha, 0.45) }
  if (f.noTrain && !f.showNoTrain) alpha = Math.min(alpha, 0.35)
  if (f.covered && f.showNoTrain) alpha = Math.min(alpha, 0.25)
  return { color, width, alpha }
}

export function segmentDistance(x: number, y: number, [x1, y1]: Point, [x2, y2]: Point) {
  const dx = x2 - x1, dy = y2 - y1, length = dx * dx + dy * dy
  const t = length ? Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / length)) : 0
  return Math.hypot(x - x1 - t * dx, y - y1 - t * dy)
}

/** The drawn service nearest the mouse, within 6 pixels; the last drawn wins a tie, as on the old page */
export function hitPath<T>(drawn: { s: T; points: Point[] }[], x: number, y: number): T | null {
  let best: T | null = null, bestDistance = 6
  drawn.forEach(d => {
    for (let i = 1; i < d.points.length; i++) {
      const distance = segmentDistance(x, y, d.points[i - 1], d.points[i])
      if (distance < bestDistance) { bestDistance = distance; best = d.s }
    }
  })
  return best
}

/** The dwell handles of a selected service: the end of each stop it stops at but the first and last */
export function dwellHandles<T extends { plans: { passes?: boolean }[]; route: { leaveRows: number[] } }>(s: T, times: number[][], x: (millis: number) => number, yOfRow: (row: number) => number) {
  const handles: { x: number; y: number; s: T; i: number }[] = []
  times.forEach((t, i) => {
    if (i === 0 || i === times.length - 1 || s.plans[i].passes) return
    handles.push({ x: x(t[1]), y: yOfRow(s.route.leaveRows[i]), s, i })
  })
  return handles
}
export const hitHandle = <H extends { x: number; y: number }>(handles: H[], x: number, y: number) => handles.find(h => Math.abs(h.x - x) < 7 && Math.abs(h.y - y) < 7)
