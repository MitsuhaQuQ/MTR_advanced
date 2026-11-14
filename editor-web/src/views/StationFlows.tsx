/**
 * A station's track use, opened by clicking a station on the whole-network map (0.80.0, user 2026-11-14): the tracks
 * around it in grey, and on each an arrow line flowing the way each line's trains run it, in the line's colour. Lines
 * sharing a track are drawn over each other, their dashes taking turns; the moves of the duties (turning back, changing
 * platforms, out and back) are dotted; a track run both ways has a red glow. For finding the conflicting flows of the
 * duties: click a line in the legend to show it alone.
 */
import { useEffect, useMemo, useState } from "react"
import { tr } from "../i18n"
import { stationFlows, type Flow, type FlowNet, type Point } from "../core/stationFlows"
import { railMidpoint } from "../core/trackSchematic"

export function StationFlows({ net, stationId, name, onClose, onShowReal }: { net: FlowNet; stationId: string; name: string; onClose: () => void; onShowReal: () => void }) {
  const [radius, setRadius] = useState(160)
  const [only, setOnly] = useState<string | null>(null)
  const [moves, setMoves] = useState(true)
  const view = useMemo(() => stationFlows(net, stationId, radius), [net, stationId, radius])
  const still = typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose() }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [onClose])
  if (!view) return null
  const [x0, z0, x1, z1] = view.box
  const pad = 12, width = Math.max(x1 - x0, 20) + pad * 2, height = Math.max(z1 - z0, 20) + pad * 2
  // Sizes in blocks, from how big the drawing is, so lines look the same at any station size
  const unit = Math.max(width, height) / 420
  const shownFlows = view.flows.filter(f => (moves || !f.move) && (!only || f.lineKey === only))
  // The flows on each rail each way, so their dashes take turns
  const groups = new Map<string, Flow[]>()
  shownFlows.forEach(f => { const key = `${f.rail}|${f.forward}`; if (!groups.has(key)) groups.set(key, []); groups.get(key)!.push(f) })
  const railPoints = new Map(view.rails.map(r => [r.rail, r.points]))
  const both = view.rails.filter(r => r.both).length
  const path = (points: Point[]) => points.map(p => `${p[0] - x0 + pad},${p[1] - z0 + pad}`).join(" ")

  return (
    <div role="dialog" aria-label={tr("{0} 股道运用", name)} className="absolute inset-y-3 right-3 flex w-[min(760px,72%)] flex-col rounded-lg border border-grid bg-panel text-[13px]" style={{ boxShadow: "var(--shadow)" }}>
      <div className="flex flex-wrap items-center gap-2 border-b border-grid px-3 py-2">
        <b className="min-w-0 flex-1 truncate text-[14px]">{tr("{0} 股道运用", name)}</b>
        <label className="flex items-center gap-1 text-dim">{tr("范围")}
          <select className="h-6 rounded-md border border-ctl bg-panel px-1" value={radius} onChange={e => setRadius(Number(e.target.value))} aria-label={tr("范围")}>
            {[80, 160, 300, 600].map(r => <option key={r} value={r}>{tr("{0} 格", r)}</option>)}
          </select>
        </label>
        <label className="flex items-center gap-1"><input type="checkbox" checked={moves} onChange={e => setMoves(e.target.checked)} />{tr("运用走行")}</label>
        <button className="btn h-6" onClick={onShowReal}>{tr("在实际图上看")}</button>
        <button className="btn btn-ghost h-6 px-1.5" onClick={onClose} aria-label={tr("关闭")}>✕</button>
      </div>
      <div className="relative min-h-0 flex-1">
        <svg className="absolute inset-0 h-full w-full" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="xMidYMid meet" role="img" aria-label={tr("{0} 股道运用图", name)}>
          {view.rails.filter(r => r.both).map(r => <polyline key={"b" + r.rail} points={path(r.points)} fill="none" stroke="#e53935" strokeOpacity={0.28} strokeWidth={unit * 9} strokeLinecap="round" strokeLinejoin="round"><title>{tr("两个方向都有车走")}</title></polyline>)}
          {view.rails.map(r => <polyline key={r.rail} points={path(r.points)} fill="none" stroke="var(--grid-strong)" strokeWidth={unit * (r.kind === 1 ? 5 : 2.5)} strokeLinecap="round" strokeLinejoin="round" />)}
          {[...groups.entries()].map(([key, group]) => {
            const points = railPoints.get(group[0].rail)
            if (!points) return null
            const way = group[0].forward ? points : [...points].reverse()
            return group.map((flow, i) => {
              const dash = unit * (flow.move ? 3 : 9), gap = unit * (flow.move ? 5 : 7)
              const period = dash * group.length + gap
              const offset = i * dash
              return (
                <polyline key={key + "|" + i} points={path(way)} fill="none" stroke={flow.color} strokeWidth={unit * (flow.move ? 2.2 : 3.2)} strokeLinecap="butt" strokeLinejoin="round"
                  strokeDasharray={`${dash} ${period - dash}`} strokeDashoffset={period + offset} data-flow={flow.move ? "move" : "service"}>
                  {!still && <animate attributeName="stroke-dashoffset" from={period + offset} to={offset} dur="1.2s" repeatCount="indefinite" />}
                  <title>{flow.move ? tr("运用走行") : tr("载客运行")}</title>
                </polyline>
              )
            })
          })}
          {[...groups.entries()].map(([key, group]) => {
            const points = railPoints.get(group[0].rail)
            if (!points || points.length < 2) return null
            const way = group[0].forward ? points : [...points].reverse()
            const mid = railMidpoint(way)
            const end = way[way.length - 1], start = way[0]
            const angle = Math.atan2(end[1] - start[1], end[0] - start[0]) * 180 / Math.PI
            const s = unit * 5
            return <polygon key={"a" + key} points={`${s},0 ${-s},${-s * 0.8} ${-s},${s * 0.8}`} fill={group[0].color} stroke="var(--panel)" strokeWidth={unit * 0.8}
              transform={`translate(${mid[0] - x0 + pad} ${mid[1] - z0 + pad}) rotate(${angle})`} />
          })}
        </svg>
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-grid px-3 py-2">
        {view.lines.map(line => (
          <button key={line.lineKey} className={"flex items-center gap-1 rounded px-1 " + (only === line.lineKey ? "bg-sel/20 font-semibold" : "")} onClick={() => setOnly(only === line.lineKey ? null : line.lineKey)}
            aria-pressed={only === line.lineKey}>
            <span className="h-[4px] w-5 rounded-pill" style={{ background: line.color }} />{line.name}
            {line.moves > 0 && <span className="text-dim">{tr("（运用走行 {0} 段）", line.moves)}</span>}
          </button>
        ))}
        {!view.lines.length && <span className="text-dim">{tr("附近没有线路或运用经过")}</span>}
        <span className="flex-1" />
        <span className="text-dim">{both ? tr("红底：两个方向都有车走的股道 {0} 段", both) : tr("没有两个方向都有车走的股道")}</span>
      </div>
    </div>
  )
}
