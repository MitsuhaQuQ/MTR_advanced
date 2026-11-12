# views/PickPanels.tsx

在路网图上"点选"时的面板（旧 `pickingPanel` / `wayPickPanel` 及其处理函数），画在地图旁边：新建线路时按顺序列出所选车站、每段的路和途经点；车厂出库/回库路线或回送路径的选择及用时。所有动作都调用旧代码：`fn.refreshPreview`、`fn.finishPicking`、`fn.stopPicking`、`fn.refreshWayPick`、`fn.finishWayPick`，并直接修改旧代码状态 `v.picking`、`v.wayPick`。不带旧的 `data-*` 属性，所以 `#inspector` 上的旧事件处理不会作用于它们。

## 导出
### `PickPanel({ className })`（组件）
- props：`className` 外层 `aside` 的类名。
- `v.picking` 存在时显示 `Picking`（新建线路），`v.wayPick` 存在时显示 `WayPick`（选出口），否则返回 null。`useLegacy()` 跟随旧代码重绘。
- 调用者：`views/Layers.tsx:66`、`views/Layers.tsx:128`。

## 内部
- `link`：链接样式按钮的类名。

### `Picking()`
- 读 `v.picking`：`stops`（`{name, pass, waypoints}`）、`legs`（每段 `{found, nodes, failure}`）、`activeLeg`、`error`。
- `change(run)`：执行修改后 `fn.refreshPreview()`（重新向服务器找路）。
- 每站一行：序号、站名、中间站"通过"勾选（改 `stop.pass` 后 `drawNetwork`、`renderAll`）、上移/下移/删除（经 `change`）。每段显示找路结果（正在找路 / 经 N 个节点、途经 M 个点 / 走不通：原因）和"选这一段的途经点"开关（设 `picking.activeLeg`）。
- 底部：所有段都找到路且至少两站时可"填线名和种别…"（`fn.finishPicking()`，打开 `finishPicking` 面板）、取消（`fn.stopPicking()`）、"改用停站图新建…"（清 `v.picking`，`renderAll`，`fn.newLineDialog()` 打开停站编辑器）。

### `WayPick()`
- 读 `v.wayPick`：`depot`、`siding`、`out`（出库/回库）、`target.path`（有则是在画回送路径）、`targets`（运行图上该存车线可发/收车的站台：`platform`、`name`、`first`、`last`、`count`）、`platform`（选中的）、`waypoints`、`turned`、`result`（`found`、`millis`、`entrances`）、`error`。
- 显示车厂和存车线、存车线出入口（`fn.stationNameOf`）；画回送路径时说明途经点操作；否则列出可选站台按钮（选中后 `fn.refreshWayPick()`）、经过轨道段数和"清空"。
- 显示用时（`durationText`）或"这样走不通"/"正在计算…"。
- 按钮"用这个"`fn.finishWayPick(true)`、"取消"`fn.finishWayPick(false)`。
