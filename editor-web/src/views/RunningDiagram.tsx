/**
 * The running diagram, drawn by the new page (rewrite step 2): the geometry from core/diagram.ts (checked against the old
 * page in test/diagram.test.ts), drawn here in layers as the old draw() did: the time axis and the time now, the station
 * rows, other lines' services on this line's track, the services (selected, the chosen train's, no train, suspended,
 * simulated), orders to and from depots, through links and returns, loop laps, dwell handles, the chosen train's moves,
 * turn backs at terminals, the depots' rows and their runs out and back, the simulated day, then the station names.
 *
 * Its pointer handling is the old canvas's: drag a dwell handle, a run out of a depot, services along the time axis or the
 * view; click to choose (twice for the route, Shift for a range, Ctrl to add, into the chosen train's duty when one is
 * chosen); right-click for later services or a run's menu; the wheel zooms, Shift-wheel scrolls. What changes is done by
 * the old functions (moveServices, editTimetable, setStopException, editDutyException, toggleInSlot …).
 *
 * The old canvas's box (#canvas-wrap) stays in the layout under this one, its canvas hidden, so the old functions that
 * measure it (centring on a service, zooming from the tool bar) keep working.
 */
import { useCallback, useEffect, useRef } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import * as D from "../core/diagram"
import { tr } from "../i18n"
import { clock, colorOf, currentLine, durationText, findService, mod, orderDepotName, shortName, timesOf } from "./now"

type Drawn = { s: any; points: D.Point[] }
type Leg = { a: number[]; b: number[]; kind: "out" | "back"; depot: string; siding: string; index: number; text: string }
type Drag =
  | { handle: { x: number; y: number; s: any; i: number }; moved: boolean }
  | { deadhead: { depot: string; siding: string; index: number; shift: number }; x: number; y: number; moved: boolean; leg: Leg }
  | { move: string[]; kept: number; x: number; y: number; moved: boolean }
  | { pan: true; x: number; y: number; viewStart: number; scrollY: number; moved: boolean }

