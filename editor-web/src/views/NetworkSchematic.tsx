/**
 * The whole network as a metro-map schematic (core/abstractNet.ts), drawn two ways:
 * - "tracks" (the track layer), as the route track diagram draws a route: every track a line through the stations and the
 *   sections between them, platforms as bars beside their tracks, turnouts in the throats and crossovers on the line as
 *   diagonals between tracks, bends at 45°;
 * - "logical" (the ownership layer), stations as small boxes showing their tracks and the sections between them as
 *   parallel lines, each in the colour of its logical line (or of the lines owning it, without logical lines); with a
 *   brush in hand, a click on a section paints all of its rails.
 * Station glyphs keep their size at any zoom. Click a station for its tracks; "在实际图上看" shows the place on the real
 * map.
 */
import { useEffect, useMemo, useRef, useState } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { abstractNetwork, type Corridor, type Place, type Schematic } from "../core/abstractNet"
import { paint, useBrush } from "./OwnershipPanel"
import { tr } from "../i18n"

const GAP = 5, LANE = 7, BOX_W = 44

/** A train to show on the tracks drawing: on the rail its head is on */
export type TrainMark = { id: string; name: string; rail: number; late: boolean; selected: boolean }

export function NetworkSchematic({ onShowReal, mode = "tracks", trains, onTrain }: { onShowReal: () => void; mode?: "tracks" | "logical"; trains?: TrainMark[]; onTrain?: (id: string) => void }) {
  const brush = useBrush()
  useLegacy()
  const { fn, v } = legacy
  // The old code reads the network on its own timer and only redraws its canvas: look for a new one now and then
  const [, setSeen] = useState<unknown>(null)
  useEffect(() => {
    const timer = setInterval(() => setSeen(legacy.v.net.data), 400)
    return () => clearInterval(timer)
  }, [])
  const data = v.net.data
  const schematic = useMemo<Schematic | null>(() => (data && data.nodes && data.rails ? abstractNetwork(data, v.data && v.data.topology ? v.data.topology.platforms : []) : null), [data]) // eslint-disable-line react-hooks/exhaustive-deps
  const box = useRef<HTMLDivElement>(null)
  const [view, setView] = useState<{ x: number; y: number; k: number } | null>(null)
  const [chosen, setChosen] = useState<string | null>(null)
  const drag = useRef<{ x: number; y: number; vx: number; vy: number; moved: boolean } | null>(null)

  // The whole network fits, the first time, whenever the map changes and when the box is first given its size; never so
  // small that the station boxes, which keep their size, run into each other (the rest is a drag away)
  const fit = () => {
    if (!schematic || !box.current) return
    const w = box.current.clientWidth || 800, h = box.current.clientHeight || 600
    const k = Math.max(0.75, Math.min(2.5, Math.min(w / schematic.width, h / schematic.height)))
    setView({ x: (w - schematic.width * k) / 2, y: (h - schematic.height * k) / 2, k })
  }
  useEffect(fit, [schematic]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!box.current || typeof ResizeObserver === "undefined") return
    let seen = 0
    const observer = new ResizeObserver(() => { if (seen++ < 2) fit() })
    observer.observe(box.current)
    return () => observer.disconnect()
  }, [schematic]) // eslint-disable-line react-hooks/exhaustive-deps

  const modes: string[] = data && data.modes ? data.modes : []
  const names: Record<string, string> = { TRAIN: "铁路", BOAT: "船", CABLE_CAR: "缆车", AIRPLANE: "飞机" }
  const head = (
    <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-grid bg-panel px-3 py-2 text-[12px]">
      {modes.length > 1 && (
        <select className="h-7 rounded-md border border-ctl bg-panel px-1" value={v.net.mode} aria-label={tr("运输方式")}
          onChange={e => { v.net.mode = e.target.value; v.net.version = -1; fn.loadNetwork(false) }}>
          {modes.map(m => <option key={m} value={m}>{names[m] ? tr(names[m]) : m}</option>)}
        </select>
      )}
      {schematic && <span className="text-dim">{tr("{0} 个车站，{1} 段区间", schematic.places.filter(p => p.kind === "station").length, schematic.corridors.length)}</span>}
      <span className="flex-1" />
      <span className="hidden text-dim md:inline">{mode === "tracks" ? tr("每条线一股道，颜色是走它的线路；站台画在股道旁，斜线是道岔和渡线。滚轮缩放，拖动平移。") : brush ? tr("点一段区间，把它整段画进画笔的逻辑线路。") : tr("区间的颜色是它属于的逻辑线路（没有逻辑线路时是归属的线路）；车站框里一行一股。")}</span>
      <button className="btn" onClick={fit} disabled={!schematic}>{tr("全图")}</button>
    </div>
  )
  if (!schematic) return <div className="flex min-h-0 min-w-0 flex-1 flex-col">{head}<p className="p-4 text-[13px] text-dim">{data ? tr("游戏里还没有这种运输方式的轨道") : tr("正在读取…")}</p></div>
  const at = new Map(schematic.places.map(p => [p.id, p]))
  const place = chosen ? at.get(chosen) : null
  const k = view?.k ?? 1
  // Glyphs keep their size on screen: drawn in screen units around each place's zoomed position
  const sx = (p: Place) => p.px * k + (view?.x ?? 0), sy = (p: Place) => p.py * k + (view?.y ?? 0)

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      {head}
      <div ref={box} className="relative min-h-0 flex-1 overflow-hidden bg-bg" style={{ cursor: drag.current ? "grabbing" : "grab", touchAction: "none" }}
        onWheel={e => {
          const r = box.current!.getBoundingClientRect(), mx = e.clientX - r.left, my = e.clientY - r.top
          const factor = e.deltaY < 0 ? 1.2 : 1 / 1.2
          setView(old => old && { k: Math.max(0.05, Math.min(8, old.k * factor)), x: mx - (mx - old.x) * factor, y: my - (my - old.y) * factor })
        }}
        onPointerDown={e => { if (view) drag.current = { x: e.clientX, y: e.clientY, vx: view.x, vy: view.y, moved: false } }}
        onPointerMove={e => {
          const d = drag.current
          if (!d) return
          if (Math.hypot(e.clientX - d.x, e.clientY - d.y) > 3) d.moved = true
          if (d.moved) setView(old => old && { ...old, x: d.vx + e.clientX - d.x, y: d.vy + e.clientY - d.y })
        }}
        onPointerUp={() => { setTimeout(() => { drag.current = null }) }}
        onPointerLeave={() => { drag.current = null }}>
        <svg className="absolute inset-0 h-full w-full" role="img" aria-label={mode === "tracks" ? tr("全网示意配线图") : tr("全网逻辑图")}>
          {mode === "tracks"
            ? <TrackDrawing schematic={schematic} sx={sx} sy={sy} chosen={chosen} onChoose={id => { if (!drag.current?.moved) setChosen(id === chosen ? null : id) }} trains={trains} onTrain={onTrain} />
            : <>
              {schematic.corridors.map((c, i) => <LogicalCorridor key={i} corridor={c} a={at.get(c.a)!} b={at.get(c.b)!} sx={sx} sy={sy}
                onPaint={brush ? () => { if (!drag.current?.moved) paintCorridor(c, brush) } : undefined} />)}
              {schematic.places.map(p => p.kind === "station"
                ? <StationGlyph key={p.id} place={p} x={sx(p)} y={sy(p)} on={p.id === chosen} onClick={() => { if (!drag.current?.moved) setChosen(p.id === chosen ? null : p.id) }} />
                : <circle key={p.id} cx={sx(p)} cy={sy(p)} r={p.kind === "junction" ? 3 : 2} fill="var(--ink)"><title>{p.kind === "junction" ? tr("道岔") : tr("尽头")}</title></circle>)}
            </>}
        </svg>
        {place && <StationCard place={place} onClose={() => setChosen(null)} onShowReal={() => {
          v.net.cx = place.x
          v.net.cz = place.z
          v.net.scale = 2
          onShowReal()
        }} />}
      </div>
    </div>
  )
}

