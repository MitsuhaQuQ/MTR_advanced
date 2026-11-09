/**
 * The stops of a route, and new line groups, in one editor beside the page (replaces the old stops dialog, stop list
 * dialog and new line dialog in the new page; the old code's calls to stopsDialog / newLineDialog come here).
 *
 * The route runs down the left as a line in its colour, a dot at each station: filled where it stops, hollow where it
 * passes. Each station shows its tracks side by side as the track diagram has them; the one used is marked in the route's
 * colour. Clicking a track fixes it there, and the other stations move to the tracks that still run with the fewest
 * changes (the old solver, solveStops); a track no way through can use is faded. Between stations: how far, or that
 * there is no way. Turning back at a station is marked.
 *
 * "改站序" switches to adding, removing and reordering stations, which goes to the game at once (sendStopList), as before.
 * A station's own track diagram, with its throats and turn platforms, is still the old dialog (oldStopsDialog).
 */
import { useEffect, useMemo, useState } from "react"
import { diagramHost, legacy, stopsHost, useLegacy, type StopsTarget } from "../legacy/engine"
import { ConfirmButton, Pill, SidePanel } from "../ui/bits"
import { tr } from "../i18n"

/** The old code's picker model (topologyModel): each stop's tracks, the ones picked, stop or pass, fixed tracks */
export type StopsModel = { layers: number[][]; picks: number[]; stop: boolean[]; pins: Map<number, number>; color: string; conflict: string }

export function StopsEditor() {
  const [target, setTarget] = useState<StopsTarget | null>(null)
  useEffect(() => {
    stopsHost.current = setTarget
    return () => { stopsHost.current = null }
  }, [])
  if (!target) return null
  const close = () => setTarget(null)
  return target.kind === "route"
    ? <RouteStops key={target.routeId + "/" + target.stop} routeId={target.routeId} onClose={close} />
    : <NewLine onClose={close} />
}

// ---------- The rules (the old code's) ----------

/** Fixes a stop's track, or frees it if it was fixed there; the others move to the best tracks that still run. */
export function pinTrack(model: StopsModel, i: number, p: number): StopsModel {
  const { fn } = legacy
  const pins = new Map(model.pins)
  if (pins.get(i) === p) pins.delete(i)
  else pins.set(i, p)
  const solution = fn.solveStops(model.layers, model.picks, pins)
  if (solution.ok) return { ...model, pins, picks: solution.picks, conflict: "" }
  const picks = model.picks.slice()
  picks[i] = p
  const names = [...pins.keys()].sort((a, b) => a - b).map(k => fn.stationLabel(model.layers[k][0]))
  return { ...model, pins, picks, conflict: tr("固定的站台连不起来：{0}。取消其中一个再试。", names.join("、")) }
}

/** What the picks give: each leg's length (Infinity: no way), where it turns back, which tracks some way can use */
function analyse(model: StopsModel) {
  const { fn } = legacy
  const n = model.layers.length
  const solution = n > 1 ? fn.solveStops(model.layers, model.picks, model.pins) : null
  const settled = Boolean(solution && solution.ok && solution.picks.every((p: number, i: number) => p === model.picks[i]))
  const legs = model.picks.slice(1).map((p, i) => fn.legCost(model.picks[i], p) as number)
  return {
    legs,
    reversals: new Set<number>(settled ? solution.reversals : []),
    feasible: (solution ? solution.feasible : model.layers.map(layer => new Set(layer))) as Set<number>[],
  }
}

// ---------- The strip of stations ----------

