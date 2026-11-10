/**
 * A station's own track diagram in the stops editor (replaces the old stops dialog's station part, oldStopsDialog): the
 * rails around the station as the game has them, turned so the train runs left to right along the track picked. The
 * station's tracks on the route's rails are clicked like the strip's, fixing the track the same way (pinTrack); other
 * platforms are grey. Dragged to move, ＋/− to show less or more around it. Below: the station's throat as the game works it
 * out, and at the first or last stop the platforms to start from or turn back at in turn. The geometry is
 * core/stationLayout.ts; the rails come from api/layout, asked again while the game is still reading the map.
 */
import { useEffect, useRef, useState } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { tr } from "../i18n"
import { topologyOf } from "../core/topology"
import { stationView, toggleTurnPlatform, turnOf, zoomMargin, type LayoutData, type Point, type TurnKind } from "../core/stationLayout"
import { routeTitle, shortName } from "./now"
import type { StopsModel } from "./StopsEditor"

const T = () => topologyOf(legacy.v.data?.topology)
const hint = "m-0 text-[12px] text-dim"

type Load = { key: string; data: LayoutData | null; error: string }

export function StationTracks({ r, model, i, onPin, onStop, onBack }: {
  r: any; model: StopsModel; i: number
  /** Fixes or frees a track of this stop, as the strip does */
  onPin: (p: number) => void
  /** Shows another stop's diagram */
  onStop: (i: number) => void
  onBack: () => void
}) {
  useLegacy()
  const { fn } = legacy
  const topo = T()
  const n = model.layers.length
  const station: string = topo.platforms[model.layers[i][0]].station
  const trackIds: string[] = model.layers[i].map(p => topo.platforms[p].id)
  const [margin, setMargin] = useState(48)
  const [pan, setPan] = useState<Point>([0, 0])
  const key = JSON.stringify([station, margin, trackIds])
  const [load, setLoad] = useState<Load>({ key: "", data: null, error: "" })
  // The rails around the station, asked for again a few times while the game is still reading the map
  useEffect(() => {
    let alive = true, timer = 0
    const ask = async (tries: number) => {
      try {
        const result = await fn.sendJson("api/layout", "POST", { station, margin, tracks: trackIds })
        if (!alive) return
        if (!result.ready) {
          if (tries < 8) { setLoad({ key, data: null, error: tr("正在读取轨道…") }); timer = window.setTimeout(() => ask(tries + 1), 600) }
          else setLoad({ key, data: null, error: tr("服务端没有回应，稍后再点一次站名") })
        } else setLoad({ key, data: result, error: "" })
      } catch {
        if (alive) setLoad({ key, data: null, error: tr("读取配线图失败") })
      }
    }
    ask(0)
    return () => { alive = false; clearTimeout(timer) }
  }, [key]) // eslint-disable-line react-hooks/exhaustive-deps

  const solution = T().solveStops(model.layers, model.picks, model.pins)
  const before = i > 0 ? "← " + topo.stationLabel(model.layers[i - 1][0]) : ""
  const after = i + 1 < n ? topo.stationLabel(model.layers[i + 1][0]) + " →" : ""
  const zoom = (zoomIn: boolean) => { setMargin(zoomMargin(margin, zoomIn)); setPan([0, 0]) }
  return (
    <section className="flex flex-col gap-2" aria-label={tr("{0} 配线图", topo.stationLabel(model.layers[i][0]))}>
      <div className="flex flex-wrap items-center gap-2">
        <button className="btn btn-ghost h-7 px-2" onClick={onBack}>{tr("← 车站列表")}</button>
        <b className="text-[14px]">{tr("{0} 配线图", topo.stationLabel(model.layers[i][0]))}</b>
        <span className="flex-1" />
        <button className="btn h-7 px-2" disabled={i === 0} onClick={() => onStop(i - 1)} title={tr("上一站")}>‹</button>
        <span className="num text-[12px] text-dim">{i + 1} / {n}</span>
        <button className="btn h-7 px-2" disabled={i === n - 1} onClick={() => onStop(i + 1)} title={tr("下一站")}>›</button>
        <button className="btn h-7 px-2" onClick={() => zoom(true)} title={tr("放大")} aria-label={tr("放大")}>＋</button>
        <button className="btn h-7 px-2" onClick={() => zoom(false)} title={tr("缩小")} aria-label={tr("缩小")}>−</button>
      </div>
      {load.key !== key || !load.data
        ? <p className={hint}>{load.key === key && load.error ? load.error : tr("正在读取…")}</p>
        : <>
          <div className="flex justify-between text-[12px] text-dim"><span>{before}</span><span>{after}</span></div>
          <Diagram data={load.data} station={station} tracks={new Set(trackIds)} model={model} i={i} solution={solution} margin={margin} pan={pan} setPan={setPan} onPin={onPin} />
          {load.data.truncated && <p className={hint}>{tr("轨道太多，只画了一部分。")}</p>}
          <p className={hint}>{tr("列车从左往右开。灰色的是别的车站或轨道不连的站台；拖动平移，＋/− 缩放。")}</p>
        </>}
      <Throat r={r} i={i} />
      <TurnPlatforms r={r} model={model} i={i} />
    </section>
  )
}

