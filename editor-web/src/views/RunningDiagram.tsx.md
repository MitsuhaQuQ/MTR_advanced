# views/RunningDiagram.tsx

由新页面绘制的运行图（重写第 2 步）：几何来自 `core/diagram.ts`（`test/diagram.test.ts`；到退役第 9 步为止与旧页面对照，快照取自仍一致时），在这里按旧 `draw()` 的层次画到 Canvas 上：时间轴与当前时刻、车站行、其他线路走本线轨道的车次、本线车次（选中、所选列车的、无车、停运、模拟）、出/回库指令、直通与回库、环线续圈、停站拖柄、所选列车的接续、终点折返、车厂行及出入库回送、模拟结果，最后是站名。

指针操作与旧画布相同：拖停站拖柄、拖出库回送、沿时间轴拖车次、拖空白平移；点击选择（双击选整条路线、Shift 选一段、Ctrl 加入；选中一列车时点击把班次加入/移出它的运用）；右键车次（之后的班次）或回送线的菜单；滚轮缩放，Shift+滚轮上下滚动。修改都由旧函数完成（`moveServices`、`editTimetable`、`setStopException`、`editDutyException`、`toggleInSlot`…，多数已经 `legacy/retired.ts` 换成 `core` 实现）。旧画布的容器 `#canvas-wrap` 仍在布局中（第 9 步起里面没有画布）位于本组件下方，使测量它的旧函数（居中到某车次、工具栏缩放）继续工作。由 `views/Layers.tsx:252` 显示。

## 类型（内部）
- `Drawn`：画出的车次及其折线 `{ s, points }`。
- `Leg`：画出的出库/回库回送 `{ a, b（端点）, kind: "out" | "back", depot, siding, index, text }`。
- `Drag`：拖动状态：拖停站拖柄 `handle`；拖出库回送 `deadhead`（累计 `shift` 毫秒）；拖车次 `move`（要移动的车次 ID，`kept` 为因单独编辑过而不动的数量）；平移 `pan`。

## 导出
### `RunningDiagram()`（组件）
- `useLegacy()` 的版本号变化、容器尺寸变化、每 30 秒（当前时刻线）时 `paint()`：按设备像素比设置画布并调用 `draw`，记录命中信息 `hits`。
- 命中：`hitService`（`D.hitPath`）、`hitLeg`（6 像素内的回送线）、`hitAccess`（5 像素内的其他线路车次）。
- `moveShift(dx, isFine)`（旧 `moveShift`）：拖动距离 → 时间偏移，按缩放取整（1 s / 5 s / 15 s / 1 min / 5 min；Alt 精细为 1 s）。
- `view()`：上次绘制的视图或由 `v.state` 组成。
- `dragDwell(handle, x)`（旧 `dragDwell`）：按拖柄位置算停站秒数（步长 1 或 5 秒）；整条路线选中时改表的该站出发设定，否则 `fn.setStopException(..., { dwell })`；经 `fn.editTimetable`。
- `zoomAt(direction, x)`：在 `v.ZOOMS` 档位间缩放，保持鼠标处时刻不动。
- 指针按下：依次判定拖柄、车次（`fn.servicesToMove(s)` 决定一起移动的车次）、可编辑时的出库回送线，否则平移。
- 指针移动：拖柄实时改停站；拖出库/车次时显示提示（`fn.showDragTip`）并预览；平移改 `state.viewStart`/`scrollY`；未拖动时更新光标和悬停提示（`fn.showTooltip`，其他线路车次为只读提示）。
- 指针抬起：
  - 拖出库回送：`fn.editDutyException(depot, siding, index, edited => outLead = max(出库路线用时, 原提前量 - 偏移)，标 EXCEPTION.OUT_LEAD)`；
  - 拖车次：`fn.moveServices(ids, shift)`；
  - 单击：已选中运用位时 `fn.toggleInSlot(s)`（Shift 时 `fn.addRangeToSlot(上次点的, s)`），记 `v.lastSlotService`，`fn.selectService`；点回送线选中该运用（`state.slot`、`v.dutyNotice`）；Ctrl → `fn.toggleChosen`；Shift → `fn.chooseRange`（并 `fn.renderTable`）；否则清范围，350 ms 内再点同一车次则选中整条路线（`state.selectedRoute`），`fn.selectService`。
- 右键：回送线 → `fn.deadheadMenu`；车次 → 菜单：选中这一班和之后各班（`fn.chooseLater`）、一起平移…（`fn.shiftLaterDialog`）、取消选择。
- 滚轮（原生监听，非被动）：Shift 上下滚动，横向滚动平移时间，否则 `zoomAt`。
- 调用者：`views/Layers.tsx:253`。

### `draw(g, w, h, moving, dragging)`
- 画一次整张图，返回命中信息 `{ drawn, handles, legs, access, view }`。颜色和字体取自 CSS 变量（`fn.css`）。
- 没有当前线路组时只写提示。否则：
  1. 车厂行留空（显示车厂且有按运用图、有出入库时刻的车厂时 `D.DEPOT_ROW_ROOM`）；`D.layoutRows`、`D.clampView`，并把夹紧后的 `viewStart`/`scrollY` 写回 `v.state`。
  2. 时间轴（`D.axisTicks`）；今天（`fn.nowDay()` + `v.FIRST_DAY_WEEKDAY`）时画当前时刻虚线和"现在 HH:MM"。
  3. 车站行及段与段之间的分隔。
  4. 显示接入时，其他线路走本线轨道的车次（`fn.accessLegs(line)`），点线只读，记入 `access`。
  5. 本线车次（选中的最后画）：无各站时刻的路线不画；被拖动的按偏移预览；`D.servicePath` 折线；`D.serviceLook` 决定颜色、线宽、透明度（依据 `fn.dutyAssignments()` 是否有车、所在车厂是否按运用图、`v.showNoTrain`、模拟未开行、是否在所选运用中等）；出/回库指令的短虚线和车厂名（`orderDepotName`）；直通/回库标记；选中车次的停站拖柄（`D.dwellHandles`）。
  6. 所选列车（运用位）各班之间的接续线（`fn.terminalNow()`，环线续圈除外），以及"车 N"标签（`fn.dutyNumber`）。
  7. `drawTerminalLinks`、（显示车厂时）`drawDepotLegs`、`drawSimulated`。
  8. 左侧站名（页面底色衬底）和时间标签；最后 `drawUntimed`。
- 调用者：本文件 `paint`。

## 内部函数
- `drawUntimed(g, line, w, c, uiFont)`：在图上方列出因没有各站时刻而未画的路线及原因（`untimedNotes`）。
- `drawTerminalLinks(g, line, pairs, xOf, yOf, c, w, numFont, polyline, movingIds)`（旧 `drawTerminalLinks`）：终点折返和接续到下一路线的连线，标注折返地点（"折返线"或 `fn.platformText(站台)`）。
- `drawDepotLegs(g, line, xOf, yOf, c, w, unit, numFont, dragging)`（旧 `drawDepotLegs`）：车厂行与每份运用的出库/回库回送（`fn.deadheadTimes(siding, day)`，拖动中的出库按偏移预览），返回 `Leg[]` 供命中。
- `drawSimulated(g, line, xOf, yOf, c, w)`（旧 `drawSimulated`）：模拟开着且有结果时，在计划之上画每个行程的虚线，晚点超过 `v.SIM_LATE_MILLIS`（`fn.simLate`）的部分为红色。
