import { railMidpoint, type Point, type TrackMap } from "../core/trackSchematic"
import type { TrainMark } from "./NetworkSchematic"
import { tr } from "../i18n"

/** Both the main view and regression previews use these exact rail paths. */
export function TrackMapDrawing({ map, chosen, onChoose, trains, onTrain, zoom = 1 }: { map: TrackMap; chosen: string | null; onChoose: (id: string) => void; trains?: TrainMark[]; onTrain?: (id: string) => void; zoom?: number }) {
  const font = 13 / Math.max(0.05, zoom)
  const path = (points: Point[]) => points.map(p => p.join(",")).join(" ")
  return <>
    {map.rails.map(r => <polyline key={r.rail} data-rail={r.rail} points={path(r.points)} fill="none" stroke={r.colors[0] || "var(--grid-strong)"} strokeWidth={r.colors.length ? 2.5 : 1.8} vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" strokeDasharray={r.kind === 2 ? "7 3" : r.kind === 3 ? "2 3" : undefined} />)}
    {map.rails.filter(r => r.kind === 1).map(r => {
      const a = r.points[0], b = r.points[r.points.length - 1], dx = b[0] - a[0], dy = b[1] - a[1], length = Math.hypot(dx, dy) || 1
      const ux = dx / length, uy = dy / length, m = railMidpoint(r.points)
      const siblings = map.rails.filter(t => t.station === r.station && t.kind === 1).map(t => railMidpoint(t.points))
      const centre = siblings.reduce((s, p) => s + (p[0] - m[0]) * -uy + (p[1] - m[1]) * ux, 0)
      const side = centre > 0 ? -1 : 1, inset = Math.min(6, length / 4), offset = side * 6
      return <g key={r.rail} data-platform-station={r.station} onClick={() => r.station && onChoose(r.station)} className="cursor-pointer">
        <line x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]} stroke={chosen === r.station ? "var(--sel)" : "transparent"} strokeWidth={16} opacity={0.2} />
        <line x1={a[0] + ux * inset - uy * offset} y1={a[1] + uy * inset + ux * offset} x2={b[0] - ux * inset - uy * offset} y2={b[1] - uy * inset + ux * offset} stroke="var(--ink)" strokeWidth={5} />
        <line x1={a[0] + ux * inset - uy * offset} y1={a[1] + uy * inset + ux * offset} x2={b[0] - ux * inset - uy * offset} y2={b[1] - uy * inset + ux * offset} stroke="var(--panel)" strokeWidth={3} />
        <title>{tr("{0} 号站台", r.name || "?")}</title>
      </g>
    })}
    {map.ends.map((p, i) => <circle key={i} cx={p[0]} cy={p[1]} r={2.4 / zoom} fill="var(--grid-strong)"><title>{tr("尽头")}</title></circle>)}
    {map.junctions.map((p, i) => <circle key={i} cx={p[0]} cy={p[1]} r={2 / zoom} fill="var(--ink)"><title>{tr("道岔")}</title></circle>)}
    {map.rails.filter(r => r.kind === 2 || r.kind === 3).map(r => { const p = railMidpoint(r.points); return <text key={r.rail} x={p[0]} y={p[1] - 8} textAnchor="middle" fontSize={11} fill="var(--muted)" stroke="var(--bg)" strokeWidth={3} paintOrder="stroke">{tr(r.kind === 2 ? "存车线" : "留置位")}</text> })}
    {map.stations.map(s => <g key={s.id} data-station={s.id} role="button" aria-label={s.name.split("|")[0]} tabIndex={0} onClick={() => onChoose(s.id)} onKeyDown={e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onChoose(s.id) } }} className="cursor-pointer"><text x={s.x} y={s.y + (s.vertical ? 0 : font / 2)} textAnchor={s.vertical ? "start" : "middle"} fontSize={font} fill="var(--ink)" stroke="var(--bg)" strokeWidth={3} paintOrder="stroke">{s.name.split("|")[0]}</text></g>)}
    {(trains || []).map(train => {
      const rail = map.rails[train.rail]
      if (!rail) return null
      const p = railMidpoint(rail.points)
      return <g key={train.id} role="button" aria-label={train.name} data-train={train.id} onClick={() => onTrain?.(train.id)} className="cursor-pointer">
        <polyline points={path(rail.points)} fill="none" stroke="var(--panel)" strokeWidth={10} vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" />
        <polyline points={path(rail.points)} fill="none" stroke={train.selected ? "var(--sel)" : train.late ? "var(--late)" : "var(--ink)"} strokeWidth={6} vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" />
        <polyline points={path(rail.points)} fill="none" stroke="transparent" strokeWidth={16} vectorEffect="non-scaling-stroke" pointerEvents="stroke" />
        <text x={p[0]} y={p[1] - 10 / Math.max(0.05, zoom)} textAnchor="middle" fontSize={12 / Math.max(0.05, zoom)} fill="var(--ink)" stroke="var(--bg)" strokeWidth={3} paintOrder="stroke">{train.name}</text>
      </g>
    })}
  </>
}
