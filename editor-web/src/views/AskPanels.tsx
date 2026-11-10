/**
 * The old page's last small dialogs as side panels (retiring the old code, step 8). One panel asks: a question, things to
 * know, perhaps one value, and an answer button (shifting later services, spacing a run, clearing a table, deleting a
 * logical line or a type, replacing duties, planning again, placing trains, a first draft of logical lines, a station's
 * wait, how early a train leaves the depot). The others choose: the depot of a service's order, a logical line's name and
 * colour, the lines owning a section, the logical lines a line runs on, and a new line's names. The rules are those of
 * src/core (called through the engine's functions); what a panel sends back goes into the draft as before.
 */
import { useState } from "react"
import { legacy, useLegacy, type AskTarget } from "../legacy/engine"
import { SidePanel } from "../ui/bits"
import { tr } from "../i18n"
import * as X from "../core/settings"
import { clock, colorOf, shortName, stopName } from "./now"

const field = "h-7 rounded-md border border-ctl bg-panel px-2 text-[13px]"
const hint = "m-0 text-[12px] text-dim"

function Buttons({ ok, onOk, onClose, error, danger }: { ok: string; onOk: () => void; onClose: () => void; error: string; danger?: boolean }) {
  return (
    <>
      <button className={"btn " + (danger ? "btn-danger" : "btn-primary")} onClick={onOk}>{ok}</button>
      <button className="btn" onClick={onClose}>{tr("取消")}</button>
      {error && <span className="text-[12px] text-warn" role="alert">{error}</span>}
    </>
  )
}

/** A question, things to know, perhaps one value; the answer goes to target.go, which may say what is wrong */
export function AskPanel({ target, onClose }: { target: AskTarget; onClose: () => void }) {
  const [value, setValue] = useState(target.input ? target.input.value : "")
  const [error, setError] = useState("")
  const answer = () => {
    const problem = target.go ? target.go(value) : undefined
    if (problem) { setError(problem); return }
    onClose()
  }
  return (
    <SidePanel open onClose={onClose} title={target.title} width={480}
      footer={target.go ? <Buttons ok={target.ok} onOk={answer} onClose={onClose} error={error} danger={target.danger} /> : <button className="btn btn-primary" onClick={onClose}>{target.ok}</button>}>
      <div className="flex flex-col gap-2 text-[13px]">
        {target.text.map(line => <p key={line} className="m-0">{line}</p>)}
        {target.list && target.list.length > 0 && <ul className="m-0 pl-5 text-caution">{target.list.map(item => <li key={item}>{item}</li>)}</ul>}
        {target.input && (
          <label className="flex items-center gap-2">{target.input.label}
            <input className={field} autoFocus value={value} inputMode={target.input.numeric ? "decimal" : undefined} placeholder={target.input.placeholder}
              onChange={e => setValue(e.target.value)} onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); answer() } }} />
            {target.input.unit}
          </label>
        )}
        {target.hints?.map(line => <p key={line} className={hint}>{line}</p>)}
      </div>
    </SidePanel>
  )
}