function StationGlyph({ place, x, y, on, onClick }: { place: Place; x: number; y: number; on: boolean; onClick: () => void }) {
  const lanes = place.lanes.length ? place.lanes : [{ offset: 0, platform: true, name: "", colors: [] }]
  const h = lanes.length * LANE + 6, degrees = place.angle * 180 / Math.PI
  const upright = Math.abs(Math.abs(degrees) - 90) < 1
  const labelY = (upright ? BOX_W : h) / 2 + 13
  return (
    <g className="cursor-pointer" onClick={onClick} role="button" aria-label={place.name.split("|")[0]}>
      <g transform={`translate(${x} ${y}) rotate(${degrees})`}>
        <rect x={-BOX_W / 2} y={-h / 2} width={BOX_W} height={h} rx={4} fill="var(--panel)" stroke={on ? "var(--sel)" : "var(--ink)"} strokeWidth={on ? 2.5 : 1.3} />
        {lanes.map((lane, i) => {
          const ly = (i - (lanes.length - 1) / 2) * LANE
          const color = lane.colors[0] || (lane.platform ? "var(--ink)" : "var(--grid-strong)")
          return <line key={i} x1={-BOX_W / 2 + 5} y1={ly} x2={BOX_W / 2 - 5} y2={ly} stroke={color} strokeWidth={lane.platform ? 3 : 1.4} strokeDasharray={lane.platform ? undefined : "3 2"} strokeLinecap="round" />
        })}
      </g>
      <text x={x} y={y + labelY} textAnchor="middle" fontSize={12} fontWeight={on ? 700 : 500} fill="var(--ink)" stroke="var(--bg)" strokeWidth={3} paintOrder="stroke">{place.name.split("|")[0]}</text>
      <title>{tr("{0}：{1} 股，{2} 个站台", place.name.split("|")[0], lanes.length, lanes.filter(l => l.platform).length)}</title>
    </g>
  )
}

