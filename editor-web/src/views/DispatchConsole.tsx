/**
 * The dispatcher's console (the old renderConsole and its buttons): the rail system to show, then a card per train on the
 * line, the latest first, with where it is going, what is going on with it, and its commands. Holding and releasing,
 * clearing at the next station, sending back to the depot (or both), cancelling go at once; a longer stop and turning back
 * early open in the card instead of a dialog. Sent as the old consoleCommand does (api/apply, a dispatch operation). The old
 * console still reads the trains every two seconds into its own hidden element.
 */
import { useEffect, useState } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { tr } from "../i18n"

export function DispatchConsole({ className, onChoose }: { className: string; onChoose: (id: string) => void }) {
  useLegacy()
  const { fn, v } = legacy
  // The console reads on its own timer: look again every second
  const [, setTick] = useState(0)
  useEffect(() => {
    const timer = setInterval(() => setTick(t => t + 1), 1000)
    return () => clearInterval(timer)
  }, [])
  const data = v.data, consoleState = v.consoleState
  const systems = (data && data.contingencySystems || []).filter((system: any) => system.routes.length)
  const d = consoleState.data
  const trains: any[] = d && d.available ? fn.consoleTrains().slice().sort((a: any, b: any) => (b.late - a.late) || a.id.localeCompare(b.id)) : []
  const canEdit = Boolean(data && data.canEdit)
  return (
    <aside className={className} aria-label={tr("调度台")}>
      <div className="flex flex-col gap-2 p-3">
        <div className="flex flex-wrap items-center gap-2">
          <select className="h-7 rounded-md border border-ctl bg-panel px-1 text-[13px]" value={consoleState.system} aria-label={tr("路网")}
            onChange={e => { consoleState.system = e.target.value; fn.renderConsole(); fn.drawNetwork(); fn.renderAll() }}>
            <option value="">{tr("全部路网")}</option>
            {systems.map((system: any) => <option key={system.anchor} value={system.anchor}>{fn.systemTitle(system)}</option>)}
          </select>
          {d && d.available && <span className="text-[12px] text-dim">{tr("{0} 列车在线上{1}，{2} 秒前", trains.length, trains.some(t => t.noDuty) ? tr("（{0} 列不在运用中）", trains.filter(t => t.noDuty).length) : "", d.age || 0)}</span>}
        </div>
        {!d || !d.available ? <p className="m-0 text-[12px] text-dim">{tr("正在向游戏要各列车的位置…")}</p> : <>
          {!canEdit && <p className="m-0 text-[12px] text-dim">{tr("没有编辑权限，只能看")}</p>}
          {!trains.length && <p className="m-0 text-[12px] text-dim">{tr("没有车在线上")}</p>}
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {trains.map(train => <TrainCard key={train.id} train={train} chosen={train.id === consoleState.selected} canEdit={canEdit} onChoose={() => onChoose(train.id)} />)}
          </ul>
        </>}
      </div>
    </aside>
  )
}