export function RunningDiagram() {
  const version = useLegacy()
  const box = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  // What was drawn last, for finding what is under the mouse
  const hits = useRef<{ drawn: Drawn[]; handles: { x: number; y: number; s: any; i: number }[]; legs: Leg[]; access: Drawn[]; view: D.View | null }>({ drawn: [], handles: [], legs: [], access: [], view: null })
  const drag = useRef<Drag | null>(null)
  const move = useRef<{ ids: string[]; shift: number } | null>(null)
  const fine = useRef(false)

  const paint = useCallback(() => {
    const element = canvas.current, wrap = box.current
    if (!element || !wrap) return
    const width = wrap.clientWidth, height = wrap.clientHeight
    const ratio = devicePixelRatio || 1
    if (element.width !== Math.max(1, width * ratio) || element.height !== Math.max(1, height * ratio)) {
      element.width = Math.max(1, width * ratio)
      element.height = Math.max(1, height * ratio)
    }
    element.style.width = width + "px"
    element.style.height = height + "px"
    const g = element.getContext("2d")
    if (!g) return
    g.setTransform(ratio, 0, 0, ratio, 0, 0)
    hits.current = draw(g, width, height, move.current, drag.current)
  }, [])

  // Drawn again whenever the old code draws, the box changes size, and every half minute for the line of the time now
  useEffect(() => { paint() }, [version, paint])
  useEffect(() => {
    if (!box.current) return
    const observer = new ResizeObserver(() => paint())
    observer.observe(box.current)
    const timer = setInterval(paint, 30000)
    return () => { observer.disconnect(); clearInterval(timer) }
  }, [paint])

  const at = (e: { clientX: number; clientY: number }) => {
    const rect = canvas.current!.getBoundingClientRect()
    return [e.clientX - rect.left, e.clientY - rect.top]
  }
  const { fn, v } = legacy
  const hitService = (x: number, y: number) => D.hitPath(hits.current.drawn, x, y)
  const hitLeg = (x: number, y: number) => {
    let best: Leg | null = null, bestDistance = 6
    hits.current.legs.forEach(leg => { const d = D.segmentDistance(x, y, leg.a as D.Point, leg.b as D.Point); if (d < bestDistance) { bestDistance = d; best = leg } })
    return best as Leg | null
  }
  const hitAccess = (x: number, y: number) => {
    let best: any = null, bestDistance = 5
    hits.current.access.forEach(d => { for (let i = 1; i < d.points.length; i++) { const dist = D.segmentDistance(x, y, d.points[i - 1], d.points[i]); if (dist < bestDistance) { bestDistance = dist; best = d.s } } })
    return best
  }
  /** How far a drag moves services, on round times by the zoom (old moveShift) */
  const moveShift = (dx: number, isFine: boolean) => {
    const px = v.state.pxPerMin
    const step = isFine || px >= 32 ? 1000 : px >= 8 ? 5000 : px >= 3 ? 15000 : px >= 1 ? 60000 : 300000
    return Math.round(dx / px * 60000 / step) * step
  }
  const view = () => hits.current.view || { viewStart: v.state.viewStart, pxPerMin: v.state.pxPerMin, scrollY: v.state.scrollY, width: box.current?.clientWidth || 800, height: box.current?.clientHeight || 600 }
  /** A stop's dwell from where its handle is dragged to, as the old dragDwell */
  const dragDwell = (handle: { s: any; i: number }, x: number) => {
    const s = handle.s, i = handle.i, arrival = timesOf(s)[i][0]
    const step = fine.current || v.state.pxPerMin >= 32 ? 1 : 5
    const dwell = Math.max(0, Math.round((D.millisAt(view(), x) - arrival) / 1000 / step) * step)
    fn.editTimetable(s.route, (timetable: any) => {
      const table = timetable.tables[s.tableIndex]
      if (v.state.selectedRoute === s.route.id) table.stops[i].departure = Math.round(s.tablePlans[i].arrival / 1000) + dwell
      else fn.setStopException(table, s.service, i, { dwell })
    })
  }
  const zoomAt = (direction: number, x: number) => {
    const zooms: number[] = v.ZOOMS, state = v.state
    const next = zooms[Math.max(0, Math.min(zooms.length - 1, zooms.indexOf(state.pxPerMin) + direction))]
    if (next === state.pxPerMin) return
    const anchor = D.millisAt(view(), x)
    state.pxPerMin = next
    state.viewStart = anchor - (x - D.LABEL_W) / next * 60000
    paint()
  }
  const setCursor = (cursor: string) => { if (canvas.current) canvas.current.style.cursor = cursor }

  const onPointerDown = (e: React.PointerEvent) => {
    const data = v.data, state = v.state
    if (!data || e.button !== 0) return
    const [x, y] = at(e)
    const handle = D.hitHandle(hits.current.handles, x, y)
    const s = !handle && hitService(x, y)
    const ids: string[] | null = s ? fn.servicesToMove(s) : null
    const chosen = !ids ? 0 : fn.inRange(s) ? state.range.ids.length : state.selectedRoute === s.route.id ? s.route.services.filter((o: any) => o.tableIndex === s.tableIndex).length : 1
    fine.current = e.altKey
    const leg = !handle && !s && data.canEdit && !state.slot ? hitLeg(x, y) : null
    if (leg && leg.kind === "out") drag.current = { deadhead: { depot: leg.depot, siding: leg.siding, index: leg.index, shift: 0 }, x: e.clientX, y: e.clientY, moved: false, leg }
    else drag.current = handle ? { handle, moved: false } : ids ? { move: ids, kept: chosen - ids.length, x: e.clientX, y: e.clientY, moved: false } : { pan: true, x: e.clientX, y: e.clientY, viewStart: state.viewStart, scrollY: state.scrollY, moved: false }
    canvas.current!.setPointerCapture(e.pointerId)
  }
  const onPointerMove = (e: React.PointerEvent) => {
    const d = drag.current, state = v.state
    const [x, y] = at(e)
    if (d && "handle" in d) {
      d.moved = true
      fine.current = e.altKey
      setCursor("grabbing")
      dragDwell(d.handle, x)
      return
    }
    if (d && "deadhead" in d) {
      const dx = e.clientX - d.x
      if (Math.abs(dx) > 3) d.moved = true
      if (d.moved) {
        setCursor("grabbing")
        d.deadhead.shift = moveShift(dx, e.altKey)
        const shift = d.deadhead.shift
        fn.showDragTip(tr("出库 {0}{1}", shift >= 0 ? tr("晚 ") : tr("早 "), durationText(Math.abs(shift))), e.clientX, e.clientY)
        paint()
      }
      return
    }
    if (d && "move" in d) {
      const dx = e.clientX - d.x
      if (Math.abs(dx) > 3) d.moved = true
      if (d.moved) {
        setCursor("grabbing")
        move.current = { ids: d.move, shift: moveShift(dx, e.altKey) }
        const shift = move.current.shift
        fn.showDragTip(tr("{0} 班，{1}{2}", d.move.length, shift >= 0 ? "+" : "−", durationText(Math.abs(shift))) + (d.kept ? tr("（{0} 班单独编辑过，不动）", d.kept) : ""), e.clientX, e.clientY)
        paint()
      }
      return
    }
    if (d && "pan" in d) {
      const dx = e.clientX - d.x, dy = e.clientY - d.y
      if (Math.abs(dx) + Math.abs(dy) > 3) d.moved = true
      if (d.moved) {
        setCursor("grabbing")
        state.viewStart = d.viewStart - dx / state.pxPerMin * 60000
        state.scrollY = d.scrollY - dy
        fn.showTooltip(null)
        paint()
      }
      return
    }
    const s = hitService(x, y)
    setCursor(s || D.hitHandle(hits.current.handles, x, y) ? "pointer" : "default")
    const accessed = !s && hitAccess(x, y)
    fn.showTooltip(s || accessed, e.clientX, e.clientY, Boolean(accessed))
  }
  const onPointerUp = (e: React.PointerEvent) => {
    let d = drag.current
    const state = v.state
    setCursor("default")
    if (d && "deadhead" in d && d.moved) {
      const moved = d.deadhead
      drag.current = null
      fn.showDragTip(null)
      const depot = fn.depotById(moved.depot), siding = depot && depot.sidings.find((o: any) => o.id === moved.siding)
      const slot = siding && (fn.dutiesOf(depot).sidings[siding.id] || [])[moved.index]
      const day = slot && fn.dutyOn(slot, state.weekday)
      const out = day && fn.deadheadTimes(siding, day).out
      if (out && moved.shift) {
        fn.editDutyException(depot, siding.id, moved.index, (edited: any) => {
          // Leaving later is a shorter lead, never shorter than the way out takes
          edited.outLead = Math.max(out.wayOut, out.lead - moved.shift)
          edited.exceptions = (edited.exceptions || 0) | v.EXCEPTION.OUT_LEAD
          return tr("{0} 改为首班前 {1} 出库（这份运用的例外）", out.number, durationText(edited.outLead))
        })
      } else paint()
      return
    }
    // A click on the run out: as a click on a depot's run, choosing its duty
    if (d && "deadhead" in d) d = drag.current = { pan: true, x: d.x, y: d.y, viewStart: state.viewStart, scrollY: state.scrollY, moved: false }
    if (d && "move" in d && d.moved) {
      const moving = move.current
      move.current = null
      drag.current = null
      fn.showDragTip(null)
      if (moving) fn.moveServices(moving.ids, moving.shift)
      else paint()
      return
    }
    if (d && !d.moved && !("handle" in d)) {
      const [x, y] = at(e)
      const s = hitService(x, y)
      drag.current = null
      if (state.slot && s) {
        // Shift: every service the train can run one after another from the one clicked before to this one
        const before = v.lastSlotService
        if (e.shiftKey && before && before !== s.id && findService(before)) fn.addRangeToSlot(findService(before), s)
        else fn.toggleInSlot(s)
        v.lastSlotService = s.id
        fn.selectService(s.id, false)
        return
      }
      const leg = !s && hitLeg(x, y)
      if (leg) {
        state.slot = { depot: leg.depot, siding: leg.siding, index: leg.index }
        v.dutyNotice = leg.text
        fn.renderAll()
        return
      }
      if (s && (e.ctrlKey || e.metaKey) && fn.toggleChosen(s, e.shiftKey)) { fn.renderAll(); return }
      if (s && e.shiftKey && fn.chooseRange(s)) { fn.renderTable(); fn.renderAll(); return }
      state.range = null
      // A second click on the same service soon after chooses every service of its route
      const now = performance.now(), last = v.lastClick, again = s && last && last.id === s.id && now - last.time < 350
      v.lastClick = again ? null : { id: s && s.id, time: now }
      state.selectedRoute = again ? s.route.id : null
      fn.selectService(s ? s.id : null, false)
      return
    }
    drag.current = null
  }
  const onContextMenu = (e: React.MouseEvent) => {
    const data = v.data, state = v.state
    if (!data) return
    const [x, y] = at(e)
    const s = hitService(x, y)
    const leg = !s ? hitLeg(x, y) : null
    if (leg) { e.preventDefault(); fn.deadheadMenu(e.nativeEvent, leg); return }
    if (!s) return
    e.preventDefault()
    const later = fn.laterServices(s)
    const items: any[] = []
    if (later.length) {
      items.push([tr("选中这一班和之后的 {0} 班", later.length), () => fn.chooseLater(s)])
      items.push([tr("这一班和之后的 {0} 班一起平移…", later.length), data.canEdit && !state.slot ? () => fn.shiftLaterDialog(s) : null])
    }
    if (state.range || state.selectedRoute) items.push([tr("取消选择"), () => { state.range = null; state.selectedRoute = null; fn.renderAll() }])
    fn.openMenuAt(e.nativeEvent, items)
  }
  useEffect(() => {
    // Not a React wheel handler: it must be able to stop the page scrolling
    const element = canvas.current
    if (!element) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const state = legacy.v.state
      if (e.shiftKey) { state.scrollY += e.deltaY; paint() }
      else if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) { state.viewStart += e.deltaX / state.pxPerMin * 60000; paint() }
      else zoomAt(e.deltaY < 0 ? 1 : -1, e.clientX - element.getBoundingClientRect().left)
    }
    element.addEventListener("wheel", onWheel, { passive: false })
    return () => element.removeEventListener("wheel", onWheel)
  }) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div ref={box} className="absolute inset-0 overflow-hidden" data-running-diagram>
      <canvas ref={canvas} aria-label={tr("运行图")} className="block touch-none select-none"
        onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerLeave={() => { if (!drag.current) fn.showTooltip(null) }}
        onContextMenu={onContextMenu} />
    </div>
  )
}