function Strip({ model, setModel, original, onStation }: {
  model: StopsModel; setModel: (model: StopsModel) => void
  /** The platform ids the game has, to mark the stations changed */
  original?: string[]
  /** A station's own diagram, when there is one to open */
  onStation?: (i: number) => void
}) {
  const { fn, v } = legacy
  const topo = v.topo
  const { legs, reversals, feasible } = useMemo(() => analyse(model), [model])
  const n = model.layers.length
  return (
    <ol className="m-0 list-none p-0" aria-label={tr("车站和股道")}>
      {model.layers.map((layer, i) => {
        const picked = model.picks[i], stops = model.stop[i] || i === 0 || i === n - 1
        const changed = original && topo.platforms[picked].id !== original[i]
        return (
          <li key={i} className="relative flex gap-3" data-stop={i}>
            <div className="relative w-5 shrink-0" aria-hidden="true">
              <span className="absolute left-[8px] w-[4px]" style={{ background: model.color, top: i === 0 ? 10 : 0, bottom: i === n - 1 ? "calc(100% - 10px)" : 0 }} />
              <span className="absolute left-[3px] top-[3px] h-[14px] w-[14px] rounded-full border-[3px]" style={{ borderColor: model.color, background: stops ? model.color : "var(--panel)" }} />
            </div>
            <div className="min-w-0 flex-1 pb-1">
              <div className="flex min-h-[20px] flex-wrap items-center gap-2">
                <span className="num w-5 text-[12px] text-dim">{i + 1}</span>
                <b className="text-[14px]">{fn.stationLabel(layer[0])}</b>
                {changed && <Pill tone="accent">{tr("改了股道")}</Pill>}
                {reversals.has(i) && <Pill tone="caution">{tr("掉头")}</Pill>}
                {model.pins.has(i) && <Pill tone="sel">{tr("固定")}</Pill>}
                <span className="flex-1" />
                {onStation && <button className="btn btn-ghost h-6 px-1.5 text-[12px]" onClick={() => onStation(i)}>{tr("站内配线…")}</button>}
                <button className="btn h-6 px-2 text-[12px]" disabled={i === 0 || i === n - 1} aria-pressed={!stops}
                  title={i === 0 || i === n - 1 ? tr("首末站总是停车") : tr("点一下在停车和通过之间切换")}
                  onClick={() => { const stop = model.stop.slice(); stop[i] = !stop[i]; setModel({ ...model, stop }) }}>
                  {stops ? tr("停车") : tr("通过")}
                </button>
              </div>
              <div className="mt-1 flex flex-wrap gap-1 pl-7" role="group" aria-label={tr("{0}的股道", fn.stationLabel(layer[0]))}>
                {layer.map(p => {
                  const platform = topo.platforms[p], on = p === picked, usable = feasible[i]?.has(p)
                  return (
                    <button key={p} className="num h-6 rounded-md border px-2 text-[12px]" aria-pressed={on}
                      title={fn.trackLabel(platform.id, fn.platformLabel(p), platform.passingTrack) + (usable ? "" : tr("（和其他站连不起来）"))}
                      style={{
                        borderColor: on ? model.color : "var(--control-line)", borderWidth: on ? 2 : 1, opacity: usable || on ? 1 : 0.45,
                        background: on ? `color-mix(in srgb, ${model.color} 16%, var(--panel))` : "var(--panel)",
                        boxShadow: model.pins.get(i) === p ? "0 0 0 2px var(--sel)" : undefined, fontWeight: on ? 600 : 400,
                      }}
                      onClick={() => setModel(pinTrack(model, i, p))}>
                      {fn.trackShort(platform.id, fn.platformLabel(p), platform.passingTrack)}
                    </button>
                  )
                })}
              </div>
              {i < n - 1 && (
                <div className={"num py-1.5 pl-7 text-[12px] " + (isFinite(legs[i]) ? "text-dim" : "font-semibold text-warn")}>
                  {isFinite(legs[i]) ? tr("↓ {0} 格", Math.round(legs[i])) : tr("↓ 沿轨道走不通")}
                </div>
              )}
            </div>
          </li>
        )
      })}
    </ol>
  )
}

// ---------- A route's stops ----------

function modelOf(r: any): StopsModel | null {
  const { fn, v } = legacy
  const ids: string[] = fn.platformsOf(r), pass: boolean[] = fn.timetableOf(r).pass
  const tracks = ids.map(id => (v.topo.index.has(id) ? v.topo.index.get(id) : -1))
  if (tracks.some(p => p < 0)) return null
  return fn.topologyModel(tracks, r.stops.map((_: unknown, i: number) => !pass[i]), fn.colorOf(r.color))
}

