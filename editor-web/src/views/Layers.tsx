/**
 * The layers' work areas. The views are still the old page's (borrowed with LegacySlot, run by the old code), each with
 * the new page's own tool bar; the tree on the left shows only the layer's own part of the old tree.
 */
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react"
import { legacy, renderLegacy, useLegacy, type MenuItem } from "../legacy/engine"
import { LegacySlot } from "../legacy/Slot"
import { menuHost } from "../legacy/engine"
import { tr } from "../i18n"
import type { LayerId } from "../shell/layers"
import { Navigator, type Part } from "./Navigator"
import { Inspector } from "./Inspector"
import { DutyBoard } from "./DutyBoard"
import { RunningDiagram } from "./RunningDiagram"
import { SummaryTable } from "./SummaryTable"
import { DispatchConsole } from "./DispatchConsole"
import { PickPanel } from "./PickPanels"
import { OwnershipPanel } from "./OwnershipPanel"
import { NetworkSchematic, type TrainMark } from "./NetworkSchematic"
import { currentLine } from "./now"

/** Which part of the old tree each layer shows (its five sections, in order) */
const TREE_PART: Partial<Record<LayerId, Part>> = { physical: 1, owners: 2, lines: 3, timetable: 4, depots: 5 }

export function LayerWork({ layer }: { layer: LayerId }) {
  useLegacy()
  const { fn, v } = legacy
  // Entering a layer puts the old page in the matching view
  useEffect(() => {
    const state = v.state
    if (layer === "physical" || layer === "owners" || layer === "dispatch") {
      state.view = "network"
      fn.setNetLayer(layer === "owners" ? "owners" : "physical")
      if (layer === "dispatch" && !v.consoleState.open) fn.toggleConsole(true)
      if (layer !== "dispatch" && v.consoleState.open) fn.toggleConsole(false)
    } else if (state.view === "network") {
      state.view = "diagram"
    }
    if (v.data) renderLegacy()
  }, [layer, fn, v])

  // Choosing on the network map (a new line's stations, a depot's way out or back) shows the map whatever the layer
  const picking = Boolean(v.picking || v.wayPick)
  useEffect(() => {
    if (picking || layer === "physical" || layer === "owners" || layer === "dispatch") return
    if (v.state.view === "network") { v.state.view = "diagram"; renderLegacy() }
  }, [picking, layer, v])
  if (!v.data) return <p className="p-4 text-[13px] text-dim">{tr("正在读取…")}</p>
  const part = TREE_PART[layer]
  const floating = picking && layer !== "physical" && layer !== "owners" && layer !== "dispatch"
  return (
    <div className="flex min-h-0 min-w-0 flex-1">
      {part && <Navigator part={part} />}
      {floating && <FloatingMap />}
      {layer === "physical" ? <PhysicalWork /> : layer === "dispatch" ? <DispatchWork /> : layer === "owners" ? <NetworkWork owners /> : layer === "depots" ? <DepotWork /> : <DiagramWork />}
    </div>
  )
}

/** The real map; with the picking panel over it unless the floating window has it beside the map */
function NetworkWork({ owners, panel = true }: { owners: boolean; panel?: boolean }) {
  const redraw = useCallback(() => legacy.fn.drawNetwork(), [])
  const map = (
    <div className="relative flex min-h-0 min-w-0 flex-1 flex-col">
      <LegacySlot id="view-network" className="flex min-h-0 min-w-0 flex-1 flex-col" onResize={redraw} />
      {panel && <PickPanel className="scroll-thin absolute right-3 top-24 z-10 max-h-[70%] w-[280px] overflow-y-auto rounded-lg border border-grid bg-panel" />}
    </div>
  )
  if (!owners) return map
  // The ownership layer: the logical map or the real one, with its brushes and the operating lines' logical lines beside
  return <OwnersWork map={map} />
}

