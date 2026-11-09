/**
 * Everything that doesn't match between the page and the game, or that the game couldn't do with what it was given
 * (syncProblems in editor.js). Platforms trains turning back hold too long come from the timetable layer, passed in as
 * `terminalWarnings` (terminalNow().warnings in the old page).
 */
import type { Connection } from "../api/client"
import { tr } from "../i18n"
import { alarmReasonText, depotLines, dutiesOf, replanText, shortfallText, stopLabel, turnPairsOf } from "./depots"
import { type Draft, platformText, stationNameOf } from "./draft"
import { type Model, routeTitle, shortName } from "./model"

export type SyncProblem = {
  level: "err" | "warn" | "hint"
  text: string
  route?: string
  /** Sent at once (api/apply), not drafted */
  actions?: { label: string; op?: any; adhoc?: { system: string; train: string } }[]
  recall?: { depot: string; siding: string; slot: number }
}

function systemTitle(model: Model, system: any) {
  const lineNames = [...new Set(system.routes.map((id: string) => model.routeById.get(id)).filter(Boolean).map((r: any) => shortName(r.line)))]
  return lineNames.length > 3 ? tr("{0} 等 {1} 条线", lineNames.slice(0, 3).join("、"), lineNames.length) : lineNames.join("、")
}
export function plansByAnchor(model: Model, anchor: string) {
  for (const system of model.contingencySystems || []) {
    if (system.anchor === anchor) return { anchor, plans: system.plans, adhoc: system.adhoc, system }
    const other = (system.others || []).find((each: any) => each.anchor === anchor)
    if (other) return { anchor, plans: other.plans, adhoc: other.adhoc, system }
  }
  return null
}

export type TerminalWarning = { type: string; from?: { route: any }; at: string; platform: string; until?: string; count?: number }

