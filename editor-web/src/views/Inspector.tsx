/**
 * The inspector beside the diagram: the new design of the old renderInspector, drawn by React into the old #inspector
 * element. The selected service, the line and its track problems, and the hints are new components; the panels made of
 * forms the old handlers on #inspector work with (a duty being edited, picking stations, choosing a depot's way, the
 * simulation's problems, a loop's runs) are the old code's HTML as it is, so those handlers still find their
 * data attributes.
 */
import { useEffect, type ReactNode } from "react"
import { createPortal } from "react-dom"
import { legacy, useLegacy } from "../legacy/engine"
import { LegacySlot } from "../legacy/Slot"
import { legacyElement } from "../legacy/stage"
import { tr } from "../i18n"

export function Inspector({ className }: { className: string }) {
  const element = legacyElement("inspector")!
  return (
    <>
      <LegacySlot id="inspector" className={className} />
      {createPortal(<Content />, element)}
    </>
  )
}

/** The old code's HTML for a panel, its data attributes kept for its handlers. */
const Raw = ({ html }: { html: string }) => (html ? <div className="inspector-raw flex flex-col gap-3" dangerouslySetInnerHTML={{ __html: html }} /> : null)

const Section = ({ title, children, aside }: { title: string; children: ReactNode; aside?: ReactNode }) => (
  <section className="flex flex-col gap-2">
    <div className="flex items-center justify-between gap-2">
      <h3 className="m-0 text-[12px] font-medium text-dim">{title}</h3>
      {aside}
    </div>
    {children}
  </section>
)

const Card = ({ children }: { children: ReactNode }) => <div className="flex flex-col gap-2 rounded-lg border border-grid bg-panel p-3">{children}</div>

const Swatch = ({ color }: { color: number }) => <span className="inline-block h-1 w-3.5 shrink-0 rounded-[2px]" style={{ background: legacy.fn.colorOf(color) }} />

function Content() {
  useLegacy()
  const { fn, v } = legacy
  const data = v.data, state = v.state
  // The network view has no inspector: a depot's way being chosen shows over its map (as the old renderInspector did)
  useEffect(() => {
    const netPanel = legacyElement("net-waypick")
    if (!netPanel) return
    const show = Boolean(v.wayPick && data && state.view === "network")
    netPanel.hidden = !show
    netPanel.innerHTML = show ? fn.wayPickPanel() : ""
  })
  if (!data) return null
  const s = state.selected ? fn.findService(state.selected) : null
  const loopRoute = s && fn.isLoop(s.route) ? s.route : state.route && data.routeById.get(state.route) && fn.isLoop(data.routeById.get(state.route)) ? data.routeById.get(state.route) : null
  const line = fn.currentLine()
  return (
    <>
      {v.picking && <Raw html={fn.pickingPanel()} />}
      {v.wayPick && <Raw html={fn.wayPickPanel()} />}
      {state.slot && <Raw html={fn.dutyPanel()} />}
      {s && <Selected s={s} />}
      {v.sim.on && <Raw html={fn.simPanel(line)} />}
      {loopRoute && <Raw html={fn.loopPanel(loopRoute)} />}
      {line && <Line line={line} />}
      <Hints />
    </>
  )
}

