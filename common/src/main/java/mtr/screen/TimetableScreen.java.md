# TimetableScreen.java（本分支新增）

客户端界面：一条线路的时刻表，布局仿日本乘务员行路表——每站一行，显示所选班次的时刻。左侧是该表的班次列表（发车时刻），右侧每站：站名、站台、最快运行时间、到达、发车、通过、待避；上方：方向切换（同一线路的其他方向）、启用、多张表（运行日、名称）；下方：班次的接续（转到另一条线路的某班次、离开站与加入站）或回厂、环线的加入站、冲突检查结果。打开：`common/src/main/java/mtr/screen/EditRouteScreen.java:58`。`getDirectionText` 被线路列表使用（`common/src/main/java/mtr/screen/DashboardScreen.java:441`、`common/src/main/java/mtr/screen/DashboardListSelectorScreen.java:192`）。网页编辑器管理的线路（`AdvancedEditing.isLockedInGame`）只能查看不能修改。

时间约定：班次发车时刻为一天内毫秒（UTC 保存，显示用配置时区，`EditDepotScreen.formatTime`）；各站到发时刻为相对班次发车的秒数（−1 表示未设，按计划推算并以灰色建议文字显示）；最快时间来自服务器（`EditorTimes.getFastest`）。

## 类 `TimetableScreen`（extends `ScreenBase`，implements `IGui`, `IPacket`）

### 字段
- `scroll`（首行站序）、`rowCount`、`baseMillis`（所选班次）、`isBinding`（填充时不触发回调）、`directionWidth`、各列 x（站台、最快、到达、发车、通过、待避）、`checkedKey`（冲突检查时的输入摘要）、`tableIndex`、`hasInitialized`、`route`、`previousScreen`。
- 控件：`buttonDirection`、`checkboxEnabled`、`textFieldMargin`（裕度百分比，第 3 轮优化后不再显示与使用）、`textFieldService` + `buttonAddService`（格式同 `EditDepotScreen.applyDepartures`，上限 `RouteTimetable.MAX_SERVICES`）、`buttonClear`、`buttonCheckConflicts`、`buttonEntryStop`（环线加入站）、接续：`buttonLinkRoute`、`textFieldLinkService`、`buttonLinkStop`、`buttonLinkEntry`、`buttonClearLink`、`buttonReturnToDepot`；表：`buttonTable`、`buttonAddTable`、`buttonDeleteTable`、`buttonsWeekday`（7 个）、`textFieldTableName`；`servicesList`（冲突班次显示为警告色）；每行到达/发车文本框、通过/待避复选框（最多 `MAX_ROWS = 32` 行）。
- `linkRouteIds`、`conflictServices`。
- 尺寸常量与颜色：`LIST_WIDTH = 130`、`TIME_WIDTH = 58` 等；`ARGB_WARNING`、`ARGB_FIELD_TEXT`。

### 构造函数 `TimetableScreen(Route initialRoute, ScreenMapper previousScreen)`
- 创建控件与回调；请求最快时间（`requestTimetableTimesC2S`）。要点：
  - 环线加入站：在非通过的停站间循环，最后回到首站（`timetable.entryPlatformId`，0 为首站）。
  - 接续线路：选择另一条线路（单选），默认取不早于本班次的最近一班；离开站在本线路的时刻表分界站之间循环（−1 为终点）；加入站在对方线路的分界站间循环（0 为首站）。
  - 回厂：切换本班次是否回厂（`returnServices`）。
  - 增表：复制当前表（`route.addTable`）；删表（不能删第一张）。
  - 运行日按钮切换 `weekdays` 位。
  - 通过复选框：勾选时清除所有表中该站的发车时刻与待避（通过的列车不能守发车时刻或等待）。
  - 待避复选框只改当前表。

### `protected void init()`
- 计算列位置与行数；左侧班次列表；有多个方向时显示方向按钮；放置各控件；窗口大小变化时先保存表名；`updateList`、`requestConflicts`、`updateLocked`。

### `void tick()`
- 各文本框 tick；更新建议时刻与冲突班次集合。

### `void render(…)`
- 表头、各行（`drawRow`，到达早于"上站实际发车 + 最快区间时间"时最快时间标红）、提示（被锁定时提示由网页编辑器管理；无最快时间时另一提示）、冲突文字。

### `void mouseMoved(…)` / `boolean mouseScrolled(…)`
- 右侧滚动站行，左侧滚动班次列表。

### `void onClose()`
- `saveRoute()` 后返回上一界面。

### `boolean isPauseScreen()`
- false。

### `static String getDirectionText(Route route)`
- "线路名 → 终点站名"，区分同名的不同方向。