function StationCard({ place, onClose, onShowReal }: { place: Place; onClose: () => void; onShowReal: () => void }) {
  return (
    <div className="absolute left-3 top-3 w-[260px] rounded-lg border border-grid bg-panel p-3 text-[13px]" style={{ boxShadow: "var(--shadow)" }}>
      <div className="mb-2 flex items-center gap-2">
        <b className="min-w-0 flex-1 truncate text-[14px]">{place.name.split("|")[0]}</b>
        <button className="btn btn-ghost h-6 px-1.5" onClick={onClose} aria-label={tr("关闭")}>✕</button>
      </div>
      <ol className="m-0 list-none p-0">
        {place.lanes.map((lane, i) => (
          <li key={i} className="flex items-center gap-2 py-0.5">
            <span className="h-[4px] w-6 shrink-0 rounded-pill" style={{ background: lane.colors[0] || (lane.platform ? "var(--ink)" : "var(--grid-strong)") }} />
            <span className="min-w-0 flex-1">{lane.platform ? tr("{0} 号站台", lane.name || "?") : tr("正线 / 存车线")}</span>
            <span className="flex gap-0.5">{lane.colors.slice(1).map(c => <span key={c} className="h-2 w-2 rounded-full" style={{ background: c }} />)}</span>
          </li>
        ))}
      </ol>
      <button className="btn mt-2" onClick={onShowReal}>{tr("在实际图上看")}</button>
    </div>
  )
}

// ---------- The logical drawing ----------

/** The colours a rail is shown in on the logical map: its logical line's, or its owners' without logical lines */
function railColors(rail: number): string[] {
  const { fn } = legacy
  if (fn.usesLogicalLines()) {
    const line = fn.railLogicalLine(rail)
    return line ? [fn.colorOf(line.color)] : []
  }
  if (!legacy.v.net.ownerOf) return []
  return (fn.railOwnersShown(rail).owners as string[]).map(owner => fn.ownerColor(owner))
}

/** Paints every section a corridor's rails are in, each once */
function paintCorridor(c: Corridor, brush: Parameters<typeof paint>[1]) {
  const { v } = legacy
  const seen = new Set<unknown>()
  c.rails.forEach(rail => {
    const entry = v.net.ownerOf && v.net.ownerOf.get(rail)
    const section = entry ? entry.section : "r" + rail
    if (seen.has(section)) return
    seen.add(section)
    paint(rail, brush)
  })
}

function LogicalCorridor({ corridor: c, a, b, sx, sy, onPaint }: { corridor: Corridor; a: Place; b: Place; sx: (p: Place) => number; sy: (p: Place) => number; onPaint?: () => void }) {
  const x1 = sx(a), y1 = sy(a), x2 = sx(b), y2 = sy(b)
  const length = Math.hypot(x2 - x1, y2 - y1) || 1, nx = -(y2 - y1) / length, ny = (x2 - x1) / length
  const colors = [...new Set(c.rails.flatMap(railColors))]
  return (
    <g className={onPaint ? "cursor-crosshair" : undefined} onClick={onPaint}>
      {onPaint && <line x1={x1} y1={y1} x2={x2} y2={y2} stroke="transparent" strokeWidth={Math.max(14, c.tracks * GAP + 8)} />}
      {Array.from({ length: c.tracks }, (_, t) => {
        const o = (t - (c.tracks - 1) / 2) * GAP
        const color = colors.length ? colors[t % colors.length] : "var(--grid-strong)"
        return <line key={t} x1={x1 + nx * o} y1={y1 + ny * o} x2={x2 + nx * o} y2={y2 + ny * o} stroke={color} strokeWidth={colors.length ? 3 : 2} strokeLinecap="round" strokeDasharray={colors.length ? undefined : "5 4"} />
      })}
    </g>
  )
}

