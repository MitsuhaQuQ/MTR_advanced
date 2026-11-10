/**
 * Retiring the old code, step 8: the old page's last small dialogs and prompts as the new page's side panels
 * (views/AskPanels.tsx), given to the legacy engine through gen-legacy's FROM_ENV. What each answer does is as before;
 * the rules are src/core's.
 */
import { tr } from "../i18n"
import * as T from "../core/timetable"
import * as S from "../core/services"
import * as X from "../core/settings"
import * as D from "../core/duties"
import { routeTitle, shortName, tableName } from "../core/format"
import * as L from "../core/stationLayout"
import type { AskTarget, PanelTarget } from "./engine"

type Legacy = { fn: Record<string, any>; v: Record<string, any> }

export function dialogRules(get: () => Legacy, open: (target: PanelTarget) => void) {
  const ask = (target: AskTarget) => open({ kind: "ask", ...target })
  const find = (id: string) => S.findService(get().v.data.routeById, id)
  const notice = (text: string) => { const { fn, v } = get(); v.dutyNotice = text; fn.renderBanner() }
  /** Sends a request about a depot to the game, then tells how it went */
  const depotRequest = (path: string, depot: any, done: string) => get().fn.sendJson(path, "POST", { depot: depot.id })
    .then((result: any) => notice(result.ok ? done : tr("没有权限，或者游戏里没有这个车厂。")))
    .catch(() => notice(tr("发送失败，游戏可能已经关闭")))
  const editTimetable = (r: any, change: T.Change) => get().fn.editTimetable(r, change)

  /** Duties planned by the game for a depot, into the draft, with what the planning found */
  async function generate(depot: any) {
    const { fn, v } = get()
    v.dutyNotice = tr("正在生成运用…")
    fn.renderBanner()
    try {
      const result = await fn.sendJson("api/plan-duties", "POST", { depot: depot.id })
      if (result.noSidings) {
        v.dutyNotice = tr("这个车厂没有存车线，生成不了运用。")
      } else {
        fn.editDuties(depot, (duties: any) => { duties.sidings = result.duties.sidings })
        v.plannedReturns.set(depot.id, result.returns || {})
        // The running kernel checks the duties in a simulated day with the draft (S6), if the server has it on
        if (v.data.simulation) {
          v.sim.checkDepot = depot.id
          if (v.sim.on) fn.simulate(); else fn.setSimulation(true)
        }
        const other = (issue: string) => !issue.startsWith("SHORT_TURNAROUND ") && !v.isOrderIssue(issue)
        // Planned with the depot's operating lines, as the game plans them; the draft is this depot's part
        const byLine = result.byLine ? tr("按运营线路 {0} 和 {1} 个车厂一起排，这里是本车厂的部分；应用后线路上其他自动排的车厂会把剩下的班次重排。", result.lineNames, result.lineDepots) + (result.uncovered && result.needed > result.available ? tr("跑满需要 {0} 列，现有 {1} 列{2}。", result.needed, result.available, result.suggestedSiding ? tr("，加到 {0}", result.suggestedSiding) : "") : "") : ""
        v.dutyNotice = byLine + tr("已生成：最多同时 {0} 列车在线上。", result.trains) + (result.uncovered ? tr("有 {0} 班没有车可派，存车线放的车不够。", result.uncovered) : "")
          + (result.missingTimes.length ? tr("{0} 还没有各站时刻，没有算进去，打开它的时刻表或刷新车厂路径后再生成。", result.missingTimes.join("、")) : "")
          + result.issues.filter((issue: string) => issue.startsWith("SHORT_TURNAROUND ")).map(fn.shortTurnaroundText).join("")
          + result.issues.filter(v.isOrderIssue).slice(0, 5).map(fn.orderIssueText).join("")
          + (result.issues.some(other) ? tr("其他问题：{0}", result.issues.filter(other).slice(0, 3).join("；")) : "") + tr("先检查一遍，再按“应用”。")
      }
    } catch (e: any) {
      v.dutyNotice = tr("生成失败：") + (e && e.message === "401" ? tr("页面的链接已经失效") : tr("游戏可能已经关闭"))
    }
    fn.renderAll()
  }

  return {
    shiftLaterDialog(s: any) {
      const later = T.laterServices(s)
      ask({
        title: tr("平移班次"), text: [tr("这一班和之后的 {0} 班平移多少？写分钟，或写秒：90s、1:30、-0:45；负数是提前", later.length)],
        input: { label: tr("平移"), value: "5" }, ok: tr("平移"),
        go: value => {
          const shift = T.parseShift(value)
          if (!shift) return tr("写分钟，或写秒：90s、1:30、-0:45；负数是提前")
          editTimetable(s.route, T.shiftLaterEdit(s, shift))
        },
      })
    },
    respaceRange() {
      const run = T.rangeServices(get().v.state, find)
      if (run.length < 2) return
      ask({
        title: tr("调整间隔"), text: [tr("这 {0} 班相邻两班之间隔多少分钟？", run.length)],
        input: { label: tr("间隔"), value: String(Math.round((run[1].start - run[0].start) / 60000) || 5), unit: tr("分钟"), numeric: true }, ok: tr("调整"),
        go: value => {
          const minutes = Number(value)
          if (!(minutes > 0)) return tr("写大于 0 的分钟数")
          // The services chosen now, after any change made while the panel was open
          const now = T.rangeServices(get().v.state, find)
          if (now.length < 2) return
          editTimetable(now[0].route, T.respaceEdit(now, minutes, () => { get().v.state.range = null }))
        },
      })
    },
    clearTable(r: any, index: number) {
      const table = T.timetableOf(get().v.draft, r).tables[index]
      if (!table || !table.services.length) return
      ask({
        title: tr("清空时刻表"), text: [tr("清空“{0}”的 {1} 个班次？改动先放进草稿，应用前都可以放弃。", tableName(table, index), table.services.length)], ok: tr("清空"), danger: true,
        go: () => {
          get().v.state.range = null
          editTimetable(r, T.clearTableEdit(index, tableName))
        },
      })
    },
    deleteLogicalLine(line: X.LogicalLine) {
      ask({
        title: tr("删除逻辑线路"), text: [tr("删除逻辑线路“{0}”？它的轨道不再属于任何逻辑线路，运营线路对它的勾选也去掉。应用前都在草稿里。", line.name)], ok: tr("删除"), danger: true,
        go: () => {
          const { fn, v } = get()
          X.deleteLogicalLine(v.data, v.draft, line.id)
          fn.saveDraft(); fn.renderAll(); fn.renderNetworkTools(); fn.drawNetwork()
        },
      })
    },
    generateDuties(depot: any) {
      const { v } = get()
      const question = v.draft.duties[depot.id] ? tr("用自动生成的运用替换“{0}”草稿里的运用？", shortName(depot.name))
        : Object.values((depot.duties || { sidings: {} }).sidings).some((slots: any) => slots.length) ? tr("用自动生成的运用替换“{0}”现在的运用？应用前都还在草稿里。", shortName(depot.name)) : ""
      if (!question) { generate(depot); return }
      ask({ title: tr("一键生成运用"), text: [question], ok: tr("生成"), go: () => { generate(depot) } })
    },
    replanDialog(depot: any) {
      const hint = get().fn.replanText(depot.shortfall)
      ask({
        title: tr("重新排运用"), text: [tr("按现在的时刻表、存车线和设置，把“{0}”所在规划单元（同一运营线路上自动排运用的车厂）的运用全部重新排一遍。", shortName(depot.name)), ...(hint ? [hint] : [])],
        hints: [tr("现有运用接得上时游戏不会自己重排，免得运用跟着用时的小变化换来换去。重排后哪份运用跑哪几班、车回哪条存车线都可能变。结果在服务端日志和这里的运用里。")],
        ok: tr("重新排"), go: () => { depotRequest("api/replan-duties", depot, tr("已请求重新排“{0}”的运用。", shortName(depot.name))) },
      })
    },
    placeTrainsDialog(depot: any) {
      ask({
        title: tr("按时刻表摆车"), text: [tr("把“{0}”的列车直接放到时刻表现在要求的位置：从今天运营日开始按游戏的规则算到现在，这时候应该在线上的车摆上去，已经发过的班次不再发；原来的车换成算出来的车，乘客和货物不保留，玩家驾驶中的车不动。", shortName(depot.name))],
        hints: [tr("只对开了速度曲线、由组件驱动的车厂有效。用的是游戏里已应用的时刻表，草稿要先应用。结果在游戏聊天栏。")],
        ok: tr("摆车"), go: () => { depotRequest("api/place-trains", depot, tr("已请求“{0}”按时刻表摆车，结果见游戏聊天栏。", shortName(depot.name))) },
      })
    },
    deleteTypeDialog(r: any) {
      const { v } = get()
      const depots = v.data.depots.filter((depot: any) => depot.routes.includes(r.id)).map((depot: any) => "“" + shortName(depot.name) + "”")
      let segments = 0
      v.data.depots.forEach((depot: any) => Object.values(D.dutiesOf(v.draft, depot).sidings).forEach((slots: any) => slots.forEach((slot: any[]) => slot.forEach(day => {
        segments += day.segments.filter((segment: any) => segment.route === r.id).length
      }))))
      const links = v.data.routes.filter((other: any) => other !== r).reduce((count: number, other: any) => count + (other.services || []).filter((s: any) => s.link && s.link.route === r.id).length, 0)
      const notes: string[] = []
      if (depots.length) notes.push(tr("车厂 {0} 跑这个种别，删除后不再跑它，要刷新车厂路径", depots.join("、")))
      if (segments) notes.push(tr("运用里有 {0} 班是它的，这些运用会走不通，要改运用", segments))
      if (links) notes.push(tr("别的种别有 {0} 班直通到它，这些直通会失效", links))
      ask({
        title: tr("删除种别"), text: [tr("删除") + " " + routeTitle(r) + tr("：它的车站、停站设置和全部时刻表都从游戏里删掉，不能恢复。")], list: notes,
        hints: [tr("先放在草稿里，按“应用”才删除。应用之前可以右键取消。这个种别别的没应用的改动也一起丢掉。")], ok: tr("删除"), danger: true,
        go: () => {
          const { fn, v } = get()
          const entry = T.draftEntry(v.draft, r)
          entry.deleted = true
          if (!entry.conflict) delete entry.error
          fn.saveDraft()
          fn.renderAll()
        },
      })
    },
    stationWaitDialog(station: { id: string; name: string }) {
      const { v } = get()
      const settings = (v.data && v.data.networkSettings) || {}
      const drafted = (v.draft.networkSettings && v.draft.networkSettings.stationWaitThresholds) || {}
      const current = station.id in drafted ? drafted[station.id] : (settings.stationWaitThresholds || {})[station.id]
      ask({
        title: tr("等候分界：{0}", shortName(station.name)), text: [tr("在这个车站两班之间等得比这长，列车就离开站台去折返线等。出入库规则里还可以按线路再改。")],
        input: { label: tr("秒（留空用全网的 {0} 秒）", Math.round(X.waitThresholdMillis(v.data, v.draft) / 1000)), value: current === null || current === undefined ? "" : String(Math.round(current / 1000)), numeric: true },
        ok: tr("放进草稿"),
        go: value => {
          const text = value.trim()
          if (text !== "" && !(Number(text) >= 0 && Number(text) <= 3600)) return tr("写 0 到 3600 秒，或者留空")
          const { fn, v } = get()
          X.setStationWait(v.draft, station.id, text === "" ? null : Math.round(Number(text)) * 1000)
          fn.saveDraft()
          fn.renderAll()
        },
      })
    },
    logicalDraftDialog() {
      const { fn, v } = get()
      const net = v.net
      const title = tr("按现在的归属生成逻辑线路初稿")
      if (!net.data || !net.data.owners || !net.data.owners.ready || !net.sectionRails) {
        fn.ensureNetworkFor(net.mode || "TRAIN")
        ask({ title, text: [tr("全网配线的归属还没从服务端读来，稍等几秒再试。")], ok: tr("知道了") })
        return
      }
      const groups = new Map<string, { owners: string[]; rails: number[] }>()
      net.sectionRails.forEach((rails: number[]) => {
        const entry = net.ownerOf.get(rails[0])
        if (!entry || !entry.owners.length) return
        const key = entry.owners.slice().sort().join("\n")
        if (!groups.has(key)) groups.set(key, { owners: entry.owners.slice().sort(), rails: [] })
        groups.get(key)!.rails.push(rails[0])
      })
      if (!groups.size) {
        ask({ title, text: [tr("现在没有任何轨道有归属，没有可以生成的初稿。")], ok: tr("知道了") })
        return
      }
      ask({
        title, text: [tr("按现在算出的归属，把归同一组运营线路的区段放进同一条逻辑线路（共 {0} 条），联络线自己的区段成为标了联络线的逻辑线路；每条运营线路勾上它现在归属的逻辑线路，寻路和现在一样。", groups.size)],
        hints: [tr("放进草稿，应用后改按逻辑线路寻路。之后可以改名、合并、重新划分。")], ok: tr("生成"),
        go: () => {
          const { fn, v } = get()
          const lineIdsOf = new Map<string, string[]>()
          const created: Record<string, any> = {}, railLines: Record<string, string> = {}
          let serial = 0
          groups.forEach(group => {
            const id = String(Date.now() * 1000 + serial++)
            const connecting = group.owners.length === 1 && group.owners[0].startsWith("connecting:")
            const first = v.data.lines.find((each: any) => each.key === group.owners[0])
            created[id] = { name: group.owners.map(fn.ownerText).join(tr("、")), color: connecting ? 0xE8891A : first ? first.color & 0xFFFFFF : 0x888888, connecting }
            group.rails.forEach(index => { railLines[v.netRailKey(index)] = id })
            if (!connecting) group.owners.forEach(owner => {
              if (!lineIdsOf.has(owner)) lineIdsOf.set(owner, [])
              lineIdsOf.get(owner)!.push(id)
            })
          })
          fn.draftSettingsPart("logicalLines", (drafted: any) => Object.assign(drafted, created))
          fn.draftSettingsPart("railLines", (drafted: any) => Object.assign(drafted, railLines))
          fn.draftSettingsPart("lineTracks", (drafted: any) => lineIdsOf.forEach((ids, lineKey) => { drafted[lineKey] = ids }))
        },
      })
    },
    serviceOrderDialog: (s: any, kind: "deploy" | "return", stop: number) => open({ kind: "serviceOrder", serviceId: s.id, order: kind, stop }),
    logicalLineDialog: (line: X.LogicalLine | null, then?: (id: string) => void) => open({ kind: "logicalLine", lineId: line ? line.id : null, then }),
    ownersDialog: (index: number) => open({ kind: "owners", index }),
    lineTracksDialog: (lineKey: string) => open({ kind: "lineTracks", lineKey }),
    finishPicking: () => open({ kind: "finishPicking" }),
    turnOf: (r: any, kind: L.TurnKind) => L.turnOf(get().v.draft, r, kind),
    toggleTurnPlatform(r: any, kind: L.TurnKind, platformId: string) {
      const { fn, v } = get()
      L.toggleTurnPlatform(v.draft, r, kind, platformId)
      T.tidyDraft(v.draft, r.id)
      fn.saveDraft()
    },
    /** A deadhead's menu on the running diagram: how early it leaves, which way and siding, back to the rules */
    deadheadMenu(e: MouseEvent, leg: any) {
      const { fn, v } = get()
      const depot = fn.depotById(leg.depot)
      const siding = depot && depot.sidings.find((o: any) => o.id === leg.siding)
      const slot = siding && (D.dutiesOf(v.draft, depot).sidings[siding.id] || [])[leg.index]
      const day = slot && D.dutyOn(slot, v.state.weekday)
      if (!day) { fn.openMenuAt(e, []); return }
      const canEdit = v.data.canEdit, EXCEPTION = v.EXCEPTION, out = leg.kind === "out"
      const pathKey = out ? "outPath" : "backPath"
      const edit = (change: (edited: any) => string | void) => fn.editDutyException(depot, siding.id, leg.index, change)
      const paths = (X.rulesOf(v.draft, depot).paths || []).filter((path: any) => path.kind === (out ? "out" : "back"))
      const pathItems = [[`${!day[pathKey] || day[pathKey] === "0" ? "✓ " : ""}${tr("最近车站后顺线路方向（默认）")}`, () => edit(edited => {
        edited[pathKey] = "0"
        edited.exceptions = (edited.exceptions || 0) | (out ? EXCEPTION.OUT_PATH : EXCEPTION.BACK_PATH)
      })], ...paths.map((path: any) => [`${day[pathKey] === path.id ? "✓ " : ""}${path.name || path.id}`, () => edit(edited => {
        edited[pathKey] = path.id
        edited.exceptions = (edited.exceptions || 0) | (out ? EXCEPTION.OUT_PATH : EXCEPTION.BACK_PATH)
      })])]
      const items: any[] = []
      if (out) {
        items.push([tr("出库提前量…"), canEdit ? () => {
          const times = fn.deadheadTimes(siding, day).out
          ask({
            title: tr("出库提前量"), text: [tr("比首班早多少出库（秒，不少于出库路线用时 {0} 秒）", Math.round(times.wayOut / 1000))],
            input: { label: tr("提前"), value: String(Math.round(times.lead / 1000)), unit: tr("秒"), numeric: true }, ok: tr("确定"),
            go: value => {
              const seconds = Math.round(Number(value))
              if (!(seconds >= 0)) return tr("写 0 或更多的秒数")
              edit(edited => {
                edited.outLead = Math.max(times.wayOut, seconds * 1000)
                edited.exceptions = (edited.exceptions || 0) | EXCEPTION.OUT_LEAD
                return tr("{0} 改为首班前 {1} 出库（这份运用的例外）", times.number, fn.durationText(edited.outLead))
              })
            },
          })
        } : null])
        items.push([tr("出库路径"), canEdit ? pathItems : null])
        items.push([tr("按规则恢复"), canEdit && day.exceptions & (EXCEPTION.OUT_LEAD | EXCEPTION.OUT_PATH | EXCEPTION.ENTRANCES) ? () => edit(edited => {
          edited.exceptions = (edited.exceptions || 0) & ~(EXCEPTION.OUT_LEAD | EXCEPTION.OUT_PATH | EXCEPTION.ENTRANCES)
          return tr("出库按规则，重新排运用时按规则重算")
        }) : null])
      } else {
        items.push([tr("回库存车线"), canEdit ? D.poolSidings(depot, siding.id).map((other: any) => [`${day.returnSiding === other.id ? "✓ " : ""}${shortName(other.name) || tr("存车线")}`, () => edit(edited => {
          edited.returnSiding = other.id
          edited.exceptions = (edited.exceptions || 0) | EXCEPTION.BACK_SIDING
        })]) : null])
        items.push([tr("回库路径"), canEdit ? pathItems : null])
        items.push([tr("按规则恢复"), canEdit && day.exceptions & (EXCEPTION.BACK_SIDING | EXCEPTION.BACK_PATH) ? () => edit(edited => {
          edited.exceptions = (edited.exceptions || 0) & ~(EXCEPTION.BACK_SIDING | EXCEPTION.BACK_PATH)
          return tr("回库按规则，重新排运用时按规则重算")
        }) : null])
      }
      items.push([tr("选中这份运用"), () => {
        get().v.state.slot = { depot: depot.id, siding: siding.id, index: leg.index }
        get().fn.renderAll()
      }])
      fn.openMenuAt(e, items)
    },
  }
}