/** One drawing of the whole diagram; what was drawn, for finding what is under the mouse */
export function draw(g: CanvasRenderingContext2D, w: number, h: number, moving: { ids: string[]; shift: number } | null, dragging: Drag | null) {
  const { fn, v } = legacy
  const data = v.data, state = v.state, sim = v.sim
  const css = (name: string) => fn.css(name)
  const c = { panel: css("--panel"), panel2: css("--panel-2"), ink: css("--ink"), muted: css("--muted"), grid: css("--grid"), strong: css("--grid-strong"), sel: css("--sel"), warn: css("--warn"), now: css("--now") || css("--ink"), duty: css("--duty") || css("--warn"), bg: css("--bg") }
  const uiFont = css("--font-ui"), numFont = css("--font-num")
  const out = { drawn: [] as Drawn[], handles: [] as { x: number; y: number; s: any; i: number }[], legs: [] as Leg[], access: [] as Drawn[], view: null as D.View | null }
  g.fillStyle = c.panel
  g.fillRect(0, 0, w, h)
  const line = data && currentLine()
  if (!line) {
    g.fillStyle = c.muted
    g.font = `13px ${uiFont}`
    g.fillText(data ? tr("没有线路") : tr("正在读取…"), 16, 32)
    return out
  }
  const depotRoom = v.showDepots && data.depots.some((depot: any) => fn.dutiesOf(depot).mode === "diagram" && depot.sidings.some((siding: any) => siding.legs)) ? D.DEPOT_ROW_ROOM : 0
  const layout = D.layoutRows(line.rows.map((row: any) => row.gap), h, depotRoom)
  const view = D.clampView({ viewStart: state.viewStart, pxPerMin: state.pxPerMin, scrollY: state.scrollY, width: w, height: h }, layout.contentHeight)
  state.viewStart = view.viewStart
  state.scrollY = view.scrollY
  out.view = view
  const xOf = (millis: number) => D.xOf(view, millis)
  const yOf = (row: number) => layout.ys[row] - view.scrollY
  const clockOffset = data.dayStartMillis + data.offsetMillis
  const polyline = (points: D.Point[]) => {
    g.beginPath()
    points.forEach(([x, y, jump], i) => (i && !jump ? g.lineTo(x, y) : g.moveTo(x, y)))
    g.stroke()
    if (!points.some(point => point[2])) return
    g.save()
    g.lineWidth = Math.min(g.lineWidth, 1.5)
    g.setLineDash([5, 4])
    g.beginPath()
    points.forEach(([x, y, jump], i) => { if (i && jump) { g.moveTo(points[i - 1][0], points[i - 1][1]); g.lineTo(x, y) } })
    g.stroke()
    g.restore()
  }

  // The time axis: hour lines, finer ones the further in, times on some
  const ticks = D.axisTicks(view, clockOffset)
  g.font = `11px ${numFont}`
  g.textAlign = "center"
  ticks.forEach(t => {
    g.strokeStyle = t.hour ? c.strong : c.grid
    g.globalAlpha = t.hour || t.labelled ? 1 : 0.45
    g.lineWidth = 1
    g.beginPath()
    g.moveTo(Math.round(t.x) + 0.5, D.AXIS_H)
    g.lineTo(Math.round(t.x) + 0.5, h)
    g.stroke()
    g.globalAlpha = 1
  })

  // The time now, dashed, on the day shown when it is today
  const nowMillis = ((Date.now() - data.dayStartMillis) % D.DAY + D.DAY) % D.DAY
  const nowX = xOf(nowMillis)
  if (state.weekday === mod(fn.nowDay() + v.FIRST_DAY_WEEKDAY, 7) && nowX >= D.LABEL_W && nowX <= w) {
    g.strokeStyle = c.now
    g.lineWidth = 1.5
    g.setLineDash([6, 4])
    g.beginPath()
    g.moveTo(Math.round(nowX) + 0.5, D.AXIS_H)
    g.lineTo(Math.round(nowX) + 0.5, h)
    g.stroke()
    g.setLineDash([])
    g.fillStyle = c.now
    g.textAlign = "left"
    g.fillText(tr("现在 {0}", clock(nowMillis)), nowX + 4, D.AXIS_H + 12)
    g.textAlign = "center"
  }

  // Station rows, and the line between sections
  g.save()
  g.beginPath()
  g.rect(0, D.AXIS_H, w, h - D.AXIS_H)
  g.clip()
  line.rows.forEach((row: any, i: number) => {
    const y = Math.round(yOf(i)) + 0.5
    g.strokeStyle = c.grid
    g.beginPath()
    g.moveTo(D.LABEL_W, y)
    g.lineTo(w, y)
    g.stroke()
    if (row.sectionStart) {
      const between = Math.round((yOf(i - 1) + yOf(i)) / 2) + 0.5
      g.strokeStyle = c.strong
      g.setLineDash([6, 4])
      g.beginPath()
      g.moveTo(0, between)
      g.lineTo(w, between)
      g.stroke()
      g.setLineDash([])
    }
  })

  g.beginPath()
  g.rect(D.LABEL_W, D.AXIS_H, w - D.LABEL_W, h - D.AXIS_H)
  g.clip()
  // Other lines' services on this line's track, under its own, dotted and read only
  if (v.showAccess) {
    fn.accessLegs(line).forEach(({ r, legs }: any) => {
      g.strokeStyle = colorOf(r.color)
      g.lineWidth = 1.2
      g.setLineDash([1, 3])
      g.lineCap = "round"
      g.globalAlpha = state.route ? 0.2 : 0.6
      r.services.forEach((s: any) => {
        if (s.suspended) return
        const times = timesOf(s)
        let points: D.Point[] = []
        const flush = () => {
          if (points.length > 1 && points.some(([x]) => x >= D.LABEL_W - 40 && x <= w + 40)) { out.access.push({ s, points }); polyline(points) }
          points = []
        }
        legs.forEach((leg: any, k: number) => {
          const before = legs[k - 1]
          if (!before || before.i !== leg.i - 1 || before.rowB !== leg.rowA) flush()
          if (!points.length) points.push([xOf(times[leg.i][1]), yOf(leg.rowA)])
          points.push([xOf(times[leg.i + 1][0]), yOf(leg.rowB)])
          const next = legs[k + 1]
          if (next && next.i === leg.i + 1 && times[leg.i + 1][1] !== times[leg.i + 1][0]) points.push([xOf(times[leg.i + 1][1]), yOf(leg.rowB)])
        })
        flush()
      })
      g.setLineDash([])
      g.lineCap = "butt"
      g.globalAlpha = 1
    })
  }

  // The services, the selected one last so it's on top
  const services = line.routes.flatMap((r: any) => r.services)
  const selectedRoute = state.route
  services.sort((a: any, b: any) => Number(a.id === state.selected) - Number(b.id === state.selected))
  const assigned = fn.dutyAssignments()
  const slotDepot = state.slot && fn.depotById(state.slot.depot)
  const slotDuty = slotDepot && (fn.dutiesOf(slotDepot).sidings[state.slot.siding] || [])[state.slot.index]
  const slotDay = slotDuty ? fn.dutyOn(slotDuty, state.weekday) : null
  const inSlot = new Set(slotDay ? slotDay.segments.map(fn.segmentKey) : [])
  const diagramRoutes = new Set(data.depots.filter((depot: any) => fn.dutiesOf(depot).mode === "diagram").flatMap((depot: any) => depot.routes))
  const slotPoints: { id: string; start: D.Point; end: D.Point; time: number }[] = []
  services.forEach((s: any) => {
    // Being dragged along the time axis: drawn where it would go
    const shift = moving && moving.ids.includes(s.id) ? moving.shift : 0
    const plain = timesOf(s)
    const times = shift ? plain.map(([a, b]: number[]) => [a + shift, b + shift]) : plain
    if (!D.inView(times, xOf, w)) return
    const points = D.servicePath(s, times, xOf, yOf)
    if (!points.length) return
    out.drawn.push({ s, points })
    const isSelected = s.id === state.selected || s.route.id === state.selectedRoute || fn.inRange(s)
    const dim = Boolean(selectedRoute && s.route.id !== selectedRoute)
    g.globalAlpha = dim ? 0.25 : 1
    if (isSelected) {
      g.strokeStyle = c.sel
      g.lineWidth = 5
      g.globalAlpha = 0.35
      polyline(points)
      g.globalAlpha = 1
    }
    const key = s.route.id + "@" + s.service
    if (inSlot.has(key)) {
      g.strokeStyle = c.duty
      g.lineWidth = 7
      g.globalAlpha = 0.3
      polyline(points)
      g.globalAlpha = dim ? 0.25 : 1
      slotPoints.push({ id: s.id, start: points[0], end: points[points.length - 1], time: plain[0][0] })
    }
    const noTrain = !s.suspended && s.route.hasTimes && diagramRoutes.has(s.route.id) && !assigned.has(key)
    const covered = !s.suspended && !noTrain && s.route.hasTimes && diagramRoutes.has(s.route.id)
    const look = D.serviceLook({ selected: isSelected, dim, noTrain, covered, hasTimes: s.route.hasTimes, suspended: s.suspended, notRun: Boolean(sim.on && sim.notRun.has(key)), showNoTrain: v.showNoTrain })
    g.strokeStyle = look.color === "route" ? colorOf(s.route.color) : look.color === "warn" ? c.warn : c.muted
    g.lineWidth = look.width
    g.globalAlpha = look.alpha
    g.setLineDash([])
    polyline(points)
    g.globalAlpha = dim ? 0.25 : 1
    // Set on the running diagram: a short dashed run from or to the depot, with its name
    ;([[s.deployOrder, points[0], -1], [s.returnOrder, points[points.length - 1], 1]] as [any, D.Point, number][]).forEach(([order, [px, py], side]) => {
      if (!order) return
      g.strokeStyle = colorOf(s.route.color)
      g.lineWidth = 1
      g.setLineDash([3, 3])
      g.beginPath()
      g.moveTo(px, py)
      g.lineTo(px + side * 14, py + 10)
      g.stroke()
      g.setLineDash([])
      g.fillStyle = c.muted
      g.font = `11px ${uiFont}`
      g.textAlign = side < 0 ? "right" : "left"
      g.fillText(side < 0 ? orderDepotName(order) + " →" : "→ " + orderDepotName(order), px + side * 16, py + 20)
    })
    // Where the train carries on onto another route, or goes back to the depot
    const [ex, ey] = points[points.length - 1]
    if (!s.returnOrder && (s.link || s.returns)) {
      const next = s.link && data.routeById.get(s.link.route)
      g.fillStyle = s.link ? c.ink : c.muted
      g.font = `11px ${uiFont}`
      g.textAlign = "left"
      g.fillText(s.link ? "→ " + (next ? shortName(next.name) : "?") : tr("回库"), ex + 4, ey + (s.route.rows[0] < s.route.rows[s.route.rows.length - 1] ? 12 : -4))
    }
    g.globalAlpha = 1
    // A square at the end of each stop of a selected service, dragged to change how long it stops there
    if (isSelected) {
      D.dwellHandles(s, plain, xOf, yOf).forEach(handle => {
        g.fillStyle = c.panel
        g.strokeStyle = c.sel
        g.lineWidth = 2
        g.beginPath()
        g.rect(handle.x - 4.5, handle.y - 4.5, 9, 9)
        g.fill()
        g.stroke()
        out.handles.push(handle)
      })
    }
  })
  // The chosen train's moves between its services
  const pairs = fn.terminalNow()
  slotPoints.sort((a, b) => a.time - b.time)
  g.strokeStyle = c.duty
  g.lineWidth = 1
  g.setLineDash([3, 3])
  for (let i = 1; i < slotPoints.length; i++) {
    const pairing = pairs.next.get(slotPoints[i - 1].id)
    if (pairing?.loop && pairing.to.id === slotPoints[i].id) continue
    g.beginPath()
    g.moveTo(slotPoints[i - 1].end[0], slotPoints[i - 1].end[1])
    g.lineTo(slotPoints[i].start[0], slotPoints[i].start[1])
    g.stroke()
  }
  g.setLineDash([])
  drawTerminalLinks(g, line, pairs, xOf, yOf, c, w, numFont, polyline, moving?.ids)
  if (v.showDepots) out.legs = drawDepotLegs(g, line, xOf, yOf, c, w, layout.ys.length > 1 ? layout.ys[1] - layout.ys[0] : D.MIN_ROW_GAP, numFont, dragging)
  drawSimulated(g, line, xOf, yOf, c, w)
  if (slotPoints.length) {
    g.fillStyle = c.duty
    g.font = `bold 11px ${uiFont}`
    g.textAlign = "right"
    g.fillText(tr("车 {0}", fn.dutyNumber(fn.depotById(state.slot.depot), state.slot.siding, state.slot.index)), slotPoints[0].start[0] - 4, slotPoints[0].start[1] - 4)
  }
  g.restore()

  // Station names over everything at the left, on the page colour, and the time labels
  g.fillStyle = c.panel
  g.fillRect(0, 0, w, D.AXIS_H)
  g.fillStyle = c.bg
  g.fillRect(0, 0, D.LABEL_W, h)
  g.strokeStyle = c.strong
  g.lineWidth = 1
  g.beginPath()
  g.moveTo(D.LABEL_W + 0.5, 0)
  g.lineTo(D.LABEL_W + 0.5, h)
  g.moveTo(0, D.AXIS_H + 0.5)
  g.lineTo(w, D.AXIS_H + 0.5)
  g.stroke()
  g.font = `12px ${uiFont}`
  g.textAlign = "right"
  g.textBaseline = "middle"
  const fit = (text: string, width: number) => {
    if (g.measureText(text).width <= width) return text
    let t = text
    while (t.length > 1 && g.measureText(t + "…").width > width) t = t.slice(0, -1)
    return t + "…"
  }
  line.rows.forEach((row: any, i: number) => {
    const y = yOf(i)
    if (y < D.AXIS_H + 4 || y > h) return
    g.fillStyle = row.repeat ? c.muted : c.ink
    g.fillText(fit((row.repeat ? "↔ " : "") + shortName(row.name), D.LABEL_W - 16), D.LABEL_W - 8, y)
  })
  g.textBaseline = "alphabetic"
  g.font = `11px ${numFont}`
  g.textAlign = "center"
  ticks.forEach(t => {
    if (!t.labelled) return
    g.fillStyle = t.hour ? c.ink : c.muted
    g.fillText(clock(t.millis, t.seconds), t.x, D.AXIS_H - 10)
  })
  return out
}