function OwnersWork({ map }: { map: ReactNode }) {
  const [view, setView] = useState<"logical" | "real">(() => { try { return localStorage.getItem("mtr-next-owners-view") === "real" ? "real" : "logical" } catch { return "logical" } })
  const choose = (next: "logical" | "real") => {
    setView(next)
    try { localStorage.setItem("mtr-next-owners-view", next) } catch { /* private window */ }
    if (next === "real") setTimeout(() => legacy.fn.drawNetwork())
  }
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col lg:flex-row">
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <div className="flex shrink-0 items-center gap-3 border-b border-grid bg-panel px-3 py-2">
          <div className="flex h-7 shrink-0 overflow-hidden rounded-md border border-ctl" role="group" aria-label={tr("视图")}>
            {([["logical", tr("逻辑图")], ["real", tr("实际图")]] as const).map(([id, label], i) => (
              <button key={id} type="button" aria-pressed={view === id} onClick={() => choose(id)}
                className={"px-2.5 text-[13px] " + (i ? "border-l border-gridstrong " : "") + (view === id ? "bg-brand font-medium text-brandink" : "bg-panel")}>{label}</button>
            ))}
          </div>
          <span className="text-[12px] text-dim">{view === "logical" ? tr("车站和区间，按逻辑线路着色；画笔点区间") : tr("按游戏坐标画；画笔点轨道，右键改归属和联络线")}</span>
        </div>
        {view === "logical" ? <NetworkSchematic mode="logical" onShowReal={() => choose("real")} /> : map}
      </div>
      <OwnershipPanel className="scroll-thin max-h-[40%] shrink-0 overflow-y-auto border-t border-grid bg-panel lg:max-h-none lg:w-[300px] lg:border-l lg:border-t-0" />
    </div>
  )
}

/**
 * The network map in a small window over the layer, while something is chosen on it (a new line's stations, a depot's way
 * out or back): moved by its title bar, resized from its corner; what is being chosen shows in the map's own panel.
 */
function FloatingMap() {
  const [at, setAt] = useState<{ x: number; y: number } | null>(null)
  const drag = useRef<{ x: number; y: number; left: number; top: number } | null>(null)
  const box = useRef<HTMLDivElement>(null)
  const { fn, v } = legacy
  const cancel = () => { if (v.wayPick) fn.finishWayPick(false); else if (v.picking) fn.stopPicking() }
  return (
    <div ref={box} role="dialog" aria-label={tr("在图上选择")}
      className="fixed z-30 flex flex-col overflow-hidden rounded-lg border border-gridstrong bg-panel"
      style={{ boxShadow: "var(--shadow)", resize: "both", width: "min(760px, 92vw)", height: "min(540px, 70vh)", minWidth: 320, minHeight: 240,
        ...(at ? { left: at.x, top: at.y } : { right: 16, bottom: 40 }) }}>
      <div className="flex shrink-0 cursor-move select-none items-center gap-2 border-b border-grid bg-panel2 px-3 py-1.5"
        onPointerDown={e => {
          const r = box.current!.getBoundingClientRect()
          drag.current = { x: e.clientX, y: e.clientY, left: r.left, top: r.top }
          ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
        }}
        onPointerMove={e => { const d = drag.current; if (d) setAt({ x: Math.max(0, d.left + e.clientX - d.x), y: Math.max(0, d.top + e.clientY - d.y) }) }}
        onPointerUp={() => { drag.current = null; fn.drawNetwork() }}>
        <b className="min-w-0 flex-1 truncate text-[13px]">{v.wayPick ? tr("在图上点出入库的轨道") : tr("在图上选站")}</b>
        <button className="btn h-6 px-2 text-[12px]" onPointerDown={e => e.stopPropagation()} onClick={cancel}>{tr("取消")}</button>
      </div>
      <div className="flex min-h-0 flex-1">
        <NetworkWork owners={false} panel={false} />
        <PickPanel className="scroll-thin w-[280px] shrink-0 overflow-y-auto border-l border-grid bg-panel" />
      </div>
    </div>
  )
}

/**
 * The dispatch layer: the trains on the schematic (the default) or on the real map, with the dispatcher's console beside
 * both (the old #net-console, borrowed out of the map). Clicking a train on the schematic chooses it in the console.
 */