function RouteStops({ routeId, onClose }: { routeId: string; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const r = v.data?.routeById.get(routeId)
  const [model, setModel] = useState<StopsModel | null>(() => (r && v.topo ? modelOf(r) : null))
  const [ordering, setOrdering] = useState(false)
  const [error, setError] = useState("")
  if (!r) return null
  const title = tr("停站：{0}", fn.routeTitle(r))
  const canEdit = v.data.canEdit
  if (ordering) return <StopOrder r={r} onBack={() => setOrdering(false)} onClose={onClose} />

  const gamePass: boolean[] = fn.gameTimetable(r).pass
  const original: string[] = r.stops.map((stop: any) => stop.platformId)
  const changes = model ? model.picks.filter((p, i) => v.topo.platforms[p].id !== original[i]).length
    + model.stop.filter((s, i) => i > 0 && i < model.stop.length - 1 && !s !== Boolean(gamePass[i])).length : 0
  const save = () => {
    if (!model) return
    if (model.conflict) { setError(tr("有固定的股道连不起来")); return }
    const newPlatforms = model.picks.map(p => v.topo.platforms[p].id)
    const entry = fn.draftEntry(r)
    if (newPlatforms.every((id, i) => id === original[i])) delete entry.platforms
    else entry.platforms = newPlatforms
    fn.tidyDraft(r.id)
    fn.editTimetable(r, (timetable: any) => {
      timetable.pass = model.stop.map((stops, i) => !stops && i > 0 && i < r.stops.length - 1)
    })
    onClose()
  }
  return (
    <SidePanel open onClose={onClose} title={title} width={600}
      hint={model ? fn.stopsSummaryOf(model) : undefined}
      footer={<>
        <button className="btn btn-primary" disabled={!canEdit || !model} onClick={save}>{tr("放进草稿")}</button>
        <button className="btn" onClick={onClose}>{tr("取消")}</button>
        {model && <button className="btn btn-ghost" disabled={!model.pins.size && !model.conflict} onClick={() => setModel(modelOf(r))}>{tr("还原")}</button>}
        <span className="flex-1" />
        {changes > 0 && <span className="text-[12px] text-dim">{tr("改了 {0} 处", changes)}</span>}
        {error && <span className="text-[12px] text-warn" role="alert">{error}</span>}
      </>}>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <button className="btn" disabled={!canEdit} onClick={() => setOrdering(true)}>{tr("增删和排序停站…")}</button>
        <button className="btn btn-ghost" onClick={() => { onClose(); diagramHost.current?.({ kind: "route", routeId: r.id }) }}>{tr("线路配线图")}</button>
      </div>
      {!model
        ? <p className="text-[13px] text-warn">{tr("有车站的站台没有轨道，没法按轨道选股道。先在游戏里把站台放回轨道上。")}</p>
        : <>
          <p className="mb-3 mt-0 text-[12px] text-dim">{tr("点一股固定它，其他站自动换成走得通、改动最少的股道；淡的股道和其他站连不起来。再点一下取消固定。")}</p>
          {model.conflict && <p className="mb-2 mt-0 text-[13px] text-warn" role="alert">{model.conflict}</p>}
          <Strip model={model} setModel={m => { setModel(m); setError("") }} original={original}
            onStation={i => { onClose(); fn.oldStopsDialog(r, i) }} />
          <p className="mt-3 text-[12px] text-dim">{tr("改股道会清掉线路手画的路径，应用后要刷新车厂路径。")}</p>
        </>}
    </SidePanel>
  )
}

/** Adding, removing and reordering a route's stations: sent to the game at once, with everything hung on them renumbered */
function StopOrder({ r, onBack, onClose }: { r: any; onBack: () => void; onClose: () => void }) {
  const { fn, v } = legacy
  const [list, setList] = useState(() => {
    const pass: boolean[] = fn.timetableOf(r).pass
    return r.stops.map((stop: any, i: number) => ({ platformId: stop.platformId as string, from: i, pass: Boolean(pass[i]) }))
  })
  const [inserting, setInserting] = useState(-1)
  const [message, setMessage] = useState("")
  const platforms: any[] = (v.topo ? v.topo.platforms : []).filter((p: any) => p.station)
  const stations = useMemo(() => [...new Map(platforms.map(p => [p.station, p.stationName])).entries()]
    .sort((a, b) => String(a[1]).localeCompare(String(b[1]))), [platforms.length]) // eslint-disable-line react-hooks/exhaustive-deps
  const [station, setStation] = useState("")
  const [platform, setPlatform] = useState("")
  const stationNow = station || (stations[0] ? stations[0][0] : "")
  const choices = platforms.filter(p => p.station === stationNow)
  const platformNow = choices.some(p => p.id === platform) ? platform : (choices[0] ? choices[0].id : "")

  const change = (next: typeof list) => {
    // The first and last stops are always stopped at
    setList(next.map((stop: any, i: number) => (i === 0 || i === next.length - 1 ? { ...stop, pass: false } : stop)))
    setMessage("")
  }
  const move = (i: number, by: number) => { const next = list.slice(); [next[i], next[i + by]] = [next[i + by], next[i]]; change(next) }
  const remove = (i: number) => {
    const hanging: string[] = list[i].from < 0 ? [] : fn.stopHangers(r, list[i].from)
    if (hanging.length) { setMessage(tr("这一站上还挂着：{0}。先处理掉再删。", hanging.join(tr("；")))); return }
    change(list.filter((_: unknown, k: number) => k !== i))
  }
  const insert = () => {
    if (!platformNow) return
    const next = list.slice()
    next.splice(inserting + 1, 0, { platformId: platformNow, from: -1, pass: false })
    change(next)
    setInserting(-1)
  }
  const send = () => {
    const result = fn.sendStopList(r, list)
    if (result) setMessage(result)
    else onClose()
  }
  const unchanged = list.length === r.stops.length && list.every((stop: any, i: number) => stop.from === i)
  return (
    <SidePanel open onClose={onClose} title={tr("增删和排序停站：{0}", fn.routeTitle(r))} width={600}
      hint={tr("改好后点“发给游戏”马上生效：时刻表、直通、出入库指令和运用按新的站序重新对应，草稿里的也一样。股道在“停站”里再挑。")}
      footer={<>
        <ConfirmButton className="btn btn-primary" disabled={unchanged || list.length < 2} confirm={tr("马上改游戏里的站序，不经过草稿")} onConfirm={send}>{tr("发给游戏")}</ConfirmButton>
        <button className="btn" onClick={onBack}>{tr("返回")}</button>
        {message && <span className="text-[12px] text-warn" role="alert">{message}</span>}
      </>}>
      <ol className="m-0 list-none p-0">
        {list.map((stop: any, i: number) => (
          <li key={i}>
            <div className="flex flex-wrap items-center gap-2 border-b border-grid py-1.5">
              <span className="num w-6 text-[12px] text-dim">{i + 1}</span>
              <span className="min-w-0 flex-1 truncate text-[13px]">{fn.platformText(stop.platformId)}</span>
              {stop.from < 0 && <Pill tone="accent">{tr("新加")}</Pill>}
              {i > 0 && i < list.length - 1 && (
                <label className="flex items-center gap-1 text-[12px] text-dim">
                  <input type="checkbox" checked={stop.pass} onChange={e => change(list.map((s: any, k: number) => (k === i ? { ...s, pass: e.target.checked } : s)))} /> {tr("通过")}
                </label>
              )}
              <button className="btn h-6 px-1.5" disabled={i === 0} onClick={() => move(i, -1)} aria-label={tr("上移")}>↑</button>
              <button className="btn h-6 px-1.5" disabled={i === list.length - 1} onClick={() => move(i, 1)} aria-label={tr("下移")}>↓</button>
              <button className="btn h-6 px-1.5 text-[12px]" disabled={list.length <= 2} onClick={() => remove(i)}>{tr("删除")}</button>
              <button className="btn h-6 px-1.5 text-[12px]" aria-expanded={inserting === i} onClick={() => setInserting(inserting === i ? -1 : i)}>{tr("在后面插入")}</button>
            </div>
            {inserting === i && (
              <div className="flex flex-wrap items-center gap-2 py-2 pl-8">
                <label className="text-[12px]">{tr("车站")} <select className="h-7 rounded-md border border-ctl bg-panel px-1" value={stationNow} onChange={e => setStation(e.target.value)}>
                  {stations.map(([id, name]) => <option key={id} value={id}>{fn.shortName(name) || id}</option>)}
                </select></label>
                <label className="text-[12px]">{tr("站台")} <select className="h-7 rounded-md border border-ctl bg-panel px-1" value={platformNow} onChange={e => setPlatform(e.target.value)}>
                  {choices.map(p => <option key={p.id} value={p.id}>{p.name || p.id}</option>)}
                </select></label>
                <button className="btn" onClick={insert}>{tr("插入")}</button>
              </div>
            )}
          </li>
        ))}
      </ol>
    </SidePanel>
  )
}

// ---------- A new line group ----------

type StationChoice = { key: string; station: string; network: number; track: number; name: string }

/** A station once for each rail network it has tracks on, named with the network when it has tracks on several */
function stationsOf(mode: string): StationChoice[] {
  const { fn, v } = legacy
  const seen = new Map<string, StationChoice>()
  v.topo.platforms.forEach((p: any, i: number) => {
    const key = p.station + "|" + p.network
    if (p.mode === mode && p.station && !seen.has(key)) seen.set(key, { key, station: p.station, network: p.network, track: i, name: fn.shortName(p.stationName) })
  })
  const list = [...seen.values()]
  const names = new Map<string, number>()
  list.forEach(s => names.set(s.name, (names.get(s.name) || 0) + 1))
  list.forEach(s => { if ((names.get(s.name) || 0) > 1) s.name += tr("（轨道网 {0}）", s.network) })
  return list.sort((a, b) => a.name.localeCompare(b.name, "zh-Hans-CN"))
}

const MODE_NAMES: Record<string, string> = { TRAIN: "铁路", BOAT: "船", CABLE_CAR: "缆车", AIRPLANE: "飞机" }

function NewLine({ onClose }: { onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data, topo = v.topo
  const modes = useMemo<string[]>(() => (topo ? [...new Set<string>(topo.platforms.filter((p: any) => p.station && p.mode).map((p: any) => p.mode))].sort() : []), [topo])
  const [mode, setMode] = useState(modes[0] || "")
  const [lineName, setLineName] = useState("")
  const [type, setType] = useState("")
  const [color, setColor] = useState("#2f6fd6")
  const [from, setFrom] = useState("")
  const [fromTrack, setFromTrack] = useState("")
  const [to, setTo] = useState("")
  const [vias, setVias] = useState<string[]>([])
  const [both, setBoth] = useState(true)
  const [dir, setDir] = useState(0)
  const [error, setError] = useState("")

  const stations = useMemo(() => (mode ? stationsOf(mode) : []), [mode])
  const find = (key: string) => stations.find(s => s.key === key)
  const fromNow = find(from) ? from : (stations[0]?.key ?? "")
  const network = find(fromNow)?.network
  const sameNetwork = stations.filter(s => s.network === network)
  const others = sameNetwork.filter(s => s.key !== fromNow)
  const toNow = others.some(s => s.key === to) ? to : (others[others.length - 1]?.key ?? "")
  const viasNow = vias.filter(key => find(key)?.network === network && key !== fromNow && key !== toNow)
  const tracksOf = (key: string): number[] => { const s = find(key); return s ? fn.stationTracks(s.track) : [] }
  const startTracks = tracksOf(fromNow)
  const startTrack = startTracks.includes(Number(fromTrack)) && fromTrack !== "" ? Number(fromTrack) : -1

  // The stations along the rails, for each direction; worked out again when the ends or the stations between change
  const planKey = [mode, fromNow, toNow, startTrack, both, viasNow.join(">")].join("|")
  const [planned, setPlanned] = useState<{ key: string; models: StopsModel[]; error: string }>({ key: "", models: [], error: "" })
  if (planned.key !== planKey) {
    const models: StopsModel[] = []
    let routeError = ""
    if (!fromNow || !toNow || fromNow === toNow) routeError = tr("选好不同的起点站和终点站")
    else {
      const make = (start: number[], ends: number[], stops: string[], pinnedStart: number): StopsModel | null => {
        const tracks = fn.findCorridor([start, ...stops.map(tracksOf), ends])
        if (!tracks || tracks.length < 2) return null
        const model: StopsModel = fn.topologyModel(tracks, tracks.map(() => true), color)
        if (pinnedStart >= 0) model.pins.set(0, pinnedStart)
        const solution = fn.solveStops(model.layers, model.picks, model.pins)
        if (solution.ok) model.picks = solution.picks
        return model
      }
      const forward = make(startTrack >= 0 ? [startTrack] : startTracks, tracksOf(toNow), viasNow, startTrack)
      if (!forward) routeError = tr("从{0}{1}到{2}沿轨道走不通", find(fromNow)!.name, viasNow.length ? tr("经过选的途经站") : "", find(toNow)!.name)
      else {
        models.push(forward)
        if (both) {
          const backward = make(tracksOf(toNow), startTracks, viasNow.slice().reverse(), -1)
          if (backward) models.push(backward)
          else routeError = tr("反方向沿轨道走不通，只建这个方向")
        }
      }
    }
    setPlanned({ key: planKey, models, error: routeError })
  }
  const models = planned.models.map(m => ({ ...m, color }))
  const shown = Math.min(dir, models.length - 1)
  const setModel = (model: StopsModel) => setPlanned(p => ({ ...p, models: p.models.map((m, i) => (i === shown ? model : m)) }))

  const nameOf = (model: StopsModel) => {
    const base = `${lineName.trim()} ${type.trim()}`.trim()
    if (models.length < 2) return base
    const a = mid(model.picks[0]), b = mid(model.picks[model.picks.length - 1])
    if (!a || !b) return base
    const dx = b[0] - a[0], dz = b[1] - a[1]
    const direction = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? "up" : "down") : (dz < 0 ? "up" : "down")
    return `${base} ${tr(v.TRACK_DIRECTIONS[direction])}`
  }
  const mid = (p: number) => { const line = topo.platforms[p].line; return line ? [(line[0] + line[2]) / 2, (line[1] + line[3]) / 2] : null }

  const create = () => {
    const name = lineName.trim(), kind = type.trim()
    const fail = !name ? tr("写上线路组名，例如 1号线")
      : data.lines.some((l: any) => l.key === mode + "|" + name.toLowerCase()) ? tr("已经有这个线路组了，请用“新建 ▾ → 种别…”")
      : !kind ? tr("写上种别名，例如 普通")
      : !models.length ? planned.error || tr("选好起点和终点")
      : models.some(m => m.conflict) ? tr("有固定的股道连不起来") : ""
    if (fail) { setError(fail); return }
    models.forEach((model, i) => {
      v.draft.created.push({
        key: "n" + Date.now().toString(36) + i, source: "", mode, lineName: name, lineKey: mode + "|" + name.toLowerCase(), name: nameOf(model), serviceType: kind,
        color: parseInt(color.slice(1), 16),
        stops: model.picks.map((p, k) => ({ platformId: topo.platforms[p].id, pass: !model.stop[k] && k > 0 && k < model.picks.length - 1 })),
        services: [], table: 0, depot: "",
      })
    })
    fn.saveDraft()
    fn.renderAll()
    onClose()
  }

  const field = "h-7 rounded-md border border-ctl bg-panel px-2 text-[13px]"
  const label = "flex flex-col gap-0.5 text-[12px] text-dim"
  return (
    <SidePanel open onClose={onClose} title={tr("新建线路")} width={640}
      hint={tr("新线路只有车站表，没有班次和运用；时刻表、班次和车厂建好以后再加。先放在草稿里，按“应用”才在游戏里建出来，并由高级编辑器管理。")}
      footer={<>
        <button className="btn btn-primary" disabled={!data?.canEdit || !modes.length} onClick={create}>{tr("新建")}</button>
        <button className="btn" onClick={onClose}>{tr("取消")}</button>
        <button className="btn btn-ghost" onClick={() => { onClose(); fn.startPicking() }}>{tr("改在全网配线图上选站…")}</button>
        {error && <span className="text-[12px] text-warn" role="alert">{error}</span>}
      </>}>
      {!modes.length ? <p className="text-[13px] text-warn">{tr("游戏里还没有属于车站、放在轨道上的站台，先在游戏里建车站和站台。")}</p> : <>
        <div className="grid grid-cols-2 gap-x-3 gap-y-2 sm:grid-cols-4">
          <label className={label}>{tr("交通方式")}
            <select className={field} value={mode} onChange={e => { setMode(e.target.value); setVias([]); setFrom(""); setTo("") }}>
              {modes.map(m => <option key={m} value={m}>{MODE_NAMES[m] ? tr(MODE_NAMES[m]) : m}</option>)}
            </select></label>
          <label className={label}>{tr("线路组名")}<input className={field} value={lineName} maxLength={128} placeholder={tr("1号线")} onChange={e => { setLineName(e.target.value); setError("") }} /></label>
          <label className={label}>{tr("种别")}<input className={field} value={type} maxLength={16} placeholder={tr("普通")} onChange={e => { setType(e.target.value); setError("") }} /></label>
          <label className={label}>{tr("颜色")}<input className="h-7 w-full rounded-md border border-ctl bg-panel" type="color" value={color} onChange={e => setColor(e.target.value)} /></label>
          <label className={label}>{tr("起点")}
            <select className={field} value={fromNow} onChange={e => { setFrom(e.target.value); setFromTrack("") }}>
              {stations.map(s => <option key={s.key} value={s.key}>{s.name}</option>)}
            </select></label>
          <label className={label}>{tr("站台")}
            <select className={field} value={startTrack >= 0 ? String(startTrack) : ""} onChange={e => setFromTrack(e.target.value)}>
              <option value="">{tr("任一股")}</option>
              {startTracks.map(p => <option key={p} value={p}>{fn.trackLabel(topo.platforms[p].id, fn.platformLabel(p), topo.platforms[p].passingTrack)}</option>)}
            </select></label>
          <label className={label}>{tr("终点")}
            <select className={field} value={toNow} onChange={e => setTo(e.target.value)}>
              {others.map(s => <option key={s.key} value={s.key}>{s.name}</option>)}
            </select></label>
          <label className="flex items-end gap-1.5 pb-1 text-[13px]"><input type="checkbox" checked={both} onChange={e => setBoth(e.target.checked)} /> {tr("反方向一起建")}</label>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <span className="text-[12px] text-dim">{tr("途经站")}</span>
          {viasNow.map((key, k) => (
            <button key={key} className="btn h-6 px-2 text-[12px]" title={tr("点一下去掉")} onClick={() => setVias(viasNow.filter((_, i) => i !== k))}>{find(key)?.name ?? "?"} ✕</button>
          ))}
          <select className={field} value="" aria-label={tr("＋ 途经站…")} onChange={e => { if (e.target.value) setVias([...viasNow, e.target.value]) }}>
            <option value="">{tr("＋ 途经站…")}</option>
            {sameNetwork.filter(s => s.key !== fromNow && s.key !== toNow && !viasNow.includes(s.key)).map(s => <option key={s.key} value={s.key}>{s.name}</option>)}
          </select>
        </div>
        <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-grid pt-3">
          <b className="text-[14px]">{tr("车站和股道")}</b>
          {models.length > 1 && models.map((m, i) => (
            <button key={i} className={"btn h-6 px-2 text-[12px]" + (i === shown ? " tint-accent font-semibold text-brand" : "")} aria-pressed={i === shown} style={i === shown ? { borderColor: "var(--accent)" } : undefined} onClick={() => setDir(i)}>
              {fn.stationLabel(m.picks[0])} → {fn.stationLabel(m.picks[m.picks.length - 1])}
            </button>
          ))}
        </div>
        <p className="my-2 text-[12px] text-dim">{tr("车站是沿轨道从起点到终点（依次经过途经站）最短的走法经过的车站。点一股固定它，其他站自动换成走得通的股道。")}</p>
        {planned.error && <p className="my-2 text-[13px] text-warn" role="alert">{planned.error}</p>}
        {models[shown] && <>
          {models[shown].conflict && <p className="my-2 text-[13px] text-warn" role="alert">{models[shown].conflict}</p>}
          <Strip model={models[shown]} setModel={m => { setModel(m); setError("") }} />
          <p className="mt-2 text-[12px] text-dim">{fn.stopsSummaryOf(models[shown])}</p>
        </>}
        {models.length > 0 && lineName.trim() && type.trim() && <p className="text-[12px] text-dim">{tr("建出：{0}", models.map(nameOf).join("、"))}</p>}
      </>}
    </SidePanel>
  )
}
