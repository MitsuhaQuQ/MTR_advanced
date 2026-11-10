/**
 * The depot layer's other dialogs as side panels (the old vehicleDialog, dutyGroupsDialog and releasePlanDialog come
 * here; kept as old<Name>), with the old rules:
 * - a vehicle's settings in a depot, checked as typed, the fields greyed while the first siding's own settings apply;
 * - the duty groups planned, each to plan again on its own, and other depots' groups to use here as a template;
 * - the plan of trains going out and back on a day, chosen in the panel.
 */
import { useState } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { SidePanel } from "../ui/bits"
import { tr } from "../i18n"
import { clock, daysText, shortName, weekdayName, weekdaysShort } from "./now"

const field = "h-7 rounded-md border border-ctl bg-panel px-2 text-[13px]"
const label = "flex flex-col gap-0.5 text-[12px] text-dim"

// ---------- A vehicle's settings ----------

export function VehiclePanel({ depotId, poolKey, onClose }: { depotId: string; poolKey: string; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const depot = v.data ? fn.depotById(depotId) : null
  const pool = depot ? (depot.pools || []).find((p: any) => p.key === poolKey) : null
  const [entry] = useState(() => (depot ? fn.depotEntryOf(depot) : null))
  const settings = entry && pool ? entry.vehicleSettings[pool.key] : null
  const shown = settings || (pool && pool.vehicleSettings) || {}
  const perSecond = (value: number) => Math.round(value * 400 * 100) / 100
  const typesOf = (text: string) => String(text || "").split(/[,，、]/).map(part => part.trim()).filter(Boolean).join(",")
  const gameTypes = depot && pool ? ((depot.pools || []).find((each: any) => each.vehicle === pool.vehicle) || {}).serviceTypes || "" : ""
  const [set, setSet] = useState(Boolean(settings))
  const [acceleration, setAcceleration] = useState(String(perSecond(shown.acceleration || 0.01)))
  const [deceleration, setDeceleration] = useState(shown.deceleration > 0 ? String(perSecond(shown.deceleration)) : "")
  const [maxSpeed, setMaxSpeed] = useState(shown.maxSpeed > 0 ? String(shown.maxSpeed) : "")
  const [manual, setManual] = useState(Boolean(shown.manual))
  const [maxManualSpeed, setMaxManualSpeed] = useState(String(shown.maxManualSpeed || 0))
  const [switchBack, setSwitchBack] = useState(String((shown.manualToAutomaticTime || 0) / 2))
  const [types, setTypes] = useState(() => (entry && pool && entry.serviceTypes[pool.vehicle] !== undefined ? entry.serviceTypes[pool.vehicle] : gameTypes))
  if (!depot || !pool || !entry) return null
  const shared = (depot.pools || []).filter((each: any) => each.vehicle === pool.vehicle).length
  const manualSpeeds: number[] = depot.manualSpeeds || []
  const problem = !set ? ""
    : !(Number(acceleration) / 400 > 0) || (deceleration.trim() !== "" && !(Number(deceleration) >= 0)) ? tr("加速度写正数，减速度写数字或者留空")
    : !(Math.round(Number(switchBack || 0) * 2) >= 0) || Math.round(Number(switchBack || 0) * 2) > 7200 ? tr("转回自动写 0 到 3600 秒")
    : !(Math.round(Number(maxSpeed || 0)) >= 0) || Math.round(Number(maxSpeed || 0)) > 300 ? tr("最高速度写 0 到 300，或者留空") : ""
  const save = () => {
    if (problem) return
    entry.vehicleSettings[pool.key] = set ? {
      acceleration: Number(acceleration) / 400, deceleration: deceleration.trim() === "" ? 0 : Number(deceleration) / 400, manual,
      maxManualSpeed: Number(maxManualSpeed) || 0, manualToAutomaticTime: switchBack.trim() === "" ? 0 : Math.round(Number(switchBack) * 2), maxSpeed: maxSpeed.trim() === "" ? 0 : Math.round(Number(maxSpeed)),
    } : null
    const text = typesOf(types)
    if (text !== typesOf(gameTypes)) entry.serviceTypes[pool.vehicle] = text
    else delete entry.serviceTypes[pool.vehicle]
    fn.storeDepotEntry(depot, entry)
    onClose()
  }
  const off = !set
  return (
    <SidePanel open onClose={onClose} title={tr("车型设置：{0}", pool.vehicle || pool.key)} width={480}
      hint={tr("车厂“{0}”里这种车型（{1}）的列车怎么跑。同车型的存车线共用这些设置；别的车厂的同车型在那个车厂下设。", shortName(depot.name), pool.key || pool.vehicle)}
      footer={<>
        <button className="btn btn-primary" disabled={!v.data.canEdit || Boolean(problem)} onClick={save}>{tr("放进草稿")}</button>
        <button className="btn" onClick={onClose}>{tr("取消")}</button>
        {problem && <span className="text-[12px] text-warn" role="alert">{problem}</span>}
      </>}>
      <label className="flex items-start gap-1.5 text-[13px]"><input type="checkbox" className="mt-1" checked={set} onChange={e => setSet(e.target.checked)} /> {tr("统一设置这个车型的加减速、最高速度和手动驾驶（不勾就按第一条存车线原来的）")}</label>
      <fieldset disabled={off} className={"m-0 mt-2 grid grid-cols-2 gap-x-3 gap-y-2 border-0 p-0 " + (off ? "opacity-50" : "")}>
        <label className={label}>{tr("加速度 m/s²")}<input className={field + " num"} type="number" step="0.01" min="0.4" max="20" value={acceleration} onChange={e => setAcceleration(e.target.value)} /></label>
        <label className={label}>{tr("减速度 m/s²（空着同加速度）")}<input className={field + " num"} type="number" step="0.01" min="0" max="20" value={deceleration} onChange={e => setDeceleration(e.target.value)} /></label>
        <label className={label}>{tr("最高速度 km/h（空着不限）")}<input className={field + " num"} type="number" step="1" min="0" max="300" value={maxSpeed} onChange={e => setMaxSpeed(e.target.value)} /></label>
        <label className="flex items-end gap-1.5 pb-1 text-[13px]"><input type="checkbox" checked={manual} onChange={e => setManual(e.target.checked)} /> {tr("允许手动驾驶")}</label>
        <label className={label + (manual ? "" : " opacity-50")}>{tr("手动驾驶最高速度（允许手动驾驶时才有用）")}
          <select className={field} value={maxManualSpeed} disabled={!manual} onChange={e => setMaxManualSpeed(e.target.value)}>
            {manualSpeeds.map((speed, index) => <option key={index} value={index}>{speed} km/h</option>)}
            {Number(maxManualSpeed) >= manualSpeeds.length && <option value={maxManualSpeed}>{tr("其他（{0}）", maxManualSpeed)}</option>}
          </select></label>
        <label className={label + (manual ? "" : " opacity-50")}>{tr("多久不操作转回自动 秒")}<input className={field + " num"} type="number" step="0.5" min="0" max="3600" disabled={!manual} value={switchBack} onChange={e => setSwitchBack(e.target.value)} /></label>
      </fieldset>
      <h3 className="label-sm m-0 mb-1 mt-4">{tr("能跑的种别")}</h3>
      <label className={label}>{tr("种别（逗号隔开，空着不限）")}<input className={field} value={types} onChange={e => setTypes(e.target.value)} /></label>
      {shared > 1 && <p className="m-0 mt-1 text-[12px] text-dim">{tr("同一车型的 {0} 种编组共用", shared)}</p>}
      <p className="mt-3 text-[12px] text-dim">{tr("改动先放进这个车厂的草稿，点“应用”才发给游戏。")}</p>
    </SidePanel>
  )
}

// ---------- Duty groups ----------

export function DutyGroupsPanel({ depotId, onClose }: { depotId: string; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data
  const depot = data ? fn.depotById(depotId) : null
  const [sent, setSent] = useState("")
  if (!depot) return null
  const vehicleName = (vehicle: string) => String(vehicle || "").replace(/^mtr_custom_train_/, "") || tr("（无车型）")
  const planned: any[] = (depot.shortfall && depot.shortfall.groups) || []
  const others: { other: any; vehicle: string; days: number; count: number }[] = []
  data.depots.filter((other: any) => other.id !== depot.id).forEach((other: any) => {
    ;([...fn.dutyGroupsOf(other).entries()] as [string, number][]).sort().forEach(([key, count]) => {
      const [vehicle, days] = key.split("|")
      if ((depot.sidings || []).some((siding: any) => (siding.vehicle || "") === vehicle)) others.push({ other, vehicle, days: Number(days), count })
    })
  })
  const canReplan = data.canEdit && fn.dutiesOf(depot).auto
  const replan = async (group: any) => {
    try {
      const result = await fn.sendJson("api/replan-duties", "POST", { depot: depot.id, group: group.key })
      setSent(result.ok ? tr("已请求只重排“{0}”{1} {2} 这一组运用。", shortName(depot.name), group.label, daysText(group.days)) : tr("没有权限，或者游戏里没有这个车厂。"))
    } catch {
      setSent(tr("发送失败，游戏可能已经关闭"))
    }
  }
  const cell = "border-t border-grid py-1.5 pr-2 align-top"
  return (
    <SidePanel open onClose={onClose} title={tr("运用组：{0}", shortName(depot.name))} width={620}
      hint={tr("运用按“车型 × 时刻表的星期”分组排：每张时刻表的日子各排一组，只改一张表时只重排它那一组，其他组沿用。")}
      footer={<><button className="btn" onClick={onClose}>{tr("关闭")}</button>{sent && <span className="text-[12px] text-dim" role="status">{sent}</span>}</>}>
      <h3 className="label-sm m-0 mb-1">{tr("本规划单元的运用组")}</h3>
      {planned.length ? <>
        <table className="w-full border-collapse text-[12px]"><tbody>
          {planned.map((group, index) => (
            <tr key={index}>
              <td className={cell + " font-medium"}>{group.label}</td>
              <td className={cell}>{daysText(group.days)}</td>
              <td className={cell}>{group.needed < 0 ? tr("最多同时 {0} 列，需要几列算不出", group.trains) : tr("最多同时 {0} 列，跑满需要 {1} 列", group.trains, group.needed)}
                {group.uncovered ? <span className="text-warn">{tr("，每天 {0} 班没有车", group.uncovered)}</span> : null}</td>
              <td className={cell + " text-dim"}>{group.kept ? tr("沿用") : tr("这次排的")}</td>
              <td className={cell + " text-right"}>{canReplan && <button className="btn h-6 px-2 text-[12px]" onClick={() => replan(group)}>{tr("只重排这组")}</button>}</td>
            </tr>
          ))}
        </tbody></table>
        <p className="mt-1 text-[12px] text-dim">{tr("“只重排这组”：下次寻路时只把这一组重新排一遍，其他组接得上就照旧；排在它后面、同一天跑同样线路的组会跟着重排。")}</p>
      </> : <p className="text-[12px] text-dim">{tr("还没有按组排过（自动运用重排一次后会列出来）")}</p>}
      <h3 className="label-sm m-0 mb-1 mt-4">{tr("套用其他车厂的运用组")}</h3>
      {others.length ? <>
        <table className="w-full border-collapse text-[12px]"><tbody>
          {others.map((entry, index) => (
            <tr key={index}>
              <td className={cell}>{shortName(entry.other.name)}</td>
              <td className={cell}>{vehicleName(entry.vehicle)}</td>
              <td className={cell}>{daysText(entry.days)}</td>
              <td className={cell}>{tr("{0} 份运用", entry.count)}</td>
              <td className={cell + " text-right"}>{data.canEdit && <button className="btn h-6 px-2 text-[12px]" onClick={() => { onClose(); fn.applyDutyGroup(depot, entry.other, entry.vehicle, entry.days) }}>{tr("套用到本车厂")}</button>}</td>
            </tr>
          ))}
        </tbody></table>
        <p className="mt-1 text-[12px] text-dim">{tr("把那个车厂这组运用（同车型、同样的星期）按存车线顺序放进本车厂的运用草稿，本车厂改为按运用图；那几天本车厂原来的运用换掉。原车厂不变：两边排了同样的班次时，要改掉一边再应用。")}</p>
      </> : <p className="text-[12px] text-dim">{tr("其他车厂没有同车型的运用")}</p>}
    </SidePanel>
  )
}

// ---------- The plan of trains going out and back ----------

export function ReleasePlanPanel({ depotId, onClose }: { depotId: string; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data
  const depot = data ? fn.depotById(depotId) : null
  const [day, setDay] = useState<number>(v.state.weekday)
  if (!depot) return null
  const duties = fn.dutiesOf(depot)
  const rows: any[] = []
  let inDepot = 0
  const placeOf = (r: any, stop: number) => (r ? `${shortName(r.name)} ${shortName(r.stops[stop] ? r.stops[stop].name : "?")}` : tr("已删除的线路"))
  depot.sidings.forEach((siding: any) => {
    const slots = duties.sidings[siding.id] || []
    let used = 0
    slots.forEach((slot: any, index: number) => {
      const on = fn.dutyOn(slot, day)
      if (!on || !on.segments.length) return
      used++
      const first = on.segments[0], last = on.segments[on.segments.length - 1]
      const firstRoute = data.routeById.get(first.route), lastRoute = data.routeById.get(last.route)
      const firstService = fn.segmentService(first), lastService = fn.segmentService(last)
      const lastStop = lastRoute ? fn.lastStopOf(last, lastRoute) : 0
      rows.push({
        start: fn.segmentStart(first), siding, index, stay: on.stay, count: on.segments.length, ...fn.legTimes(siding, on),
        first: `${firstService ? clock(fn.timesOf(firstService)[Math.min(first.from, firstService.plans.length - 1)][1]) : "--:--"} ${placeOf(firstRoute, first.from)}`,
        last: `${lastService ? clock(fn.timesOf(lastService)[fn.lastStopOf(last, lastService.route)][0]) : "--:--"} ${placeOf(lastRoute, lastStop)}`,
      })
    })
    if (siding.maxTrains >= 0) inDepot += Math.max(0, siding.maxTrains - used)
  })
  rows.sort((a, b) => a.start - b.start)
  const days: string[] = weekdaysShort()
  const th = "border-b border-grid py-1 pr-2 text-left font-normal text-dim"
  const td = "border-b border-grid py-1 pr-2"
  return (
    <SidePanel open onClose={onClose} title={tr("出入库计划：{0}（{1}）", shortName(depot.name), weekdayName(day))} width={760}
      hint={<>{duties.auto ? tr("这个车厂没设运用，按游戏根据时刻表自动生成的运用出车。") : ""}{tr("车厂在首班前按出库用时放车；出库太晚（超过 10 分钟）的车直接放到它的运用里还赶得上的第一站。放车前要先拿到出库线到首站的进路，正线的方向预约在内，拿不到就在库里等。")}</>}
      footer={<button className="btn" onClick={onClose}>{tr("关闭")}</button>}>
      <div className="mb-3 flex gap-1" role="group" aria-label={tr("星期")}>
        {days.map((name, d) => (
          <button key={d} type="button" aria-pressed={d === day} onClick={() => setDay(d)}
            className={"h-7 rounded-md border px-2.5 text-[13px] " + (d === day ? "tint-accent border-brand font-semibold text-brand" : "border-ctl bg-panel")}>{tr("周{0}", name)}</button>
        ))}
      </div>
      {rows.length ? (
        <table className="w-full border-collapse text-[12px]">
          <thead><tr>
            <th className={th}>{tr("车")}</th><th className={th}>{tr("存车线")}</th><th className={th}>{tr("出库")}</th><th className={th}>{tr("首班（出库后）")}</th>
            <th className={th}>{tr("末班到达")}</th><th className={th}>{tr("到库")}</th><th className={th}>{tr("班次")}</th><th className={th}>{tr("之后")}</th>
          </tr></thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={i}>
                <td className={td + " font-medium"}>{tr("车 {0}", fn.dutyNumber(depot, row.siding.id, row.index))}</td>
                <td className={td}>{shortName(row.siding.name) || tr("存车线")}</td>
                <td className={td + " num"}>{row.out}</td><td className={td}>{row.first}</td><td className={td}>{row.last}</td>
                <td className={td + " num"}>{row.home}</td><td className={td + " num"}>{row.count}</td>
                <td className={td + (row.stay ? " text-caution" : "")}>{row.stay ? tr("留在线上过夜") : tr("回库")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p className="text-[12px] text-dim">{tr("这天没有运用，车厂不放车。")}</p>}
      <p className="mt-3 text-[12px] text-dim">{tr("今天出车 {0} 列，在库 {1} 列（存车线不限列车数的不算）。", rows.length, inDepot)}</p>
      <p className="text-[12px] text-dim">{depot.releaseInterval > 0 ? tr("两列车出库至少间隔 {0} 秒（在游戏的车厂界面里设）。", depot.releaseInterval) : tr("没设出库间隔（在游戏的车厂界面里设）。")}{tr("同一车厂车型相同的存车线共用列车：某条存车线没车时由另一条替补出车，回库时进下一班最早要车、还有空位的那条。")}</p>
    </SidePanel>
  )
}
