/**
 * The layers' work areas. The views are still the old page's (borrowed with LegacySlot, run by the old code), each with
 * the new page's own tool bar; the tree on the left shows only the layer's own part of the old tree.
 */
import { useCallback, useEffect, useState, type ReactNode } from "react"
import { legacy, renderLegacy, useLegacy, type MenuItem } from "../legacy/engine"
import { LegacySlot } from "../legacy/Slot"
import { menuHost } from "../legacy/engine"
import { tr } from "../i18n"
import type { LayerId } from "../shell/layers"
import { Navigator, type Part } from "./Navigator"
import { Inspector } from "./Inspector"
import { DutyBoard } from "./DutyBoard"

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
      if (layer !== "dispatch") fn.setNetLayer(layer === "owners" ? "owners" : "physical")
      if (layer === "dispatch" && !v.consoleState.open) fn.toggleConsole(true)
      if (layer !== "dispatch" && v.consoleState.open) fn.toggleConsole(false)
    } else if (state.view === "network") {
      state.view = "diagram"
    }
    if (v.data) renderLegacy()
  }, [layer, fn, v])

  if (!v.data) return <p className="p-4 text-[13px] text-dim">{tr("正在读取…")}</p>
  const part = TREE_PART[layer]
  return (
    <div className="flex min-h-0 min-w-0 flex-1">
      {part && <Navigator part={part} />}
      {layer === "physical" || layer === "owners" || layer === "dispatch" ? <NetworkWork /> : layer === "depots" ? <DepotWork /> : <DiagramWork />}
    </div>
  )
}

function NetworkWork() {
  const redraw = useCallback(() => legacy.fn.drawNetwork(), [])
  return <LegacySlot id="view-network" className="flex min-h-0 min-w-0 flex-1 flex-col" onResize={redraw} />
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
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <DiagramTools extra={extra} />
      <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          {showsDiagram && <LegacySlot id="canvas-wrap" className={"flex min-h-0 " + (showsTable ? "basis-1/2" : "flex-1")} style={{ minHeight: 240 }} onResize={redraw} />}
          {showsTable && <LegacySlot id="view-table" className={"scroll-thin min-h-0 overflow-auto border-t border-grid " + (showsDiagram ? "basis-1/2" : "flex-1")} />}
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
  const r = data && (state.route ? data.routeById.get(state.route) : fn.currentLine() && fn.currentLine().routes[0])
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