function DispatchWork() {
  useLegacy()
  const { fn, v } = legacy
  // The console reads the trains on its own timer and only redraws its canvas: look again every second
  const [, setTick] = useState(0)
  useEffect(() => {
    const timer = setInterval(() => setTick(t => t + 1), 1000)
    return () => clearInterval(timer)
  }, [])
  const [view, setView] = useState<"schematic" | "real">(() => { try { return localStorage.getItem("mtr-next-dispatch-view") === "real" ? "real" : "schematic" } catch { return "schematic" } })
  const choose = (next: "schematic" | "real") => {
    setView(next)
    try { localStorage.setItem("mtr-next-dispatch-view", next) } catch { /* private window */ }
    if (next === "real") setTimeout(() => fn.drawNetwork())
  }
  // The console's trains as marks on the schematic, by the rail their heads are on
  const trains: TrainMark[] = (v.consoleState.data && v.consoleState.data.available ? fn.consoleTrains() : []).map((train: any) => ({
    id: train.id, name: fn.consoleTrainName(train), rail: fn.consoleRail(train.head), late: train.late >= 300, selected: train.id === v.consoleState.selected,
  })).filter((train: TrainMark) => train.rail >= 0)
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col lg:flex-row">
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <div className="flex shrink-0 items-center gap-3 border-b border-grid bg-panel px-3 py-2">
          <div className="flex h-7 shrink-0 overflow-hidden rounded-md border border-ctl" role="group" aria-label={tr("视图")}>
            {([["schematic", tr("示意图")], ["real", tr("实际图")]] as const).map(([id, label], i) => (
              <button key={id} type="button" aria-pressed={view === id} onClick={() => choose(id)}
                className={"px-2.5 text-[13px] " + (i ? "border-l border-gridstrong " : "") + (view === id ? "bg-brand font-medium text-brandink" : "bg-panel")}>{label}</button>
            ))}
          </div>
          <span className="text-[12px] text-dim">{tr("{0} 列车在线上；点列车在右边看它和下命令", trains.length)}</span>
        </div>
        {view === "schematic"
          ? <NetworkSchematic onShowReal={() => choose("real")} trains={trains} onTrain={id => { v.consoleState.selected = v.consoleState.selected === id ? "" : id; fn.renderConsole(); fn.drawNetwork(); fn.renderAll() }} />
          : <LegacySlot id="view-network" className="flex min-h-0 min-w-0 flex-1 flex-col" onResize={() => fn.drawNetwork()} />}
      </div>
      <DispatchConsole className="scroll-thin max-h-[45%] shrink-0 overflow-y-auto border-t border-grid bg-panel lg:max-h-none lg:w-[340px] lg:border-l lg:border-t-0"
        onChoose={id => { v.consoleState.selected = v.consoleState.selected === id ? "" : id; fn.renderConsole(); fn.drawNetwork(); fn.renderAll() }} />
    </div>
  )
}

/** The track layer: the schematic of the whole network (the default), or the real map where boundaries are edited. */
function PhysicalWork() {
  const [view, setView] = useState<"schematic" | "real">(() => { try { return localStorage.getItem("mtr-next-net-view") === "real" ? "real" : "schematic" } catch { return "schematic" } })
  const choose = (next: "schematic" | "real") => {
    setView(next)
    try { localStorage.setItem("mtr-next-net-view", next) } catch { /* private window */ }
    if (next === "real") setTimeout(() => legacy.fn.drawNetwork())
  }
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <div className="flex shrink-0 items-center gap-3 border-b border-grid bg-panel px-3 py-2">
        <div className="flex h-7 shrink-0 overflow-hidden rounded-md border border-ctl" role="group" aria-label={tr("视图")}>
          {([["schematic", tr("示意图")], ["real", tr("实际图")]] as const).map(([id, label], i) => (
            <button key={id} type="button" aria-pressed={view === id} onClick={() => choose(id)}
              className={"px-2.5 text-[13px] " + (i ? "border-l border-gridstrong " : "") + (view === id ? "bg-brand font-medium text-brandink" : "bg-panel")}>{label}</button>
          ))}
        </div>
        <span className="text-[12px] text-dim">{view === "schematic" ? tr("看配线：车站和区间几股道、谁在走") : tr("按游戏坐标画；右键轨道设寻路边界")}</span>
      </div>
      {view === "schematic" ? <NetworkSchematic onShowReal={() => choose("real")} /> : <NetworkWork owners={false} />}
    </div>
  )
}

