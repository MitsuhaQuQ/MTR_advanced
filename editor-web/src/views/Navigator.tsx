/**
 * The layer's navigator on the left: the new design of the old tree (renderTree in editor.js), one layer's part at a
 * time. It is drawn by React into the old #tree element, and each row keeps the old node's class and data attributes,
 * so the old page's handlers on #tree still do what a click, a key or a right-click on it does (onTreeClick, the tree's
 * menus); only the look and the layout are new.
 */
import { useLayoutEffect, type ReactNode } from "react"
import { createPortal } from "react-dom"
import { legacy, useLegacy } from "../legacy/engine"
import { LegacySlot } from "../legacy/Slot"
import { legacyElement } from "../legacy/stage"
import { tr } from "../i18n"
import { topologyOf } from "../core/topology"

export type Part = 1 | 2 | 3 | 4 | 5

export function Navigator({ part }: { part: Part }) {
  const tree = legacyElement("tree")!
  // Out of the old tree's styles: the rows are styled here
  useLayoutEffect(() => { tree.className = "nav-tree" }, [tree])
  return (
    <aside className="hidden w-[288px] shrink-0 flex-col border-r border-grid bg-bg md:flex" aria-label={tr("导航")}>
      <LegacySlot id="tree" className="scroll-thin min-h-0 flex-1 overflow-y-auto px-2 py-2" />
      {createPortal(<Part part={part} />, tree)}
    </aside>
  )
}

const Heading = ({ children, action }: { children: ReactNode; action?: ReactNode }) => (
  <div className="flex items-center justify-between gap-2 px-2 pb-1.5 pt-1">
    <h2 className="m-0 text-[12px] font-medium text-dim">{children}</h2>
    {action}
  </div>
)

const Swatch = ({ color, wide }: { color: number; wide?: boolean }) => (
  <span className={"inline-block shrink-0 rounded-[2px] " + (wide ? "h-1 w-3.5" : "h-3 w-3")} style={{ background: legacy.fn.colorOf(color) }} />
)

const Count = ({ children, title, tone }: { children: ReactNode; title?: string; tone?: "warn" | "caution" }) => (
  <span title={title} className={"num ml-auto shrink-0 whitespace-nowrap pl-2 text-[11px] " + (tone === "warn" ? "text-warn" : tone === "caution" ? "text-caution" : "text-dim")}>{children}</span>
)

const Dot = ({ title, error }: { title: string; error?: boolean }) => (
  <span title={title} className={"inline-flex h-4 min-w-4 shrink-0 items-center justify-center rounded-pill px-1 text-[10px] font-bold " + (error ? "tint-warn text-warn" : "tint-sel text-sel")}>{error ? "!" : "•"}</span>
)

/** Old HTML already escaped by the old code (problemDot, lockMark): shown as it is. */
const Raw = ({ html }: { html: string }) => (html ? <span className="nav-raw shrink-0" dangerouslySetInnerHTML={{ __html: html }} /> : null)

type RowProps = { attrs: Record<string, string>; depth?: number; caret?: "open" | "closed"; active?: boolean; title?: string; children: ReactNode; muted?: boolean }
/** One row: the old ".node" with its data attributes, the old handlers find it by them. */
function Row({ attrs, depth = 0, caret, active, title, children, muted }: RowProps) {
  const data = Object.fromEntries(Object.entries(attrs).map(([key, value]) => ["data-" + key, value]))
  return (
    <div {...data} className={"node flex h-8 cursor-pointer select-none items-center gap-2 rounded-md pr-2 text-[13px] outline-none focus-visible:ring-2 focus-visible:ring-sel "
      + (active ? "bg-brand text-brandink" : (muted ? "text-dim " : "") + "hover:bg-panel2")}
      tabIndex={0} title={title} style={{ paddingLeft: 8 + depth * 16 }} aria-expanded={caret ? caret === "open" : undefined} aria-current={active ? "true" : undefined}>
      <span className={"caret w-3 shrink-0 text-[10px] transition-transform " + (active ? "" : "text-dim ") + (caret === "open" ? "rotate-90" : "")}>{caret ? "▶" : ""}</span>
      {children}
    </div>
  )
}

