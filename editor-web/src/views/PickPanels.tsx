/**
 * The panels of choosing on the network map (the old pickingPanel / wayPickPanel and their handlers), drawn beside the
 * map: a new line's stations in order, each leg's way and its waypoints; a depot's way out or back, or a deadhead path,
 * with its time. Every action is the old code's: refreshPreview, finishPicking, stopPicking, refreshWayPick,
 * finishWayPick. None of the old data attributes, so the old handlers on #inspector don't act on them.
 */
import { legacy, useLegacy } from "../legacy/engine"
import { tr } from "../i18n"

export function PickPanel({ className }: { className: string }) {
  useLegacy()
  const { v } = legacy
  if (v.picking) return <aside className={className} aria-label={tr("新建线路")}><Picking /></aside>
  if (v.wayPick) return <aside className={className} aria-label={tr("选出口")}><WayPick /></aside>
  return null
}

const link = "text-[12px] text-sel hover:underline disabled:text-dim disabled:no-underline"

function Picking() {
  const { fn, v } = legacy
  const picking = v.picking
  const stops: any[] = picking.stops
  const change = (run: () => void) => { run(); fn.refreshPreview() }
  const allFound = stops.length >= 2 && picking.legs.length === stops.length - 1 && picking.legs.every((leg: any) => leg.found)
  return (
    <div className="flex flex-col gap-2 p-3 text-[13px]">
      <h3 className="m-0 text-[14px] font-semibold">{tr("新建线路")}</h3>
      <p className="m-0 text-[12px] text-dim">{tr("在图上依次点车站；站里有多个站台时选一个。经过已选车站的线路会高亮。两站之间的路是服务端按游戏的寻路找的；要走别的路，选一段后在图上点节点作为途经点，再点一次去掉。")}</p>
      {stops.length ? (
        <ol className="m-0 flex list-none flex-col p-0">
          {stops.map((stop, i) => {
            const leg = picking.legs[i], next = stops[i + 1]
            const legText = !next ? "" : !leg ? tr("正在找路…") : leg.found ? tr("经 {0} 个节点{1}", leg.nodes.length, stop.waypoints.length ? tr("，途经 {0} 个点", stop.waypoints.length) : "") : tr("走不通：{0}", leg.failure)
            return (
              <li key={i} className="border-b border-grid py-1.5 last:border-b-0">
                <div className="flex items-center gap-1.5">
                  <span className="num w-5 text-[12px] text-dim">{i + 1}</span>
                  <span className="min-w-0 flex-1 truncate">{stop.name}</span>
                  {i > 0 && i < stops.length - 1 && <label className="flex items-center gap-1 text-[12px] text-dim"><input type="checkbox" checked={Boolean(stop.pass)} onChange={e => { stop.pass = e.target.checked; fn.drawNetwork(); fn.renderAll() }} /> {tr("通过")}</label>}
                  <button type="button" className="btn btn-ghost h-6 px-1" disabled={i === 0} aria-label={tr("上移")} onClick={() => change(() => { [stops[i - 1], stops[i]] = [stops[i], stops[i - 1]] })}>↑</button>
                  <button type="button" className="btn btn-ghost h-6 px-1" disabled={i === stops.length - 1} aria-label={tr("下移")} onClick={() => change(() => { [stops[i + 1], stops[i]] = [stops[i], stops[i + 1]] })}>↓</button>
                  <button type="button" className="btn btn-ghost h-6 px-1" aria-label={tr("删掉这一站")} onClick={() => change(() => { stops.splice(i, 1); picking.activeLeg = -1 })}>×</button>
                </div>
                {next && (
                  <div className={"pl-6 text-[12px] " + (leg && !leg.found ? "text-warn" : "text-dim")}>
                    → {legText}{" "}
                    <button type="button" className={link} aria-pressed={picking.activeLeg === i} onClick={() => { picking.activeLeg = picking.activeLeg === i ? -1 : i; fn.drawNetwork(); fn.renderAll() }}>
                      {picking.activeLeg === i ? tr("选途经点中，点这里结束") : tr("选这一段的途经点")}
                    </button>
                  </div>
                )}
              </li>
            )
          })}
        </ol>
      ) : <p className="m-0 text-[12px] text-dim">{tr("还没选车站")}</p>}
      {picking.error && <p className="m-0 text-[12px] text-warn">{picking.error}</p>}
      <div className="flex flex-wrap gap-1.5">
        <button type="button" className="btn btn-primary" disabled={!allFound} onClick={() => fn.finishPicking()}>{tr("填线名和种别…")}</button>
        <button type="button" className="btn" onClick={() => fn.stopPicking()}>{tr("取消")}</button>
        <button type="button" className={link} onClick={() => { v.picking = null; fn.renderAll(); fn.newLineDialog() }}>{tr("改用停站图新建…")}</button>
      </div>
    </div>
  )
}

