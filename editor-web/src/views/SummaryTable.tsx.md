# views/SummaryTable.tsx

由新页面绘制的汇总时刻表（重写第 3 步）：一条路线的表（当天的各时刻表、最低性能、添加班次，每班一列显示各站到发），或一个线路组的各路线按同一方向合在一起。单元格按旧的 `timeCell` / `terminalCell` / `serviceHead` 算出并保留它们的类名（所以样式相同）；单元格用页面自己的输入框就地编辑（Enter：只改这一班；Shift+Enter：这一班和之后各班；Tab：下一格；Esc：放弃）。规则都是旧函数：`changeTime`、`typedTime`、`cellMenu`、`toggleChosen`、`chooseRange`、`clearTable`、`editTimetable`、`setManaged`、`servicesToAdd` 和 `TimetableBatch.fitSpan`（多数已由 `legacy/retired.ts` 换成 `core/timetable.ts` 实现）。表头里的出入库回送（`v.deadheadIndex`）由旧代码的 `renderTable` 算出（第 9 步起它只算这个，旧表和"旧版画法"开关已删）。由 `views/Layers.tsx` 显示。

## 类型
- `Editing`（内部）：正在编辑的单元格 `{ svc（车次 ID）, stop, kind（arr/dep/pass/derived）, value（输入文本）, old（原时刻，运营日毫秒）, bad }`。

## 导出
### `SummaryTable({ className })`（组件）
- props：`className` 外层类名。不显示表时（`fn.showsTable()` 为假）或无数据返回 null。
- 选中车次变化时把对应列（`th.svc-head.hl`）滚动到可见。
- 模式：当前线路组有多条路线且未选具体路线时为"线路组模式"，否则显示路线（选中的或线路组第一条）。
- 编辑：
  - `startEditing(cell)`：从单元格的 `data-svc/stop/kind` 取车次，`derived`（终点接续推算的格）和整格"レ"通过不可编辑；初值为当前时刻（带秒）。
  - `commit(move, toLater)`：`typedTime(value, old)` 解析，失败标红；变化至少 1 秒时 `fn.changeTime(s, stop, kind, time, toLater)`；`move` 非 0 时在重绘后编辑前/后一个单元格。
  - `editor(cell)`：正在编辑该格时返回输入框（Enter/Shift+Enter/Tab/Esc，失焦即提交）。
- 单元格（`cellProps`、`cellOf`、`headOf`）：
  - 首站到达/末站出发格：用 `fn.terminalNow()` 的前后接续（`previous`/`next`）显示接续时刻，提示 `fn.terminalText`；`inferred`（就近推断）、`fast`（冲突）、`out`（换站台等）类名；出库/回库截断时显示"—"。
  - 运营范围外（`fn.servedStops`）显示"—"并提示出车/回送车厂（`orderDepotName`）。
  - 其他：通过站到达格"↓ 时刻"、出发格"レ"；类名 `pass`、`exc`（单班例外）、`fast`（快过最快时刻）、`wait`（待避）、`pending-run`（单班通过/停车例外）、`out`（直通接出）、`pending`（正在选接出站，`state.pendingExit`）、`hl`（选中）。
  - 表头：班次时刻（可编辑首站出发）、例外标记 `*`、无车/停运样式、显示回送时的出库/回库说明（`v.showDeadheads`、`v.deadheadIndex`）、出车/回送/接续/直通/回库注记。
- `isSelected(s)`：选中车次、整条路线选中或在多选范围内（`fn.inRange`）。
- `showDeadheadsBox`："显示回送"勾选（`v.showDeadheads`，记入 `localStorage` 的 `mtr-editor-show-deadheads`，然后 `fn.renderTable()`、`fn.renderAll()`）。
- 指针和按键（原旧表 `#view-table` 上的处理，旧表已删）：
  - `onClick`：表头 Ctrl 点击 `fn.toggleChosen(s, shift)`、Shift 点击 `fn.chooseRange(s)`（设 `v.tableMessage`）；否则清范围，选中该车次（`fn.centerOn(s)`、`fn.renderAll()`）并开始编辑。
  - `onContextMenu`：`fn.cellMenu(event, cell)`。
  - `onKeyDown`：在单元格上按 Enter 或数字开始编辑。
- 线路组模式：按各路线行序判断方向（`wayOf`），可切换方向（`state.lineWay`）；行按 `line.rows`（方向相反时倒序），每站按需要显示到/发两行，路线没经过的格标 `‖`（跨段/不在此段）或 `↳`（直通经过）；表头注记为路线名（路线色）。
- 单路线模式：工具行（标题、星期与班次数、取时刻中/有草稿提示、`pathFailure`、`fn.shortfallsOf(r)`、管理状态与"改由高级编辑器管理/交回游戏内编辑"（`fn.setManaged`）、限速（`fn.speedLimitOf`））；表按钮（点一下切到该表运行的第一天 `fn.setWeekday`；右键菜单 `tableMenu`：改名和星期 `fn.tableDialog`、清空 `fn.clearTable`、删除这张表（页内确认后 `fn.editTimetable` 删除）、"＋ 新建表"）；当天表的最低性能输入（`perf`：加减速 m/s² ÷ 400、最高速度 km/h ÷ 72 存入表，失焦时 `fn.editTimetable`；`perfKey` 让切换路线/表时输入框重置）；`AddServices` 或"这一天不运行"；页内确认条 `asking`。各站行：站名（时刻边界"界"、车比站台长 ⚠、环线接入站"入"，提示含 `fn.trackTitle(stop)`），全部通过的中间站只显示一行"通过"。

## 内部组件
### `Frame({ className, root, tools, children, ...events })`
- 用旧样式的盒子（`legacy legacy-slot` + `table-view`）包住工具行和可滚动表格，事件挂在 `table-view` 上。

### `AddServices({ r, today, showDeadheadsBox, ask })`
- 添加班次（旧 `addServices`）：起始、结束、间隔三个输入（初值和保存在 `v.addInputs`，并写入 `localStorage` 的 `mtr-editor-add-services`）。
- `add()`：`fn.servicesToAdd(start, end, count)` 得到时刻，非法时 `v.tableMessage = v.PATTERN_HINT`。起止和间隔都填时，用 `TimetableBatch.fitSpan(现有车次, from, to, 新时刻)` 求保留/挪动/新增/删除，再 `fn.editTimetable` 依次删除（`fn.removeService`）、挪动（`fn.retimeService`）、新增（不超过 `v.MAX_SERVICES`）；有要删除的先用 `ask` 页内确认。否则只追加不重复的时刻。
- 还有"显示回送"勾选、"清空"（`fn.clearTable(r, today)`）和表提示 `v.tableMessage`。
