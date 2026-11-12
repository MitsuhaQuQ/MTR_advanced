# views/Layers.tsx

各工作层的工作区。视图大多仍是旧页面的（用 `LegacySlot` 借来、由旧代码运行），各配上新页面自己的工具栏；左侧树只显示该层在旧树中对应的部分（`views/Navigator.tsx`）。由 `App.tsx` 渲染（媒体层除外）。大量通过 `legacy.fn` / `legacy.v` 操作旧代码状态（`v.state.view`、`v.state.split`、`v.showDepots` 等）。

## 常量
- `TREE_PART`：层 → 旧树的第几部分：配线 1、归属 2、线路 3、时刻 4、车厂 5（调度、媒体没有）。

## 导出
### `LayerWork({ layer })`（组件）
- props：`layer: LayerId`。
- 进入某层时把旧页面切到对应视图：配线/归属/调度层 `state.view = "network"` 并 `fn.setNetLayer("owners" | "physical")`，调度层打开调度台（`fn.toggleConsole(true)`），其他网络层关闭它；其他层若停在网络视图则切回 `diagram`；有数据时 `renderLegacy()`。
- 正在地图上点选（`v.picking` 或 `v.wayPick`）而当前层不是网络层时，显示浮动地图 `FloatingMap`；不在点选时把网络视图切回 `diagram`。
- 布局：`Navigator`（有树部分时）、`FloatingMap`（需要时），以及按层：`PhysicalWork` / `DispatchWork` / `NetworkWork owners` / `DepotWork` / `DiagramWork`。无数据时"正在读取…"。
- 调用者：`App.tsx:70`。

## 内部组件与函数
- `NetworkWork({ owners, panel = true })`：旧实景地图（`LegacySlot id="view-network"`，尺寸变化时 `fn.drawNetwork()`），`panel` 时右上角叠加 `PickPanel`；`owners` 时交给 `OwnersWork`。
- `OwnersWork({ map })`：归属层：在"逻辑图"（`NetworkSchematic mode="logical"`）与"实际图"（`map`）之间切换（记在 `localStorage` 的 `mtr-next-owners-view`），旁边是 `OwnershipPanel`。
- `FloatingMap()`：点选时浮在层上的小地图窗口：标题栏可拖动（松开时重画地图），右下角可调大小；"取消"调用 `fn.finishWayPick(false)` 或 `fn.stopPicking()`；内含 `NetworkWork`（无叠加面板）和右侧 `PickPanel`。
- `DispatchWork()`：调度层：每秒刷新；"示意图/实际图"切换（`mtr-next-dispatch-view`）；把调度台列车转成 `TrainMark`（`fn.consoleTrains`、`fn.consoleTrainName`、`fn.consoleRail(train.head)`，晚点 ≥300 秒标记）交给 `NetworkSchematic`；点列车或卡片切换 `v.consoleState.selected` 并 `fn.renderConsole()`（只通知重绘）、`fn.drawNetwork()`、`fn.renderAll()`；右侧 `DispatchConsole`。
- `PhysicalWork()`：配线层："示意图"、"实际图"（`NetworkSchematic shape`）和"轨道编辑"（旧实景地图，可右键设寻路边界），选择记在 `mtr-next-net-view`。
- `DepotWork()`：车厂层："运用表"（`DutyBoard` + `Inspector`）或"运行图"（`DiagramWork`），记在 `mtr-next-depot-view`。离开时（`useLayoutEffect` 清理）退出运用分配模式：清 `state.slot`、`selected`、`selectedRoute`、`range`、`v.lastSlotService`、`v.dutyNotice`，关闭"显示车厂"（并写 `mtr-editor-show-depots`）。
- `DiagramWork({ extra })`：时刻层（及车厂层的运行图）：`DiagramTools`；`fn.showsDiagram()` 时显示运行图（借来旧的 `#canvas-wrap` 盒子供旧函数量尺寸，上面叠 `RunningDiagram`），`fn.showsTable()` 时显示 `SummaryTable`；右侧 `Inspector`；没选线路时提示。（"旧版画法"开关和旧表 `view-table` 已在退役第 9 步删除。）
- `Group({ label, children })`：工具栏分组。
- `Toggle({ on, onClick, children, title })`：开关按钮。
- `remember(key, on)`：把开关写进 `localStorage`（"1"/"0"）。
- `DiagramTools({ extra })`：运行图工具栏：
  - 视图：运行图 / 汇总时刻表 / 上下显示（`fn.canSplit()` 时），改 `state.view`、`state.split` 后 `fn.renderAll()`；
  - 显示：显示车厂（`v.showDepots`）、显示接入（`v.showAccess`）、没车的班次（`v.showNoTrain`）；
  - 模拟（服务器允许时）：模拟运行（`fn.setSimulation`）、从现在开始（切换 `sim.fromNow` 后 `fn.renderSimStatus()`、`fn.simulate()`）、重新模拟、旧状态元素 `sim-status`；
  - 缩放：`fn.zoom(-1/1)`；
  - 新建菜单：线路（在全网配线图上选站，`fn.startPicking()`）、线路（按停站图，`fn.newLineDialog()`）、种别（`fn.newTypeDialog`）、时刻表（`fn.tableDialog(r, -1)`）、直通关系（`fn.throughDialog`）；
  - 删除种别 / 取消删除（`fn.deleteTypeDialog` / `fn.keepType`，按 `fn.isDeleted`）。