function Part({ part }: { part: Part }) {
  useLegacy()
  const { v } = legacy
  if (!v.data) return <p className="px-2 text-[13px] text-dim">{tr("正在读取…")}</p>
  switch (part) {
    case 1: return <Physical />
    case 2: return <Owners />
    case 3: return <OperatingLines />
    case 4: return <Timetables />
    case 5: return <Depots />
  }
}

const onNetwork = (layer: string) => legacy.v.state.view === "network" && legacy.v.net.layer === layer

function RouteLabel({ r }: { r: any }) {
  const { fn } = legacy
  const draft = fn.draftOf(r)
  return (
    <>
      <Swatch color={r.color} wide />
      <span title={fn.routeTitle(r)} className={"min-w-0 truncate " + (fn.isDeleted(r) ? "line-through opacity-60" : "")}>{fn.routeTitle(r)}</span>
      {fn.isDeleted(r) ? <Dot error={Boolean(draft.error)} title={draft.error || tr("将删除：按“应用”后从游戏里删掉，右键取消")} /> : draft ? <Dot title={tr("有没应用的草稿")} /> : null}
      <ProblemDot r={r} />
    </>
  )
}

/** A mark for a route whose tracks don't run along the rails (old problemDot): red for errors, amber for warnings */
function ProblemDot({ r }: { r: any }) {
  const { fn, v } = legacy
  const problems = topologyOf(v.data?.topology).routeProblems(r, fn.platformsOf(r))
  if (!problems.length) return null
  const level = problems.some(p => p.level === "error") ? "error" : "warn"
  return <span className={"nav-raw shrink-0"}><span className={"problem-dot " + level} title={problems.map(p => p.text).join("；")}>!</span></span>
}

function Created({ made, title, depth }: { made: any; title: string; depth: number }) {
  return (
    <Row attrs={{ created: made.key }} depth={depth}>
      <Swatch color={made.color} wide />
      <span className="min-w-0 truncate">＋ {legacy.fn.shortName(made.name)}</span>
      <Dot error={Boolean(made.error)} title={made.error || title} />
    </Row>
  )
}

// ---------- 1. The physical track ----------
function Physical() {
  return (
    <>
      <Heading>{tr("物理线路")}</Heading>
      <Row attrs={{ layer: "physical" }} active={onNetwork("physical")} title={tr("轨道、寻路边界、留置位；右键轨道设寻路边界")}>
        <span className="truncate">{tr("全网配线")}</span>
      </Row>
      <p className="mx-2 mt-3 text-[12px] leading-[18px] text-dim">{tr("右键轨道设寻路边界；出入库规则在车厂层里设。")}</p>
    </>
  )
}

// ---------- 2. Logical ownership ----------
function Owners() {
  const { fn, v } = legacy
  const data = v.data, state = v.state
  const lines = [...fn.logicalLinesShown().values()].sort((a: any, b: any) => a.name.localeCompare(b.name))
  const drafted = fn.draftSettings().logicalLines || {}
  const connecting = data.routes.filter(fn.isConnecting).sort((a: any, b: any) => fn.routeTitle(a).localeCompare(fn.routeTitle(b)))
  return (
    <>
      <Heading action={data.canEdit && <Row attrs={{ "new-logical": "1" }}><span className="text-brand">{tr("＋ 新建逻辑线路…")}</span></Row>}>{tr("逻辑归属")}</Heading>
      <Row attrs={{ layer: "owners" }} active={onNetwork("owners") && !state.route && !v.net.focusLogical} title={tr("每段轨道属于哪条逻辑线路；右键轨道划入或移出")}>
        <span className="truncate">{tr("归属图")}</span>
      </Row>
      {lines.length ? lines.map((line: any) => (
        <Row key={line.id} attrs={{ logical: line.id }} depth={1} active={onNetwork("owners") && v.net.focusLogical === line.id} title={tr("点开在归属图上看它；右键改名、改颜色、设为联络线或删除")}>
          <Swatch color={line.color} />
          <span className="min-w-0 truncate">{line.name}</span>
          {line.id in drafted && <Dot title={tr("有没应用的草稿")} />}
          {line.connecting && <Count tone="caution">{tr("联络线")}</Count>}
        </Row>
      )) : (
        <>
          <p className="mx-2 my-2 text-[12px] leading-[18px] text-dim">{tr("还没有逻辑线路：现在按寻路边界和站台自动算归属")}</p>
          {data.canEdit && <Row attrs={{ "generate-logical": "1" }} depth={1}><span className="text-brand">{tr("按现在的归属生成初稿…")}</span></Row>}
          {connecting.length > 0 && <Heading>{tr("联络线")}</Heading>}
          {connecting.map((r: any) => <Row key={r.id} attrs={{ connecting: r.id }} depth={1} active={onNetwork("owners") && state.route === r.id}><RouteLabel r={r} /></Row>)}
        </>
      )}
    </>
  )
}