function WayPick() {
  const { fn, v } = legacy
  const wayPick = v.wayPick
  const depot = fn.depotById(wayPick.depot)
  const result = wayPick.result
  const choosingPath = wayPick.target.path !== undefined
  const found = result && result.found
  const entrances: string[] = (result && result.entrances) || []
  const siding = depot && depot.sidings.find((s: any) => s.id === wayPick.siding)
  return (
    <div className="flex flex-col gap-2 p-3 text-[13px]">
      <h3 className="m-0 text-[14px] font-semibold">{choosingPath ? tr("画回送路径") : wayPick.out ? tr("选出口") : tr("选回口")}</h3>
      <p className="m-0">{fn.shortName(depot ? depot.name : "")} · {tr("存车线")} {siding ? siding.name : ""}</p>
      <p className="m-0 text-[12px] text-dim">{tr("这条存车线的出入口")}：{entrances.length ? entrances.map(id => fn.stationNameOf(id)).join("、") : tr("（还没算出）")}</p>
      {choosingPath ? <p className="m-0 text-[12px]">{tr("途经 {0} 个点；点节点加入或去掉，点车站换终点。", wayPick.waypoints.length)}</p> : <>
        <p className="m-0 text-[12px] text-dim">{wayPick.out ? tr("运行图上从这些站发车（出库开到哪）") : tr("运行图上在这些站收车（从哪回库）")}：</p>
        <div className="flex flex-wrap gap-1">
          {(wayPick.targets || []).length ? wayPick.targets.map((each: any) => (
            <button key={each.platform} type="button" aria-pressed={each.platform === wayPick.platform}
              className={"btn h-auto min-h-6 px-2 py-0.5 text-left text-[12px] " + (each.platform === wayPick.platform ? "tint-accent border-brand text-brand" : "")}
              onClick={() => { wayPick.platform = each.platform; fn.refreshWayPick() }}>
              {fn.shortName(each.name)} · {wayPick.out ? tr("最早 {0}", fn.clock(each.first)) : tr("最晚 {0}", fn.clock(each.last))} · {tr("{0} 班", each.count)}
            </button>
          )) : <span className="text-[12px] text-dim">{tr("这天运行图上没有这些线路的班次")}</span>}
        </div>
        <p className="m-0 text-[12px]">{tr("经过的轨道：{0} 段（按点的顺序）", Math.floor(wayPick.waypoints.length / 2))}{" "}
          {wayPick.waypoints.length > 0 && <button type="button" className={link} onClick={() => { wayPick.waypoints = []; wayPick.turned = new Set(); fn.refreshWayPick() }}>{tr("清空")}</button>}</p>
      </>}
      <p className={"m-0 font-medium " + (result && !found ? "text-warn" : "")}>{!result ? tr("正在计算…") : found ? tr("{0}用时 {1}", wayPick.out ? tr("出库") : tr("回库"), result.millis >= 0 ? fn.durationText(result.millis) : "") : tr("这样走不通")}</p>
      {wayPick.error && <p className="m-0 text-[12px] text-warn">{wayPick.error}</p>}
      <div className="flex gap-1.5">
        <button type="button" className="btn btn-primary" onClick={() => fn.finishWayPick(true)}>{tr("用这个")}</button>
        <button type="button" className="btn" onClick={() => fn.finishWayPick(false)}>{tr("取消")}</button>
      </div>
    </div>
  )
}
