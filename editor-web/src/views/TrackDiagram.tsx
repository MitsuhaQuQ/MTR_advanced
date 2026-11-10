/**
 * The track diagram of a route or a line group, as a schematic (core/schematic.ts): the tracks along the route on
 * parallel lanes, the turnouts, the platforms with their numbers, the stations, the rails in the colours of the routes
 * running on them, and the isolation. Replaces the old page's straightened drawing in the new page; opened from a
 * route's or a line's menu in the navigator (the old code calls routeDiagramDialog / lineDiagramDialog, which come
 * from here). Right-click a rail for the same isolation menu as before.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { diagramHost, legacy, menuHost, useLegacy, type DiagramTarget } from "../legacy/engine"
import { post } from "../api/client"
import { buildSchematic, usageOf, type RouteLayout, type Schematic } from "../core/schematic"
import { tr } from "../i18n"
import { topologyOf } from "../core/topology"

const GAP = 26, TOP = 64, PAD = 32, BOTTOM = 28

export function TrackDiagram() {
  const [target, setTarget] = useState<DiagramTarget | null>(null)
  useEffect(() => {
    diagramHost.current = setTarget
    return () => { diagramHost.current = null }
  }, [])
  if (!target) return null
  return <Dialog target={target} onClose={() => setTarget(null)} />
}

type Loaded = { layout: RouteLayout | null; network: any | null; error: string }

/** The route the diagram follows: the route itself, or the line group's route with the most stops. */
function trunkOf(target: DiagramTarget) {
  const data = legacy.v.data
  if (!data) return null
  if (target.kind === "route") return data.routeById.get(target.routeId) || null
  const line = data.lines.find((l: any) => l.key === target.lineKey)
  return line ? [...line.routes].sort((a: any, b: any) => b.stops.length - a.stops.length)[0] : null
}

function Dialog({ target, onClose }: { target: DiagramTarget; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const trunk = trunkOf(target)
  const line = target.kind === "line" ? v.data?.lines.find((l: any) => l.key === target.lineKey) : null
  const [loaded, setLoaded] = useState<Loaded>({ layout: null, network: null, error: "" })
  const [zoom, setZoom] = useState(0)
  const [tick, setTick] = useState(0)
  const box = useRef<HTMLDivElement>(null)

  // The route's straightened rails from the server (asked again until it has them), and the network for who runs where
  useEffect(() => {
    if (!trunk) return
    let stopped = false
    const mode = String(trunk.lineKey).split("|")[0]
    const load = async (fresh: boolean, tries: number): Promise<void> => {
      try {
        const layout = await post<RouteLayout>("route-layout", { route: trunk.id, halfWidth: 24, fresh })
        if (stopped) return
        if (!layout.ready) {
          if (tries < 15) { setLoaded(l => ({ ...l, error: tr("正在沿路径取轨道…") })); setTimeout(() => load(false, tries + 1), 700) }
          else setLoaded(l => ({ ...l, error: tr("服务端没有回应，关掉再打开试试") }))
          return
        }
        const network = v.net.data && v.net.mode === mode ? v.net.data : await post<any>("network", { mode, fresh: false, version: -1 }).catch(() => null)
        if (!stopped) setLoaded({ layout, network, error: "" })
      } catch {
        if (!stopped) setLoaded(l => ({ ...l, error: tr("读取线路配线图失败") }))
      }
    }
    load(true, 0)
    return () => { stopped = true }
  }, [trunk?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose() }
    addEventListener("keydown", onKey)
    return () => removeEventListener("keydown", onKey)
  }, [onClose])

  const schematic = useMemo<Schematic | null>(() => {
    if (!trunk || !loaded.layout || !loaded.layout.found) return null
    const usage = loaded.network && loaded.network.routes ? usageOf(loaded.network, trunk.id) : new Map()
    const platforms = new Set<string>((line ? line.routes : [trunk]).flatMap((r: any) => fn.platformsOf(r)))
    return buildSchematic(loaded.layout, usage, trunk.id, platforms)
  }, [loaded, trunk?.id, line, tick]) // eslint-disable-line react-hooks/exhaustive-deps

  // The first time, the whole line fits the width
  useEffect(() => {
    if (schematic && !zoom && box.current) setZoom(Math.max(0.3, Math.min(6, (box.current.clientWidth - PAD * 2) / Math.max(schematic.width, 1))))
  }, [schematic, zoom])

  const title = target.kind === "line" ? tr("线路组配线图：{0}", fn.shortName(line?.name || "")) : tr("线路配线图：{0}", trunk ? fn.routeTitle(trunk) : "?")
  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-label={title}>
      <div className="absolute inset-0" style={{ background: "color-mix(in srgb, var(--ink) 30%, transparent)" }} onClick={onClose} />
      <div className="relative flex max-h-full w-full max-w-[1200px] flex-col rounded-lg border border-grid bg-panel" style={{ boxShadow: "var(--shadow)" }}>
        <header className="flex flex-wrap items-center gap-2 border-b border-grid px-4 py-3">
          <h2 className="m-0 min-w-0 flex-1 truncate text-[16px] font-bold">{title}</h2>
          <button className="btn" onClick={() => setZoom(z => Math.max(0.1, z / 1.4))} aria-label={tr("缩小")}>−</button>
          <button className="btn" onClick={() => setZoom(z => Math.min(20, z * 1.4))} aria-label={tr("放大")}>＋</button>
          <button className="btn" onClick={() => setZoom(0)}>{tr("全图")}</button>
          <button className="btn btn-ghost" onClick={onClose} aria-label={tr("关闭")}>✕</button>
        </header>
        <div ref={box} className="scroll-thin min-h-0 flex-1 overflow-auto px-4 py-3">
          {!trunk ? <p className="text-[13px] text-dim">{tr("没有线路")}</p>
            : !loaded.layout ? <p className="text-[13px] text-dim">{loaded.error || tr("正在读取…")}</p>
            : !loaded.layout.found ? <p className="text-[13px] text-warn">{tr("沿轨道寻不出这条线路的路径。先右键线路“编辑停站…”，在连通图里修好股道。")}</p>
            : schematic && zoom > 0 && <Drawing schematic={schematic} trunk={trunk} zoom={zoom} onChanged={() => setTick(t => t + 1)} onOpenStop={stop => { onClose(); if (v.data.canEdit && trunk.stops.length > 1) fn.stopsDialog(trunk, stop) }} />}
        </div>
        <Legend layout={loaded.layout} line={Boolean(line)} />
      </div>
    </div>
  )
}