// ---------- 3. Operating lines ----------
function OperatingLines() {
  const { fn, v } = legacy
  const data = v.data, state = v.state, draft = v.draft
  const draftLines = new Map<string, any[]>()
  draft.created.filter((made: any) => !made.source && !data.lines.some((l: any) => l.key === made.lineKey)).forEach((made: any) => {
    if (!draftLines.has(made.lineKey)) draftLines.set(made.lineKey, [])
    draftLines.get(made.lineKey)!.push(made)
  })
  return (
    <>
      <Heading action={data.canEdit && <button type="button" data-new-line className="rounded-sm px-1.5 py-0.5 text-[12px] text-brand hover:bg-panel2">{tr("＋ 新建线路")}</button>}>{tr("运营线路")}</Heading>
      {!data.lines.length && <p className="mx-2 text-[13px] text-dim">{tr("没有线路")}</p>}
      {data.lines.map((line: any) => {
        const open = state.openNodes.has("oline:" + line.key)
        return (
          <div key={line.key}>
            <Row attrs={{ line: line.key, section: "lines" }} caret={open ? "open" : "closed"}>
              <Swatch color={line.color} />
              <span className="min-w-0 truncate font-medium">{fn.shortName(line.name)}</span>
              <Count>{tr("{0} 个种别", line.routes.length)}</Count>
            </Row>
            {open && line.routes.map((r: any) => (
              <Row key={r.id} attrs={{ route: r.id, section: "lines" }} depth={1} title={tr("点开编辑停站；右键更多")}>
                <RouteLabel r={r} />
                <Count>{tr("{0} 站", r.stops.length)}</Count>
              </Row>
            ))}
            {open && draft.created.filter((made: any) => made.lineKey === line.key).map((made: any) => <Created key={made.key} made={made} depth={1} title={tr("新建的种别，应用后才在游戏里出现；右键删除")} />)}
          </div>
        )
      })}
      {[...draftLines.values()].map(list => (
        <div key={list[0].lineKey}>
          <Row attrs={{}} caret="open" muted>
            <Swatch color={list[0].color} />
            <span className="min-w-0 truncate font-medium">{tr("（草稿）{0}", list[0].lineName)}</span>
          </Row>
          {list.map(made => <Created key={made.key} made={made} depth={1} title={tr("新建的线路，应用后才在游戏里出现；右键删除")} />)}
        </div>
      ))}
    </>
  )
}

