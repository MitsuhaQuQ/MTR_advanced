# views/DepotExtras.tsx

车厂层的另外几个对话框改为侧面板（旧代码的 `vehicleDialog`、`dutyGroupsDialog`、`releasePlanDialog` 改接到这里；旧实现保留为 `old<Name>`），沿用旧规则：
- 车厂里某车型的设置，输入时即检查；第一条存车线自己的设置生效时输入框变灰；
- 已排出的运用组，每组可单独重排；以及把其他车厂的运用组当模板套用到本车厂；
- 某天列车出库和回库的计划，可在面板内选星期。

由 `views/LinePanels.tsx` 按 `PanelTarget.kind`（`vehicle` / `groups` / `release`）显示。通过旧代码函数（`fn.depotById`、`fn.depotEntryOf`、`fn.storeDepotEntry`、`fn.dutiesOf`、`fn.editDuties`、`fn.dutyOn`、`fn.segmentService`、`fn.segmentStart`、`fn.lastStopOf`、`fn.legTimes`、`fn.dutyNumber`、`fn.sendJson`）读写；`core/depotMove.ts` 提供运用组的规则。

## 内部常量
- `field`、`label`：输入框和标签样式。

## 导出组件
### `VehiclePanel({ depotId, poolKey, onClose })`
- 车厂中某车辆池（车型）的设置。props：`depotId`、`poolKey`、`onClose`。
- 初值来自 `fn.depotEntryOf(depot)` 的 `vehicleSettings[poolKey]`，没有则游戏中的 `pool.vehicleSettings`。
- 换算：`perSecond(value)`：游戏内加速度单位（每 tick 的值）× 400 → m/s²（保留两位）；保存时 ÷ 400。转回自动时间：界面为秒，存储值为秒 × 2（存储单位未确定）。
- 字段："统一设置"勾选（不勾则 `null`，按第一条存车线原来的设置）、加速度、减速度（空同加速度，存 0）、最高速度 km/h（空不限，0–300）、允许手动驾驶、手动驾驶最高速度（`depot.manualSpeeds` 档位序号）、多久不操作转回自动（0–3600 秒）；"能跑的种别"（逗号/顿号分隔，`typesOf` 规整为逗号连接，与游戏相同则不存）。
- `problem`：输入不合法时的提示，存在时禁止保存。
- 保存：写入条目的 `vehicleSettings[poolKey]` 和 `serviceTypes[vehicle]`，`fn.storeDepotEntry(depot, entry)`（进草稿 `depots`）。
- 调用者：`views/LinePanels.tsx:37`。

### `DutyGroupsPanel({ depotId, onClose })`
- 运用按"车型 × 时刻表星期"分组。props：`depotId`、`onClose`。
- 上半："本规划单元的运用组"（`depot.shortfall.groups`：`label`、`days`、`trains`、`needed`、`uncovered`、`kept`、`key`），自动运用时每组可"只重排这组"→ POST `api/replan-duties`（`{depot, group}`），结果显示在底部。
- 下半："套用其他车厂的运用组"：对其他每个车厂 `dutyGroupsOf(other, fn.dutiesOf(other))` 列出（车厂、车型、星期、份数）；本车厂有多种车型或没有该车型时可选放到哪种车型（`ontoOf(index)` 默认同车型或第一种）。
- `apply(entry, targetVehicle)`：关闭面板，`fn.editDuties(depot, duties => applyDutyGroup(duties, depot, 来源车厂, 来源运用, 车型, 星期, 目标车型))`，返回提示文字（成为 `v.dutyNotice`）。原车厂不变。
- `vehicleName(vehicle)`：去掉 `mtr_custom_train_` 前缀。
- 调用者：`views/LinePanels.tsx:38`。

### `ReleasePlanPanel({ depotId, onClose })`
- 某天的出入库计划。props：`depotId`、`onClose`。状态：所选星期（默认当前 `state.weekday`）。
- 对每条存车线的每个运用位，取当天条目，生成一行：车号（`fn.dutyNumber`）、存车线、出库时刻、首班（出库后，时刻与地点）、末班到达、到库时刻、班次数、之后（过夜/回库）；`fn.legTimes(siding, on)` 给出 `out`/`home` 等时刻。按首班开始时刻排序。
- 统计今天出车数和在库数（不限列车数的存车线不算）；显示出库间隔说明（`depot.releaseInterval`）。
- `placeOf(r, stop)`：路线短名 + 站名。
- 调用者：`views/LinePanels.tsx:40`。