/** The service selected on the diagram or in the table: what it is, how it goes on, and its times at each stop. */
function Selected({ s }: { s: any }) {
  const { fn, v } = legacy
  const data = v.data
  const next = s.link && data.routeById.get(s.link.route)
  const times = fn.timesOf(s)
  const noTrain = !s.suspended && fn.hasNoTrain(s)
  const facts: [string, ReactNode][] = [
    [tr("班次"), <span className="num">{fn.clock(s.start)}</span>],
    [tr("表"), s.table.name || tr("表 ") + (s.tableIndex + 1)],
  ]
  if (s.link) facts.push([tr("接续"), <>{next ? fn.routeTitle(next) : "?"} <span className="num">{fn.clock(fn.mod(s.link.toService - data.dayStartMillis, v.DAY))}</span></>])
  if (s.returns) facts.push([tr("之后"), tr("回库")])
  return (
    <Section title={tr("选中的班次")} aside={<button type="button" className="link text-[12px] text-sel hover:underline" onClick={() => { legacy.fn.openView("table", s.route.lineKey, s.route.id) }}>{tr("在时刻表里看")}</button>}>
      <Card>
        <div className="flex items-center gap-2 text-[14px] font-semibold"><Swatch color={s.route.color} /><span className="min-w-0 truncate" title={fn.routeTitle(s.route)}>{fn.routeTitle(s.route)}</span></div>
        <div className="flex flex-wrap gap-1.5">
          {s.suspended && <Tag tone="plain">{tr("停运")}</Tag>}
          {noTrain && <Tag tone="warn">{tr("没有车跑这一班")}</Tag>}
          {s.exceptions && <Tag tone="sel">{tr("有只改这一班的例外")}</Tag>}
          {fn.draftOf(s.route) && <Tag tone="sel">{tr("有没应用的草稿")}</Tag>}
        </div>
        <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[13px]">
          {facts.map(([k, value]) => <div key={k} className="contents"><dt className="text-dim">{k}</dt><dd className="m-0 min-w-0">{value}</dd></div>)}
        </dl>
        <ol className="m-0 flex list-none flex-col p-0">
          {s.route.stops.map((stop: any, i: number) => {
            const pass = s.plans[i].passes, exception = s.exceptions && s.exceptions.get(i)
            const arrival = i === 0 ? "" : fn.clock(times[i][0], true), departure = i === times.length - 1 ? "" : pass ? "↓" : fn.clock(times[i][1], true)
            return (
              <li key={i} className="grid grid-cols-[14px_1fr_auto_auto] items-center gap-x-2 py-0.5 text-[12px]">
                <span className="relative flex h-5 justify-center">
                  <span className="absolute w-0.5" style={{ background: legacy.fn.colorOf(s.route.color), top: i === 0 ? "50%" : 0, bottom: i === s.route.stops.length - 1 ? "50%" : 0 }} />
                  <span className={"relative mt-[6px] h-2 w-2 rounded-full border-2 " + (pass ? "bg-panel" : "")} style={{ borderColor: legacy.fn.colorOf(s.route.color), background: pass ? undefined : legacy.fn.colorOf(s.route.color) }} />
                </span>
                <span className={"min-w-0 truncate " + (pass ? "text-dim" : "")}>{fn.shortName(stop.name)}</span>
                <span className={"num " + (exception ? "text-sel" : pass ? "text-dim" : "")}>{arrival}</span>
                <span className={"num " + (exception ? "text-sel" : pass ? "text-dim" : "")}>{departure}</span>
              </li>
            )
          })}
        </ol>
      </Card>
    </Section>
  )
}

const Tag = ({ tone, children, title }: { tone: "warn" | "sel" | "plain" | "caution"; children: ReactNode; title?: string }) => (
  <span title={title} className={"inline-flex h-5 items-center whitespace-nowrap rounded-pill px-2 text-[12px] font-medium " + { warn: "tint-warn text-warn", sel: "tint-sel text-sel", plain: "bg-panel2 text-dim", caution: "tint-caution text-caution" }[tone]}>{children}</span>
)