// ---------- 4. Timetables and diagrams ----------
function Timetables() {
  const { fn, v } = legacy
  const data = v.data, state = v.state
  return (
    <>
      <Heading>{tr("时刻表和交路")}</Heading>
      {data.lines.map((line: any) => {
        const open = state.openNodes.has("line:" + line.key) || line.key === state.line
        const count = line.routes.reduce((a: number, r: any) => a + r.services.length, 0)
        const lineView = (view: string) => state.line === line.key && !state.route && state.view === view
        return (
          <div key={line.key}>
            <Row attrs={{ line: line.key }} caret={open ? "open" : "closed"}>
              <Swatch color={line.color} />
              <span className="min-w-0 truncate font-medium">{fn.shortName(line.name)}</span>
              <Count title={tr("这一天的班次")}>{count}</Count>
            </Row>
            {open && (
              <>
                <ViewChips depth={1} attrs={{ line: line.key }} table={lineView("table")} diagram={lineView("diagram")} />
                {line.routes.map((r: any) => {
                  const routeOpen = state.openNodes.has("route:" + r.id) || state.route === r.id
                  return (
                    <div key={r.id}>
                      <Row attrs={{ route: r.id }} depth={1} caret={routeOpen ? "open" : "closed"}>
                        <RouteLabel r={r} />
                        <Count>{r.services.length}</Count>
                      </Row>
                      {routeOpen && <ViewChips depth={2} attrs={{ route: r.id }} table={state.route === r.id && state.view === "table"} diagram={state.route === r.id && state.view === "diagram"} />}
                    </div>
                  )
                })}
              </>
            )}
          </div>
        )
      })}
    </>
  )
}

/** The two views of a line or a route, side by side instead of two rows. */
function ViewChips({ depth, attrs, table, diagram }: { depth: number; attrs: Record<string, string>; table: boolean; diagram: boolean }) {
  const chip = (view: "table" | "diagram", on: boolean, label: string) => (
    <div {...Object.fromEntries(Object.entries({ ...attrs, view }).map(([k, value]) => ["data-" + k, value]))} tabIndex={0} aria-current={on ? "true" : undefined}
      className={"node inline-flex h-6 cursor-pointer items-center rounded-pill border px-2.5 text-[12px] outline-none focus-visible:ring-2 focus-visible:ring-sel "
        + (on ? "border-brand bg-brand text-brandink" : "border-ctl text-ink hover:border-brand")}>{label}</div>
  )
  return (
    <div className="flex gap-1.5 py-1" style={{ paddingLeft: 28 + depth * 16 - 16 }}>
      {chip("diagram", diagram, tr("运行图"))}
      {chip("table", table, tr("汇总时刻表"))}
    </div>
  )
}

