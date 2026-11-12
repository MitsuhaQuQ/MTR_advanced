# StopRules.java

"一个服务何时到达/离开每个停站"的唯一规则集：是否通过、是否待避、是否计划得比列车能跑的还快（S3e、A09）。列车跑路径、运用规划器和时刻表页面都从这里取时间；网页不自己算而是问游戏。整个行程（包括中途加入的路线）的算术在 `RouteTimetable.applyPlan`。被游戏侧时刻表界面 `mtr.screen.TimetableScreen:711`、网页编辑器 `mtr.client.editor.EditorData`/`EditorTimes` 调用。

## 类 `StopRules`（final，工具类）

### 常量
- `TOO_FAST_TOLERANCE_MILLIS = 500`：为舍入留的容差；计划到达比最快可能早超过 500 毫秒才算"太快"。

### `private StopRules()`
禁止实例化。

### `public static List<RouteTimetable.StopPlan> plan(List<? extends PlanStop> stops, long[][] fastest, int marginPercent, int table, Map<Integer, ServiceExceptions.StopException> stopExceptions)`
- 作用：单独计算一条路线的时间。逐站：若有最快时间则到达取 `fastest[i][column]`，出发取 `fastest[i][column+1]`（中间的通过站出发 = 到达）；无时间则沿用上一站出发。构造 `StopPlan(最快到达, 最快出发, 设定到达, 设定出发, 待避)`；再套用服务例外，最后用一个只设了 `marginPercent` 的临时 `RouteTimetable` 调 `applyPlan` 算出计划时间。注释：通过站一定不停，即使最快时间是在改为通过之前算出的（最快时间只在路径刷新时变化）。
- 参数：
  - `stops` 路线停站；
  - `fastest` 每站最快（到达, 出发）对（`TimetableTimes` 格式），未找到的站为 -1；未知时 null；单位毫秒；
  - `marginPercent` 额外计划余量百分比；
  - `table` 表号；
  - `stopExceptions` 某一班的按站例外，null 表示用表本身的时间。
- 返回值：每站一个 `StopPlan`（`applyPlan` 已填好 `arrivalMillis`/`departureMillis`）。
- 主要调用者：`common/src/main/java/mtr/client/editor/EditorData.java:586`、`EditorData.java:650`、`mtr/screen/TimetableScreen.java:711`。

### `public static List<StopTime> times(…同上参数…)`
- 作用：在 `plan` 结果上为每站生成 `StopTime`：待避仅对中间站有效；`passes` 见下；`tooFast` 判定为 `arrival < 上站出发 + (本站最快到达 - 上站最快出发) - 500ms`（`fastest` 为 null 时恒为 false）。
- 返回值：`StopTime` 列表，毫秒，从服务首站计划到达起算。
- 主要调用者：`common/src/main/java/mtr/client/editor/EditorData.java:594`、`EditorData.java:657`、`mtr/client/editor/EditorTimes.java:237`。

### `public static boolean passes(PlanStop stop, RouteTimetable.StopPlan plan, boolean middle)`
- 作用：中间站时，例外指定通过（`exceptionPass == 1`），或路线本身通过且例外没有指定停车（`exceptionPass != 0`），则该班通过此站。
- 参数：`middle` 是否首末站之间。
- 主要调用者：本类 `times`（`StopRules.java:65`）。

## 内部类 `StopTime`（public static final）
一个服务在一个停站的结果，毫秒，从服务首站计划到达起算。
- `arrivalMillis`、`departureMillis`：到达/出发。
- `overtake`：在此等待被越行。
- `passes`：通过不停。
- `tooFast`：计划到达早于从上一站能跑到的时间。
- 私有构造，只由 `times` 创建。
