/**
 * One logical line at a time, as a dispatcher's screen (0.99.0, user 2026-11-17 with a metro ATS screen as the example:
 * the physical track as the logical lines divide it, not a line of services): every rail the ownership layer gives the
 * chosen logical line, both ways, turn backs and depot leads included, drawn as the network's schematic (横竖 45°,
 * core/trackSchematic.ts and core/orthogonalTracks.ts, the same drawing as the network views), each rail coloured by what
 * is on it now — free, taken by a train's block section, or on a route locked through a station throat turnout — and each
 * train's number tag where its head is, with the way it runs and how late it is. Read again every two seconds from
 * api/console (the same snapshot as the dispatch cards); click a tag to choose the train in the cards beside it. Signals,
 * switch positions, screen doors and the event log are not shown yet.
 */
import { useEffect, useMemo, useRef, useState } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { railKey } from "../core/schematic"
import { trackSchematic, type DrawRail } from "../core/trackSchematic"
import { orthogonalTracks } from "../core/orthogonalTracks"
import { tr } from "../i18n"
import { shortName } from "./now"

const POLL_MS = 2000
const FREE = "#3b82f6", TAKEN = "#ef4444", LOCKED = "#22c55e"

type Console = { available?: boolean; trains?: any[]; taken?: string[][]; locked?: string[][] }
type Point = [number, number]

/** The point a part of the way along a polyline, and the direction it runs there */
function along(points: Point[], part: number): { at: Point; dx: number; dy: number } {
  const lengths: number[] = []
  let total = 0
  for (let i = 1; i < points.length; i++) {
    const length = Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
    lengths.push(length)
    total += length
  }
  let rest = Math.max(0, Math.min(1, part)) * total
  for (let i = 1; i < points.length; i++) {
    const length = lengths[i - 1]
    if (rest <= length || i === points.length - 1) {
      const t = length > 0 ? Math.min(1, rest / length) : 0
      const [x1, y1] = points[i - 1], [x2, y2] = points[i]
      return { at: [x1 + (x2 - x1) * t, y1 + (y2 - y1) * t], dx: (x2 - x1) / (length || 1), dy: (y2 - y1) / (length || 1) }
    }
    rest -= length
  }
  return { at: points[0], dx: 1, dy: 0 }
}