function Diagram({ data, station, tracks, model, i, solution, margin, pan, setPan, onPin }: {
  data: LayoutData; station: string; tracks: Set<string>; model: StopsModel; i: number; solution: any; margin: number; pan: Point
  setPan: (pan: Point) => void; onPin: (p: number) => void
}) {
  const topo = T()
  // The way the train runs here: along the track picked, the way it arrives (or leaves, at the first stop)
  const state = solution && solution.ok ? (i > 0 ? solution.arrive[i] : solution.depart[0]) : undefined
  const forwards = state === undefined || (state & 1) === 0
  const view = stationView(data, station, tracks, topo.platforms[model.picks[i]].id, forwards, margin, pan)
  const { unit } = view
  const svg = useRef<SVGSVGElement>(null)
  const [drag, setDrag] = useState<{ x: number; y: number; perPixel: number; moved: Point } | null>(null)
  const box = drag ? [view.viewBox[0] + drag.moved[0], view.viewBox[1] + drag.moved[1], view.viewBox[2], view.viewBox[3]] : view.viewBox
  return (
    <svg ref={svg} className="layout-svg w-full touch-none select-none rounded-md border border-grid bg-panel2" viewBox={box.join(" ")} preserveAspectRatio="xMidYMid meet"
      style={{ aspectRatio: `${view.width} / ${view.height}`, cursor: drag ? "grabbing" : "grab" }} role="img" aria-label={tr("配线图")}
      onPointerDown={e => {
        if ((e.target as Element).closest("[data-track]")) return
        const rect = svg.current!.getBoundingClientRect()
        // The drawing is scaled to fit its box, so a pixel is as many blocks as the box makes it
        setDrag({ x: e.clientX, y: e.clientY, perPixel: Math.max(view.viewBox[2] / (rect.width || 1), view.viewBox[3] / (rect.height || 1)), moved: [0, 0] })
        svg.current!.setPointerCapture?.(e.pointerId)
      }}
      onPointerMove={e => { if (drag) setDrag({ ...drag, moved: [(drag.x - e.clientX) * drag.perPixel, (drag.y - e.clientY) * drag.perPixel] }) }}
      onPointerUp={() => { if (drag) { setPan([pan[0] + drag.moved[0], pan[1] + drag.moved[1]]); setDrag(null) } }}
      onPointerCancel={() => setDrag(null)}>
      {view.rails.map((line, k) => <polyline key={k} points={line.map(p => p.join(",")).join(" ")} fill="none" stroke="var(--muted)" strokeWidth={unit * 1.5} />)}
      {view.turnouts.map((t, k) => <circle key={k} cx={t[0]} cy={t[1]} r={unit * 3} fill="var(--caution)" />)}
      {view.platforms.map(({ platform: p, a, b, ours, start }) => {
        const index = topo.index.get(p.id)
        const mine = ours && index !== undefined
        const on = mine && model.picks[i] === index, pinned = mine && model.pins.get(i) === index
        const dead = mine && solution && !solution.feasible[i].has(index)
        const label = `${pinned ? "● " : ""}${topo.trackShort(p.id, p.name, p.passingTrack)}${!ours && p.stationName && p.station !== station ? " · " + shortName(p.stationName) : ""}`
        const title = mine ? `${topo.trackLabel(p.id, p.name, p.passingTrack)}：${pinned ? tr("已固定，点一下取消") : dead ? tr("和其他站连不起来，点一下仍可固定") : tr("点一下固定走这一股")}`
          : `${shortName(p.stationName) || tr("不属于车站")} ${p.name || "?"}${p.station === station ? tr("：和这条线的轨道不连") : ""}`
        return (
          <g key={p.id} data-track={mine ? index : undefined} role={mine ? "button" : undefined} tabIndex={mine ? 0 : undefined} aria-pressed={mine ? on : undefined}
            className="station-track" style={{ cursor: mine ? "pointer" : "default", outline: "none" }} onClick={mine ? () => onPin(index!) : undefined}
            onKeyDown={mine ? e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onPin(index!) } } : undefined}>
            <title>{title}</title>
            {/* Fixed: a band of the selection colour under it; the keyboard's focus a thinner one */}
            {pinned && <line x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]} strokeLinecap="round" strokeWidth={unit * 13} stroke="var(--sel)" strokeOpacity={0.45} />}
            {mine && <line className="focus-band" x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]} strokeLinecap="round" strokeWidth={unit * 10} stroke="var(--sel)" strokeOpacity={0} />}
            <line x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]} strokeLinecap="round" strokeWidth={unit * (on ? 7 : 5)}
              stroke={on ? model.color : mine ? "var(--ink)" : "var(--grid-strong)"} strokeDasharray={dead ? `${unit * 4} ${unit * 3}` : undefined} />
            <text x={start[0] - unit * 6} y={start[1] + unit * 4} fontSize={unit * 11} textAnchor="end" fill={mine ? "var(--ink)" : "var(--muted)"} fontWeight={on ? 700 : 400}>{label}</text>
          </g>
        )
      })}
    </svg>
  )
}

