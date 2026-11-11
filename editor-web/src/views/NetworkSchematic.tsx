/**
 * The whole network shares one graph for actual curves and schematic wiring:
 * - "tracks" (core/trackSchematic.ts): real rails and shared switch nodes under a common distance compression,
 *   platforms beside their tracks, with wider track spacing and labels for sidings. Nothing is replaced by inferred
 *   station throats or independently laid-out corridor chords. orthogonalTracks relocates this graph for the schematic;
 * - "logical" (the ownership layer), stations as small boxes showing their tracks and the sections between them as
 *   parallel lines, each in the colour of its logical line (or of the lines owning it, without logical lines); with a
 *   brush in hand, a click on a section paints all of its rails.
 * Station labels keep their size at any zoom. Click a station for its tracks; "在实际图上看" shows the place on the real
 * map.
 */
import { useEffect, useMemo, useRef, useState } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { abstractNetwork, type Corridor, type Place, type Schematic } from "../core/abstractNet"
import { paint, useBrush } from "./OwnershipPanel"
import { tr } from "../i18n"
import { colorOf } from "./now"
import { trackSchematic } from "../core/trackSchematic"
import { TrackMapDrawing } from "./TrackMapDrawing"
import { orthogonalTracks } from "../core/orthogonalTracks"

const GAP = 5, LANE = 7, BOX_W = 44

/** A train to show on the tracks drawing: on the rail its head is on */
export type TrainMark = { id: string; name: string; rail: number; late: boolean; selected: boolean }

export function NetworkSchematic({ onShowReal, mode = "tracks", shape = "real", trains, onTrain }: { onShowReal: () => void; mode?: "tracks" | "logical"; shape?: "real" | "schematic"; trains?: TrainMark[]; onTrain?: (id: string) => void }) {
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
  const platformData = v.data?.topology?.platforms
  const realMap = useMemo(() => data?.nodes && data?.rails ? trackSchematic(data, platformData || []) : null, [data, platformData])
  const trackMap = useMemo(() => realMap && shape === "schematic" ? orthogonalTracks(realMap) : realMap, [realMap, shape])
  const schematic = useMemo<Schematic | null>(() => (data && data.nodes && data.rails ? abstractNetwork(data, v.data && v.data.topology ? v.data.topology.platforms : []) : null), [data]) // eslint-disable-line react-hooks/exhaustive-deps
  const box = useRef<HTMLDivElement>(null)
  const [view, setView] = useState<{ x: number; y: number; k: number } | null>(null)
  const [chosen, setChosen] = useState<string | null>(null)
  const drag = useRef<{ x: number; y: number; vx: number; vy: number; moved: boolean } | null>(null)

  // Fit the complete diagram, including its local switchwork, on first load or after changing layers.
  const fit = () => {
    if (!schematic || !box.current) return
    const w = box.current.clientWidth || 800, h = box.current.clientHeight || 600
    const bounds = mode === "tracks" && trackMap ? trackMap : schematic
    const k = Math.max(0.05, Math.min(2.5, Math.min(w / bounds.width, h / bounds.height)))
    setView({ x: (w - bounds.width * k) / 2, y: (h - bounds.height * k) / 2, k })
  }
  useEffect(fit, [schematic, trackMap, mode]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!box.current || typeof ResizeObserver === "undefined") return
    let seen = 0
    const observer = new ResizeObserver(() => { if (seen++ < 2) fit() })
    observer.observe(box.current)
    return () => observer.disconnect()
  }, [schematic, trackMap, mode]) // eslint-disable-line react-hooks/exhaustive-deps

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
        <svg className="absolute inset-0 h-full w-full" role="img" aria-label={mode === "tracks" ? tr(shape === "real" ? "全网实际配线图" : "全网示意配线图") : tr("全网逻辑图")}>
          {mode === "tracks"
            ? trackMap && <g transform={`translate(${view?.x ?? 0} ${view?.y ?? 0}) scale(${k})`}><TrackMapDrawing map={trackMap} zoom={k} chosen={chosen} onChoose={id => { if (!drag.current?.moved) setChosen(id === chosen ? null : id) }} trains={trains} onTrain={onTrain} /></g>
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
  const lanes = place.lanes.length ? place.lanes : [{ offset: 0, platform: true, name: "", colors: [], negative: true, positive: true }]
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
    return line ? [colorOf(line.color)] : []
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