// ---------- 5. Depots ----------
function Depots() {
  const { fn, v } = legacy
  const data = v.data, state = v.state, draft = v.draft
  return (
    <>
      <Heading>{tr("车厂")}</Heading>
      {!data.depots.length && <p className="mx-2 text-[13px] text-dim">{tr("没有车厂")}</p>}
      {data.depots.map((depot: any) => {
        const open = state.openNodes.has("depot:" + depot.id)
        const duties = fn.dutiesOf(depot)
        const dutyDraft = draft.duties[depot.id]
        const short = duties.auto && depot.shortfall && depot.shortfall.uncovered > 0
        const pools = (depot.pools || []).length ? depot.pools : [{ key: "", vehicle: "", fleet: null, sum: -1, sidings: depot.sidings.map((s: any) => s.id) }]
        return (
          <div key={depot.id}>
            <Row attrs={{ depot: depot.id }} caret={open ? "open" : "closed"}>
              <span className="min-w-0 truncate font-medium">{fn.shortName(depot.name)}</span>
              <Raw html={fn.lockMark(depot)} />
              {dutyDraft && <Dot error={Boolean(dutyDraft.error)} title={dutyDraft.error || tr("运用有没应用的草稿")} />}
              <Count tone={short ? "warn" : undefined} title={short ? fn.shortfallText(depot.shortfall) : duties.auto && depot.shortfall && depot.shortfall.replan ? fn.replanText(depot.shortfall) : undefined}>
                {duties.auto ? tr("自动运用") : duties.mode === "diagram" ? tr("按运用图") : tr("自动排班")}
                {short ? " · " + (fn.missingTrains(depot.shortfall) < 0 ? tr("缺车") : tr("缺 {0} 列", fn.missingTrains(depot.shortfall))) : ""}
                {duties.auto && depot.shortfall && depot.shortfall.replan ? " · " + tr("建议重排") : ""}
              </Count>
            </Row>
            {open && pools.map((pool: any) => {
              const draftedFleet = draft.depots?.[depot.id]?.poolFleets ? draft.depots[depot.id].poolFleets[pool.key] : undefined
              const poolSidings = (pool.sidings || []).map((id: string) => depot.sidings.find((s: any) => s.id === id)).filter(Boolean)
              const count = poolSidings.reduce((sum: number, siding: any) => sum + (duties.sidings[siding.id] || []).length, 0)
              const entry = draft.depots?.[depot.id]
              const vehicleDrafted = entry && (entry.vehicleSettings && entry.vehicleSettings[pool.key] !== undefined && !fn.sameVehicle(entry.vehicleSettings[pool.key], pool.vehicleSettings) || entry.serviceTypes && entry.serviceTypes[pool.vehicle] !== undefined)
              let number = 0
              return (
                <div key={pool.key}>
                  <Row attrs={{ pool: pool.key, "of-depot": depot.id }} depth={1} title={tr("右键“车型设置…”改加减速、最高速度、手动驾驶和能跑的种别")}>
                    <span className="min-w-0 truncate">{tr("车型 {0}", pool.vehicle || pool.key || "?")}</span>
                    {vehicleDrafted && <Dot title={tr("有没应用的草稿")} />}
                    <Count>{fn.poolFleetText(draftedFleet !== undefined ? draftedFleet : pool.fleet, pool.sum)} · {tr("{0} 份运用", count)}</Count>
                  </Row>
                  {poolSidings.flatMap((siding: any) => (duties.sidings[siding.id] || []).map((slot: any, index: number) => {
                    number++
                    const day = fn.dutyOn(slot, state.weekday)
                    const active = state.slot && state.slot.depot === depot.id && state.slot.siding === siding.id && state.slot.index === index
                    const problems = day ? fn.dutyProblems(depot, siding.id, index, state.weekday).length : 0
                    return (
                      <Row key={siding.id + "/" + index} attrs={{ slot: String(index), siding: siding.id, "of-depot": depot.id }} depth={2} active={active}>
                        <span className="min-w-0 truncate">{tr("运用 {0}", number)} <span className={active ? "" : "text-dim"}>{fn.startText(slot, siding)}</span></span>
                        {problems > 0 && <Dot error title={tr("运用有问题")} />}
                        <Count>{day ? day.segments.length + tr(" 班") + (day.stay ? tr(" 留") : "") : tr("休")}</Count>
                      </Row>
                    )
                  }))}
                  {!count && <p className="my-1 text-[12px] text-dim" style={{ paddingLeft: 56 }}>{tr("没有运用")}</p>}
                </div>
              )
            })}
            {open && (
              <>
                <p className="mb-0.5 mt-2 text-[12px] font-medium text-dim" style={{ paddingLeft: 32 }}>{tr("存车线")}</p>
                {!depot.sidings.length && <p className="my-1 text-[12px] text-dim" style={{ paddingLeft: 40 }}>{tr("没有存车线")}</p>}
                {depot.sidings.map((siding: any) => {
                  const drafted = draft.depots?.[depot.id]?.sidings?.[siding.id]
                  const fleet = drafted ? drafted.fleet : siding.fleet
                  const given = drafted ? drafted.routes : siding.routes || []
                  const givenText = given.length ? tr("只跑：{0}", given.map((id: string) => (data.routeById.get(id) ? fn.routeTitle(data.routeById.get(id)) : "?")).join("、")) : tr("跑车厂的全部线路")
                  return (
                    <Row key={siding.id} attrs={{ siding: siding.id, "of-depot": depot.id }} depth={1}>
                      <span className="min-w-0 truncate">{fn.shortName(siding.name) || tr("存车线")}</span>
                      <Count title={givenText}>{fleet < 0 ? tr("停车不限") : tr("最多停 {0} 列", fleet)}{given.length ? " · " + tr("{0} 条线路", given.length) : ""}</Count>
                    </Row>
                  )
                })}
              </>
            )}
          </div>
        )
      })}
    </>
  )
}
