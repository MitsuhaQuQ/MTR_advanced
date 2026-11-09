/**
 * The whole network as a metro-map schematic (core/abstractNet.ts): stations as small boxes showing their tracks side by
 * side (platforms numbered and in the colours of the routes stopping there, tracks through thin), corridors between them
 * with as many parallel lines as tracks, in the routes' colours, crossovers on the line marked. The boxes keep their size
 * at any zoom, so a station's track layout reads at a glance. Click a station for its tracks; "实际图" shows the same
 * place on the real map, where boundaries are edited.
 */
import { useEffect, useMemo, useRef, useState } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { abstractNetwork, type Place, type Schematic } from "../core/abstractNet"
import { tr } from "../i18n"

const GAP = 5, LANE = 7, BOX_W = 44

export function NetworkSchematic({ onShowReal }: { onShowReal: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  // The old code reads the network on its own timer and only redraws its canvas: look for a new one now and then
  const [, setSeen] = useState<unknown>(null)
  useEffect(() => {
    const timer = setInterval(() => setSeen(legacy.v.net.data), 400)
    return () => clearInterval(timer)
  }, [])
  const data = v.net.data
  const schematic = useMemo<Schematic | null>(() => (data && data.nodes && data.rails ? abstractNetwork(data, v.topo ? v.topo.platforms : []) : null), [data]) // eslint-disable-line react-hooks/exhaustive-deps
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
      <span className="hidden text-dim md:inline">{tr("每条线一股道，颜色是走它的线路；车站框里一行一股，粗的是站台。滚轮缩放，拖动平移。")}</span>
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
        <svg className="absolute inset-0 h-full w-full" role="img" aria-label={tr("全网示意配线图")}>
          {schematic.corridors.map((c, i) => {
            const a = at.get(c.a)!, b = at.get(c.b)!
            const x1 = sx(a), y1 = sy(a), x2 = sx(b), y2 = sy(b)
            const length = Math.hypot(x2 - x1, y2 - y1) || 1, nx = -(y2 - y1) / length, ny = (x2 - x1) / length
            return (
              <g key={i}>
                {Array.from({ length: c.tracks }, (_, t) => {
                  const o = (t - (c.tracks - 1) / 2) * GAP
                  const color = c.colors.length ? c.colors[t % c.colors.length] : "var(--grid-strong)"
                  return <line key={t} x1={x1 + nx * o} y1={y1 + ny * o} x2={x2 + nx * o} y2={y2 + ny * o} stroke={color} strokeWidth={c.colors.length ? 2.5 : 2} strokeLinecap="round" />
                })}
                {c.crossovers > 0 && c.tracks > 1 && (() => {
                  const mx = (x1 + x2) / 2, my = (y1 + y2) / 2, ux = (x2 - x1) / length * 5, uy = (y2 - y1) / length * 5, w = (c.tracks - 1) / 2 * GAP
                  return <g stroke="var(--ink)" strokeWidth={1.2}><line x1={mx - ux + nx * w} y1={my - uy + ny * w} x2={mx + ux - nx * w} y2={my + uy - ny * w} /><line x1={mx - ux - nx * w} y1={my - uy - ny * w} x2={mx + ux + nx * w} y2={my + uy + ny * w} /><title>{tr("区间里有 {0} 处渡线", c.crossovers)}</title></g>
                })()}
              </g>
            )
          })}
          {schematic.places.map(p => p.kind === "station"
            ? <StationGlyph key={p.id} place={p} x={sx(p)} y={sy(p)} on={p.id === chosen} onClick={() => { if (!drag.current?.moved) setChosen(p.id === chosen ? null : p.id) }} />
            : <circle key={p.id} cx={sx(p)} cy={sy(p)} r={p.kind === "junction" ? 3 : 2} fill="var(--ink)"><title>{p.kind === "junction" ? tr("道岔") : tr("尽头")}</title></circle>)}
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
