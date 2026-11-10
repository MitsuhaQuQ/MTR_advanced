/**
 * A depot's duties on the day shown, as a board: a row per train (numbered on through its vehicle pool, as the old page
 * numbers them), the day across, each service a block in its route's colour, the runs out of and back to the depot
 * dashed, and the services no train runs in a row of their own. Choose a train, then click a service to add it to that
 * train's duty or take it out (a service another train runs moves over). The rules are the old code's: selectSlot,
 * toggleInSlot, editDuties, changeSlots, deadheadTimes, dutyProblems, and the depot's dialogs for the rest.
 */
import { useState, type ReactNode } from "react"
import { legacy, menuHost, useLegacy, type MenuItem } from "../legacy/engine"
import { tr } from "../i18n"
import { clock, colorOf, daysText, routeTitle, shortName, weekdayName } from "./now"

const HOUR = 3600000, LABEL = 148, ROW = 34

export function DutyBoard() {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data, state = v.state
  const [chosen, setChosen] = useState<string | null>(null)
  const [pxPerHour, setPxPerHour] = useState(110)
  if (!data) return null
  if (!data.depots.length) return <p className="p-4 text-[13px] text-dim">{tr("没有车厂")}</p>
  const depot = fn.depotById(state.slot?.depot ?? chosen ?? data.depots[0].id) || data.depots[0]
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <Head depot={depot} choose={id => { setChosen(id); if (state.slot && state.slot.depot !== id) { state.slot = null; fn.renderAll() } }} pxPerHour={pxPerHour} setPxPerHour={setPxPerHour} />
      <Board depot={depot} pxPerHour={pxPerHour} />
    </div>
  )
}

function Head({ depot, choose, pxPerHour, setPxPerHour }: { depot: any; choose: (id: string) => void; pxPerHour: number; setPxPerHour: (n: number) => void }) {
  const { fn, v } = legacy
  const data = v.data, state = v.state
  const duties = fn.dutiesOf(depot)
  const canEdit = data.canEdit
  const short = depot.shortfall && depot.shortfall.uncovered > 0
  const more = (e: React.MouseEvent) => {
    const box = e.currentTarget.getBoundingClientRect()
    const items: MenuItem[] = [
      [tr("编辑车厂…"), () => fn.depotDialog(depot)],
      [tr("出入库规则…"), () => fn.depotRulesDialog(depot)],
      [tr("按时刻表把列车摆到现在的位置…"), canEdit ? () => fn.placeTrainsDialog(depot) : null],
      [tr("运用组…"), () => fn.dutyGroupsDialog(depot)],
      [tr("出入库计划…"), () => fn.releasePlanDialog(depot)],
      [tr("丢掉运用的草稿"), v.draft.duties[depot.id] ? () => { delete v.draft.duties[depot.id]; fn.saveDraft(); fn.renderAll() } : null],
    ]
    menuHost.current?.open(box.left, box.bottom + 4, items)
  }
  return (
    <div className="flex shrink-0 flex-col gap-2 border-b border-grid bg-panel px-3 py-2">
      <div className="flex flex-wrap items-center gap-2">
        <select className="h-7 rounded-md border border-ctl bg-panel px-2 text-[14px] font-semibold" value={depot.id} onChange={e => choose(e.target.value)} aria-label={tr("车厂")}>
          {data.depots.map((d: any) => <option key={d.id} value={d.id}>{shortName(d.name)}</option>)}
        </select>
        <span className={"inline-flex h-5 items-center rounded-pill px-2 text-[12px] font-medium " + (duties.mode === "diagram" && !duties.auto ? "tint-accent text-brand" : "bg-panel2 text-dim")}>
          {duties.auto ? tr("自动运用") : duties.mode === "diagram" ? tr("按运用图") : tr("自动排班")}
        </span>
        <span className="text-[12px] text-dim">{weekdayName(state.weekday)} · {daysText(fn.dayGroup(depot, state.weekday))}</span>
        <span className="flex-1" />
        <div className="flex h-7 overflow-hidden rounded-md border border-ctl" role="group" aria-label={tr("缩放")}>
          {[[70, tr("全天")], [110, tr("标准")], [200, tr("细看")]].map(([px, label], i) => (
            <button key={px} type="button" aria-pressed={pxPerHour === px} onClick={() => setPxPerHour(px as number)}
              className={"px-2.5 text-[13px] " + (i ? "border-l border-gridstrong " : "") + (pxPerHour === px ? "bg-brand font-medium text-brandink" : "bg-panel")}>{label}</button>
          ))}
        </div>
        <button className="btn" disabled={!canEdit} onClick={() => fn.generateDuties(depot)} title={tr("按时刻表排出运用，放进草稿看过再应用")}>{tr("一键生成运用…")}</button>
        {duties.auto && <button className="btn" disabled={!canEdit} onClick={() => fn.replanDialog(depot)}>{tr("重新排运用…")}</button>}
        <button className="btn" onClick={() => fn.setDutyMode(depot, duties.mode === "diagram" ? "online" : "diagram")}>{duties.mode === "diagram" ? tr("改为自动排班") : tr("改为按运用图")}</button>
        <button className="btn" onClick={more} aria-haspopup="menu">{tr("更多 ▾")}</button>
      </div>
      {(short || (depot.shortfall && depot.shortfall.replan && duties.auto)) && (
        <p className={"m-0 text-[12px] " + (short ? "text-warn" : "text-caution")}>{short ? fn.shortfallText(depot.shortfall) : fn.replanText(depot.shortfall)}</p>
      )}
      <p className="m-0 text-[12px] text-dim">{state.slot ? tr("已选中一列车：点色块把那一班移出，点“没车”一行或别的车的色块把那一班加进来；Esc 取消选择。") : tr("点左边一列车选中它，再点班次加进它的运用或移出。右键一列车可以清空或删除。")}</p>
    </div>
  )
}