/** Turn backs at terminals, and trains going on from one route to the next (old drawTerminalLinks) */
function drawTerminalLinks(g: CanvasRenderingContext2D, line: any, pairs: { next: Map<string, any> }, xOf: (m: number) => number, yOf: (r: number) => number, c: Record<string, string>, w: number, numFont: string, polyline: (points: D.Point[]) => void, moving?: string[]) {
  const { fn, v } = legacy
  const inLine = new Set(line.routes.map((r: any) => r.id))
  g.save()
  pairs.next.forEach((pairing: any) => {
    const from = findService(pairing.from.id), to = findService(pairing.to.id)
    if (!from || !to || !inLine.has(from.route.id) || from.suspended) return
    const fromRow = from.route.rows[pairing.from.last], toRow = inLine.has(to.route.id) ? to.route.rows[0] : fromRow
    const arrive = pairing.from.times[pairing.from.last][0], leave = pairing.to.times[0][1]
    if (xOf(Math.max(arrive, leave)) < D.LABEL_W || xOf(Math.min(arrive, leave)) > w) return
    const y1 = yOf(fromRow), y2 = yOf(toRow)
    g.globalAlpha = pairing.source === "nearest" ? 0.45 : 1
    g.strokeStyle = pairing.conflict ? c.warn : colorOf(from.route.color)
    g.lineWidth = pairing.source === "manual" ? 3 : 2
    g.beginPath()
    if (pairing.kind === "same" && pairing.loop && inLine.has(to.route.id)) {
      if (moving?.includes(from.id) || moving?.includes(to.id)) return
      const lastY = yOf(from.route.leaveRows[pairing.from.last])
      if (pairing.overlap) {
        g.fillStyle = c.warn
        g.font = `10px ${numFont}`
        g.textAlign = "left"
        g.fillText(tr("下一圈已发车"), xOf(arrive) + 2, lastY - 3)
        return
      }
      const connection: [number, number][] | null = (globalThis as any).TerminalPairs.loopConnection(xOf(arrive), xOf(leave), lastY, yOf(to.route.leaveRows[0]))
      if (connection) polyline([connection[0], connection[1], [...connection[2], true]])
    } else if (pairing.kind === "same") {
      g.moveTo(xOf(arrive), y1); g.lineTo(xOf(leave), y1)
      g.stroke()
    } else if (pairing.kind === "change") {
      g.setLineDash([5, 4])
      g.moveTo(xOf(pairing.from.times[pairing.from.last][1]), y1)
      g.lineTo(xOf(pairing.to.times[0][0]), y2)
      g.stroke()
      g.setLineDash([])
    } else {
      // Out to the rail beside the row, waiting there, and back to the platform
      const yRail = y1 + (y1 > D.AXIS_H + 20 ? -7 : 7)
      g.setLineDash([3, 3])
      g.moveTo(xOf(pairing.from.times[pairing.from.last][1]), y1)
      g.lineTo(xOf(pairing.railStart), yRail)
      g.moveTo(xOf(pairing.railEnd), yRail)
      g.lineTo(xOf(pairing.to.times[0][0]), y2)
      g.stroke()
      g.setLineDash([])
      g.beginPath()
      g.moveTo(xOf(pairing.railStart), yRail)
      g.lineTo(xOf(pairing.railEnd), yRail)
      g.stroke()
    }
    if (v.state.pxPerMin >= 3 && (pairing.moved || pairing.kind !== "same")) {
      g.fillStyle = g.strokeStyle
      g.font = `10px ${numFont}`
      g.textAlign = "left"
      g.fillText(pairing.kind === "hold" ? tr("折返线") : fn.platformText(pairing.kind === "same" ? pairing.platform : pairing.to.platforms[0]), xOf(arrive) + 2, y1 - 3)
    }
  })
  g.restore()
}

