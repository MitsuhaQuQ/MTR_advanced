/**
 * A line moved to another depot (0.69.2, Q world 2026-11-12): its routes out of the old depot's routes and sidings and
 * into the new depot's on a vehicle of it, with the old depot's duties of them; and, to swap, a line of the new depot moved
 * back the same way. The old depot's duties of a moved line are dropped (the user's choice): set by hand they go now,
 * planned by the game they go when it plans again without the routes. Everything goes into the draft.
 */
import { useState } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { SidePanel } from "../ui/bits"
import { tr } from "../i18n"
import { shortName } from "./now"
import { entryWith, entryWithout, hasDutiesOf, putTaken, takeRoutes, vehiclesOf } from "../core/depotMove"

const field = "h-7 rounded-md border border-ctl bg-panel px-2 text-[13px]"
const label = "flex flex-col gap-0.5 text-[12px] text-dim"
const vehicleName = (name: string) => String(name || "").replace(/^mtr_custom_train_/, "") || tr("（无车型）")
const modeOf = (key: string) => key.split("|")[0]

export function MoveLinePanel({ depotId, lineKey, onClose }: { depotId: string; lineKey: string; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data
  // The routes as drafted, so a move after another sees the first
  const linesOf = (depot: any) => {
    if (!depot) return []
    const own: string[] = fn.depotEntryOf(depot).routes
    return (data.lines as any[]).map(line => ({ key: line.key as string, name: line.name as string, routes: line.routes.filter((r: any) => own.includes(r.id)) })).filter(line => line.routes.length)
  }
  const sources = (data.depots as any[]).filter(depot => linesOf(depot).length)
  const [fromId, setFromId] = useState<string>(() => depotId || ((sources.find(depot => linesOf(depot).some(line => line.key === lineKey)) || sources[0] || {}).id ?? ""))
  const from = fn.depotById(fromId)
  const fromLines = linesOf(from)
  const [line, setLine] = useState(lineKey)
  const chosen = fromLines.find(each => each.key === line) || fromLines[0]
  const [unticked, setUnticked] = useState<string[]>([])
  const routes: string[] = chosen ? chosen.routes.map((r: any) => r.id).filter((id: string) => !unticked.includes(id)) : []
  const targets = (data.depots as any[]).filter(depot => depot.id !== fromId && (!chosen || linesOf(depot).every(each => modeOf(each.key) === modeOf(chosen.key))))
  const [toId, setToId] = useState("")
  const to = targets.find(depot => depot.id === toId) || targets[0]
  const toVehicles: string[] = to ? vehiclesOf(to) : []
  const [vehicle, setVehicle] = useState("")
  const onto = toVehicles.includes(vehicle) ? vehicle : toVehicles[0] ?? ""
  const fromDuties = from ? fn.dutiesOf(from) : null
  const hasDuties = Boolean(fromDuties && hasDutiesOf(fromDuties, routes))
  const [withDuties, setWithDuties] = useState<boolean | null>(null)
  // By default the duties go along where they were set by hand; planned by the game, both depots plan again
  const carry = hasDuties && (withDuties ?? !fromDuties.auto)
  const [swap, setSwap] = useState(false)
  const backLines = to ? linesOf(to).filter(each => !chosen || each.key !== chosen.key) : []
  const [backLine, setBackLine] = useState("")
  const back = backLines.find(each => each.key === backLine) || backLines[0]
  const fromVehicles: string[] = from ? vehiclesOf(from) : []
  const [backVehicle, setBackVehicle] = useState("")
  const backOnto = fromVehicles.includes(backVehicle) ? backVehicle : fromVehicles[0] ?? ""
  const [error, setError] = useState("")

  /** One way of the move: the depots' routes, then the duties @return how many days of duties went along */
  const moveOne = (source: any, target: any, ids: string[], vehicleThere: string, along: boolean) => {
    fn.storeDepotEntry(source, entryWithout(fn.depotEntryOf(source), ids))
    fn.storeDepotEntry(target, entryWith(fn.depotEntryOf(target), target, ids, vehicleThere))
    const sourceDuties = fn.dutiesOf(source)
    if (!hasDutiesOf(sourceDuties, ids)) return 0
    let taken = new Map<string, any[][]>()
    if (!sourceDuties.auto) fn.editDuties(source, (duties: any) => { taken = takeRoutes(duties, source, ids) })
    else taken = takeRoutes(JSON.parse(JSON.stringify(sourceDuties)), source, ids)
    if (!along) return 0
    let count = 0
    fn.editDuties(target, (duties: any) => { count = Math.max(0, putTaken(duties, target, taken, vehicleThere)) })
    return count
  }
  const apply = () => {
    if (!from || !to || !chosen || !routes.length) { setError(tr("先选原车厂、线路和目标车厂")); return }
    if (!toVehicles.length) { setError(tr("目标车厂没有存车线")); return }
    if (swap && back && !fromVehicles.length) { setError(tr("原车厂没有存车线")); return }
    const moved = moveOne(from, to, routes, onto, carry)
    let text = tr("已把“{0}”从车厂“{1}”换到“{2}”（{3}），{4} 份运用跟着搬；原车厂这条线的运用已去掉。", shortName(chosen.name) || chosen.key, shortName(from.name), shortName(to.name), vehicleName(onto), moved)
    if (swap && back) {
      const movedBack = moveOne(to, from, back.routes.map((r: any) => r.id), backOnto, carry)
      text += tr("同时把“{0}”换到“{1}”（{2}），{3} 份运用跟着搬。", shortName(back.name) || back.key, shortName(from.name), vehicleName(backOnto), movedBack)
    }
    v.dutyNotice = text + tr("都在草稿里，检查后再应用。")
    fn.renderBanner()
    fn.renderAll()
    onClose()
  }

  return (
    <SidePanel open onClose={onClose} title={tr("线路换车厂")} width={560}
      hint={tr("把一条线路（可以只选其中几个种别）从一个车厂换到另一个车厂，跑那个车厂的车型。原车厂的负责线路和存车线里去掉它，原车厂这条线的运用直接去掉；目标车厂加上它。时刻表不变。")}
      footer={<>
        <button className="btn btn-primary" disabled={!data.canEdit || !routes.length || !to} onClick={apply}>{tr("放进草稿")}</button>
        <button className="btn" onClick={onClose}>{tr("取消")}</button>
        {error && <span className="text-[12px] text-warn" role="alert">{error}</span>}
      </>}>
      <div className="grid grid-cols-2 gap-2">
        <label className={label}>{tr("原车厂")}
          <select className={field} value={fromId} onChange={e => { setFromId(e.target.value); setLine(""); setUnticked([]); setWithDuties(null); setToId("") }}>
            {sources.map(depot => <option key={depot.id} value={depot.id}>{shortName(depot.name)}</option>)}
          </select></label>
        <label className={label}>{tr("线路")}
          <select className={field} value={chosen ? chosen.key : ""} onChange={e => { setLine(e.target.value); setUnticked([]); setWithDuties(null) }}>
            {fromLines.map(each => <option key={each.key} value={each.key}>{shortName(each.name) || each.key}</option>)}
          </select></label>
      </div>
      {chosen && chosen.routes.length > 1 && <div className="mt-2 flex flex-wrap gap-1.5">
        {chosen.routes.map((r: any) => {
          const on = !unticked.includes(r.id)
          return <button key={r.id} type="button" aria-pressed={on} onClick={() => setUnticked(on ? [...unticked, r.id] : unticked.filter(id => id !== r.id))}
            className={"inline-flex h-6 items-center rounded-pill border px-2 text-[12px] " + (on ? "tint-accent border-brand text-brand" : "border-ctl bg-panel")}>{shortName(r.name)}</button>
        })}
      </div>}
      <div className="mt-3 grid grid-cols-2 gap-2">
        <label className={label}>{tr("目标车厂")}
          <select className={field} value={to ? to.id : ""} onChange={e => { setToId(e.target.value); setVehicle(""); setBackLine("") }}>
            {targets.map(depot => <option key={depot.id} value={depot.id}>{shortName(depot.name)}</option>)}
          </select></label>
        <label className={label}>{tr("用目标车厂的车型")}
          <select className={field} value={onto} onChange={e => setVehicle(e.target.value)}>
            {toVehicles.map(each => <option key={each} value={each}>{vehicleName(each)}</option>)}
          </select></label>
      </div>
      <label className="mt-3 flex items-start gap-1.5 text-[13px]">
        <input type="checkbox" className="mt-1" checked={carry} disabled={!hasDuties} onChange={e => setWithDuties(e.target.checked)} />
        <span>{tr("连运用一起搬")}<br /><span className="text-[12px] text-dim">{!hasDuties ? tr("原车厂这条线没有运用，目标车厂按时刻表排")
          : fromDuties.auto ? tr("原车厂的运用是自动排的：不搬时两边都按时刻表重排；搬过去后目标车厂改为按运用图")
          : tr("原车厂的运用是手排的：搬到目标车厂所选车型的存车线，目标车厂改为按运用图")}</span></span></label>
      <label className="mt-3 flex items-center gap-1.5 text-[13px]">
        <input type="checkbox" checked={swap} disabled={!backLines.length} onChange={e => setSwap(e.target.checked)} />{tr("对换：同时把目标车厂的一条线换到原车厂")}</label>
      {swap && back && <div className="mt-2 grid grid-cols-2 gap-2">
        <label className={label}>{tr("换回来的线路")}
          <select className={field} value={back.key} onChange={e => setBackLine(e.target.value)}>
            {backLines.map(each => <option key={each.key} value={each.key}>{shortName(each.name) || each.key}</option>)}
          </select></label>
        <label className={label}>{tr("用原车厂的车型")}
          <select className={field} value={backOnto} onChange={e => setBackVehicle(e.target.value)}>
            {fromVehicles.map(each => <option key={each} value={each}>{vehicleName(each)}</option>)}
          </select></label>
      </div>}
    </SidePanel>
  )
}