type Row = { siding: any; index: number; number: number; slot: any[]; day: any | null }

function Board({ depot, pxPerHour }: { depot: any; pxPerHour: number }) {
  const { fn, v } = legacy
  const data = v.data, state = v.state
  const duties = fn.dutiesOf(depot)
  const weekday = state.weekday

  // Trains in pool order, numbered on through the pool (worked out each time: the old code changes duties in place)
  const rows = (() => {
    const pools = (depot.pools || []).length ? depot.pools : [{ key: "", vehicle: "", sidings: depot.sidings.map((s: any) => s.id) }]
    return pools.map((pool: any) => {
      const list: Row[] = []
      let number = 0
      ;(pool.sidings || []).map((id: string) => depot.sidings.find((s: any) => s.id === id)).filter(Boolean).forEach((siding: any) => {
        ;(duties.sidings[siding.id] || []).forEach((slot: any[], index: number) => list.push({ siding, index, number: ++number, slot, day: fn.dutyOn(slot, weekday) }))
      })
      return { pool, list }
    })
  })()

  // The services of the depot's routes no train runs that day
  const noTrain = data.routes.filter((r: any) => depot.routes.includes(r.id)).flatMap((r: any) => r.services || [])
    .filter((s: any) => !s.suspended && fn.hasNoTrain(s)).sort((a: any, b: any) => a.start - b.start)

  // The span of the day shown: from the first run out to the last run back, by whole hours
  const times: number[] = []
  rows.forEach(({ list }: { list: Row[] }) => list.forEach(row => {
    if (!row.day) return
    row.day.segments.forEach((seg: any) => { if (fn.segmentService(seg)) times.push(fn.segmentStart(seg), fn.segmentEnd(seg)) })
    const legs = fn.deadheadTimes(row.siding, row.day)
    if (legs.out) times.push(legs.out.leave)
    if (legs.back) times.push(legs.back.home)
  }))
  noTrain.forEach((s: any) => { const t = fn.timesOf(s); times.push(t[0][1], t[t.length - 1][0]) })
  const from = times.length ? Math.floor(Math.min(...times) / HOUR) * HOUR : 4 * HOUR
  const to = times.length ? Math.ceil(Math.max(...times) / HOUR) * HOUR : 24 * HOUR
  const x = (t: number) => ((t - from) / HOUR) * pxPerHour
  const width = x(to) + 24
  const hours: number[] = []
  for (let t = from; t <= to; t += HOUR) hours.push(t)
  const canEdit = data.canEdit
  // Each service no train runs on the first row free of others, at least a few pixels apart
  const packed = (() => {
    const ends: number[] = []
    const items: { s: any; a: number; b: number; lane: number }[] = noTrain.map((s: any) => {
      const t = fn.timesOf(s), a = t[0][1], b = t[t.length - 1][0]
      let lane = ends.findIndex(end => x(a) > end + 3)
      if (lane < 0) { lane = ends.length; ends.push(0) }
      ends[lane] = x(b)
      return { s, a, b, lane }
    })
    return { items, lanes: Math.max(1, ends.length) }
  })()

  const rowMenu = (e: React.MouseEvent, row: Row) => {
    e.preventDefault()
    const sid = row.siding.id, idx = row.index
    menuHost.current?.open(e.clientX, e.clientY, [
      [tr("清空这列车当天的运用"), canEdit && row.day ? () => fn.editDuties(depot, (all: any) => { if (fn.dutyOn(all.sidings[sid][idx], state.weekday)) fn.dutyForEditing(depot, all.sidings[sid][idx], state.weekday).segments = [] }) : null],
      [tr("删除这个库位"), canEdit ? () => fn.changeSlots(depot, sid, (slots: any[]) => slots.splice(idx, 1)) : null],
      [tr("在 {0} 添加运用", shortName(row.siding.name) || tr("存车线")), canEdit ? () => fn.changeSlots(depot, sid, (slots: any[]) => slots.push([])) : null],
    ])
  }
  const clickService = (s: any) => {
    if (state.slot && canEdit) fn.toggleInSlot(s)
    else { state.selected = s.id; state.selectedRoute = null; fn.renderAll() }
  }
  const selectedRow = (row: Row) => Boolean(state.slot) && state.slot.depot === depot.id && state.slot.siding === row.siding.id && state.slot.index === row.index

  return (
    <div className="scroll-thin min-h-0 flex-1 overflow-auto bg-panel">
      <div className="relative" style={{ width: LABEL + width, minWidth: "100%" }}>
        {/* the hours across, kept in view while scrolling down */}
        <div className="sticky top-0 z-20 flex h-7 border-b border-gridstrong bg-panel">
          <div className="sticky left-0 z-10 flex w-[148px] shrink-0 items-center border-r border-gridstrong bg-bg px-3 text-[12px] text-dim">{tr("车 / 存车线")}</div>
          <div className="relative flex-1">
            {hours.map(t => <span key={t} className="num absolute top-1.5 text-[11px] font-semibold" style={{ left: x(t) + 3 }}>{clock(t)}</span>)}
          </div>
        </div>
        {rows.map(({ pool, list }: { pool: any; list: Row[] }) => (
          <div key={pool.key}>
            {rows.length > 1 && <div className="sticky left-0 border-b border-grid bg-panel2 px-3 py-1 text-[12px] font-medium text-dim">{tr("车型 {0}", pool.vehicle || pool.key || "?")}</div>}
            {list.map(row => {
              const on = selectedRow(row)
              const problems = row.day ? fn.dutyProblems(depot, row.siding.id, row.index, weekday) : []
              const legs = row.day ? fn.deadheadTimes(row.siding, row.day) : { out: null, back: null }
              const segs = row.day ? row.day.segments.map((seg: any) => ({ seg, s: fn.segmentService(seg) })).filter((p: any) => p.s) : []
              return (
                <div key={row.siding.id + "/" + row.index} className={"group flex border-b border-grid " + (on ? "tint-duty" : "hover:bg-panel2")} style={{ height: ROW }}
                  onContextMenu={e => rowMenu(e, row)}>
                  <button type="button" onClick={() => fn.selectSlot(depot.id, row.siding.id, row.index)} aria-pressed={Boolean(on)}
                    className={"sticky left-0 z-10 flex w-[148px] shrink-0 flex-col justify-center border-r border-gridstrong px-3 text-left " + (on ? "bg-duty text-panel" : "bg-bg")}>
                    <span className="flex items-center gap-1.5 text-[13px] font-medium">
                      {tr("运用 {0}", row.number)}
                      {problems.length > 0 && <span className="text-warn" title={problems.map((p: any) => p.text).join("\n")}>!</span>}
                      {row.day?.stay && <span className={"text-[11px] " + (on ? "" : "text-caution")}>{tr("留")}</span>}
                    </span>
                    <span className={"truncate text-[11px] " + (on ? "" : "text-dim")}>{fn.startText(row.slot, row.siding)}</span>
                  </button>
                  <div className="relative flex-1">
                    {hours.map(t => <span key={t} className="absolute inset-y-0 w-px bg-grid" style={{ left: x(t) }} />)}
                    {!row.day && <span className="absolute left-2 top-2 text-[12px] text-dim">{tr("这一天休")}</span>}
                    {legs.out && <Leg x1={x(legs.out.leave)} x2={segs.length ? x(fn.segmentStart(segs[0].seg)) : x(legs.out.leave)} label={legs.out.number} exception={legs.out.exception} on={on} />}
                    {legs.back && <Leg x1={segs.length ? x(fn.segmentEnd(segs[segs.length - 1].seg)) : x(legs.back.leave)} x2={x(legs.back.home)} label={legs.back.number} exception={legs.back.exception} on={on} />}
                    {segs.map(({ seg, s }: any, i: number) => {
                      const a = fn.segmentStart(seg), b = fn.segmentEnd(seg)
                      const chosen = s.id === state.selected
                      return (
                        <Block key={i} left={x(a)} width={Math.max(4, x(b) - x(a))} color={colorOf(s.route.color)} chosen={chosen}
                          title={`${clock(a)}–${clock(b)} ${routeTitle(s.route)}${on && canEdit ? "\n" + tr("点一下从这列车移出") : state.slot ? "\n" + tr("点一下移到选中的车") : ""}`}
                          onClick={() => clickService(s)}>{clock(a)}</Block>
                      )
                    })}
                  </div>
                </div>
              )
            })}
            {!list.length && <p className="m-0 border-b border-grid px-3 py-2 text-[12px] text-dim">{tr("没有运用")}</p>}
          </div>
        ))}
        {/* services no train runs, packed into as many rows as keep them apart */}
        <div className="flex border-b border-grid tint-warn" style={{ minHeight: Math.max(ROW, packed.lanes * 9 + 10) }}>
          <div className="sticky left-0 z-10 flex w-[148px] shrink-0 flex-col justify-center border-r border-gridstrong bg-bg px-3">
            <span className={"text-[13px] font-medium " + (noTrain.length ? "text-warn" : "text-dim")}>{tr("没车")}</span>
            <span className="text-[11px] text-dim">{tr("{0} 班", noTrain.length)}</span>
          </div>
          <div className="relative flex-1" style={{ height: Math.max(ROW, packed.lanes * 9 + 10) }}>
            {packed.items.map(({ s, a, b, lane }) => {
              return (
                <button key={s.id} type="button" title={`${clock(a)} ${routeTitle(s.route)}\n${state.slot ? tr("点一下加进选中的车") : tr("先在左边选一列车")}`}
                  onClick={() => clickService(s)}
                  className={"absolute rounded-sm border border-warn " + (s.id === state.selected ? "bg-warn" : "bg-panel hover:bg-warn")}
                  style={{ left: x(a), width: Math.max(4, x(b) - x(a)), top: 5 + lane * 9, height: 7 }} />
              )
            })}
          </div>
        </div>
      </div>
    </div>
  )
}