/** The isolation of a rail as the draft has it: a boundary set or cleared in the draft shows. */
function statusOf(key: string, status: number) {
  const drafted = legacy.v.draft.boundaries?.[key]
  if (drafted === undefined) return status
  return drafted ? 3 : status === 3 ? 0 : status
}

function Drawing({ schematic, trunk, zoom, onChanged, onOpenStop }: { schematic: Schematic; trunk: any; zoom: number; onChanged: () => void; onOpenStop: (stop: number) => void }) {
  const { fn } = legacy
  const { min, max } = schematic.lanes
  const width = Math.round(schematic.width * zoom + PAD * 2)
  const height = TOP + (max - min) * GAP + BOTTOM + 24
  const x = (value: number) => PAD + value * zoom
  const y = (lane: number) => TOP + 12 + (max - lane) * GAP
  const colour = (value: number) => fn.colorOf(value)
  const menu = useCallback((e: React.MouseEvent, a: string, b: string, status: number) => {
    e.preventDefault()
    if (!legacy.v.data.canEdit) { menuHost.current?.open(e.clientX, e.clientY, [[tr("没有编辑权限"), null]]); return }
    fn.railMenu(e.nativeEvent, trunk, { a, b, status }, onChanged)
  }, [fn, trunk, onChanged])
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={tr("线路配线图")} className="block" style={{ fontFamily: "var(--font-ui)" }}>
      {/* stations: a light line down from the name, the stop's number for the route */}
      {schematic.stations.map((s, i) => (
        <g key={i} className={s.stop >= 0 ? "cursor-pointer" : ""} onClick={() => s.stop >= 0 && onOpenStop(s.stop)}>
          <line x1={x(s.x)} x2={x(s.x)} y1={TOP - 8} y2={height - BOTTOM} stroke="var(--grid)" strokeDasharray="6 4" />
          <text x={x(s.x)} y={TOP - 30} textAnchor="middle" fontSize={13} fontWeight={600} fill="var(--ink)">{fn.shortName(s.name) || tr("（未命名车站）")}</text>
          {s.stop >= 0 && <text x={x(s.x)} y={TOP - 14} textAnchor="middle" fontSize={11} fill="var(--muted)">{tr("第 {0} 站", s.stop + 1)}</text>}
          <title>{s.stop >= 0 ? tr("{0}，点一下打开这一站", fn.shortName(s.name)) : fn.shortName(s.name)}</title>
        </g>
      ))}
      {/* long runs shortened */}
      {schematic.breaks.map((b, i) => (
        <g key={"b" + i} stroke="var(--muted)" strokeWidth={1.2}>
          <line x1={x(b) - 5} y1={y(0) + 7} x2={x(b) - 1} y2={y(0) - 7} />
          <line x1={x(b) + 1} y1={y(0) + 7} x2={x(b) + 5} y2={y(0) - 7} />
        </g>
      ))}
      {/* rails: grey where no route runs, in the routes' colours where they do, the route's own wider */}
      {schematic.segments.map((s, i) => {
        const x1 = x(s.x1), x2 = x(s.x2), y1 = y(s.lane1), y2 = y(s.lane2)
        const status = statusOf(s.key, s.status)
        const others = s.routes.filter(r => r.route !== trunk.id).slice(0, 3)
        return (
          <g key={i}>
            <line x1={x1} y1={y1} x2={x2} y2={y2} stroke="var(--grid-strong)" strokeWidth={2} strokeLinecap="round" />
            {others.map((r, k) => {
              const shift = s.ours ? (k + 1) * 3.5 * (s.lane1 >= 0 ? -1 : 1) : (k - (others.length - 1) / 2) * 3
              return <line key={k} x1={x1} y1={y1 + shift} x2={x2} y2={y2 + shift} stroke={colour(r.color)} strokeWidth={2} strokeLinecap="round" opacity={0.85} />
            })}
            {s.ours && <line x1={x1} y1={y1} x2={x2} y2={y2} stroke={colour(trunk.color)} strokeWidth={5} strokeLinecap="round" />}
            {status === 3 && <line x1={x1} y1={y1} x2={x2} y2={y2} stroke="var(--boundary)" strokeWidth={2} strokeDasharray="2 4" />}
            {status === 1 && <line x1={x1} y1={y1} x2={x2} y2={y2} stroke="var(--connecting)" strokeWidth={2} strokeDasharray="6 3" />}
            {status === 2 && <line x1={x1} y1={y1} x2={x2} y2={y2} stroke="var(--muted)" strokeWidth={2} strokeDasharray="6 3" />}
            <line x1={x1} y1={y1} x2={x2} y2={y2} stroke="transparent" strokeWidth={12} onContextMenu={e => menu(e, s.a, s.b, s.status)}>
              <title>{[s.routes.length ? s.routes.map(r => legacy.v.data.routeById.get(r.route) ? fn.routeTitle(legacy.v.data.routeById.get(r.route)) : r.route).join("、") : tr("没有线路走这段轨道"),
                status === 3 ? tr("寻路边界") : status === 1 ? tr("联络线独占") : status === 2 ? tr("已放行") : ""].filter(Boolean).join(" · ")}</title>
            </line>
          </g>
        )
      })}
      {/* platforms beside their track, on the side away from the path: the route's in its colour */}
      {schematic.platforms.map(p => {
        const above = p.lane >= 0, top = above ? y(p.lane) - 11 : y(p.lane) + 5
        const w = Math.max(6, x(p.x2) - x(p.x1))
        return (
          <g key={p.id}>
            <rect x={x(p.x1)} y={top} width={w} height={6} rx={1.5} fill={p.ours ? colour(trunk.color) : "var(--panel-2)"} stroke={p.ours ? "none" : "var(--grid-strong)"} />
            <text x={x(p.x1) + w / 2} y={above ? top - 3 : top + 16} textAnchor="middle" fontSize={10} fill={p.ours ? "var(--ink)" : "var(--muted)"} className="num">{p.name || "?"}</text>
            <title>{`${fn.shortName(p.stationName) || tr("不属于车站")} ${topologyOf(legacy.v.data?.topology).trackLabel(p.id, p.name, p.passingTrack)}`}</title>
          </g>
        )
      })}
      {/* turnouts and boundary nodes */}
      {schematic.turnouts.map((t, i) => <circle key={"t" + i} cx={x(t.x)} cy={y(t.lane)} r={2.6} fill="var(--panel)" stroke="var(--ink)" strokeWidth={1.4}><title>{tr("道岔")}</title></circle>)}
      {schematic.boundaries.map((b, i) => <circle key={"n" + i} cx={x(b.x)} cy={y(b.lane)} r={4} fill="var(--boundary)"><title>{tr("寻路边界节点（在游戏里节点界面改）")}</title></circle>)}
    </svg>
  )
}

function Legend({ layout, line }: { layout: RouteLayout | null; line: boolean }) {
  const missing = layout ? layout.stops.filter(s => s < 0).length : 0
  return (
    <footer className="flex flex-col gap-1 border-t border-grid px-4 py-2 text-[12px] leading-[18px] text-dim">
      <span>{tr("每条横线是一股轨道，按离线路远近排，不按实际距离；没有道岔和站台的长区间压短了（//）。粗线是这条线路走的轨道，细彩线是别的线路，灰线没有线路走；小圆点是道岔，短块是站台。")}</span>
      <span>{tr("紫色点线是寻路边界，橙色虚线是联络线独占，灰色虚线是已放行；右键一段轨道改，放进草稿。")}{line ? " " + tr("线路组按停站最多的种别画，分支只画到离开这条路径为止。") : ""}</span>
      {missing > 0 && <span>{tr("有 {0} 站的站台不在路径上，没有标出。", missing)}</span>}
      {layout?.truncated && <span>{tr("轨道太多，只画了一部分。")}</span>}
    </footer>
  )
}
