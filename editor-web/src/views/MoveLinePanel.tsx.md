# views/MoveLinePanel.tsx

"线路换车厂"侧面板（0.69.2，Q 世界 2026-11-12）：把一条线路（可只选部分种别）的路线从原车厂的负责线路和存车线中移出、放进另一个车厂的某车型，原车厂这条线的运用一起处理；可选"对换"，同时把目标车厂的一条线反向移过来。原车厂被移走线路的运用直接去掉（用户的选择）：手排的立即去掉，游戏自动排的在下次不含这些路线重排时去掉。全部只进草稿。规则在 `core/depotMove.ts`；通过旧代码函数 `fn.depotEntryOf`、`fn.storeDepotEntry`、`fn.dutiesOf`、`fn.editDuties`（`legacy/retired.ts`）写草稿。由 `views/LinePanels.tsx` 在 `PanelTarget.kind === "moveLine"` 时显示（入口：旧树车厂/线路右键菜单的"线路换车厂…"，见 `legacy/engine.ts` 的 `treeExtras`）。

## 内部常量与工具
- `field`、`label`：输入框和标签样式。
- `vehicleName(name)`：车型名去掉 `mtr_custom_train_` 前缀，空时"（无车型）"。
- `modeOf(key)`：线路键的运输方式（`|` 前部分）。

## 导出
### `MoveLinePanel({ depotId, lineKey, onClose })`（组件）
- props：`depotId` 预选的原车厂（空串则按 `lineKey` 找有该线路的车厂，否则第一个）；`lineKey` 预选的线路（可空）；`onClose` 关闭面板。
- 内部函数：
  - `linesOf(depot)`：车厂按草稿负责的路线（`fn.depotEntryOf(depot).routes`）所属的线路组，每组只含该车厂负责的路线；连续移动时能看到前一次的结果。
  - 状态：原车厂 `fromId`、线路 `line`、未勾选的种别 `unticked`、目标车厂 `toId`（只列运输方式相同的其他车厂）、目标车型 `vehicle`、是否连运用一起搬 `withDuties`（默认：原运用手排则搬，自动排则不搬）、是否对换 `swap`、换回来的线路 `backLine` 和车型 `backVehicle`、错误 `error`。
  - `moveOne(source, target, ids, vehicleThere, along)`：一个方向的移动：`fn.storeDepotEntry(source, entryWithout(...))`、`fn.storeDepotEntry(target, entryWith(...))`；原车厂运用中有这些路线时 `takeRoutes`（手排的经 `fn.editDuties` 改草稿，自动排的只在副本上取）；`along` 时 `fn.editDuties(target, putTaken(...))`。返回搬过去的运用日条目数。
  - `apply()`：检查已选原车厂、线路、目标车厂，目标车厂和（对换时）原车厂都有存车线；执行 `moveOne`（对换时再反向一次）；把说明写进 `v.dutyNotice`，`fn.renderBanner()`、`fn.renderAll()`，关闭面板。
- 界面：`SidePanel`（宽 560），原车厂/线路下拉、多个种别时的种别开关、目标车厂/车型下拉、"连运用一起搬"勾选及说明、"对换"勾选及换回线路/车型；底部"放进草稿"（需编辑权限）、"取消"、错误提示。
- 副作用：改草稿（车厂条目、运用），设置 `v.dutyNotice`。
- 调用者：`views/LinePanels.tsx:39`。
