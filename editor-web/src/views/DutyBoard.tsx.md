# views/DutyBoard.tsx

车厂层的运用看板：显示所选车厂在当前运行日的运用，每列车一行（编号在车辆池内连续，同旧页面），横轴为一天的时间，每班一个路线色块，出库/回库回送画虚线，没有车跑的班次单独一行。先选一列车，再点班次把它加进这列车的运用或移出（别的车跑的班次会移过来）。规则都是旧代码的：`fn.selectSlot`、`fn.toggleInSlot`、`fn.editDuties`、`fn.changeSlots`、`fn.deadheadTimes`、`fn.dutyProblems`，其余经车厂的各对话框（`fn.depotDialog` 等，见 `legacy/engine.ts` 改接的面板）。由 `views/Layers.tsx` 在车厂层显示。

## 常量（内部）
- `HOUR`：一小时毫秒数。`LABEL = 148`：左侧车名栏宽。`ROW = 34`：行高（像素）。

## 导出
### `DutyBoard()`（组件）
- 无 props；读 `v.data`、`v.state`。状态：用户选择的车厂 `chosen`、缩放 `pxPerHour`（70 全天 / 110 标准 / 200 细看）。
- 显示的车厂：选中运用所在车厂 > 用户选择 > 第一个车厂。没有车厂时提示。
- 渲染 `Head` 和 `Board`。换车厂时若有其他车厂的选中运用则清除（`state.slot = null`、`fn.renderAll()`）。
- 调用者：`views/Layers.tsx:230`。

## 内部组件
### `Head({ depot, choose, pxPerHour, setPxPerHour })`
- 车厂下拉、运用方式标签（自动运用 / 按运用图 / 自动排班，`fn.dutiesOf`）、星期与运行日类型（`fn.dayGroup`）、缩放按钮组。
- 按钮："一键生成运用…"（`fn.generateDuties`）、自动运用时"重新排运用…"（`fn.replanDialog`）、切换"按运用图/自动排班"（`fn.setDutyMode(depot, mode)`）、"更多 ▾"菜单（`menuHost`）：编辑车厂…（`fn.depotDialog`）、出入库规则…（`fn.depotRulesDialog`）、按时刻表摆车…（`fn.placeTrainsDialog`）、运用组…（`fn.dutyGroupsDialog`）、线路换车厂…（`panelHost` 的 `moveLine`）、出入库计划…（`fn.releasePlanDialog`）、丢掉运用的草稿（删 `v.draft.duties[depot]`，`fn.saveDraft()`、`fn.renderAll()`）。
- 缺车时显示 `fn.shortfallText`，自动运用有可重排提示时显示 `fn.replanText`；操作说明随是否选中一列车变化。

### `type Row`
- `{ siding, index, number（池内编号）, slot, day（当天条目或 null） }`。

### `Board({ depot, pxPerHour })`
- `rows`：按车辆池（没有池时视作一个池）、按池内存车线顺序列出所有运用位并连续编号。
- `noTrain`：车厂负责路线中当天未停运且无车跑（`fn.hasNoTrain`）的车次，按开始时刻排序。
- 时间范围：所有班段（`fn.segmentStart/End`）、回送（`fn.deadheadTimes(siding, day)` 的 `out.leave`、`back.home`）和无车班次的时刻，取整小时；没有时 4:00–24:00。
- `packed`：无车班次排进尽量少的行（间隔至少 3 像素）。
- `rowMenu(e, row)`：右键一列车：清空当天运用（`fn.editDuties` + `fn.dutyForEditing` 后清 `segments`）、删除这个库位、在该存车线添加运用（`fn.changeSlots`）。无编辑权限时为灰项。
- `clickService(s)`：已选中一列车且可编辑时 `fn.toggleInSlot(s)`；否则选中该车次（`state.selected`）并 `renderAll`。
- 每行：左侧按钮（`fn.selectSlot(depot, siding, index)`；"运用 N"、问题标记 `!`（`fn.dutyProblems`）、过夜"留"、`fn.startText(slot, siding)`）；时间格线；"这一天休"；出/回库 `Leg`；各班 `Block`。
- 最后一行"没车"：各无车班次的小方块，点击同 `clickService`。

### `Block({ left, width, color, chosen, title, onClick, children })`
- 一个班次色块（选中时加环），宽度大于 36 像素时显示时刻。

### `Leg({ x1, x2, label, exception, on })`
- 出库或回库的虚线，上方写回送编号；只对这份运用单独设定（例外）时用蓝色。
