/**
 * The line layer's other dialogs as side panels (the old code's speedLimitDialog / throughDialog / contingencyDialog come
 * here; the rules are the old ones, the old functions kept as old<Name>):
 * - line speed limits: a row per route, checked as typed against 0–300 km/h and the fastest rail along it;
 * - through services: from a route at its last stop or a boundary into another at its first stop or a boundary, each
 *   service in the time range linked to the first of the other's it can catch; every pair listed, each can be left out;
 * - a rail system's contingency plans: a card per plan, added and removed in place;
 * - a route's timetable: a new one, or a table's name and days, the week shown as it will be.
 * Each puts its changes in the draft (through links as setLink does), as before.
 */
import { useEffect, useMemo, useState, type ReactNode } from "react"
import { legacy, panelHost, useLegacy, type PanelTarget } from "../legacy/engine"
import { SidePanel } from "../ui/bits"
import { tr } from "../i18n"
import { DepotPanel } from "./DepotPanel"

export function LinePanels() {
  const [target, setTarget] = useState<PanelTarget | null>(null)
  useEffect(() => {
    panelHost.current = setTarget
    return () => { panelHost.current = null }
  }, [])
  if (!target) return null
  const close = () => setTarget(null)
  if (target.kind === "speed") return <SpeedLimits key={target.routeIds.join()} routeIds={target.routeIds} onClose={close} />
  if (target.kind === "depot") return <DepotPanel key={target.depotId} depotId={target.depotId} onClose={close} />
  if (target.kind === "table") return <TableDays key={target.routeId + "/" + target.index} routeId={target.routeId} index={target.index} onClose={close} />
  if (target.kind === "through") return <Through key={target.routeId} routeId={target.routeId} onClose={close} />
  return <Contingency key={target.anchor} anchor={target.anchor} onClose={close} />
}

const field = "h-7 rounded-md border border-ctl bg-panel px-2 text-[13px]"
const Footer = ({ children, error }: { children: ReactNode; error: string }) => <>{children}{error && <span className="text-[12px] text-warn" role="alert">{error}</span>}</>

// ---------- Line speed limits ----------

function SpeedLimits({ routeIds, onClose }: { routeIds: string[]; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const routes: any[] = routeIds.map(id => v.data?.routeById.get(id)).filter(Boolean)
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(routes.map(r => { const limit = fn.speedLimitOf(r); return [r.id, limit > 0 ? String(limit) : ""] })))
  const [error, setError] = useState("")
  const problemOf = (r: any) => {
    const text = (values[r.id] || "").trim(), value = text === "" ? 0 : Math.round(Number(text))
    if (!(value >= 0) || value > 300) return tr("限速要在 0 到 300 km/h 之间")
    if (r.fastestRail && value > r.fastestRail) return tr("{0}：不能高于轨道限速 {1} km/h", fn.routeTitle(r), r.fastestRail)
    return ""
  }
  const save = () => {
    const problem = routes.map(problemOf).find(Boolean)
    if (problem) { setError(problem); return }
    routes.forEach(r => { const text = (values[r.id] || "").trim(); fn.setSpeedLimit(r, text === "" ? 0 : Math.round(Number(text))) })
    fn.saveDraft()
    fn.renderAll()
    onClose()
  }
  return (
    <SidePanel open onClose={onClose} title={tr("线路限速")} width={520}
      hint={tr("列车在这条线路两站之间的轨道上不超过这个速度，也不超过轨道自己的限速；出入库和折返不受影响。空着为不限。")}
      footer={<Footer error={error}><button className="btn btn-primary" disabled={!v.data?.canEdit} onClick={save}>{tr("放进草稿")}</button><button className="btn" onClick={onClose}>{tr("取消")}</button></Footer>}>
      <ul className="m-0 list-none p-0">
        {routes.map(r => {
          const problem = problemOf(r), drafted = fn.draftOf(r) && "speedLimit" in fn.draftOf(r)
          return (
            <li key={r.id} className="border-b border-grid py-2" data-speed-route={r.id}>
              <div className="flex items-center gap-2">
                <span className="h-[4px] w-5 shrink-0 rounded-pill" style={{ background: fn.colorOf(r.color) }} />
                <span className="min-w-0 flex-1 truncate text-[13px]">{fn.routeTitle(r)}</span>
                {drafted && <span className="h-1.5 w-1.5 rounded-full bg-caution" title={tr("有没应用的草稿")} />}
                <input className={field + " num w-20 text-right"} type="number" min={0} max={r.fastestRail || 300} step={1} placeholder={tr("不限")} value={values[r.id]}
                  aria-label={tr("{0} 的限速", fn.routeTitle(r))} aria-invalid={Boolean(problem)} style={problem ? { borderColor: "var(--warn)" } : undefined}
                  onChange={e => { setValues({ ...values, [r.id]: e.target.value }); setError("") }} />
                <span className="text-[12px] text-dim">km/h</span>
              </div>
              <div className={"mt-0.5 pl-7 text-[12px] " + (problem ? "text-warn" : "text-dim")}>
                {problem || (r.fastestRail ? tr("轨道限速最高 {0} km/h", r.fastestRail) : tr("还不知道沿途轨道的限速"))}
              </div>
            </li>
          )
        })}
      </ul>
    </SidePanel>
  )
}