// ---------- The track drawing ----------

const HALF = 40, TRACK = 12

/** A station's lane offset across its axis, in pixels, the lanes centred */
const laneOffset = (p: Place, lane: number) => (lane - (p.lanes.length - 1) / 2) * TRACK

function TrackDrawing({ schematic, sx, sy, chosen, onChoose, trains, onTrain }: { schematic: Schematic; sx: (p: Place) => number; sy: (p: Place) => number; chosen: string | null; onChoose: (id: string) => void; trains?: TrainMark[]; onTrain?: (id: string) => void }) {
  const at = new Map(schematic.places.map(p => [p.id, p]))
  const axis = (p: Place) => [Math.cos(p.angle), Math.sin(p.angle)]
  /** Where a track meets a place: a station's lane at its end facing the other place, or across a junction */
  const endOf = (p: Place, lane: number, toward: Place, index: number, count: number, nx: number, ny: number) => {
    const cx = sx(p), cy = sy(p)
    if (p.kind !== "station" || !p.lanes.length) {
      const o = (index - (count - 1) / 2) * TRACK * (p.kind === "junction" ? 0.4 : 1)
      return [cx + nx * o, cy + ny * o]
    }
    const [ux, uy] = axis(p), side = (sx(toward) - cx) * ux + (sy(toward) - cy) * uy >= 0 ? 1 : -1
    const o = laneOffset(p, lane < 0 ? Math.min(index, p.lanes.length - 1) : lane)
    return [cx + ux * HALF * side - uy * o, cy + uy * HALF * side + ux * o]
  }
  // Each corridor's tracks, end to end on the screen
  const drawn = schematic.corridors.map(c => {
    const a = at.get(c.a)!, b = at.get(c.b)!
    const x1 = sx(a), y1 = sy(a), x2 = sx(b), y2 = sy(b)
    const length = Math.hypot(x2 - x1, y2 - y1) || 1, nx = -(y2 - y1) / length, ny = (x2 - x1) / length
    const lines = c.lines.length ? c.lines : [{ aLane: -1, bLane: -1, colors: c.colors }]
    return { lines, ends: lines.map((t, k) => [endOf(a, t.aLane, b, k, lines.length, nx, ny), endOf(b, t.bLane, a, k, lines.length, nx, ny)]) }
  })
  /** Where a rail is on the screen: along its corridor's track, or on its station's lane */
  const pointOf = (rail: number): number[] | null => {
    const spot = schematic.spots.get(rail)
    if (!spot) return null
    if ("corridor" in spot) {
      const e = drawn[spot.corridor].ends[Math.min(spot.track, drawn[spot.corridor].ends.length - 1)]
      return [e[0][0] + (e[1][0] - e[0][0]) * spot.at, e[0][1] + (e[1][1] - e[0][1]) * spot.at]
    }
    const p = at.get(spot.place)
    if (!p) return null
    const o = laneOffset(p, Math.max(0, spot.lane))
    return [sx(p) - Math.sin(p.angle) * o, sy(p) + Math.cos(p.angle) * o]
  }
  return (
    <>
      {schematic.corridors.map((c, i) => {
        const { lines, ends } = drawn[i]
        return (
          <g key={i}>
            {lines.map((t, k) => {
              const [[ax, ay], [bx, by]] = ends[k]
              return <line key={k} x1={ax} y1={ay} x2={bx} y2={by} stroke={t.colors[0] || "var(--grid-strong)"} strokeWidth={t.colors.length ? 2.5 : 2} strokeLinecap="round" />
            })}
            {c.crossovers > 0 && ends.length > 1 && (() => {
              // Crossovers on the line: an X between the outer tracks halfway
              const p = ends[0], q = ends[ends.length - 1]
              const m = (e: number[][], f: number) => [e[0][0] + (e[1][0] - e[0][0]) * f, e[0][1] + (e[1][1] - e[0][1]) * f]
              const [a1, a2, b1, b2] = [m(p, 0.44), m(p, 0.56), m(q, 0.44), m(q, 0.56)]
              return <g stroke="var(--ink)" strokeWidth={1.3}><line x1={a1[0]} y1={a1[1]} x2={b2[0]} y2={b2[1]} /><line x1={b1[0]} y1={b1[1]} x2={a2[0]} y2={a2[1]} /><title>{tr("区间里有 {0} 处渡线", c.crossovers)}</title></g>
            })()}
          </g>
        )
      })}
      {schematic.places.map(p => p.kind === "station"
        ? <StationTracks key={p.id} place={p} x={sx(p)} y={sy(p)} on={p.id === chosen} onClick={() => onChoose(p.id)} />
        : p.kind === "junction" ? <circle key={p.id} cx={sx(p)} cy={sy(p)} r={2.6} fill="var(--panel)" stroke="var(--ink)" strokeWidth={1.4}><title>{tr("道岔")}</title></circle> : null)}
      {(trains || []).map(train => {
        const point = pointOf(train.rail)
        if (!point) return null
        const color = train.selected ? "var(--sel)" : train.late ? "var(--late)" : "var(--ink)"
        const width = Math.max(28, train.name.length * 11 + 10)
        return (
          <g key={train.id} className="cursor-pointer" role="button" aria-label={train.name} data-train={train.id} onClick={e => { e.stopPropagation(); onTrain?.(train.id) }}>
            <circle cx={point[0]} cy={point[1]} r={5} fill={color} stroke="var(--panel)" strokeWidth={1.5} />
            <rect x={point[0] - width / 2} y={point[1] - 25} width={width} height={17} rx={8.5} fill={color} />
            <text x={point[0]} y={point[1] - 13} textAnchor="middle" fontSize={11} fontWeight={600} fill="var(--panel)">{train.name}</text>
          </g>
        )
      })}
    </>
  )
}