/** The depot layer: the duty board (the default), or the running diagram where duties can be edited too. */
function DepotWork() {
  const [view, setView] = useState<"board" | "diagram">(() => { try { return localStorage.getItem("mtr-next-depot-view") === "diagram" ? "diagram" : "board" } catch { return "board" } })
  const choose = (next: "board" | "diagram") => { setView(next); try { localStorage.setItem("mtr-next-depot-view", next) } catch { /* private window */ } }
  const tabs = (
    <div className="flex h-7 shrink-0 overflow-hidden rounded-md border border-ctl" role="group" aria-label={tr("视图")}>
      {([["board", tr("运用表")], ["diagram", tr("运行图")]] as const).map(([id, label], i) => (
        <button key={id} type="button" aria-pressed={view === id} onClick={() => choose(id)}
          className={"px-2.5 text-[13px] " + (i ? "border-l border-gridstrong " : "") + (view === id ? "bg-brand font-medium text-brandink" : "bg-panel")}>{label}</button>
      ))}
    </div>
  )
  if (view === "diagram") return <DiagramWork extra={tabs} />
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <div className="flex shrink-0 items-center gap-3 border-b border-grid bg-panel px-3 py-2">{tabs}</div>
      <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
        <DutyBoard />
        <Inspector className="scroll-thin max-h-[40%] shrink-0 overflow-y-auto border-t border-grid lg:max-h-none lg:w-[300px] lg:border-l lg:border-t-0" />
      </div>
    </div>
  )
}

/** The running diagram and the summary timetable, with the inspector beside them. */
function DiagramWork({ extra }: { extra?: ReactNode }) {
  useLegacy()
  const { fn, v } = legacy
  const state = v.state
  const showsDiagram = fn.showsDiagram(), showsTable = fn.showsTable()
  const redraw = useCallback(() => legacy.fn.draw(), [])
  const oldDiagram = v.oldDiagram === true
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <DiagramTools extra={extra} />
      <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          {showsDiagram && (
            <div className={"relative flex min-h-0 " + (showsTable ? "basis-1/2" : "flex-1") + (oldDiagram ? "" : " new-diagram")} style={{ minHeight: 240 }}>
              <LegacySlot id="canvas-wrap" className="flex min-h-0 flex-1" onResize={redraw} />
              {!oldDiagram && <RunningDiagram />}
            </div>
          )}
          {showsTable && (oldDiagram
            ? <LegacySlot id="view-table" className={"scroll-thin min-h-0 overflow-auto border-t border-grid " + (showsDiagram ? "basis-1/2" : "flex-1")} />
            : <SummaryTable className={"scroll-thin min-h-0 overflow-auto border-t border-grid " + (showsDiagram ? "basis-1/2" : "flex-1")} />)}
        </div>
        <Inspector className="scroll-thin max-h-[40%] shrink-0 overflow-y-auto border-t border-grid lg:max-h-none lg:w-[300px] lg:border-l lg:border-t-0" />
      </div>
      {!state.line && <p className="p-4 text-[13px] text-dim">{tr("在左边选一条线路的汇总时刻表")}</p>}
    </div>
  )
}

const Group = ({ label, children }: { label?: string; children: ReactNode }) => (
  <div className="flex shrink-0 items-center gap-1.5 border-l border-grid pl-3 first:border-l-0 first:pl-0">
    {label && <span className="label-sm hidden xl:inline">{label}</span>}
    {children}
  </div>
)

const Toggle = ({ on, onClick, children, title }: { on: boolean; onClick: () => void; children: ReactNode; title?: string }) => (
  <button type="button" aria-pressed={on} title={title} onClick={onClick}
    className={"inline-flex h-[26px] shrink-0 items-center rounded-pill border px-2.5 text-[12px] " + (on ? "border-ink bg-ink text-panel" : "border-ctl bg-panel text-ink hover:border-brand")}>{children}</button>
)

const remember = (key: string, on: boolean) => { try { localStorage.setItem(key, on ? "1" : "0") } catch { /* private window */ } }