function TrainCard({ train, chosen, canEdit, onChoose }: { train: any; chosen: boolean; canEdit: boolean; onChoose: () => void }) {
  const { fn, v } = legacy
  const [sent, setSent] = useState<string[]>([])
  const [open, setOpen] = useState<"" | "extend" | "turn">("")
  const [seconds, setSeconds] = useState("60")
  const [station, setStation] = useState("")
  const [error, setError] = useState("")
  const held = (train.dispatch & 1) !== 0
  const send = async (key: string, ...operations: any[]) => {
    for (const operation of operations) await fn.consoleCommand(null, { train: train.id, ...operation })
    setSent(list => [...list, key])
  }
  const state: string = fn.consoleTrainState(train)
  const late = train.late >= 300
  // Where it can turn back: the stations of its rail system, or of its route
  const r = v.data.routeById.get(train.route)
  const system = r ? fn.systemOfRoute(r.id) : null
  const stations: any[] = (system ? system.stations || [] : r ? r.stops.map((stop: any) => ({ id: stop.station, name: stop.name })) : []).filter((each: any) => each.id)
  const button = (key: string, label: string, run: () => void) => (
    <button key={key} type="button" className="btn h-6 px-2 text-[12px]" disabled={sent.includes(key)} onClick={e => { e.stopPropagation(); run() }}>{sent.includes(key) ? tr("已发送") : label}</button>
  )
  return (
    <li className={"rounded-lg border p-2.5 " + (chosen ? "tint-sel border-sel" : "border-grid bg-panel")} onClick={onChoose} data-console-card={train.id}>
      <div className="flex items-center gap-2">
        <b className="text-[13px]">{fn.consoleTrainName(train)}</b>
        <span className="min-w-0 flex-1 truncate text-[12px] text-dim">{fn.shortName(train.routeName || "")}</span>
        {late && <span className="tint-warn rounded-pill px-2 text-[11px] font-medium text-warn">{tr("晚点 {0} 分钟", Math.floor(train.late / 60))}</span>}
        {held && <span className="tint-caution rounded-pill px-2 text-[11px] font-medium text-caution">{tr("扣停")}</span>}
      </div>
      <p className="m-0 mt-0.5 text-[12px] text-dim">{train.next ? tr("下一站 {0}", fn.shortName(train.next)) : ""}{train.block ? tr("，区段 {0}", train.block.slice(-4)) : ""}</p>
      {state && <p className="m-0 mt-0.5 text-[12px]">{state}</p>}
      {canEdit && !train.noDuty && (
        <div className="mt-2 flex flex-wrap gap-1">
          {button(held ? "release" : "hold", held ? tr("放行") : tr("扣停"), () => send(held ? "release" : "hold", { command: held ? "release" : "hold" }))}
          <button type="button" className="btn h-6 px-2 text-[12px]" aria-expanded={open === "extend"} onClick={e => { e.stopPropagation(); setOpen(open === "extend" ? "" : "extend"); setError("") }}>{tr("延长停站…")}</button>
          <button type="button" className="btn h-6 px-2 text-[12px]" aria-expanded={open === "turn"} onClick={e => { e.stopPropagation(); setOpen(open === "turn" ? "" : "turn"); setStation(stations[0]?.id || ""); setError("") }}>{tr("提前折返…")}</button>
          {button("clearOut", tr("下一站清客"), () => send("clearOut", { command: "clearOut" }))}
          {button("return", tr("指定回库"), () => send("return", { command: "return" }))}
          {/* Cleared at the next station, then back to the depot: the two commands at once */}
          {button("clearReturn", tr("下一站清客后回库"), () => send("clearReturn", { command: "clearOut" }, { command: "return" }))}
          {train.dispatch ? button("cancel", tr("撤销"), () => send("cancel", { command: "cancel" })) : null}
        </div>
      )}
      {open === "extend" && (
        <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[12px]" onClick={e => e.stopPropagation()}>
          {tr("多停")}<input className="h-6 w-16 rounded-md border border-ctl bg-panel px-1 num" type="number" min={1} max={1800} value={seconds} onChange={e => setSeconds(e.target.value)} />{tr("秒")}
          <button type="button" className="btn btn-primary h-6 px-2 text-[12px]" onClick={() => {
            const value = Math.round(Number(seconds))
            if (!(value > 0) || value > 1800) { setError(tr("要在 1 到 1800 秒之间")); return }
            setOpen("")
            send("extend", { command: "extend", seconds: value })
          }}>{tr("发送")}</button>
          <span className="w-full text-dim">{tr("正停在站上的就在这一站，正在跑的在下一站。")}</span>
        </div>
      )}
      {open === "turn" && (
        <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[12px]" onClick={e => e.stopPropagation()}>
          {tr("在")}<select className="h-6 rounded-md border border-ctl bg-panel px-1" value={station} onChange={e => setStation(e.target.value)}>
            {stations.map(each => <option key={each.id} value={each.id}>{fn.shortName(each.name)}</option>)}
          </select>{tr("折返")}
          <button type="button" className="btn btn-primary h-6 px-2 text-[12px]" onClick={() => {
            if (!station) { setError(tr("选一个车站")); return }
            setOpen("")
            send("turn", { command: "turn", station })
          }}>{tr("发送")}</button>
          <span className="w-full text-dim">{tr("在那一站清客后空车折到对向站台，接着跑运用的下一段。同时下了“下一站清客”的，折返后空车回库。")}</span>
        </div>
      )}
      {error && <p className="m-0 mt-1 text-[12px] text-warn" role="alert">{error}</p>}
    </li>
  )
}