const Block = ({ left, width, color, chosen, title, onClick, children }: { left: number; width: number; color: string; chosen: boolean; title: string; onClick: () => void; children: ReactNode }) => (
  <button type="button" title={title} onClick={onClick}
    className={"absolute top-[7px] flex h-5 items-center overflow-hidden rounded-sm px-1 text-[10px] text-white " + (chosen ? "ring-2 ring-sel ring-offset-1 ring-offset-panel" : "")}
    style={{ left, width, background: color }}>
    {width > 36 && <span className="num truncate">{children}</span>}
  </button>
)

/** A run out of the depot or back to it: dashed, its number over it; blue where set for this duty alone. */
const Leg = ({ x1, x2, label, exception, on }: { x1: number; x2: number; label: string; exception: boolean; on: boolean }) => (
  <span className="absolute" style={{ left: Math.min(x1, x2), width: Math.max(2, Math.abs(x2 - x1)), top: 16 }} title={label}>
    <span className={"block border-t-2 border-dashed " + (exception ? "border-sel" : on ? "border-duty" : "border-dim")} />
    {Math.abs(x2 - x1) > 34 && <span className={"num absolute -top-3.5 left-0 text-[9px] " + (exception ? "text-sel" : "text-dim")}>{label}</span>}
  </span>
)