/** The new page's tool bar over the diagram: views, what to show, simulation, zoom, and making new things. */
function DiagramTools({ extra }: { extra?: ReactNode }) {
  useLegacy()
  const { fn, v } = legacy
  const state = v.state, data = v.data, sim = v.sim
  const setView = (view: "diagram" | "table" | "split") => {
    if (view === "split") { state.split = true; state.view = state.view === "table" ? "table" : "diagram" }
    else { state.split = false; state.view = view }
    fn.renderAll()
  }
  const split = state.split && fn.canSplit()
  const current = split ? "split" : state.view
  const r = data && (state.route ? data.routeById.get(state.route) : currentLine() && currentLine().routes[0])
  const selected = data && state.route && data.routeById.get(state.route)
  const newMenu = (e: React.MouseEvent) => {
    const box = e.currentTarget.getBoundingClientRect()
    const items: MenuItem[] = [
      [tr("线路（在全网配线图上选站）…"), data && data.canEdit ? () => fn.startPicking() : null],
      [tr("线路（按停站图）…"), data && data.canEdit ? () => fn.newLineDialog() : null],
      [tr("种别…"), r && data.canEdit ? () => fn.newTypeDialog(r.lineKey, r) : null],
      [tr("时刻表…"), r ? () => fn.tableDialog(r, -1) : null],
      [tr("直通关系…"), data && data.routes.length > 1 ? () => fn.throughDialog(r) : null],
    ]
    menuHost.current?.open(box.left, box.bottom + 4, items)
  }
  return (
    <div className="scroll-thin flex shrink-0 items-center gap-3 overflow-x-auto border-b border-grid bg-panel px-3 py-2">
      {extra && <Group>{extra}</Group>}
      <Group>
        <div className="flex h-7 shrink-0 overflow-hidden rounded-md border border-ctl" role="group" aria-label={tr("视图")}>
          {([["diagram", tr("运行图")], ["table", tr("汇总时刻表")], ...(fn.canSplit() ? [["split", tr("上下显示")]] : [])] as [string, string][]).map(([view, label], i) => (
            <button key={view} type="button" aria-pressed={current === view} onClick={() => setView(view as "diagram")}
              className={"px-2.5 text-[13px] " + (i ? "border-l border-gridstrong " : "") + (current === view ? "bg-brand font-medium text-brandink" : "bg-panel text-ink")}>{label}</button>
          ))}
        </div>
      </Group>
      <Group label={tr("显示")}>
        <Toggle on={v.showDepots} title={tr("车厂行和出入库回送")} onClick={() => { v.showDepots = !v.showDepots; remember("mtr-editor-show-depots", v.showDepots); fn.renderAll() }}>{tr("显示车厂")}</Toggle>
        <Toggle on={v.showAccess} title={tr("别的线路走同一段轨道的班次，只读")} onClick={() => { v.showAccess = !v.showAccess; remember("mtr-editor-show-access", v.showAccess); fn.renderAll() }}>{tr("显示接入")}</Toggle>
        <Toggle on={v.oldDiagram === true} title={tr("用旧版的画法画运行图和汇总时刻表，对照新画法用；新画法有问题时也可以先切回去")} onClick={() => { v.oldDiagram = !(v.oldDiagram === true); remember("mtr-next-old-diagram", v.oldDiagram); fn.renderAll() }}>{tr("旧版画法")}</Toggle>
        <Toggle on={v.showNoTrain} title={tr("把没有车跑的班次标成红色，有车的变淡")} onClick={() => { v.showNoTrain = !v.showNoTrain; remember("mtr-editor-show-no-train", v.showNoTrain); fn.renderAll() }}>{tr("没车的班次")}</Toggle>
      </Group>
      {data && data.simulation && (
        <Group label={tr("模拟")}>
          <button className={"btn " + (sim.on ? "btn-primary" : "")} aria-pressed={sim.on} onClick={() => fn.setSimulation(!sim.on)} title={tr("按草稿模拟一天，在运行图上叠加实际运行线")}>{tr("模拟运行")}</button>
          {sim.on && <button className="btn" aria-pressed={sim.fromNow} onClick={() => { sim.fromNow = !sim.fromNow; fn.renderSimStatus(); fn.simulate() }}>{tr("从现在开始")}</button>}
          {sim.on && <button className="btn" onClick={() => fn.simulate()} title={tr("重新模拟")} aria-label={tr("重新模拟")}>↻</button>}
          <LegacySlot id="sim-status" className="text-[12px]" />
        </Group>
      )}
      <Group label={tr("缩放")}>
        <button className="btn" onClick={() => fn.zoom(-1)} aria-label={tr("缩小时间轴")} title={tr("缩小时间轴")}>−</button>
        <button className="btn" onClick={() => fn.zoom(1)} aria-label={tr("放大时间轴")} title={tr("放大时间轴")}>＋</button>
      </Group>
      <Group>
        <button className="btn" onClick={newMenu} aria-haspopup="menu">{tr("新建 ▾")}</button>
        <button className="btn" disabled={!selected || !data.canEdit} onClick={() => (fn.isDeleted(selected) ? fn.keepType(selected) : fn.deleteTypeDialog(selected))}
          title={selected ? "" : tr("先选中一个种别")}>{selected && fn.isDeleted(selected) ? tr("取消删除") : tr("删除种别")}</button>
      </Group>
    </div>
  )
}
