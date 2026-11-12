/** Depot helpers moved from editor.js (dutiesOf, shortfallText, replanText, depotLines, turnTimeOf, turnPairsOf). */
import { tr } from "../i18n"
import type { Draft } from "./draft"
import { type Model, shortName } from "./model"

/** A depot's duties as drafted, or as set, or the game's own when it plans them (dutiesOf). */
export const dutiesOf = (draft: Draft, depot: any) => {
  if (draft.duties[depot.id]) return draft.duties[depot.id].duties
  const set = depot.duties || { mode: "online", sidings: {} }
  const auto = depot.autoDuties
  return set.mode !== "diagram" && auto && auto.mode === "diagram" && Object.keys(auto.sidings).length ? Object.assign({}, auto, { auto: true }) : set
}

export function shortfallText(shortfall: any) {
  // -1: the duties don't repeat week after week, so what they need isn't known. Each group of vehicles is short on its
  // own where there is more than one: trains aren't lent between them
  const pools = (shortfall.pools || []).filter((pool: any) => pool.uncovered > 0)
  const main = (shortfall.pools || []).length > 1 && pools.length ? tr("{0}：", shortfall.lines) + pools.map((pool: any) => (pool.needed < 0 ? tr("{0} 算不出需要几列，现有 {1} 列，每天 {2} 班没有车", pool.label, pool.available, pool.uncovered) : tr("{0} 跑满需要 {1} 列，现有 {2} 列，每天 {3} 班没有车", pool.label, pool.needed, pool.available, pool.uncovered))).join(tr("；"))
    : shortfall.needed < 0 ? tr("{0}：运用排不成每周一样，算不出需要几列；现有 {1} 列，每天 {2} 班没有车", shortfall.lines, shortfall.available, shortfall.uncovered) : tr("{0}：跑满需要 {1} 列，现有 {2} 列，每天 {3} 班没有车", shortfall.lines, shortfall.needed, shortfall.available, shortfall.uncovered)
  // Services no number of trains could run aren't trains short; a depot with no rule saying which station its trains go
  // by plans no line, said as that
  const unset = (shortfall.entrancesUnset || []).map((key: string) => key.split("|").slice(1).join("|") || key)
  const unrunnable = !shortfall.unrunnable ? "" : unset.length
    ? tr("（其中每天 {0} 班没排：车厂接到线路的车站不止一个，{1} 还没有出入库规则指定从哪站进出；在车厂右键「出入库规则…」里加一条）", shortfall.unrunnable, unset.join("、"))
    : tr("（其中每天 {0} 班加多少车都跑不了：存车线出不去、也没有车能折返过去，要查出入库路线或折返）", shortfall.unrunnable)
  return main + unrunnable + (shortfall.suggested ? tr("，建议加到 {0}", shortfall.suggested) : "")
}

/** How many trains short, each group of vehicles counted on its own (-1 if not known), as the server works it out. */
export function missingTrains(shortfall: any) {
  if (shortfall.missing !== undefined) return shortfall.missing
  return shortfall.needed < 0 ? -1 : Math.max(0, shortfall.needed - shortfall.available)
}

export function replanText(shortfall: any) {
  if (!shortfall || !shortfall.replan) return ""
  const parts = shortfall.replan.split(";").map((item: string) => {
    const [name, then, now] = item.split(":")
    return name === "minReverse" ? tr("换端最短时间 {0} 秒 → {1} 秒", Math.round(+then / 1000), Math.round(+now / 1000)) : name === "buffers" ? tr("采用的实测余量变了") : name === "ways" ? tr("轨道变了：出入库或站内换站台的路和排运用时不一样，现在没车的班次也许能排上") : name
  })
  return tr("运用是按原来的设置排的（{0}）。现在的运用照样接得上，所以没有自动重排；按新设置重排可能更省车，需要时在车厂右键菜单里选“重新排运用”。", parts.join("，"))
}

export function depotLines(model: Model, depot: any): [string, string][] {
  const lines = new Map<string, string>()
  ;(depot.routes || []).forEach((id: string) => {
    const r = model.routeById.get(id)
    if (r && !lines.has(r.lineKey)) lines.set(r.lineKey, shortName(r.line) || r.lineKey)
  })
  return [...lines.entries()]
}

/**
 * Whether two line keys ("TRAIN|s1|s1") name the same line: the same transport mode and the same name in one language at
 * least, as the server matches a depot rule's lines (DepotRules.sameLine). A rule ticked when the line was "S1" is still
 * for it once its routes are "S1各停|S1 Local" (Q world, 2026-11-12).
 */
export function sameLine(key1: string, key2: string) {
  if (key1 === key2) return true
  const [mode1, ...names1] = key1.split("|")
  const [mode2, ...names2] = key2.split("|")
  if (!names1.length || !names2.length || mode1 !== mode2) return false
  return names1.some(name => name.trim() !== "" && names2.some(other => other.trim() === name.trim()))
}

/** How long a change of platform within a station takes as measured: millis, null with no way, undefined if not measured. */
export function turnTimeOf(depot: any, from: string, to: string) {
  const path = depot.path || {}
  if (!path.turnTimesMeasured) return undefined
  const millis = ((path.turnTimes || {})[from] || {})[to]
  return millis === undefined ? null : millis
}

export const stopLabel = (stop: any) => `${shortName(stop.name)} ${stop.platform}`.trim()

/** Each change of platform within a station the depot's routes may make, with how long it takes as measured. */
export function turnPairsOf(model: Model, depot: any) {
  if (!(depot.path || {}).turnTimesMeasured) return []
  const routes = depot.routes.map((id: string) => model.routeById.get(id)).filter((r: any) => r && r.stops.length)
  const pairs: { from: any; to: any; millis: number | null | undefined }[] = [], seen = new Set<string>()
  routes.forEach((before: any) => {
    const from = before.stops[before.stops.length - 1]
    routes.forEach((after: any) => {
      const to = after.stops[0], key = from.platformId + ">" + to.platformId
      if (!from.station || from.station !== to.station || from.platformId === to.platformId || seen.has(key)) return
      seen.add(key)
      pairs.push({ from, to, millis: turnTimeOf(depot, from.platformId, to.platformId) })
    })
  })
  return pairs
}

/** Why a train is reported, with how late it is or how long it has waited. */
export function alarmReasonText(alarm: any) {
  const reason = alarm.reason === "DEADLOCK" ? tr("和别的车互相等着走不了（锁死）")
    : alarm.reason === "WAITING" ? tr("等进路已 {0} 分钟", Math.floor(alarm.waiting / 60))
    : alarm.reason === "LATE" ? tr("晚点 {0} 分钟", Math.floor(alarm.late / 60)) : ""
  const turn = alarm.turn === 1 ? tr("，将提前折返") : alarm.turn === 2 ? tr("，已提前折返") : alarm.turn === 3 ? tr("，指定的车站折不回去") : ""
  const dispatched = alarm.dispatch ? tr("，有调度命令") : ""
  const takeover = alarm.takeover === 1 ? tr("，等备车接替") : alarm.takeover === 2 ? tr("，已交给备车，空车回库") : alarm.takeover === 3 ? tr("，没有备车可接替") : alarm.takeover === 4 ? tr("，备车出场要经过受影响区段") : ""
  return reason + (alarm.skipping ? tr("，跳站中") : "") + turn + takeover + dispatched
}
