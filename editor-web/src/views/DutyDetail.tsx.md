# views/DutyDetail.tsx

检查器中所选列车（运用位）的运用详情（旧 `dutyPanel` 及其在 `#inspector` 上的处理函数）：车厂与存车线、适用的运行日、每一班一行（时刻、路线色、起止站，× 移出）、两班之间在哪等、各班的问题、过夜和第二天的运用、回库存车线、出车方式与出车存车线。所有修改都和以前一样经旧代码 `fn.editDuties` + `fn.dutyForEditing`（规则在 `core/duties.ts`，经 `legacy/retired.ts`）。元素不带旧的 `data-*` 属性，`#inspector` 上仍在的旧处理函数不会重复处理。由 `views/Inspector.tsx` 在 `v.state.slot` 有值时显示。

## 内部常量
- `select`：下拉框样式。

## 导出
### `DutyDetail()`（组件）
- 无 props；读旧代码状态 `v.state.slot`（`{depot, siding, index}`）、`v.state.weekday`、`v.data`、`v.EXCEPTION`。`useLegacy()` 跟随旧代码重绘。没有数据、没有选中或存车线不存在时返回 null。
- 计算：`duties = fn.dutiesOf(depot)`、当前运用位 `slot`、当天条目 `day = fn.dutyOn(slot, weekday)`、运行日类型 `group = fn.dayGroup(depot, weekday)`、问题 `fn.dutyProblems(...)`、同车型存车线 `pool = fn.poolSidings(...)`、出车方式 `fn.startOf(...)`、是否设了等候例外 `waitOwn`、已删除路线的班次数 `deleted`。
- 内部函数：
  - `problemsAt(i)`：第 `i` 班（-1 为整份）的问题段落。
  - `edit(change)`：`fn.editDuties(depot, all => change(fn.dutyForEditing(depot, 运用位, weekday), all))`：先把当天所属运行日类型拆成独立条目再改。
  - `sidingName(s)`：存车线名或"存车线"。
  - `moveToSiding(to)`：把这份运用移到同车型另一条存车线：先给原存车线所有运用位补运用号，把按位置引用的过夜接续（`next`）改成按运用号（`nextDuty`）（P2-D），然后从原存车线取出、加到目标存车线末尾（原存车线空了则删除）；之后把 `state.slot` 指向新位置并 `renderAll`。
- 显示与操作：
  - 标题"运用：车 N"（`fn.dutyNumber`）与"取消选择"（清 `state.slot`）。
  - 运行日（与其他天共用时提示改了会分开）、方式（按运用图 / 自动排班）、`fn.plannedReturnText(...)` 给出的回库说明（HTML，`dangerouslySetInnerHTML`）。
  - 有已删除路线班次时说明，手排时可"全部移出"。
  - 每一班：时刻（`fn.segmentStart`–`fn.segmentEnd`）、路线、起止站、×（移出）；有后续接续时 `fn.terminalText(fn.terminalNow().next.get(...))`；两班之间"等候"下拉（0 运行时定、1 在站台等、2 去折返线等、3 换站台再发），改动写 `waits` 并标 `EXCEPTION.WAITS`；该班问题。
  - 整份问题、`fn.dutyCheckText(depot)`（模拟验证结论）。
  - 有编辑权限时：过夜勾选（`stay`）；过夜时"第二天接着跑"（本存车线按位置 `same:i` 或其他存车线按运用号 `duty:id`，标出不从这个站台出发或没有运用的）；不过夜时"回库存车线"（"自动"或同车型存车线，手设时标 `EXCEPTION.BACK_SIDING`）；"出车方式"（`prefer`/`only`/`any`，改运用位所有条目的 `start`）；多条同车型存车线时"出车存车线"（`moveToSiding`）；`only` 但存车线最多停 0 列时警告。
- 副作用：经 `fn.editDuties` 改草稿运用（随后旧代码保存草稿并 `renderAll`），修改 `v.state.slot`。
- 调用者：`views/Inspector.tsx:62`。