/** The depots' rows and each duty's run out and back (old drawDepotLegs); the runs drawn, for the mouse */
function drawDepotLegs(g: CanvasRenderingContext2D, line: any, xOf: (m: number) => number, yOf: (r: number) => number, c: Record<string, string>, w: number, unit: number, numFont: string, dragging: Drag | null): Leg[] {
  const { fn, v } = legacy
  const data = v.data, state = v.state
  const legs: Leg[] = []
  const lineRoutes = new Set(line.routes.map((r: any) => r.id))
  const rowOfStation = (id: string) => line.rows.findIndex((row: any) => row.id === id && !row.repeat)
  const depotRows = new Map<string, { y: number; name: string }>()
  g.save()
  g.font = `10px ${numFont}`
  data.depots.forEach((depot: any) => {
    const duties = fn.dutiesOf(depot)
    if (duties.mode !== "diagram") return
    depot.sidings.forEach((siding: any) => {
      const sidingLegs = siding.legs, slots = duties.sidings[siding.id] || []
      if (!sidingLegs || !slots.length) return
      const entranceRow = rowOfStation(sidingLegs.entrance)
      slots.forEach((slot: any, index: number) => {
        const day = fn.dutyOn(slot, state.weekday)
        if (!day || !day.segments.length) return
        const first = day.segments[0], firstService = fn.segmentService(first)
        const last = day.segments[day.segments.length - 1], lastService = fn.segmentService(last)
        if (!firstService || !lineRoutes.has(firstService.route.id)) return
        const baseRow = entranceRow >= 0 ? entranceRow : firstService.route.rows[first.from]
        if (baseRow === undefined || baseRow < 0) return
        const side = baseRow < line.rows.length / 2 ? -1 : 1
        const depotY = Math.max(D.AXIS_H + 4, yOf(baseRow) + side * Math.min(D.DEPOT_ROW_ROOM - 8, Math.max(12, unit * 0.45)))
        const key = depot.id + ":" + baseRow
        if (!depotRows.has(key)) depotRows.set(key, { y: depotY, name: shortName(depot.name) || tr("车厂") })
        const active = state.slot && state.slot.depot === depot.id && state.slot.siding === siding.id && state.slot.index === index
        const sidingName = shortName(siding.name) || tr("存车线")
        const deadheads = fn.deadheadTimes(siding, day)
        const style = (exception: boolean) => {
          g.strokeStyle = active ? c.duty : exception ? c.sel : c.muted
          g.lineWidth = active || exception ? 2 : 1
          g.setLineDash([3, 3])
        }
        if (deadheads.out) {
          const dragged = dragging && "deadhead" in dragging && dragging.deadhead.depot === depot.id && dragging.deadhead.siding === siding.id && dragging.deadhead.index === index ? dragging.deadhead.shift || 0 : 0
          const leave = deadheads.out.leave + dragged
          const a = [xOf(leave), depotY], b = [xOf(deadheads.out.arrive), yOf(deadheads.out.row)]
          style(deadheads.out.exception || dragged !== 0)
          g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke()
          legs.push({ a, b, kind: "out", depot: depot.id, siding: siding.id, index, text: `${deadheads.out.number} ${tr("出库 {0} ← {1} {2}", clock(leave, true), shortName(depot.name), sidingName)}${deadheads.out.exception ? tr("（这份运用的例外）") : ""}` })
          if (state.pxPerMin >= 3) { g.fillStyle = g.strokeStyle; g.textAlign = "right"; g.fillText(`${deadheads.out.number} ${clock(leave)}`, a[0] - 2, depotY + (side < 0 ? -2 : 9)) }
        }
        if (deadheads.back && lineRoutes.has(lastService.route.id)) {
          const a = [xOf(deadheads.back.leave), yOf(deadheads.back.row)], b = [xOf(deadheads.back.home), depotY]
          style(deadheads.back.exception)
          g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke()
          legs.push({ a, b, kind: "back", depot: depot.id, siding: siding.id, index, text: `${deadheads.back.number} ${tr("回库 {0} → {1} {2}", clock(deadheads.back.home, true), shortName(depot.name), sidingName)}${deadheads.back.exception ? tr("（这份运用的例外）") : ""}` })
          if (state.pxPerMin >= 3) { g.fillStyle = g.strokeStyle; g.textAlign = "left"; g.fillText(`${deadheads.back.number} ${clock(deadheads.back.home)}`, b[0] + 2, depotY + (side < 0 ? -2 : 9)) }
        }
        g.setLineDash([])
      })
    })
  })
  depotRows.forEach(({ y, name }) => {
    g.strokeStyle = c.grid
    g.lineWidth = 1
    g.setLineDash([1, 3])
    g.beginPath(); g.moveTo(D.LABEL_W, y); g.lineTo(w, y); g.stroke()
    g.setLineDash([])
    g.fillStyle = c.muted
    g.textAlign = "right"
    g.fillText("▤ " + name, D.LABEL_W - 6, y + 3)
  })
  g.restore()
  return legs
}