export function syncProblems(connection: Connection, model: Model | null, draft: Draft, terminalWarnings: TerminalWarning[] = []): SyncProblem[] {
  const problems: SyncProblem[] = []
  if (connection !== "ok") problems.push({ level: "err", text: connection === "world" ? tr("游戏现在不在世界里") : connection === "key" ? tr("页面的链接已经失效") : tr("连不上游戏") })
  if (!model) return problems
  const data = model
  const depotById = (id: string) => data.depotById(id) as any
  const routeName = (id: string) => (data.routeById.get(id) ? routeTitle(data.routeById.get(id)!) : tr("已删除的线路"))
  Object.entries(draft.routes).forEach(([id, entry]) => {
    if (entry.conflict) problems.push({ level: "err", text: tr("{0}：游戏里在草稿之后改过，草稿先不发", routeName(id)), route: id })
    else if (entry.error) problems.push({ level: "err", text: tr("{0}：没有应用，{1}", routeName(id), entry.error), route: id })
  })
  Object.entries(draft.duties).forEach(([id, entry]) => {
    if (entry.error) problems.push({ level: "err", text: tr("车厂{0}的运用没有应用：{1}", shortName(depotById(id) ? depotById(id).name : "?"), entry.error) })
  })
  Object.entries(draft.depots || {}).forEach(([id, entry]) => {
    if (entry.error) problems.push({ level: "err", text: tr("车厂{0}的设置没有应用：{1}", shortName(depotById(id) ? depotById(id).name : "?"), entry.error) })
  })
  ;(draft.created || []).filter(made => made.error).forEach(made => problems.push({ level: "err", text: tr("新种别 {0} 没有建成：{1}", shortName(made.name), made.error) }))
  // Plans a dispatcher started, on until ended, and settings for trains no longer reported
  const reported = new Set(data.depots.flatMap((depot: any) => (depot.alarms || []).map((alarm: any) => alarm.train)))
  ;(data.contingencySystems || []).forEach((system: any) => {
    ;[system].concat(system.others || []).forEach((owner: any) => {
      owner.plans.filter((plan: any) => plan.active).forEach((plan: any) => problems.push({ level: "warn", text: tr("路网{0}：预案「{1}」已启用", systemTitle(data, system), plan.name || plan.id), actions: [{ label: tr("停用预案"), op: { type: "contingency", system: owner.anchor, action: "deactivate", plan: plan.id } }] }))
      Object.keys(owner.adhoc || {}).filter(train => !reported.has(train)).forEach(train => problems.push({ level: "warn", text: tr("路网{0}：列车 {1} 有临时处置", systemTitle(data, system), train), actions: [{ label: tr("解除"), op: { type: "contingency", system: owner.anchor, action: "release", train } }] }))
    })
    // Rails joined systems that each have plans: both hold, until merged
    if ((system.others || []).some((other: any) => other.plans.length)) problems.push({ level: "warn", text: tr("路网{0}由 {1} 套故障预案接在一起，都还生效（原锚点小的优先）。在线路右键“路网故障预案…”里可以合成一套", systemTitle(data, system), 1 + system.others.length) })
  })
  data.depots.forEach((depot: any) => {
    const name = shortName(depot.name) || tr("（未命名车厂）")
    const path = depot.path || {}
    if (!depot.routes.length) problems.push({ level: "warn", text: tr("车厂{0}没选线路，不会出车", name) })
    else if (path.state === "failed") problems.push({ level: "err", text: tr("车厂{0}的路径没生成：{1}", name, path.text || tr("原因不明，看游戏里的车厂界面")) })
    else if (path.state === "generating") problems.push({ level: "warn", text: tr("车厂{0}正在生成路径", name) })
    else if (path.state === "stale") problems.push({ level: "warn", text: tr("车厂{0}的路径是旧的，线路或轨道改过，要刷新", name) })
    ;(path.detours || []).forEach((detour: any) => problems.push({ level: "warn", text: tr("车厂{0}：从 {1} 到 {2} 要空车走 {3} 格（两个站台只相距 {4} 格），绕远了。在 {1} 的线路上设站前或站后折返（折返线、多站台轮用），或者让下一班从同一个站台发车", name, shortName(detour.from), shortName(detour.to), detour.length, detour.apart) }))
    if (path.failedDuties > 0) problems.push({ level: "err", text: tr("车厂{0}有 {1} 份运用找不到路，这些车不会按运用出车：{2}", name, path.failedDuties, (path.failedDutyKeys || []).slice(0, 3).join("、")) })
    // Trains waiting on the line with no duty to go on with, which a click takes straight back
    ;(depot.held || []).forEach((held: any) => {
      const siding = depot.sidings.find((other: any) => other.id === held.siding)
      const why = held.reason === "OTHER_PLATFORM" ? tr("第二天的运用不从这个站台出发") : held.reason === "ALREADY_RUN" ? tr("第二天的运用已经由别的车跑了") : tr("第二天没有接着跑的运用")
      problems.push({ level: "err", text: tr("车厂{0}：{1} 车 {2} 留在线上过夜后{3}，停在 {4} 等处理。改好运用后它会接着跑", name, shortName(siding ? siding.name : "") || tr("存车线"), held.slot + 1, why, shortName(held.platform)), recall: { depot: depot.id, siding: held.siding, slot: held.slot } })
    })
    // Trains late or stuck with no plan on for them, and those a plan or a dispatcher's setting is on for
    ;(depot.alarms || []).forEach((alarm: any) => {
      const siding = depot.sidings.find((other: any) => other.id === alarm.siding)
      const r = data.routeById.get(alarm.route)
      const trainText = tr("{0} 车 {1}（{2}）", shortName(siding ? siding.name : "") || tr("存车线"), alarm.slot + 1, r ? routeTitle(r) : alarm.route)
      const owner = plansByAnchor(data, alarm.system)
      const plan = owner ? owner.plans.find((each: any) => each.id === alarm.plan) : null
      if (!alarm.handled) {
        const actions: SyncProblem["actions"] = []
        if (plan) actions.push({ label: tr("启用预案「{0}」", plan.name || plan.id), op: { type: "contingency", system: alarm.system, action: "activate", plan: plan.id } })
        actions.push({ label: tr("临时处置…"), adhoc: { system: alarm.system, train: alarm.train } })
        problems.push({ level: "err", text: tr("车厂{0}：{1}{2}，没有生效的故障预案", name, trainText, alarmReasonText(alarm)), actions })
      } else {
        const actions = alarm.plan === "0" ? [{ label: tr("解除"), op: { type: "contingency", system: alarm.system, action: "release", train: alarm.train } }]
          : plan && plan.manual ? [{ label: tr("停用预案"), op: { type: "contingency", system: alarm.system, action: "deactivate", plan: plan.id } }] : []
        problems.push({ level: "warn", text: tr("车厂{0}：{1}{2}，按{3}处理", name, trainText, alarmReasonText(alarm), alarm.plan === "0" ? tr("临时处置") : tr("预案「{0}」", plan ? plan.name || plan.id : alarm.plan)), actions })
      }
    })
    ;(depot.returnMisses || []).forEach((miss: any) => problems.push({ level: "warn", text: tr("车厂{0}：一班设了回送车厂 {1}，但那里没有同车型、有空位又有路的存车线，车按平常回库了", name, shortName((depotById(miss.depot) || {}).name || miss.depot)) }))
    turnPairsOf(data, depot).filter(pair => pair.millis === null).forEach(pair => problems.push({ level: "warn", text: tr("车厂{0}：{1} 到 {2} 换不过去（同站换站台没有路），自动排班不会让车这样接续", name, stopLabel(pair.from), stopLabel(pair.to)) }))
    const duties = dutiesOf(draft, depot)
    // Duties handed on to their pool or left with no siding of their vehicle
    const poolName = (key: string) => { const pool = (depot.pools || []).find((each: any) => each.key === key); return pool ? pool.vehicle || key : key.split("/")[0] || tr("（不明）") }
    Object.entries(duties.unplaced || {}).forEach(([key, count]) => problems.push({ level: "warn", text: tr("车厂{0}：车型 {1} 的 {2} 份运用没有这种车型的存车线，不运行；加回这种车型的存车线后自动放回", name, poolName(key), count) }))
    const movedTo = new Map<string, number>()
    ;(duties.moves || []).filter((move: any) => move.to !== "0" && move.from !== "0").forEach((move: any) => movedTo.set(move.from + ">" + move.to, (movedTo.get(move.from + ">" + move.to) || 0) + 1))
    movedTo.forEach((count, key) => {
      const [from, to] = key.split(">")
      const fromSiding = depot.sidings.find((siding: any) => String(siding.id) === from), toSiding = depot.sidings.find((siding: any) => String(siding.id) === to)
      problems.push({ level: "warn", text: tr("车厂{0}：{1} 删除了或换了车型，它的 {2} 份运用移到同车型的 {3}（改一下运用后不再提示）", name, fromSiding ? shortName(fromSiding.name) || tr("存车线") : tr("一条已删除的存车线"), count, toSiding ? shortName(toSiding.name) || tr("存车线") : to) })
    })
    const deleted = Object.values(duties.sidings).some((slots: any) => slots.some((slot: any) => slot.some((day: any) => day.segments.some((segment: any) => !data.routeById.get(segment.route)))))
    if (deleted) problems.push({ level: "warn", text: tr("车厂{0}的运用里有已经删除的线路的班次", name) })
    if (depot.shortfall && depot.shortfall.uncovered > 0) problems.push({ level: "warn", text: tr("车厂{0}：{1}", name, shortfallText(depot.shortfall)) })
    // Lines not planned for want of a rule saying which station the depot's trains go by
    const unsetLines = (depot.shortfall && depot.shortfall.entrancesUnset) || []
    if (unsetLines.length) {
      const lineName = (key: string) => { const found = depotLines(data, depot).find(([lineKey]) => lineKey === key); return found ? found[1] : key.split("|").slice(1).join("|") || key }
      problems.push({ level: "err", text: tr("车厂{0}接到线路的车站不止一个，{1} 没有出入库规则指定从哪站进出，这些线路不排运用；在车厂右键「出入库规则…」里加一条", name, unsetLines.map(lineName).join("、")) })
    }
    if (duties.auto && depot.shortfall && depot.shortfall.replan) problems.push({ level: "warn", text: tr("车厂{0}：{1}", name, replanText(depot.shortfall)) })
    // Ways out and back path generation found missing, which the planning keeps off; and those not measured yet
    depot.sidings.forEach((siding: any) => {
      const sidingName = shortName(siding.name) || tr("存车线")
      ;(siding.noWayOut || []).forEach((platformId: string) => problems.push({ level: "warn", text: tr("车厂{0}：{1} 到 {2} 没有出库的路，这条存车线不从这里出车", name, sidingName, platformText(data, platformId)) }))
      ;(siding.noWayBack || []).forEach((platformId: string) => problems.push({ level: "warn", text: tr("车厂{0}：从 {2} 回不到 {1}，车改回车池里别的存车线", name, sidingName, platformText(data, platformId)) }))
      const firsts = [...new Set((depot.routes || []).map((id: string) => data.routeById.get(id)).filter(Boolean).filter((r: any) => !(siding.routes || []).length || siding.routes.includes(r.id)).map((r: any) => r.stops[0] && r.stops[0].platformId).filter(Boolean))] as string[]
      const estimated = firsts.filter(platformId => !(siding.measuredOut || []).includes(platformId) && !(siding.noWayOut || []).includes(platformId))
      if (estimated.length && siding.measuredOut) problems.push({ level: "hint", text: tr("车厂{0}：{1} 到 {2} 的出库还没量过，按距离估计排，刷新车厂路径后按实测", name, sidingName, estimated.map(platformId => platformText(data, platformId)).join("、")) })
    })
    if (duties.mode === "diagram" && depot.sidings.some((siding: any) => (duties.sidings[siding.id] || []).length && !siding.legs)) problems.push({ level: "warn", text: tr("车厂{0}还没有出入库时刻，刷新路径后运行图上才画出出库和回库", name) })
  })
  // Platforms in more than one station's area with none set for them, and where they went
  ;((data.topology && data.topology.overlapping) || []).slice(0, 20).forEach((overlap: any) => problems.push({ level: "hint", text: overlap.byIdAlone
    ? tr("{0} 在几个车站的范围里，这些车站都还没有别的站台可参照，暂时归到 {1}；右键时刻表里这一站可以指定", platformText(data, overlap.platform), stationNameOf(data, overlap.station))
    : tr("{0} 在几个车站的范围里，按最近的站台归到 {1}；右键时刻表里这一站可以指定", platformText(data, overlap.platform), stationNameOf(data, overlap.station)) }))
  // Platforms trains turning back hold for long, or that have no room for one at all
  terminalWarnings.slice(0, 12).forEach(warning => {
    const from = warning.from
    if (warning.type === "HOLDS_PLATFORM") problems.push({ level: "warn", text: tr("{0}：{1} 到的车在 {2} 一直停到 {3}，{4} 列车改用别的站台或折返线", from ? routeTitle(from.route) : "?", warning.at, warning.platform, warning.until, warning.count), route: from && from.route.id })
    else problems.push({ level: "err", text: tr("{0}：{1} 到 {2} 的车没有空站台可停，也没有空的折返线，要等前面的车开走", from ? routeTitle(from.route) : "?", warning.at, warning.platform), route: from && from.route.id })
  })
  data.routes.filter(r => r.timetable && !r.hasTimes).forEach(r => problems.push({ level: "warn", text: r.pathFailure ? tr("{0}：找不到路，{1}", routeTitle(r), r.pathFailure) : tr("{0}：还没有各站时刻", routeTitle(r)), route: r.id }))
  return problems
}
