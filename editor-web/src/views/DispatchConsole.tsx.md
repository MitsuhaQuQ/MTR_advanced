# views/DispatchConsole.tsx

调度台（旧 `renderConsole` 及其按钮）：先选要显示的路网，然后线上每列车一张卡片（晚点多的在前），写明下一站、当前状况和可下的命令。扣停/放行、下一站清客、指定回库（或两者一起）、撤销都立即发送；延长停站和提前折返在卡片里展开输入而不是弹对话框。发送方式与旧 `consoleCommand` 相同（`api/apply` 的 `dispatch` 操作）。旧调度台仍每两秒把列车读进它自己的隐藏元素（`v.consoleState.data`），本组件只读它。调用旧代码：`fn.consoleTrains`、`fn.consoleTrainName`、`fn.consoleTrainState`、`fn.consoleCommand`（均在 `legacy/retired.ts`，规则在 `core/dispatch.ts`）、`fn.renderConsole`、`fn.drawNetwork`、`fn.renderAll`、`fn.systemOfRoute`。

## 导出
### `DispatchConsole({ className, onChoose })`（组件）
- props：`className` 外层 `aside` 的类名；`onChoose(id)` 点卡片时选中列车（在路网图上定位）。
- 每秒自刷新一次（调度台数据由旧代码的计时器更新）；`useLegacy()` 跟随旧代码重绘。
- 路网下拉框：`v.consoleState.system`（空为全部路网），选项来自 `data.contingencySystems` 中有路线的路网（`systemTitle`）；改变时写回并 `renderConsole`、`drawNetwork`、`renderAll`。
- 显示在线列车数（以及不在运用中的数量）和数据年龄；数据未到时显示"正在向游戏要各列车的位置…"；无权限、无车时提示。
- 列车列表按 `late` 降序、ID 升序，每列一个 `TrainCard`。
- 调用者：`views/Layers.tsx:171`。

## 内部组件
### `TrainCard({ train, chosen, canEdit, onChoose })`
- props：`train`（调度台数据中的一列车：`id`、`route`、`routeName`、`next`、`block`、`late`（秒）、`dispatch` 位、`noDuty`…）；`chosen` 是否选中；`canEdit`；`onChoose`。
- 状态：`sent`（命令键 → 发送时列车的 `dispatch` 和时间；列车 `dispatch` 未变且不到 `SENT_MS` 10 秒时按钮显示"已发送"，否则恢复，0.77.0 起不再一直灰着）、`open`（展开的输入：`extend` 或 `turn`）、`seconds`（多停秒数，默认 60）、`station`（折返车站）、`error`。
- `send(key, ...operations)`：逐条 `fn.consoleCommand(null, {train: id, ...operation})`（POST `api/apply`），全部成功才记为已发送，否则显示"发送失败"。
- 折返可选车站：所属路网的车站（`fn.systemOfRoute`），否则本路线各停站。
- 按钮（有权限且在运用中时）：扣停/放行（按 `dispatch & 1` 切换）、延长停站…（1–1800 秒，`{command:"extend", seconds}`）、提前折返…（`{command:"turn", station}`）、下一站清客（`clearOut`）、指定回库（`return`）、下一站清客后回库（两条一起）、有命令时"撤销"（`cancel`）。
- 显示：车名、路线短名、晚点 ≥5 分钟标签、扣停标签、下一站与区段（后 4 位）、`fn.consoleTrainState` 文字。
- 调用者：本文件 `DispatchConsole`。