/** The line shown: each route with its services today and how many have a train; then tracks off the rails. */
function Line({ line }: { line: any }) {
  const { fn, v } = legacy
  const data = v.data, state = v.state
  const covered = fn.coverageNow().routes
  const troubled = line.routes.map((r: any) => [r, fn.routeProblems(r)]).filter(([, problems]: any) => problems.length)
  return (
    <>
      <Section title={tr("线路")}>
        <Card>
          <div className="flex items-center gap-2 text-[14px] font-semibold"><span className="inline-block h-3 w-3 rounded-[2px]" style={{ background: fn.colorOf(line.color) }} />{fn.shortName(line.name)}</div>
          <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
            {line.routes.map((r: any) => {
              const all = (r.services || []).filter((x: any) => (x.table.weekdays >> state.weekday) & 1), today = all.filter((x: any) => !x.suspended)
              const without = today.filter(fn.hasNoTrain).length, suspended = all.length - today.length
              const depots = data.depots.filter((depot: any) => (depot.routes || []).includes(r.id) && depot.shortfall && depot.shortfall.uncovered > 0)
              return (
                <li key={r.id} className="flex flex-col gap-0.5">
                  <span className="flex items-center gap-2 text-[13px]"><Swatch color={r.color} /><span className="min-w-0 truncate" title={fn.routeTitle(r)}>{fn.routeTitle(r)}</span><span className="num ml-auto shrink-0 text-[12px] text-dim">{tr("{0} 班", r.services.length)}</span></span>
                  <span className="flex flex-wrap gap-1 pl-5">
                    {!covered.has(r.id)
                      ? <Tag tone="plain" title={tr("这条线路还没有车厂的运用（车厂没选这条线路，或自动排班还没生成），看不出哪班有车")}>{tr("看不出哪班有车")}</Tag>
                      : <Tag tone={without ? "warn" : "plain"} title={depots.map((depot: any) => fn.shortName(depot.name) + "：" + fn.shortfallText(depot.shortfall)).join("\n") || undefined}>{tr("有车 {0} · 没车 {1}", today.length - without, without)}</Tag>}
                    {suspended > 0 && <Tag tone="plain">{tr("（停运 {0}）", suspended)}</Tag>}
                  </span>
                </li>
              )
            })}
          </ul>
        </Card>
      </Section>
      {troubled.length > 0 && (
        <Section title={tr("股道问题")}>
          <Card>
            {troubled.map(([r, problems]: any) => (
              <div key={r.id} className="flex flex-col gap-1">
                <span className="flex items-center gap-2 text-[13px] font-medium"><Swatch color={r.color} />{fn.routeTitle(r)}</span>
                <ul className="m-0 flex flex-col gap-0.5 pl-5 text-[12px] leading-[18px]">
                  {problems.map((p: any, i: number) => <li key={i} className={p.level === "error" ? "text-warn" : "text-caution"}>{p.text}</li>)}
                </ul>
              </div>
            ))}
            <p className="m-0 text-[12px] text-dim">{tr("右键线路“编辑停站…”在连通图上改股道。")}</p>
          </Card>
        </Section>
      )}
    </>
  )
}

/** How the diagram and the table are worked, folded away once read. */
function Hints() {
  const items = [
    tr("滚轮缩放时间轴，拖空白处平移，Shift + 滚轮上下移动。"),
    tr("按住一条线左右拖，平移这一班；Shift 点选一段、Ctrl 点选加入或剔除一班（Ctrl+Shift 加入或剔除一段），拖其中任一条一起平移；按住 Alt 拖按秒取整。右键一条线可以选中它和之后的全部班次。"),
    tr("单击一条线选中这一班，拖它停站末端的方块只改这一班（例外，蓝色）。双击选中同一种别同一方向的全部班次，拖方块改整张表的停站时间。"),
    tr("按运用跑的车厂：线路色较粗的线有车跑，灰色细线没有车；顶栏“没车的班次”把没车的标成红色。"),
    tr("时刻表里点格子直接输入，回车只改这一班，Shift+回车连之后的全部班次一起改；右键可以设通过、待避、推到之后的班次；右键班次表头可以把这一班各站的时刻改动整列同步到之后的每一班。改动先存为草稿，点顶栏的“应用”才发给游戏。"),
  ]
  return (
    <details className="rounded-lg border border-grid bg-panel px-3 py-2 text-[12px] leading-[18px] text-dim">
      <summary className="cursor-pointer select-none font-medium text-ink">{tr("怎么操作")}</summary>
      <ul className="mb-0 mt-2 flex flex-col gap-1.5 pl-4">{items.map((text, i) => <li key={i}>{text}</li>)}</ul>
    </details>
  )
}