/** The depot a service's train comes out of, or goes back to from a stop before the last */
export function ServiceOrderPanel({ serviceId, order, stop, onClose }: { serviceId: string; order: "deploy" | "return"; stop: number; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const s = fn.findService(serviceId)
  const current = s ? (order === "deploy" ? s.deployOrder : s.returnOrder) : null
  const [depot, setDepot] = useState<string>(current ? String(current.depot) : "0")
  if (!s) return null
  const line = fn.currentLine()
  const lineRoutes = new Set((line ? line.routes : [s.route]).map((r: any) => r.id))
  const depots = v.data.depots.filter((each: any) => !each.transportMode || !s.route.transportMode || each.transportMode === s.route.transportMode)
  const near = depots.filter((each: any) => each.routes.some((id: string) => lineRoutes.has(id))), far = depots.filter((each: any) => !near.includes(each))
  const title = order === "deploy" ? tr("{0} 班从{1}出车", clock(s.start), stopName(s.route, stop)) : tr("{0} 班在{1}转回送", clock(s.start), stopName(s.route, stop))
  const save = () => {
    fn.editTimetable(s.route, (timetable: any) => fn.setServiceOrder(timetable.tables[s.tableIndex], s.service, order, stop, depot))
    onClose()
  }
  return (
    <SidePanel open onClose={onClose} title={title} width={440} footer={<Buttons ok={tr("确定")} onOk={save} onClose={onClose} error="" />}>
      <div className="flex flex-col gap-2 text-[13px]">
        <p className={hint}>{order === "deploy" ? tr("这一班从这一站起载客：车厂出一列车空跑到这里接上，之前的站不停。") : tr("这一班到这一站为止载客：之后的站不停，车从这里空跑回所选车厂。")}</p>
        <label className="flex items-center gap-2">{tr("车厂")}
          <select className={field} value={depot} onChange={e => setDepot(e.target.value)}>
            <option value="0">{tr("自动选的车厂")}</option>
            {near.length > 0 && <optgroup label={tr("跑这条线路的")}>{near.map((each: any) => <option key={each.id} value={each.id}>{shortName(each.name)}</option>)}</optgroup>}
            {far.length > 0 && <optgroup label={tr("其他车厂")}>{far.map((each: any) => <option key={each.id} value={each.id}>{shortName(each.name)}</option>)}</optgroup>}
          </select>
        </label>
      </div>
    </SidePanel>
  )
}

const hex = (color: number) => "#" + (color & 0xffffff).toString(16).padStart(6, "0")

/** A new logical line, or one changed: name, colour, connecting line; then what is done with its ID */
export function LogicalLinePanel({ lineId, then, onClose }: { lineId: string | null; then?: (id: string) => void; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const line: X.LogicalLine | undefined = lineId ? X.logicalLinesShown(v.data, v.draft).get(lineId) : undefined
  const [name, setName] = useState(line ? line.name : "")
  const [color, setColor] = useState(hex(line ? line.color : 0x3b82f6))
  const [connecting, setConnecting] = useState(Boolean(line && line.connecting))
  const [error, setError] = useState("")
  const save = () => {
    if (!name.trim()) { setError(tr("写上名称")); return }
    const id = line ? line.id : X.newLogicalId()
    fn.draftSettingsPart("logicalLines", (drafted: Record<string, any>) => { drafted[id] = { name: name.trim(), color: parseInt(color.slice(1), 16), connecting } })
    then?.(id)
    onClose()
  }
  return (
    <SidePanel open onClose={onClose} title={line ? tr("逻辑线路：{0}", line.name) : tr("新建逻辑线路")} width={440}
      footer={<Buttons ok={line ? tr("确定") : tr("新建")} onOk={save} onClose={onClose} error={error} />}>
      <div className="flex flex-col gap-2 text-[13px]">
        <label className="flex items-center gap-2">{tr("名称")}<input className={field + " flex-1"} autoFocus maxLength={64} value={name} placeholder={tr("例如 京沪线")} onChange={e => setName(e.target.value)} /></label>
        <label className="flex items-center gap-2">{tr("颜色")}<input type="color" value={color} onChange={e => setColor(e.target.value)} /></label>
        <label className="flex items-center gap-2"><input type="checkbox" checked={connecting} onChange={e => setConnecting(e.target.checked)} />{tr("联络线：所有运营线路都能走")}</label>
        <p className={hint}>{tr("逻辑线路是轨道本身的线路，和运营线路分开：每段轨道最多属于一条；运营线路走它勾选的逻辑线路，没勾时走它停靠的站台所在的逻辑线路。")}</p>
      </div>
    </SidePanel>
  )
}

/** Ticking lines, each with its colour */
function Choices({ items, chosen, setChosen, empty }: { items: { id: string; name: string; color: number }[]; chosen: string[]; setChosen: (ids: string[]) => void; empty: string }) {
  if (!items.length) return <p className={hint}>{empty}</p>
  return (
    <div className="flex flex-col gap-1">
      {items.map(item => (
        <label key={item.id} className="flex items-center gap-2">
          <input type="checkbox" checked={chosen.includes(item.id)} onChange={e => setChosen(e.target.checked ? [...chosen, item.id] : chosen.filter(id => id !== item.id))} />
          <span className="inline-block h-3 w-3 rounded-sm" style={{ background: colorOf(item.color) }} />{item.name}
        </label>
      ))}
    </div>
  )
}

/** The lines owning a rail's section, from the lines of the map's kind of transport */
export function OwnersPanel({ index, onClose }: { index: number; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const shown = fn.railOwnersShown(index)
  const [chosen, setChosen] = useState<string[]>(shown.owners)
  const [error, setError] = useState("")
  const lines = v.data.lines.filter((line: any) => line.key.startsWith((v.net.mode || "TRAIN") + "|")).map((line: any) => ({ id: line.key, name: shortName(line.name), color: line.color }))
  const save = () => {
    if (!chosen.length) { setError(tr("至少勾一条线路；要让所有线路都能走，请用右键“对所有线路开放”")); return }
    fn.setOwnerOverride(index, chosen)
    onClose()
  }
  return (
    <SidePanel open onClose={onClose} title={tr("区段归属")} width={440} footer={<Buttons ok={tr("确定")} onOk={save} onClose={onClose} error={error} />}>
      <div className="flex flex-col gap-2 text-[13px]">
        <p className="m-0">{tr("这一段轨道（到下一个道岔或边界为止的整个区段）只让勾选的线路走。联络线和没有站台的线路不受限制。")}</p>
        <Choices items={lines} chosen={chosen} setChosen={setChosen} empty={tr("没有这种运输方式的线路")} />
        <p className={hint}>{tr("改动先存为草稿，点“应用”后服务端重新算归属，走不了的线路会重新寻路。")}</p>
      </div>
    </SidePanel>
  )
}

/** The logical lines an operating line runs on: ticked, or none for those of its platforms */
export function LineTracksPanel({ lineKey, onClose }: { lineKey: string; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const line = v.data.lines.find((each: any) => each.key === lineKey)
  const [chosen, setChosen] = useState<string[]>(X.lineTracksShown(v.data, v.draft)[lineKey] || [])
  const items = [...X.logicalLinesShown(v.data, v.draft).values()].filter(each => !each.connecting)
  const save = () => {
    fn.draftSettingsPart("lineTracks", (drafted: Record<string, any>) => { drafted[lineKey] = chosen.length ? chosen : null })
    onClose()
  }
  return (
    <SidePanel open onClose={onClose} title={tr("能走的逻辑线路：{0}", line ? shortName(line.name) : lineKey)} width={440} footer={<Buttons ok={tr("确定")} onOk={save} onClose={onClose} error="" />}>
      <div className="flex flex-col gap-2 text-[13px]">
        <p className="m-0">{tr("这条运营线路的各种别只走勾选的逻辑线路（和联络线、不属于任何逻辑线路的轨道）。都不勾时，走它停靠的站台所在的逻辑线路。")}</p>
        <Choices items={items} chosen={chosen} setChosen={setChosen} empty={tr("还没有逻辑线路")} />
        <p className={hint}>{tr("改动先存为草稿，点“应用”后服务端重新算，走不了的线路会重新寻路。")}</p>
      </div>
    </SidePanel>
  )
}

/** A new line picked on the map: its line group's name, its type's name and its colour, into the draft */
export function FinishPickingPanel({ onClose }: { onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const picking = v.picking
  const [lineName, setLineName] = useState("")
  const [type, setType] = useState("")
  const [color, setColor] = useState<string>(picking ? picking.color : "#3b82f6")
  const [error, setError] = useState("")
  if (!picking) return null
  const stops: any[] = picking.stops
  const save = () => {
    const name = lineName.trim(), typeName = type.trim()
    if (!name) { setError(tr("写上线路组名，例如 1号线")); return }
    if (v.data.lines.some((l: any) => l.key === picking.mode + "|" + name.toLowerCase())) { setError(tr("已经有这个线路组了，请用“新建 ▾ → 种别…”")); return }
    if (!typeName) { setError(tr("写上种别名，例如 普通")); return }
    v.draft.created.push({
      key: "n" + Date.now().toString(36), source: "", mode: picking.mode, lineName: name, lineKey: picking.mode + "|" + name.toLowerCase(),
      name: `${shortName(stops[0].name)} → ${shortName(stops[stops.length - 1].name)}`, serviceType: typeName, color: parseInt(color.slice(1), 16),
      stops: stops.map((stop, k) => ({ platformId: stop.platformId, pass: stop.pass && k > 0 && k < stops.length - 1, waypoints: stop.waypoints.slice() })),
      services: [], table: 0, depot: "",
    })
    v.picking = null
    fn.saveDraft()
    fn.renderAll()
    onClose()
  }
  return (
    <SidePanel open onClose={onClose} title={tr("新建线路")} width={440} footer={<Buttons ok={tr("放进草稿")} onOk={save} onClose={onClose} error={error} />}>
      <div className="flex flex-col gap-2 text-[13px]">
        <label className="flex items-center gap-2">{tr("线路组名")}<input className={field + " flex-1"} autoFocus value={lineName} placeholder={tr("例如 1号线")} onChange={e => setLineName(e.target.value)} /></label>
        <label className="flex items-center gap-2">{tr("种别名")}<input className={field + " flex-1"} value={type} placeholder={tr("例如 普通")} onChange={e => setType(e.target.value)} /></label>
        <label className="flex items-center gap-2">{tr("颜色")}<input type="color" value={color} onChange={e => setColor(e.target.value)} /></label>
        <p className={hint}>{stops.map(stop => stop.name).join(" → ")}</p>
      </div>
    </SidePanel>
  )
}
