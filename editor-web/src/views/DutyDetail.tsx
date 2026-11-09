/**
 * The chosen train's duty in the inspector (the old dutyPanel and its handlers on #inspector): its depot and siding, the
 * days it is for, each service as a row (time, route colour, from and to, taken out with ×), where the train waits before
 * the next, what is wrong at each, the night on the line and the next day's duty, the siding to come back to, how its
 * train comes out and from which siding. Every change is the old code's editDuties on dutyForEditing, as before.
 *
 * Its elements carry none of the old data attributes, so the old handlers still on #inspector don't act on them too.
 */
import type { ReactNode } from "react"
import { legacy, useLegacy } from "../legacy/engine"
import { tr } from "../i18n"

const select = "h-7 w-full rounded-md border border-ctl bg-panel px-1 text-[13px]"

export function DutyDetail() {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data, state = v.state
  if (!data || !state.slot) return null
  const depot = fn.depotById(state.slot.depot)
  const siding = depot && depot.sidings.find((o: any) => o.id === state.slot.siding)
  if (!siding) return null
  const duties = fn.dutiesOf(depot)
  const slots = duties.sidings[siding.id] || [], slot = slots[state.slot.index] || []
  const day = fn.dutyOn(slot, state.weekday), group = fn.dayGroup(depot, state.weekday)
  const problems: { i: number; text: string }[] = fn.dutyProblems(depot, siding.id, state.slot.index, state.weekday)
  const problemsAt = (i: number) => problems.filter(p => p.i === i).map((p, k) => <p key={k} className="m-0 text-[12px] text-warn">{p.text}</p>)
  const edit = (change: (day: any, duties: any) => unknown) => fn.editDuties(depot, (all: any) => change(fn.dutyForEditing(depot, all.sidings[state.slot.siding][state.slot.index], state.weekday), all))
  const deleted = day ? day.segments.filter((segment: any) => !data.routeById.get(segment.route)).length : 0
  const pool: any[] = fn.poolSidings(depot, siding.id)
  const start = fn.startOf(slots[state.slot.index] || [])
  const waitOwn = Boolean((day && day.exceptions || 0) & v.EXCEPTION.WAITS)
  const sidingName = (s: any) => fn.shortName(s.name) || tr("存车线")

  const moveToSiding = (to: string) => {
    if (to === state.slot.siding) return
    const from = state.slot.siding, other = depot.sidings.find((each: any) => each.id === to)
    let moved: any = null
    fn.editDuties(depot, (all: any) => {
      const list = all.sidings[from] || []
      // Its siding's references by place become ones by number first, so they still find it (P2-D)
      list.forEach((each: any) => { if (!fn.dutyIdOf(each)) { const id = fn.newDutyId(); each.forEach((d: any) => { d.duty = id }) } })
      list.forEach((each: any) => each.forEach((d: any) => {
        if (d.stay && d.next >= 0 && list[d.next] && (!d.nextDuty || d.nextDuty === "0")) { d.nextDuty = fn.dutyIdOf(list[d.next]); d.next = -1 }
      }))
      const [taken] = list.splice(state.slot.index, 1)
      if (!list.length) delete all.sidings[from]
      const target = all.sidings[to] || (all.sidings[to] = [])
      target.push(taken)
      moved = { depot: depot.id, siding: to, index: target.length - 1 }
      return tr("运用移到了 {0}，编号不变", fn.shortName(other ? other.name : "") || tr("存车线"))
    })
    if (moved) { state.slot = moved; fn.renderAll() }
  }

  const nextOptions: ReactNode[] = []
  let nextChosen = ""
  if (day && day.stay) {
    pool.forEach(other => (duties.sidings[other.id] || []).forEach((each: any, i: number) => {
      const next = fn.dutyOn(each, (state.weekday + 1) % 7)
      const fits = next && next.segments.length && fn.dutyStartPlatform(next) === fn.dutyEndPlatform(day)
      const own = other.id === siding.id, id = fn.dutyIdOf(each)
      if (!own && !id) return
      const value = own ? "same:" + i : "duty:" + id
      const chosen = day.nextDuty && day.nextDuty !== "0" ? id === day.nextDuty : own && fn.nextSlotOf(day, state.slot.index) === i
      if (chosen) nextChosen = value
      nextOptions.push(<option key={value} value={value}>{(own ? "" : sidingName(other) + " ") + tr("车 {0}{1}{2}", i + 1, own && i === state.slot.index ? tr("（自己）") : "", fits ? "" : next && next.segments.length ? tr("（不从这个站台出发）") : tr("（没有运用）"))}</option>)
    }))
  }

  return (
    <section className="flex flex-col gap-2" aria-label={tr("运用：车 {0}", fn.dutyNumber(depot, state.slot.siding, state.slot.index))}>
      <div className="flex items-center justify-between gap-2">
        <h3 className="m-0 text-[12px] font-medium text-dim">{tr("运用：车 {0}", fn.dutyNumber(depot, state.slot.siding, state.slot.index))}</h3>
        <button type="button" className="text-[12px] text-sel hover:underline" onClick={() => { state.slot = null; fn.renderAll() }}>{tr("取消选择")}</button>
      </div>
      <div className="flex flex-col gap-2 rounded-lg border border-[var(--duty)] bg-panel p-3" style={{ boxShadow: "inset 3px 0 0 var(--duty)" }}>
        <div className="text-[14px] font-semibold">{fn.shortName(depot.name)} · {sidingName(siding)}</div>
        <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[13px]">
          <dt className="text-dim">{tr("运行日")}</dt>
          <dd className="m-0">{fn.daysText(group)}{day && day.days !== group ? <span className="text-caution">{tr("（现在和 {0} 共用，改了会分开）", fn.daysText(day.days & ~group))}</span> : null}</dd>
          <dt className="text-dim">{tr("方式")}</dt>
          <dd className="m-0">{duties.mode === "diagram" ? tr("按运用图") : tr("自动排班（游戏按时刻表自动生成运用；这里设的运用先存着，改为按运用图才用）")}</dd>
        </dl>
        {/* The old code's text of where the train goes back to, as dt/dd pairs */}
        <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[13px] [&_dd]:m-0 [&_dt]:text-dim" dangerouslySetInnerHTML={{ __html: fn.plannedReturnText(depot, siding.id, state.slot.index, state.weekday) }} />
        {deleted > 0 && (
          <p className="m-0 text-[12px] text-warn">
            {duties.auto
              ? tr("这份运用里有 {0} 班属于已经删除的线路。它是游戏按时刻表自动排的，车厂重新寻路后会按现在的线路重排（服务端换了新版后会自动做）。", deleted)
              : tr("这份运用里有 {0} 班属于已经删除的线路，这些班不会再跑。右键车厂重新生成运用，或在这里把它们移出。", deleted)}
            {!duties.auto && <button type="button" className="ml-1 text-sel hover:underline" onClick={() => edit(d => { d.segments = d.segments.filter((segment: any) => data.routeById.get(segment.route)) })}>{tr("全部移出")}</button>}
          </p>
        )}
        {day && day.segments.length > deleted ? (
          <ol className="m-0 flex list-none flex-col p-0">
            {day.segments.map((segment: any, i: number) => {
              const r = data.routeById.get(segment.route)
              if (!r) return null
              const s = fn.segmentService(segment), last = fn.lastStopOf(segment, r)
              const goesOn = i + 1 < day.segments.length && fn.terminalNow().next.get(fn.segmentKey(segment))
              const wait = day.waits && i < day.waits.length ? day.waits[i] : 0
              return (
                <li key={i} className="border-b border-grid py-1.5 last:border-b-0">
                  <div className="flex items-center gap-2 text-[13px]">
                    <span className="h-3.5 w-1 shrink-0 rounded-pill" style={{ background: fn.colorOf(r.color) }} />
                    <span className="num shrink-0">{s ? fn.clock(fn.segmentStart(segment)) + "–" + fn.clock(fn.segmentEnd(segment)) : "--:--"}</span>
                    <span className="min-w-0 flex-1 truncate" title={fn.routeTitle(r)}>{fn.shortName(r.name)} <span className="text-dim">{fn.shortName(r.stops[segment.from] ? r.stops[segment.from].name : "?")} → {fn.shortName(r.stops[last] ? r.stops[last].name : "?")}</span></span>
                    {data.canEdit && <button type="button" className="btn btn-ghost h-6 px-1.5 text-[12px]" title={tr("移出这一班")} aria-label={tr("移出这一班")}
                      onClick={() => edit(d => { d.segments.splice(i, 1) })}>×</button>}
                  </div>
                  {goesOn && <p className="m-0 pl-3 text-[12px] text-dim">{fn.terminalText(goesOn)}</p>}
                  {i + 1 < day.segments.length && data.canEdit && (
                    <label className="flex items-center gap-1.5 pl-3 text-[12px] text-dim">{tr("等候")}
                      <select className={"h-6 rounded-md border bg-panel px-1 text-[12px] " + (waitOwn ? "border-sel text-sel" : "border-ctl")} value={wait} onChange={e => {
                        const value = Number(e.target.value)
                        edit(d => {
                          const waits = Array.isArray(d.waits) && d.waits.length === d.segments.length - 1 ? d.waits : new Array(Math.max(0, d.segments.length - 1)).fill(0)
                          waits[i] = value
                          d.waits = waits
                          d.exceptions = (d.exceptions || 0) | v.EXCEPTION.WAITS
                        })
                      }}>
                        {([[0, tr("运行时定")], [1, tr("在站台等")], [2, tr("去折返线等")], [3, tr("换站台再发")]] as [number, string][]).map(([value, text]) => <option key={value} value={value}>{text}</option>)}
                      </select>
                      {waitOwn && <span className="text-sel">{tr("例外")}</span>}
                    </label>
                  )}
                  <div className="pl-3">{problemsAt(i)}</div>
                </li>
              )
            })}
          </ol>
        ) : deleted ? null : <p className="m-0 text-[12px] text-dim">{tr("这天没有运用，在运行图上点班次加进来。")}</p>}
        {problemsAt(-1)}
        {fn.dutyCheckText(depot) && <p className="m-0 text-[12px] text-dim">{fn.dutyCheckText(depot)}</p>}
        {data.canEdit && <div className="flex flex-col gap-2 border-t border-grid pt-2 text-[12px]">
          {day && day.segments.length > 0 && (
            <label className="flex items-center gap-1.5 text-[13px]"><input type="checkbox" checked={Boolean(day.stay)} onChange={e => { const stay = e.target.checked; edit(d => { d.stay = stay }) }} /> {tr("跑完最后一班留在线上过夜")}</label>
          )}
          {day && day.stay && (
            <label className="flex flex-col gap-0.5 text-dim">{tr("第二天接着跑")}
              <select className={select} value={nextChosen} onChange={e => {
                const [kind, value] = e.target.value.split(":")
                // Another siding's duty by its number, or one of this siding by its place (P2-D)
                edit(d => { d.nextDuty = kind === "duty" ? value : "0"; d.next = kind === "duty" || Number(value) === state.slot.index ? -1 : Number(value) })
              }}>{nextOptions}</select></label>
          )}
          {day && day.segments.length > 0 && !day.stay && (
            <label className="flex flex-col gap-0.5 text-dim">{tr("回库存车线")}
              <select className={select} value={day.returnSiding || "0"} onChange={e => {
                const returnSiding = e.target.value
                // Set by hand: kept when the duties are planned again (运用补完 step 2)
                edit(d => { d.returnSiding = returnSiding; d.exceptions = returnSiding === "0" ? (d.exceptions || 0) & ~v.EXCEPTION.BACK_SIDING : (d.exceptions || 0) | v.EXCEPTION.BACK_SIDING })
              }}>
                <option value="0">{tr("自动（车池里那时最早要用车的）")}</option>
                {pool.map(other => <option key={other.id} value={other.id}>{sidingName(other)}</option>)}
              </select></label>
          )}
          <label className="flex flex-col gap-0.5 text-dim">{tr("出车方式")}
            <select className={select} value={start} onChange={e => { const value = e.target.value; fn.editDuties(depot, (all: any) => { (all.sidings[state.slot.siding][state.slot.index] || []).forEach((d: any) => { d.start = value }) }) }}>
              {([["prefer", tr("优先从这条存车线出")], ["only", tr("只从这条存车线出")], ["any", tr("不指定（车池里有车、出库最快的）")]] as [string, string][]).map(([value, text]) => <option key={value} value={value}>{text}</option>)}
            </select></label>
          {pool.length > 1 && (
            <label className="flex flex-col gap-0.5 text-dim">{tr("出车存车线")}
              <select className={select} value={siding.id} onChange={e => moveToSiding(e.target.value)}>
                {pool.map(other => <option key={other.id} value={other.id}>{sidingName(other)}</option>)}
              </select></label>
          )}
          {start === "only" && siding.fleet === 0 && <p className="m-0 text-caution">{tr("只从这条存车线出车，但它最多停 0 列，这份运用不会有车")}</p>}
        </div>}
        <p className="m-0 text-[12px] text-dim">{tr("重新生成运用时，出车方式和出车存车线按生成结果重来。")}</p>
        <p className="m-0 text-[12px] text-dim">{tr("选着这列车时，在运行图上点一班把它加进来，再点一次移出；别的车跑的班次会移过来。改的是 {0} 这几天（表的组合相同）。按 Esc 取消选择。按运用图的车厂按这里的运用出车，应用后要刷新车厂路径。", fn.daysText(group))}</p>
      </div>
    </section>
  )
}