/** A station drawn as its tracks: each a line along the axis, platforms as bars beside them, turnouts as diagonals */
function StationTracks({ place, x, y, on, onClick }: { place: Place; x: number; y: number; on: boolean; onClick: () => void }) {
  const lanes = place.lanes.length ? place.lanes : [{ offset: 0, platform: true, name: "", colors: [] as string[] }]
  const degrees = place.angle * 180 / Math.PI, n = lanes.length
  const width = (n - 1) * TRACK
  const upright = Math.abs(Math.sin(place.angle)) > 0.7
  return (
    <g className="cursor-pointer" onClick={onClick} role="button" aria-label={place.name.split("|")[0]}>
      <g transform={`translate(${x} ${y}) rotate(${degrees})`}>
        <rect x={-HALF - 4} y={-width / 2 - 9} width={HALF * 2 + 8} height={width + 18} rx={4} fill={on ? "color-mix(in srgb, var(--sel) 12%, transparent)" : "transparent"} stroke={on ? "var(--sel)" : "none"} />
        {lanes.map((lane, i) => {
          const o = (i - (n - 1) / 2) * TRACK
          return <line key={"l" + i} x1={-HALF} y1={o} x2={HALF} y2={o} stroke={lane.colors[0] || "var(--grid-strong)"} strokeWidth={lane.colors.length ? 2.5 : 2} strokeLinecap="round" />
        })}
        {place.turnouts.map((t, i) => {
          const x1 = t.end * (HALF - 20), x2 = t.end * (HALF - 4)
          return <line key={"t" + i} x1={x1} y1={laneOffset(place, t.from)} x2={x2} y2={laneOffset(place, t.to)} stroke="var(--ink)" strokeWidth={1.4} strokeLinecap="round" />
        })}
        {lanes.map((lane, i) => {
          if (!lane.platform) return null
          // The platform on the outer side of its track
          const o = (i - (n - 1) / 2) * TRACK, side = o < 0 || (o === 0 && n > 1) ? -1 : 1
          return <rect key={"p" + i} x={-HALF + 10} y={o + (side < 0 ? -7.5 : 3)} width={HALF * 2 - 20} height={4.5} rx={1} fill="var(--panel)" stroke="var(--ink)" strokeWidth={1}><title>{tr("{0} 号站台", lane.name || "?")}</title></rect>
        })}
      </g>
      <text x={upright ? x + width / 2 + 10 : x} y={upright ? y + 4 : y + width / 2 + 20} textAnchor={upright ? "start" : "middle"} fontSize={12} fontWeight={on ? 700 : 500}
        fill="var(--ink)" stroke="var(--bg)" strokeWidth={3} paintOrder="stroke">{place.name.split("|")[0]}</text>
      <title>{tr("{0}：{1} 股，{2} 个站台", place.name.split("|")[0], lanes.length, lanes.filter(l => l.platform).length)}</title>
    </g>
  )
}