### `private List<FormattedCharSequence> getHintLines(Component)` / `private int getMarginY()`
- 提示按 `|` 与窗口宽度换行；表格下方区域的起始 y。

### `private void onLinkRouteSelected()` / `private void onLinkServiceChanged(String)`
- 选定接续线路后写入接续（同一线路保留原加入站）；接续班次时刻输入有效（8 位 `HH:MM:SS`）时写入，否则标红。

### `private String getOrderStopName(ServiceOrder)` / `private static String getOrderDepotName(ServiceOrder)`
- 出入厂指令的站名与车厂名（0 为任意车厂）。

### `private void updateLinkWidgets()`
- 按所选班次显示接续控件；有停运、出厂指令或入厂指令（在网页编辑器运行图中设置，这里只显示）时在回厂按钮上显示它们；文字过长截断（`fitButtonText`）。

### `private String fitButtonText(String)`
- 按 `CHECK_BUTTON_WIDTH` 截断加 "..."。

### `private void saveRoute()`
- 未锁定时：写启用、表名，`timetable.sanitize()`，发 `setTimetable` 的 `PACKET_UPDATE_ROUTE`。

### `private RouteTimetable getTable()` / `private void saveTableFields()` / `private void selectTable(int)` / `private void onTableChanged()` / `private void updateTableWidgets()`
- 当前表；保存表名（裕度保持原值不用）；切表；刷新；表按钮"n/总数"、运行日颜色（绿为运行）。

### `private boolean isLocked()` / `private void updateLocked()`
- 是否由网页编辑器管理；锁定时禁用所有修改控件（只关闭，不改变它们自己的可用状态逻辑）。

### `private List<Route> getLineRoutes()` / `private void onSelectNextDirection()`
- 同一线路（`Route.isSameLine`）的各线路；保存后切到下一方向并请求其最快时间与冲突。

### `private void updateEntryStopText()` / `private void updateDirectionText()`
- 只有环线显示加入站；方向按钮显示"服务种类 → 终点"。

### `private void drawRow(PoseStack, int index, StopPlan plan, int y, boolean hasTimes, boolean tooFast)`
- 站名（冲突站红色）、站台、最快到达（m:ss）、首行显示班次发车时刻、通过站显示 ↓。

### `private boolean isPass(int index)` / `private boolean hasFastestTimes()` / `private List<StopPlan> getPlans()`
- 中间站且通过；最快时间齐全；按 `StopRules.plan(…)` 计算本班次各站计划时刻（所选班次自己的时刻优先）。

### `private void requestConflicts()` / `private int getCheckKey()` / `private TimetableConflicts.Result getConflictResult()`
- 记下输入摘要并请求服务器检查（`requestTimetableConflictsC2S`）；摘要随任何影响时刻的输入变化；摘要不变时才使用结果（`ClientData.TIMETABLE_CONFLICTS`）。

### `private List<Conflict> getServiceConflicts(Result)` / `private boolean hasConflict(int index)` / `private void updateConflictServices()`
- 与本班次相关的冲突；某站是否有冲突；冲突的班次时刻集合（用于列表着色）。

### `private void drawConflictText(PoseStack, int x, int y)` / `private Component getConflictSummary(Result)`
- 状态：过期、检查中、无冲突（或有未检查的线路）、冲突摘要：追踪间隔冲突（0.82.0：这一站到下一站和前一班只隔几秒，至少要几秒，`gui.mtr.timetable_conflicts_found_headway`）、咽喉道岔冲突（L2：两列车同时以不同进路过咽喉道岔）、折返冲突、站台冲突（站名、站台、对方线路与班次）；数量达上限显示 "N+"。

### `private int parseMargin()`
- 裕度（上限 `MAX_MARGIN_PERCENT`，无效用默认）。

### `private void bindRows()`
- 按滚动填充各行：首站无到达、末站和通过站无发车、只有中间站有通过/待避；时刻显示为班次发车 + 秒 × 1000 的时刻；然后更新建议与接续控件。

### `private void updateSuggestions()`
- 空文本框显示计划时刻作为建议。

### `private void onTimeChanged(int row, boolean isArrival, String text)` / `private void setTime(RoutePlatform, boolean isArrival, int seconds)`
- 空为 −1；有效（8 位）时存为相对班次发车的秒数（跨午夜取模一天）；无效标红。

### `private void onSelectService(…)` / `private void onDeleteService(…)` / `private void updateList()`
- 选择班次；删除班次（锁定时不可）；刷新班次列表，所选不在表中时选运营日第一班（按 `TimetableConfig.getTimeInDay`）。

### `private static String formatDuration(long millis)`
- `m:ss`。