/** The stop's throat as the game works it out from the route's path: turnouts in and out, interlocking zones, crossings */
function Throat({ r, i }: { r: any; i: number }) {
  const { fn, v } = legacy
  const stop = r.stops[i]
  if (!stop || !stop.throat) return null
  const throat = stop.throat
  const offDepot = v.data.depots.find((depot: any) => depot.throatLocksOffBy && depot.routes.includes(r.id))
  const side = (entry: boolean) => (entry ? tr("进站") : tr("出站"))
  const crossings: string[] = throat.crossings.map((c: any) => {
    const other = v.data.routeById.get(c.route)
    return c.zone
      ? tr("{0}和 {1} 的{2}在联锁区（{3}, {4}）", side(c.entry), other ? routeTitle(other) : "?", side(c.otherEntry), c.x, c.z)
      : tr("{0}和 {1} 的{2}在道岔（{3}, {4}）", side(c.entry), other ? routeTitle(other) : "?", side(c.otherEntry), c.x, c.z)
  })
  return (
    <div className="flex flex-col gap-1">
      <p className={hint}>{tr("咽喉：进站经过 {0} 组道岔，出站经过 {1} 组（按游戏里的线路路径）。", throat.entry, throat.exit)}{throat.zones ? tr("还经过 {0} 个联锁区（联锁色的信号闭塞分区），整块锁住，车尾离开才放开。", throat.zones) : ""}</p>
      {offDepot && <p className="m-0 text-[12px] text-caution">{fn.lockOffText(offDepot)}</p>}
      {crossings.length > 0 && <p className={hint}>{tr("和这些进路交叉，不能同时走：")}{crossings.join(tr("；"))}{tr("。时刻表检查会找出时间上撞在一起的班次。")}</p>}
    </div>
  )
}

/**
 * How trains turn back at the last stop (站前 or 站后) and the platforms they take turns at, or at the first stop the
 * platforms this route would rather start from (种别优先), each a box to tick; the order ticked is the order tried
 */
function TurnPlatforms({ r, model, i }: { r: any; model: StopsModel; i: number }) {
  const { fn, v } = legacy
  const n = model.layers.length
  const stop = r.stops[i]
  if (!stop || n < 2 || (i !== 0 && i !== n - 1)) return null
  const topo = T()
  const kind: TurnKind = i === 0 ? "startPlatforms" : "terminalPlatforms"
  const chosen = turnOf(v.draft, r, kind)
  const ownId = topo.platforms[model.picks[i]] ? topo.platforms[model.picks[i]].id : stop.platformId
  // A route's own last platform is always where it turns back usually, so only its other platforms are offered there
  const options: any[] = (stop.candidates || []).filter((c: any) => i === 0 || c.id !== ownId)
  const canEdit = v.data.canEdit
  const toggle = (id: string) => {
    toggleTurnPlatform(v.draft, r, kind, id)
    fn.tidyDraft(r.id)
    fn.saveDraft()
    fn.renderAll()
  }
  const boxes = options.length ? (
    <div className="flex flex-wrap gap-x-3 gap-y-1">
      {options.map(c => {
        const at = chosen.indexOf(c.id)
        return (
          <label key={c.id} className="flex items-center gap-1 text-[13px]">
            <input type="checkbox" checked={at >= 0} disabled={!canEdit} onChange={() => toggle(c.id)} />
            {topo.trackLabel(c.id, c.name, c.passingTrack)}{at >= 0 && <span className="num text-[12px] text-dim">{tr("第 {0} 个", at + 1)}</span>}
          </label>
        )
      })}
    </div>
  ) : <p className={hint}>{tr("本站没有和这股连通的其他站台。")}</p>
  if (i === 0) {
    return (
      <div className="flex flex-col gap-1 rounded-md border border-grid p-2">
        <b className="text-[13px]">{tr("优先股道（种别优先）")}</b>
        <p className={hint}>{tr("在本站掉头接这条线的车，先进勾选的站台，按勾选的先后；只在上一条线路设了轮用站台时起作用。")}</p>
        {boxes}
      </div>
    )
  }
  const rear = stop.waypoints > 0
  const mode = rear
    ? (chosen.length ? tr("站前加站后：先进勾选的站台掉头，站台都占着或赶不上下一班时，才走末站途经点里列的站后折返线。") : tr("站后折返：走末站途经点里列的折返线（{0} 个节点），按顺序取能赶上下一班、进路没被别的车占着的那股。", stop.waypoints))
    : tr("站前折返：车在站台上掉头，下一班从同一站台发车。勾选的站台和本站台轮用，在前一站出发时按下一班种别的优先股道、再按勾选的先后选。")
  return (
    <div className="flex flex-col gap-1 rounded-md border border-grid p-2">
      <b className="text-[13px]">{tr("折返方式")}</b>
      <p className={hint}>{mode}</p>
      <p className={hint}>{tr("轮用站台：")}</p>
      {boxes}
      <p className={hint}>{tr("按运用跑的车才轮用；按频率发车的车只用本站台。")}</p>
    </div>
  )
}