export function LineMonitor({ onTrain }: { onTrain: (id: string) => void }) {
  useLegacy()
  const { fn, v } = legacy
  const logicalLines: { id: string; name: string; color: number; connecting?: boolean }[] = fn.logicalLinesShown ? [...fn.logicalLinesShown().values()] : []
  const [lineId, setLineId] = useState<string>(() => { try { return localStorage.getItem("mtr-next-line-monitor") || "" } catch { return "" } })
  const chosen = logicalLines.find(l => l.id === lineId) || logicalLines[0]
  const [live, setLive] = useState<Console>({})
  const [netVersion, setNetVersion] = useState(0)
  const box = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ width: 1000, height: 600 })

  // The network the old code reads (api/network): look for a new one now and then, as NetworkSchematic does
  useEffect(() => {
    let last = v.net?.data
    const timer = setInterval(() => { if (v.net?.data !== last) { last = v.net?.data; setNetVersion(n => n + 1) } }, 400)
    if (!v.net?.data && fn.loadNetwork) fn.loadNetwork(false)
    return () => clearInterval(timer)
  }, [])

  // What is on the rails now
  useEffect(() => {
    let stopped = false
    const read = async () => {
      try {
        const data = await fn.fetchJson("api/console")
        if (!stopped) { v.consoleState.data = data; setLive(data) }
      } catch { /* the game closed: keep the last */ }
    }
    read()
    const timer = setInterval(() => { if (document.visibilityState === "visible") read() }, POLL_MS)
    return () => { stopped = true; clearInterval(timer) }
  }, [])

  useEffect(() => {
    const element = box.current
    if (!element) return
    const observer = new ResizeObserver(() => setSize({ width: element.clientWidth, height: element.clientHeight }))
    observer.observe(element)
    return () => observer.disconnect()
  }, [box.current])

  const data = v.net?.data
  const platforms = v.data?.topology?.platforms
  const map = useMemo(() => data?.nodes && data?.rails ? orthogonalTracks(trackSchematic(data, platforms || [])) : null, [data, platforms, netVersion])
  // The rails of the chosen logical line, as the ownership layer has them (its draft included)
  const rails: DrawRail[] = useMemo(() => {
    if (!map || !chosen || !fn.railLogicalLine) return []
    return map.rails.filter(rail => fn.railLogicalLine(rail.rail)?.id === chosen.id)
  }, [map, chosen?.id, netVersion])
  const keyOf = (rail: DrawRail) => railKey(String(data.nodes[rail.nodes[0]][2]), String(data.nodes[rail.nodes[1]][2]))

  const choose = (id: string) => {
    setLineId(id)
    try { localStorage.setItem("mtr-next-line-monitor", id) } catch { /* private window */ }
  }

  const header = (
    <div className="flex shrink-0 flex-wrap items-center gap-3 border-b border-grid bg-panel px-3 py-2">
      <select className="h-7 rounded-md border border-ctl bg-panel px-2 text-[14px] font-semibold" value={chosen?.id || ""} onChange={e => choose(e.target.value)} aria-label={tr("逻辑线路")}>
        {logicalLines.map(l => <option key={l.id} value={l.id}>{shortName(l.name)}</option>)}
      </select>
      <span className="inline-flex items-center gap-1 text-[12px] text-dim"><i className="inline-block h-1 w-5 rounded" style={{ background: FREE }} />{tr("空闲")}</span>
      <span className="inline-flex items-center gap-1 text-[12px] text-dim"><i className="inline-block h-1 w-5 rounded" style={{ background: TAKEN }} />{tr("占用")}</span>
      <span className="inline-flex items-center gap-1 text-[12px] text-dim"><i className="inline-block h-1 w-5 rounded" style={{ background: LOCKED }} />{tr("进路锁定")}</span>
      {live.available === false && <span className="text-[12px] text-warn">{tr("还没有收到游戏的列车数据")}</span>}
    </div>
  )
  if (!logicalLines.length) return <div className="flex min-h-0 flex-1 flex-col">{header}<p className="m-3 text-dim">{tr("还没有逻辑线路：先在归属层建逻辑线路，把轨道刷给它")}</p></div>
  if (!map) return <div className="flex min-h-0 flex-1 flex-col">{header}<p className="m-3 text-dim">{tr("正在读路网…")}</p></div>
  if (!rails.length) return <div className="flex min-h-0 flex-1 flex-col">{header}<p className="m-3 text-dim">{tr("这条逻辑线路还没有轨道：在归属层用画笔把轨道刷给它")}</p></div>

  // The view: the logical line's rails, fitted into the box
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  rails.forEach(rail => rail.points.forEach(([x, y]) => { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y) }))
  // Across and along apart: a long line fitted to the width would put its tracks on top of each other, so they are
  // spread apart down the box (up to a few times the scale along)
  const margin = 60
  const scaleX = Math.min((size.width - margin * 2) / Math.max(1, maxX - minX), 6)
  const scaleY = Math.max(scaleX, Math.min((size.height - margin * 2 - 40) / Math.max(1, maxY - minY), scaleX * 8, 30))
  const X = (x: number) => margin + (x - minX) * scaleX
  const Y = (y: number) => margin + 30 + (y - minY) * scaleY
  const stationIds = new Set(rails.map(rail => rail.station).filter(Boolean) as string[])
  const stations = map.stations.filter(s => stationIds.has(s.id))

  const taken = new Set((live.taken || []).map(pair => railKey(pair[0], pair[1])))
  const locked = new Set<string>()
  ;(live.locked || []).forEach(([node, side1, side2]) => { locked.add(railKey(node, side1)); locked.add(railKey(node, side2)) })
  const byKey = new Map(rails.map(rail => [keyOf(rail), rail]))

  // Each train's tag where its head is on the line's rails
  const tags: { id: string; name: string; x: number; y: number; dx: number; dy: number; late: number; selected: boolean }[] = []
  for (const train of live.trains || []) {
    if (!train.head) continue
    const rail = byKey.get(railKey(train.head[0], train.head[1]))
    if (!rail) continue
    const firstIsLower = BigInt(String(data.nodes[rail.nodes[0]][2])) < BigInt(String(data.nodes[rail.nodes[1]][2]))
    const fromLower = typeof train.along === "number" ? train.along : 0.5
    const part = firstIsLower ? fromLower : 1 - fromLower
    const place = along(rail.points as Point[], part)
    // Towards the rail's higher node: along its points if they start at the lower one
    const forwards = train.towardsHigher === firstIsLower
    // The way it runs on the screen, scaled as the drawing is
    const sdx = (forwards ? place.dx : -place.dx) * scaleX, sdy = (forwards ? place.dy : -place.dy) * scaleY, slen = Math.hypot(sdx, sdy) || 1
    tags.push({ id: train.id, name: fn.consoleTrainName ? fn.consoleTrainName(train) : String(train.id).slice(-4), x: X(place.at[0]), y: Y(place.at[1]), dx: sdx / slen, dy: sdy / slen, late: train.late || 0, selected: train.id === v.consoleState.selected })
  }

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      {header}
      <div ref={box} className="min-h-0 flex-1 overflow-hidden bg-[#0b1220]" data-line-monitor>
        <svg width={size.width} height={size.height} role="img" aria-label={tr("{0} 的线路监控", shortName(chosen.name))}>
          {rails.map(rail => {
            const key = keyOf(rail)
            const state = taken.has(key) ? "taken" : locked.has(key) ? "locked" : "free"
            const points = rail.points.map(([x, y]) => `${X(x)},${Y(y)}`).join(" ")
            return (
              <g key={rail.rail}>
                {rail.kind === 1 && <polyline points={points} fill="none" stroke="#475569" strokeWidth={10} strokeLinecap="butt" opacity={0.6} />}
                <polyline data-rail={key} data-state={state} points={points} fill="none" strokeLinecap="round" strokeLinejoin="round"
                  stroke={state === "taken" ? TAKEN : state === "locked" ? LOCKED : FREE} strokeWidth={state === "free" ? 3 : 4.5}
                  strokeDasharray={rail.kind === 2 || rail.kind === 3 ? "6 4" : undefined} />
              </g>
            )
          })}
          {map.junctions.filter(([x, y]) => x >= minX && x <= maxX && y >= minY && y <= maxY).map(([x, y], i) => <circle key={i} cx={X(x)} cy={Y(y)} r={2.5} fill="#e2e8f0" />)}
          {stations.map(s => (
            <g key={s.id}>
              <line x1={X(s.x)} x2={X(s.x)} y1={Y(minY) - 18} y2={Y(maxY) + 12} stroke="#334155" strokeDasharray="3 4" />
              <text x={X(s.x)} y={Y(minY) - 24} textAnchor="middle" fill="#e2e8f0" fontSize={13} fontWeight={600}>{shortName(s.name)}</text>
            </g>
          ))}
          {tags.map(tag => {
            const label = tag.name + (tag.late >= 60 ? " +" + Math.floor(tag.late / 60) + "'" : "")
            const w = Math.max(40, label.length * 7 + 18)
            const y = tag.y - 22
            const arrow = Math.atan2(tag.dy, tag.dx) * 180 / Math.PI
            return (
              <g key={tag.id} data-train={tag.id} style={{ cursor: "pointer" }} onClick={() => onTrain(tag.id)}>
                <line x1={tag.x} x2={tag.x} y1={tag.y} y2={y + 9} stroke="#94a3b8" strokeWidth={1} />
                <circle cx={tag.x} cy={tag.y} r={3} fill="#facc15" />
                <path d="M 0 -4 L 7 0 L 0 4 z" fill="#22d3ee" transform={`translate(${tag.x + tag.dx * 9},${tag.y + tag.dy * 9}) rotate(${arrow})`} />
                <rect x={tag.x - w / 2} y={y - 9} width={w} height={18} rx={2} fill="#111827" stroke={tag.selected ? "#facc15" : tag.late >= 300 ? "#f87171" : "#22d3ee"} strokeWidth={tag.selected ? 2 : 1} />
                <text x={tag.x} y={y + 4} textAnchor="middle" fill={tag.late >= 300 ? "#fca5a5" : "#e2e8f0"} fontSize={11} fontFamily="monospace">{label}</text>
              </g>
            )
          })}
        </svg>
      </div>
    </div>
  )
}
