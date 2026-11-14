/**
 * Everything that doesn't match between the page and the game, or that the game couldn't do with what it was given
 * (syncProblems in editor.js). Platforms trains turning back hold too long come from the timetable layer, passed in as
 * `terminalWarnings` (terminalNow().warnings in the old page).
 */
import type { Connection } from "../api/client"
import { tr } from "../i18n"
import { alarmReasonText, depotLines, dutiesOf, replanText, shortfallText, stopLabel, turnPairsOf } from "./depots"
import { changedSince, type Draft, platformText, stationNameOf } from "./draft"
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
    const r: any = data.routeById.get(id)
    if (entry.conflict) problems.push({ level: "err", text: entry.conflictBy ? tr("{0}：和 {1} 的修改冲突，游戏里在草稿之后被 {1} 改过，草稿先不发", routeName(id), entry.conflictBy) : tr("{0}：游戏里在草稿之后改过，草稿先不发", routeName(id)), route: id })
    else if (entry.error) problems.push({ level: "err", text: tr("{0}：没有应用，{1}", routeName(id), entry.error), route: id })
    // Changed in the game since the draft, before it is sent (2026-11-14): what was changed shows on the page already
    else if (changedSince(entry, r)) problems.push({ level: "warn", text: r && r.editors ? tr("{0}：游戏里在你的草稿之后改过（{1} 正在编辑它），现在写入会冲突；页面上已是游戏里的新内容，看一下再决定保留草稿还是放弃", routeName(id), r.editors) : tr("{0}：游戏里在你的草稿之后改过，现在写入会冲突；页面上已是游戏里的新内容，看一下再决定保留草稿还是放弃", routeName(id)), route: id })
    else if (r && r.editors) problems.push({ level: "hint", text: tr("{0}：{1} 正在游戏里编辑这条线路，同时改可能冲突", routeName(id), r.editors), route: id })
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
    // Conflicting routes (0.80.0, item A): safe, a train going into a dead end only with its way out, but they hold each other up
    // Passing points with both tracks in one interlocking zone (2026-11-14, user feedback): the trains deadlock there
    const zones = (path.conflicts || []).filter((conflict: any) => conflict.kind === "zone")
    if (zones.length) {
      const places = zones.slice(0, 4).map((zone: any) => `(${zone.x}, ${zone.y}, ${zone.z})`).join("、")
      problems.push({ level: "err", text: tr("车厂{0}：会让处两股道都刷进了同一个联锁区（{1}）。联锁区要整块锁住，一列车停在一股上、车尾还在联锁区里，另一股也被占着，对向车过不去，两车会互相卡死。联锁色只刷道岔本身，不要刷到列车等着交会的那两股道和站台上", name, places) })
    }
    const conflicts = (path.conflicts || []).filter((conflict: any) => conflict.kind !== "zone")
    if (conflicts.length) {
      const lines = conflicts.slice(0, 4).map((conflict: any) => conflict.kind === "turns"
        ? tr("{0} 到 {1} 之间掉头 {2} 次", shortName(conflict.from), shortName(conflict.to), conflict.turns)
        : tr("{0} 到 {1} 和 {2} 到 {3} 在同一段轨道上方向相反", shortName(conflict.from), shortName(conflict.to), shortName(conflict.otherFrom), shortName(conflict.otherTo)))
      problems.push({ level: "warn", text: tr("车厂{0}的运用里存在大量冲突进路（{1} 处）：{2}。列车进尽头折返线前会先拿到出来的进路，不会死锁，但会互相等。建议：在折返线末端再加一组道岔通往另一方向，改成单边站前折返；或者让每条线各用一边站台站前折返，进站保持正线方向，出站才过渡线到对向轨道", name, conflicts.length, lines.join("；")) })
    }
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
    // Lines with no rule saying which station the depot's trains go by: they go by the most convenient (2026-11-13)
    // The same list holds, as "against:<platform>", the platforms a way out or back runs against the line outside an
    // interlocking zone: used, but trains could meet head on there (2026-11-13)
    const unsetEntries: string[] = (depot.shortfall && depot.shortfall.entrancesUnset) || []
    const againstPlatforms = unsetEntries.filter(entry => entry.startsWith("against:")).map(entry => entry.slice("against:".length))
    // And as "againstlong:<platform>:<blocks>", ways out or back running the line's track against it for long (2026-11-14)
    const againstLong = new Map<string, number>()
    unsetEntries.filter(entry => entry.startsWith("againstlong:")).forEach(entry => {
      const [platformId, blocks] = entry.slice("againstlong:".length).split(":")
      againstLong.set(platformId, Math.max(againstLong.get(platformId) || 0, Number(blocks) || 0))
    })
    // And as "againstspan:<platform>:<platform>", stretches between two stations run all the way against the line
    const againstSpans = unsetEntries.filter(entry => entry.startsWith("againstspan:")).map(entry => entry.slice("againstspan:".length).split(":"))
    const unsetLines = unsetEntries.filter(entry => !entry.startsWith("against:") && !entry.startsWith("againstlong:") && !entry.startsWith("againstspan:"))
    if (againstPlatforms.length) problems.push({ level: "warn", text: tr("车厂{0}：出入库要逆着线路方向经过 {1}，那里（站台和两头的道岔）没刷联锁色，和正线列车可能对头卡死；把那一段刷成联锁区（默认品红）就安全了，或者在出入库规则里指定一条顺向的路，或者在离入库线最近的车站加一组渡线", name, againstPlatforms.map(platformId => platformText(data, platformId)).join("、")) })
    if (againstSpans.length) problems.push({ level: "warn", text: tr("车厂{0}：出入库要逆着线路方向跑过整个站间（{1}），这段里的正线列车迎面过来时中间没有车站可避让，可能对头卡死；建议在离入库线最近的车站加一组渡线，不要逆向跨站", name, againstSpans.map(([from, to]) => tr("{0} 到 {1}", platformText(data, from), platformText(data, to))).join("、")) })
    if (againstLong.size) problems.push({ level: "warn", text: tr("车厂{0}：出入库要逆着线路方向跑很长一段正线（{1}），后面的正线列车迎面过来时无处避让，可能对头卡死；建议在离入库线最近的车站加一组渡线，让逆向的一段尽量短", name, [...againstLong.entries()].map(([platformId, blocks]) => tr("经 {0} {1} 格", platformText(data, platformId), blocks)).join("、")) })
    if (unsetLines.length) {
      const lineName = (key: string) => { const found = depotLines(data, depot).find(([lineKey]) => lineKey === key); return found ? found[1] : key.split("|").slice(1).join("|") || key }
      problems.push({ level: "hint", text: tr("车厂{0}接到线路的车站不止一个，{1} 没有出入库规则，已按方便的方向自动出入库；要固定从哪站进出，在车厂右键「出入库规则…」里加一条", name, unsetLines.map(lineName).join("、")) })
    }
    // Ways out leaving earlier didn't help (2026-11-13): the server sends them as measured buffers below zero
    ;(depot.buffers || []).filter((b: any) => b.kind === "out" && b.seconds < 0).forEach((b: any) => problems.push({ level: "warn", text: tr("车厂{0}：{1} 的出库提前了也没用，到 {2} 仍晚 {3} 秒（在路上等正线列车之类按钟点走的东西），要改时刻表或出库路", name, b.from, b.to, -b.seconds) }))
    if (duties.auto && depot.shortfall && depot.shortfall.replan) problems.push({ level: "warn", text: tr("车厂{0}：{1}", name, replanText(depot.shortfall)) })
    // Ways out and back path generation found missing, which the planning keeps off; and those not measured yet
    depot.sidings.forEach((siding: any) => {
      const sidingName = shortName(siding.name) || tr("存车线")
      const why = (reasons: any, platformId: string) => (reasons && reasons[platformId] ? tr("（原因：{0}）", reasons[platformId]) : "")
      // No way out to any first stop: trains taken back into it can't leave (2026-11-13, 车厂内调车)
      const cannotLeave = Array.isArray(siding.measuredOut) && !siding.measuredOut.length && (siding.noWayOut || []).length > 0
      if (cannotLeave) problems.push({ level: "warn", text: tr("车厂{0}：{1} 到哪个首站都没有出库的路，收进这里的车出不去；在车厂里加一条通到能出车的存车线的轨道（调车线），或检查出入库规则", name, sidingName) })
      ;(siding.noWayOut || []).forEach((platformId: string) => problems.push({ level: "warn", text: tr("车厂{0}：{1} 到 {2} 没有出库的路，这条存车线不从这里出车", name, sidingName, platformText(data, platformId)) + why(siding.noWayOutWhy, platformId) }))
      ;(siding.noWayBack || []).forEach((platformId: string) => problems.push({ level: "warn", text: tr("车厂{0}：从 {2} 回不到 {1}，车改回车池里别的存车线", name, sidingName, platformText(data, platformId)) + why(siding.noWayBackWhy, platformId) }))
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