/** The simulated day over the plan: each trip dashed, red where it ran late (old drawSimulated) */
function drawSimulated(g: CanvasRenderingContext2D, line: any, xOf: (m: number) => number, yOf: (r: number) => number, c: Record<string, string>, w: number) {
  const { fn, v } = legacy
  const sim = v.sim, state = v.state
  if (!sim.on || !sim.result) return
  const routes = new Map(line.routes.map((r: any) => [r.id, r]))
  g.lineWidth = 1.5
  g.setLineDash([5, 4])
  sim.trips.forEach((trip: any) => {
    const r: any = routes.get(trip.route)
    if (!r) return
    const points: [number, number, boolean][] = []
    trip.stops.forEach((stop: number[]) => {
      if (stop[0] < 0 || stop[0] >= r.rows.length) return
      const y = yOf(r.rows[stop[0]]), leaveY = yOf(r.leaveRows[stop[0]]), late = fn.simLate(stop) > v.SIM_LATE_MILLIS
      if (stop[1] >= 0) points.push([xOf(stop[1]), y, late])
      if (stop[1] >= 0 && leaveY !== y) points.push([xOf(stop[1]), leaveY, late])
      if (stop[2] >= 0 && stop[2] !== stop[1]) points.push([xOf(stop[2]), leaveY, late])
    })
    if (points.length < 2 || points[points.length - 1][0] < D.LABEL_W - 40 || points[0][0] > w + 40) return
    g.globalAlpha = state.route && r.id !== state.route ? 0.25 : 0.9
    for (let i = 1; i < points.length; i++) {
      g.strokeStyle = points[i][2] ? c.warn : c.ink
      g.beginPath(); g.moveTo(points[i - 1][0], points[i - 1][1]); g.lineTo(points[i][0], points[i][1]); g.stroke()
    }
  })
  g.setLineDash([])
  g.globalAlpha = 1
}