// ---------- Through services ----------

function Through({ routeId, onClose }: { routeId: string; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data, state = v.state
  const routes: any[] = data ? data.routes.filter((r: any) => r.stops.length > 1) : []
  const a0 = data?.routeById.get(routeId) || (state.route && data?.routeById.get(state.route)) || routes[0]
  const lastStation = (r: any) => r.stops[r.stops.length - 1].station
  const [fromId, setFromId] = useState<string>(a0?.id ?? "")
  const a = data?.routeById.get(fromId) || a0
  const others = routes.filter(r => r !== a)
  const [toId, setToId] = useState<string>(() => (others.find(r => r.stops[0].station === lastStation(a0)) || others[0])?.id ?? "")
  const b = others.find(r => r.id === toId) || others[0]
  const [fromStop, setFromStop] = useState(-1)
  const [toStop, setToStop] = useState(0)
  const [t1, setT1] = useState("05:00")
  const [t2, setT2] = useState("26:00")
  const [gap, setGap] = useState("3")
  const [wait, setWait] = useState("12")
  const [replace, setReplace] = useState(false)
  const [left, setLeft] = useState<Set<string>>(new Set())
  const [error, setError] = useState("")
  const boundaries = (r: any) => r.stops.map((_: unknown, i: number) => i).filter((i: number) => fn.isBoundary(r, i))
  const fromStopNow = fromStop < 0 || (a && boundaries(a).includes(fromStop)) ? fromStop : -1
  const toStopNow = toStop === 0 || (b && boundaries(b).includes(toStop)) ? toStop : 0

  // The old rule: each service in the range to the first of the other's it can catch, none twice, none waiting too long
  const found = useMemo(() => {
    if (!a || !b) return null
    const start = fn.parseClock(t1), end = fn.parseClock(t2)
    if (start === null || end === null) return null
    const exitIndex = fromStopNow < 0 ? a.stops.length - 1 : fromStopNow
    const gapMillis = Math.max(0, +gap || 0) * 60000, waitMillis = Math.max(+wait || 0, 0) * 60000 + gapMillis
    const local = (s: any) => fn.mod(s.start + data.dayStartMillis + data.offsetMillis, v.DAY)
    const used = new Set(b.services.map((s: any) => s.service).filter((service: number) => a.services.some((s: any) => !replace && s.link && s.link.route === b.id && s.link.toService === service)))
    const result: { s: any; out: number; to: any; into: number }[] = []
    a.services.filter((s: any) => local(s) >= start && local(s) <= end && (replace || !s.link)).forEach((s: any) => {
      const out = fn.timesOf(s)[exitIndex][0]
      const next = b.services.filter((o: any) => !used.has(o.service)).map((o: any) => ({ o, time: fn.timesOf(o)[toStopNow][1] }))
        .filter((x: any) => x.time >= out + gapMillis && x.time <= out + waitMillis).sort((x: any, y: any) => x.time - y.time)[0]
      if (!next) return
      used.add(next.o.service)
      result.push({ s, out, to: next.o, into: next.time })
    })
    return { result, exitIndex }
  }, [a, b, fromStopNow, toStopNow, t1, t2, gap, wait, replace, v.draft]) // eslint-disable-line react-hooks/exhaustive-deps
  if (!a || !b) return null
  const keyOf = (x: { s: any }) => String(x.s.id)
  const chosen = found ? found.result.filter(x => !left.has(keyOf(x))) : []

  const create = () => {
    if (!found) { setError(tr("时刻写成 07:10")); return }
    if (!chosen.length) { setError(tr("没有能接上的班次")); return }
    chosen.forEach(x => fn.setLink(x.s, fromStopNow, x.to, toStopNow))
    onClose()
  }
  const label = "flex flex-col gap-0.5 text-[12px] text-dim"
  return (
    <SidePanel open onClose={onClose} title={tr("新建直通关系")} width={600}
      hint={tr("中途接出和接入只能在游戏里标了时刻边界的站台（站台界面里设置）；没有边界的线路只能末站接首站。每一班接上对方能赶上的第一班，同一班不接两次，等太久的不接。")}
      footer={<Footer error={error}>
        <button className="btn btn-primary" disabled={!data.canEdit || !chosen.length} onClick={create}>{tr("新建 {0} 对", chosen.length)}</button>
        <button className="btn" onClick={onClose}>{tr("取消")}</button>
      </Footer>}>
      <div className="grid grid-cols-2 gap-x-3 gap-y-2">
        <label className={label}>{tr("从")}
          <select className={field} value={a.id} onChange={e => { setFromId(e.target.value); setFromStop(-1); setLeft(new Set()) }}>
            {routes.map(r => <option key={r.id} value={r.id}>{fn.routeTitle(r)}</option>)}
          </select></label>
        <label className={label}>{tr("接出")}
          <select className={field} value={fromStopNow} onChange={e => { setFromStop(+e.target.value); setLeft(new Set()) }}>
            <option value={-1}>{tr("{0}（末站）", fn.stopName(a, a.stops.length - 1))}</option>
            {boundaries(a).map((i: number) => <option key={i} value={i}>{tr("{0}（时刻边界）", fn.stopName(a, i))}</option>)}
          </select></label>
        <label className={label}>{fn.trc ? fn.trc("route", "到") : tr("到")}
          <select className={field} value={b.id} onChange={e => { setToId(e.target.value); setToStop(0); setLeft(new Set()) }}>
            {others.map(r => <option key={r.id} value={r.id}>{fn.routeTitle(r)}</option>)}
          </select></label>
        <label className={label}>{tr("接入")}
          <select className={field} value={toStopNow} onChange={e => { setToStop(+e.target.value); setLeft(new Set()) }}>
            <option value={0}>{tr("{0}（首站）", fn.stopName(b, 0))}</option>
            {boundaries(b).filter((i: number) => i > 0).map((i: number) => <option key={i} value={i}>{tr("{0}（时刻边界）", fn.stopName(b, i))}</option>)}
          </select></label>
        <label className={label}>{tr("首站发车从")}<input className={field + " num"} value={t1} onChange={e => setT1(e.target.value)} /></label>
        <label className={label}>{tr("到（{0}的表）", fn.WEEKDAY_NAMES[state.weekday])}<input className={field + " num"} value={t2} onChange={e => setT2(e.target.value)} /></label>
        <label className={label}>{tr("衔接至少（分钟）")}<input className={field + " num"} type="number" min={0} max={60} value={gap} onChange={e => setGap(e.target.value)} /></label>
        <label className={label}>{tr("最多再等（分钟）")}<input className={field + " num"} type="number" min={0} max={120} value={wait} onChange={e => setWait(e.target.value)} /></label>
      </div>
      <label className="mt-2 flex items-center gap-1.5 text-[13px]"><input type="checkbox" checked={replace} onChange={e => setReplace(e.target.checked)} /> {tr("替换这些班次原有的直通")}</label>
      <div className="mt-3 border-t border-grid pt-3">
        {!found ? <p className="m-0 text-[13px] text-warn">{tr("时刻写成 07:10")}</p>
          : !found.result.length ? <p className="m-0 text-[13px] text-dim">{tr("没有能接上的班次")}</p>
          : <>
            <p className="m-0 mb-2 text-[12px] text-dim">{tr("能接上 {0} 对；取消勾选的不接。", found.result.length)}</p>
            <table className="w-full border-collapse text-[12px]">
              <thead><tr className="text-left text-dim">
                <th className="w-6" /><th className="font-normal">{tr("班次")}</th><th className="font-normal">{fn.stopName(a, found.exitIndex)} {tr("到")}</th><th className="font-normal">{tr("接")}</th><th className="font-normal">{fn.stopName(b, toStopNow)} {tr("发")}</th><th className="font-normal">{tr("等")}</th>
              </tr></thead>
              <tbody>
                {found.result.map(x => {
                  const on = !left.has(keyOf(x))
                  return (
                    <tr key={keyOf(x)} className={"num border-t border-grid " + (on ? "" : "text-dim")}>
                      <td><input type="checkbox" checked={on} aria-label={tr("{0} 班接 {1} 班", fn.clock(x.s.start), fn.clock(x.to.start))}
                        onChange={() => { const next = new Set(left); if (on) next.add(keyOf(x)); else next.delete(keyOf(x)); setLeft(next) }} /></td>
                      <td>{fn.clock(x.s.start)}</td><td>{fn.clock(x.out)}</td><td>{fn.clock(x.to.start)}</td><td>{fn.clock(x.into)}</td>
                      <td>{tr("{0} 分", Math.round((x.into - x.out) / 60000))}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </>}
      </div>
    </SidePanel>
  )
}

// ---------- Contingency plans ----------

type Plan = { id: string; name: string; routes: string[]; lateMinutes: number; waitMinutes: number; deadlock: boolean; manual: boolean; turn: string; turnStation: string; afterTurn: string; skipStations: string[]; takeover: boolean; active?: boolean }

function Contingency({ anchor, onClose }: { anchor: string; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data, draft = v.draft
  const system = (data?.contingencySystems || []).find((s: any) => s.anchor === anchor)
  const [plans, setPlans] = useState<Plan[]>(() => (system ? (draft.contingencies[system.anchor] || system.plans || []).map((p: Plan) => ({ ...p, routes: [...(p.routes || [])], skipStations: [...(p.skipStations || [])] })) : []))
  const [merge, setMerge] = useState(false)
  const [error, setError] = useState("")
  if (!system) return null
  const routes: any[] = system.routes.map((id: string) => data.routeById.get(id)).filter(Boolean)
  const stations: any[] = system.stations || []
  const others: any[] = (system.others || []).filter((other: any) => other.plans.length || draft.contingencies[other.anchor])
  const change = (i: number, patch: Partial<Plan>) => { setPlans(plans.map((p, k) => (k === i ? { ...p, ...patch } : p))); setError("") }
  const toggle = (list: string[], id: string, on: boolean) => (on ? [...list, id] : list.filter(each => each !== id))
  const add = () => {
    const id = String(Math.max(0, ...plans.map(p => Number(p.id) || 0)) + 1)
    setPlans([...plans, { id, name: "", routes: [], lateMinutes: 5, waitMinutes: 10, deadlock: true, manual: true, turn: "none", turnStation: "0", afterTurn: "resume", skipStations: [], takeover: false }])
  }
  const save = () => {
    const result: Plan[] = plans.map(p => ({ ...p, name: p.name.trim().slice(0, 64), lateMinutes: Math.max(0, Math.min(1440, Math.round(Number(p.lateMinutes) || 0))), waitMinutes: Math.max(0, Math.min(1440, Math.round(Number(p.waitMinutes) || 0))) }))
    for (const p of result) {
      if (p.turn === "station" && p.turnStation === "0") { setError(tr("选了指定车站折返，但没选车站")); return }
      if (!p.lateMinutes && !p.waitMinutes && !p.deadlock) { setError(tr("预案“{0}”没有任何条件，不会生效", p.name || p.id)); return }
    }
    if (merge) {
      let next = Math.max(0, ...result.map(p => Number(p.id) || 0))
      others.forEach(other => {
        (draft.contingencies[other.anchor] || other.plans).forEach((p: Plan) => result.push({ ...p, id: String(++next), active: undefined }))
        draft.contingencies[other.anchor] = []
      })
      if (result.length > 64) { setError(tr("合在一起超过 64 个预案")); return }
    }
    const strip = (list: Plan[]) => JSON.stringify(list.map(p => ({ ...p, active: undefined })))
    if (strip(result) === strip(system.plans || [])) delete draft.contingencies[system.anchor]
    else draft.contingencies[system.anchor] = result
    fn.saveDraft()
    fn.renderAll()
    onClose()
  }
  const small = "flex items-center gap-1.5 text-[13px]"
  return (
    <SidePanel open onClose={onClose} title={tr("路网故障预案：{0}", fn.systemTitle(system))} width={620}
      hint={tr("一片连通的轨道（路网）共用一套预案，管路网里所有线路的车，不管来自哪个车厂。列车晚点、等进路太久或锁死时按预案处理。没有预案的线路只报警，运行照常；几个预案都符合时取编号最小的。报警和启用在“同步状态”里。")}
      footer={<Footer error={error}>
        <button className="btn btn-primary" disabled={!data.canEdit} onClick={save}>{tr("放进草稿")}</button>
        <button className="btn" onClick={add}>{tr("新建预案")}</button>
        <button className="btn" onClick={onClose}>{tr("取消")}</button>
      </Footer>}>
      {others.length > 0 && (
        <div className="tint-caution mb-3 rounded-md p-2 text-[12px]">
          <p className="m-0 text-caution">{tr("轨道接通后，这片路网里还有 {0} 套原来别的路网的预案，都还生效，排在这一套之后：", others.length)}</p>
          <ul className="my-1 pl-4">{others.map(other => <li key={other.anchor}>{(draft.contingencies[other.anchor] || other.plans).map((p: Plan) => tr("预案 {0}「{1}」", p.id, p.name)).join("、") || tr("（已在草稿里清空）")}</li>)}</ul>
          <label className={small}><input type="checkbox" checked={merge} onChange={e => setMerge(e.target.checked)} /> {tr("合成一套：把它们的预案并进这一套（重新编号），那几套清空")}</label>
        </div>
      )}
      {!plans.length && <p className="text-[13px] text-dim">{tr("还没有预案：这片路网的车晚点或锁死时只报警。")}</p>}
      {plans.map((p, i) => (
        <section key={p.id} className="mb-3 rounded-lg border border-grid p-3" data-plan={p.id}>
          <div className="mb-2 flex items-center gap-2">
            <b className="num text-[13px]">{tr("预案 {0}", p.id)}</b>
            {p.active && <span className="text-[12px] text-caution">{tr("已启用")}</span>}
            <input className={field + " min-w-0 flex-1"} value={p.name} maxLength={64} placeholder={tr("名称")} aria-label={tr("名称")} onChange={e => change(i, { name: e.target.value })} />
            <button className="btn btn-ghost h-7 px-2 text-[12px]" onClick={() => setPlans(plans.filter((_, k) => k !== i))}>{tr("删除")}</button>
          </div>
          <div className="label-sm mb-1">{tr("适用线路（都不勾就是路网的全部线路）：")}</div>
          <div className="mb-2 flex flex-wrap gap-x-3 gap-y-1">
            {routes.map(r => <label key={r.id} className={small}><input type="checkbox" checked={p.routes.includes(r.id)} onChange={e => change(i, { routes: toggle(p.routes, r.id, e.target.checked) })} /> {fn.routeTitle(r)}</label>)}
          </div>
          <div className="label-sm mb-1">{tr("满足任一条件的车受影响（填 0 不看这一条）：")}</div>
          <div className="mb-2 flex flex-wrap items-center gap-3">
            <label className={small}>{tr("晚点达到（分钟）")}<input className={field + " num w-16"} type="number" min={0} max={1440} value={p.lateMinutes} onChange={e => change(i, { lateMinutes: e.target.value as any })} /></label>
            <label className={small}>{tr("等进路达到（分钟）")}<input className={field + " num w-16"} type="number" min={0} max={1440} value={p.waitMinutes} onChange={e => change(i, { waitMinutes: e.target.value as any })} /></label>
            <label className={small}><input type="checkbox" checked={p.deadlock} onChange={e => change(i, { deadlock: e.target.checked })} /> {tr("锁死（和别的车互相等着走不了）")}</label>
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <label className={small}>{tr("启用方式")}
              <select className={field} value={p.manual ? "manual" : "auto"} onChange={e => change(i, { manual: e.target.value === "manual" })}>
                <option value="auto">{tr("条件满足就自动启用")}</option><option value="manual">{tr("只报警，等调度员启用")}</option>
              </select></label>
            <label className={small}>{tr("临时折返")}
              <select className={field} value={p.turn} onChange={e => change(i, { turn: e.target.value })}>
                <option value="none">{tr("不折返")}</option><option value="nearest">{tr("最近的能折返的站")}</option><option value="station">{tr("指定车站")}</option>
              </select></label>
            {p.turn === "station" && <label className={small}>{tr("折返站")}
              <select className={field} value={p.turnStation} onChange={e => change(i, { turnStation: e.target.value })}>
                <option value="0">—</option>{stations.map(s => <option key={s.id} value={s.id}>{fn.shortName(s.name)}</option>)}
              </select></label>}
            {p.turn !== "none" && <label className={small}>{tr("折返以后")}
              <select className={field} value={p.afterTurn === "deadhead" ? "deadhead" : "resume"} onChange={e => change(i, { afterTurn: e.target.value })}>
                <option value="resume">{tr("接线运营（空车折返后重新载客）")}</option><option value="deadhead">{tr("回送回库")}</option>
              </select></label>}
          </div>
          {p.turn !== "none" && <p className="my-1 text-[12px] text-dim">{tr("折返前都先在折返站清客。")}</p>}
          <div className="label-sm mb-1 mt-2">{tr("跳过这些站（通过不停）：")}</div>
          <div className="flex flex-wrap gap-x-3 gap-y-1">
            {stations.length ? stations.map(s => <label key={s.id} className={small}><input type="checkbox" checked={p.skipStations.includes(s.id)} onChange={e => change(i, { skipStations: toggle(p.skipStations, s.id, e.target.checked) })} /> {fn.shortName(s.name)}</label>)
              : <span className="text-[12px] text-dim">{tr("路网的线路还没有车站")}</span>}
          </div>
          <label className={small + " mt-2"}><input type="checkbox" checked={p.takeover} onChange={e => change(i, { takeover: e.target.checked })} /> {tr("由备车接替余下的运用")}</label>
        </section>
      ))}
    </SidePanel>
  )
}

// ---------- A timetable's days ----------

/**
 * A new table of a route, or a table's name and days (old tableDialog): the week as seven buttons, each saying which
 * table runs that day now; a day ticked moves to this table, and the week below shows every table's days as they will
 * be. A new table can copy another's services (with their one-off changes) and be made for the line's every route.
 */
function TableDays({ routeId, index, onClose }: { routeId: string; index: number; onClose: () => void }) {
  useLegacy()
  const { fn, v } = legacy
  const r = v.data?.routeById.get(routeId)
  const timetable = r ? fn.timetableOf(r) : null
  const isNew = index < 0
  const table = timetable && !isNew ? timetable.tables[index] : null
  const line = r ? v.data.lines.find((l: any) => l.key === r.lineKey) : null
  const [name, setName] = useState(table ? table.name : "")
  const [mask, setMask] = useState<number>(table ? table.weekdays : 0)
  const [copy, setCopy] = useState(-1)
  const [all, setAll] = useState(Boolean(line && line.routes.length > 1))
  const [error, setError] = useState("")
  if (!r || !timetable) return null
  const days: string[] = v.WEEKDAY_SHORT
  const ownerOf = (day: number) => timetable.tables.findIndex((t: any) => (t.weekdays >> day) & 1)
  const tableTitle = (t: any, i: number) => fn.tableName(t, i)
  // Every table's days once this is done; this table last when new
  const after = [...timetable.tables.map((t: any, i: number) => ({ name: i === index ? name || tableTitle(t, i) : tableTitle(t, i), mask: i === index ? mask : t.weekdays & ~mask, self: i === index })),
    ...(isNew ? [{ name: name || tr("新表"), mask, self: true }] : [])]
  const save = () => {
    const routes = isNew && all && line ? line.routes : [r]
    if (isNew && routes.some((route: any) => fn.timetableOf(route).tables.length >= v.MAX_TABLES)) { setError(tr("一条线路最多 {0} 张表", v.MAX_TABLES)); return }
    routes.forEach((route: any) => fn.editTimetable(route, (edited: any) => {
      edited.tables.forEach((t: any, i: number) => { if (i !== index) t.weekdays &= ~mask })
      if (isNew) {
        const copied = copy >= 0 && edited.tables[copy]
        edited.tables.push({
          name: name.trim(), weekdays: mask, marginPercent: copied ? copied.marginPercent : edited.tables[0].marginPercent,
          services: copied ? [...copied.services] : [], returns: copied ? [...copied.returns] : [], suspended: copied ? [...(copied.suspended || [])] : [], orders: copied ? (copied.orders || []).map((order: any) => ({ ...order })) : [],
          links: copied ? copied.links.map((link: any) => ({ ...link })) : [],
          stops: (copied || edited.tables[0]).stops.map((stop: any) => ({ ...stop })),
          exceptions: copied ? JSON.parse(JSON.stringify(copied.exceptions)) : [],
        })
      } else {
        edited.tables[index].name = name.trim()
        edited.tables[index].weekdays = mask
      }
    }))
    if (mask) fn.setWeekday(days.findIndex((_, i) => (mask >> i) & 1))
    onClose()
  }
  return (
    <SidePanel open onClose={onClose} title={(isNew ? tr("新建时刻表") : tr("时刻表设置")) + "：" + fn.routeTitle(r)} width={560}
      hint={tr("一天只用一张表，勾选的星期从原来的表移到这张表。复制的班次连同只改这一班的例外一起复制。")}
      footer={<Footer error={error}>
        <button className="btn btn-primary" disabled={!v.data.canEdit} onClick={save}>{isNew ? tr("新建") : tr("确定")}</button>
        <button className="btn" onClick={onClose}>{tr("取消")}</button>
      </Footer>}>
      <label className="flex flex-col gap-0.5 text-[12px] text-dim">{tr("表名")}
        <input className={field} value={name} maxLength={32} placeholder={tr("休日")} onChange={e => setName(e.target.value)} /></label>
      <div className="label-sm mb-1 mt-3">{tr("运行的星期")}</div>
      <div className="grid grid-cols-7 gap-1" role="group" aria-label={tr("运行的星期")}>
        {days.map((day, i) => {
          const on = Boolean((mask >> i) & 1), owner = ownerOf(i)
          return (
            <button key={i} type="button" aria-pressed={on} onClick={() => { setMask(mask ^ (1 << i)); setError("") }}
              className={"flex flex-col items-center rounded-md border px-1 py-1.5 text-[13px] " + (on ? "tint-accent border-brand font-semibold text-brand" : "border-ctl bg-panel")}>
              {tr("周{0}", day)}
              <span className="mt-0.5 max-w-full truncate text-[11px] font-normal text-dim">{owner < 0 ? tr("无") : tableTitle(timetable.tables[owner], owner)}</span>
            </button>
          )
        })}
      </div>
      {isNew && <>
        <label className="mt-3 flex flex-col gap-0.5 text-[12px] text-dim">{tr("班次")}
          <select className={field} value={copy} onChange={e => setCopy(+e.target.value)}>
            <option value={-1}>{tr("空表")}</option>
            {timetable.tables.map((t: any, i: number) => <option key={i} value={i}>{tr("复制“{0}”的班次", tableTitle(t, i))}</option>)}
          </select></label>
        <label className="mt-2 flex items-center gap-1.5 text-[13px]">
          <input type="checkbox" checked={all} disabled={!line || line.routes.length < 2} onChange={e => setAll(e.target.checked)} /> {tr("{0}的全部线路一起建", fn.shortName(r.line))}
        </label>
      </>}
      <div className="label-sm mb-1 mt-4">{tr("改完以后")}</div>
      <table className="w-full border-collapse text-[12px]">
        <tbody>
          {after.map((t, i) => (
            <tr key={i} className="border-t border-grid">
              <td className={"max-w-[140px] truncate py-1 pr-2 " + (t.self ? "font-semibold text-brand" : "")}>{t.name}</td>
              {days.map((day, d) => <td key={d} className="w-8 text-center">{(t.mask >> d) & 1 ? <span className={"inline-block h-4 w-6 rounded-sm " + (t.self ? "bg-brand" : "bg-gridstrong")} title={tr("周{0}", day)} /> : null}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
      {!mask && <p className="mt-2 text-[12px] text-caution">{tr("没勾任何一天：这张表不会运行。")}</p>}
    </SidePanel>
  )
}
