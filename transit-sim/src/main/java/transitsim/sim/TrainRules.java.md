# TrainRules.java

独立运行的列车在停站时遵守的规则：停多久、何时可以离开、何时开门、以多快的速度巡航以保持准点（S4a）。游戏服务器列车和组件仿真器使用同一套。移动列车由 `transitsim.motion.TrainMotion` 负责；运行哪个服务由 `TrainService` 负责。

调用者：游戏侧 `TrainServer`（`:289` `getTotalDwellTicks`、`:294`/`:295` `isHeldAtStop`/`getHoldReason`、`:300`/`:329` `getTicksUntilDoorsClose`、`:353` `getCruiseCap`、`:365` `openDoors`、`:1085` `findRunEnd`、`:1092` `getPlannedDepartureMillis`、`:1608` `findNextStop`、`:1702` `isBlockingOthers`、`:1708` `getEarlyThresholdMillis`）；组件 `TickRunner`（`:2057` `waitsBeforeRequestingRun`、`:2392` `isWaitingForDeparture`、`:2988` `isHeldAtStop`、`:3058` `openDoors`、`:1870` `areDoorsOpen`、`:2026` `findNextStop`）；`SpeedCurves.findCruiseCap`。实现 `TrainRules.Train` 的有游戏 `TrainServer`、组件 `TickRunner.HeadlessTrain`、测试 `MotionFixture`。

## 类 TrainRules（final，不可实例化）

### 常量
- `MIN_DWELL_TICKS = 170`：晚点列车赶点时的最短停站（tick），即车门所需时间。
- `DOOR_CLOSE_MILLIS`：= (`DOOR_MOVE_TIME` + `DOOR_DELAY`) × 50 毫秒；列车在计划发车前这么久关门，使其准点离开。
- `DEFAULT_EARLY_THRESHOLD_MILLIS = 30000`：早到阈值默认值（网络未设定时）：全速运行到站最多可以早这么多再降速巡航（优化第 4 轮第 5 步）。

### `static int getTotalDwellTicks(Train train, int dwellTicks)`
- 作用：晚点列车缩短停站以赶点，但不少于车门所需。手动驾驶或下一停站无计划时返回路径停站时间；否则调用下面的重载。
- 参数：`dwellTicks` 路径上的停站时间（tick）。
- 调用者：`TrainServer.java:289`、`TickRunner`。

### `static int getTotalDwellTicks(int dwellTicks, float elapsedDwellTicks, long departureMillis, long currentMillis)`
总停站 = max(min(路径停站, 已停 + 到计划发车剩余 tick), min(路径停站, `MIN_DWELL_TICKS`))。调用者：`TrainServer.java:1237`。

### `static boolean isWaitingForDeparture(long departureMillis, long currentMillis)`
定时停站的列车是否仍在等计划发车：现在 < 发车 − `DOOR_CLOSE_MILLIS`。调用者：`TickRunner.java:2392`。

### `static boolean waitsBeforeRequestingRun(Train train, int stop)`
在为站台之后的轨道预订运行前，先检查时钟/调度扣车：调度扣车、留线过夜、等运用发车、在折返轨等待离开时刻（且没挡住别人）、或该站为定时停站且未到发车时刻，都返回 true。调用者：`TickRunner.java:2057`。

### `static int getTicksUntilDoorsClose(Train train)`
- 作用：停站列车多久后开始关门，供客户端的关门音和动画（0.65.30）：否则它们从站台轨道的停站时间算起，时刻表更晚的发车会让它太短，导致门还开着就响了关门提示。
- 返回：到开始关门的 tick；车门未开、或被未知结束时间的原因扣住（调度员、越行列车、前方运行未获批、待选择去向、前方轨道被占）时 −1。手动驾驶、连续运动、调度扣车、留线时也为 −1。
- 逻辑：扣到时刻 = max(运用发车 − 关门时间, 定时停站计划发车 − 关门时间)；越行等待中返回 −1；运用列车前方运行未获批且已过扣到时刻返回 −1；其余委托下面的重载。
- 调用者：`TrainServer.java:300`、`TrainServer.java:1190`。
- 注：源码在其上方有一段孤立 javadoc（"停站列车保持开门不离开时为真"），描述的是 `isHeldAtStop`。

### `static int getTicksUntilDoorsClose(int totalDwellTicks, float elapsedDwellTicks, long holdUntilMillis, long currentMillis)`
- 作用：车门在"停站时间减关门所需"处关闭（同 `areDoorsOpen`），已知时刻的扣车会推迟到那时。若扣车已过且已停到关门点仍在站，说明被未知原因扣住，返回 −1；否则 = ⌈关门点 − 已停⌉（≥ 0）+ 扣车剩余 tick。
- 参数：`holdUntilMillis` 开门扣车到何时，0 为无。

### `static boolean isHeldAtStop(Train train)`
`getHoldReason` 非空。调用者：`TrainServer.java:294`、`TickRunner.java:2988`、`MotionFixture.java:419`。

### `static String getHoldReason(Train train)`
- 作用：停站列车是否被扣及原因，判断方式与 `isHeldAtStop` 相同，包含选择去向的各步（0.65.31）；列车保存该结果供调试显示。按顺序：手动 → ""；调度扣车 "dispatch"（D6）；留线过夜 "staying"；未到运用发车 "duty_departure"；在折返轨未到离开时刻且没挡住别人 "turn_back"（若挡住了别人则去站台等，6 号线 2026-11-12 的问题）；定时停站未到发车 "timetable"；有越行列车将到 "overtake"（最多等 `MAX_OVERTAKE_WAIT_MILLIS`）；非连续运动时依次让服务选择：回库 "depot_return"、运用回送 "duty_return"、折返去向 "turn_back_way"、调度去向 "dispatch_way"、交接 "hand_over"、提前折返 "early_turn"、跳站 "skip"（这些 `choose*` 方法在需要等待时返回 true，可能修改服务路径）；最后运用列车前方运行未获批 "run"；否则 ""。
- 副作用：调用 `TrainService.choose*`，可能改变列车去向。
- 调用者：`TrainServer.java:295`、`TickRunner.java:2989`。

### `static float getCruiseCap(Train train)`
- 作用：距下一站计划到达有富余时降速巡航，但只降到"比早到阈值更早"的那部分：早到不超过阈值时全速运行、把余量用在站台停留（给前方延误留余地）；更早时巡航到恰好早阈值到达（第 4 轮第 5 步）。阈值为 0 时准点到达。晚点列车无上限、全速赶点（设计文档 8.2）。途中被拦后重新出发的列车从当前位置求上限（审查 A04）。
- 返回：巡航上限（方块/tick），无上限为正无穷。
- 调用者：`TrainServer.java:353`、`TrainServer.java:564`、`TickRunner`。

### `static double getTargetTicks(long plannedMillis, long nowMillis, long earlyThresholdMillis)`
到下一站可用的 tick 数 = (计划到达 − max(0, 阈值) − 现在) / 50。调用者：`MotionFixture.java:377`；`getCruiseCapOf`。

### `private static float getCruiseCapOf(Train train)`
不遵循速度曲线或下一停站越界时无上限；无计划到达（≤ 0）时无上限；否则 `SpeedCurves.findCruiseCap(进度, 速度, 下一停站, 目标 tick)`。

### `static boolean openDoors(Train train)`
- 作用：独立运行列车此刻是否开门（手动列车由司机开门，不询问）。连续运动（缆车）：在有停站时间的轨道上，按距轨道两端的距离推算开门值；否则：折返轨（无站台 id）不开门（避免在折返轨长时间等待时开门），站台按 `areDoorsOpen`。
- 调用者：`TrainServer.java:365`、`TickRunner.java:3058`。

### `static boolean areDoorsOpen(float elapsedDwellTicks, int dwellTicks)`
停站 `elapsedDwellTicks` 时车门是否开着：已停 ≥ `DOOR_DELAY` 且 < 停站 − `DOOR_DELAY` − min(`DOOR_MOVE_TIME`, 停站/2 − `DOOR_DELAY`)。调用者：`TickRunner.java:1870`、`TickRunner.java:2393`。

### `static int findNextStoppingIndex(MotionTrack track, int headIndex)`
车头所在轨道起第一个有停站时间的轨道，否则最后一条。仓库内未找到外部调用者。

### `static int findNextStop(MotionTrack track, double railProgress)`
车头前方第一个停站（停车位置 > 当前进度，使停在轨道末端之前的列车不会再取同一站，第 4 轮），否则最后一条。调用者：`TrainServer.java:1608`、`TickRunner.java:2946`。

### `static int findRunEnd(MotionTrack track, int fromIndex)`
从该轨道起第一个停站（含），否则最后一条。调用者：`TrainServer.java:1085`、`TrainServer.java:1379`。

### `static long getPlannedDepartureMillis(Train train)`
列车计划离站时刻（两车同时申请时决定先后）：有运用发车时刻用它；否则下一停站计划发车；都没有用当前时刻。调用者：`TrainServer.java:1092`、`TickRunner.java:2060`。

## 内部接口 Train
规则读取的列车及其环境。游戏服务器列车与仿真器实现。
- `long getMillis()`：时刻表计时的当前时刻。
- `TrainService<?> getService()`。
- `default boolean isBlockingOthers()`：上次 resolve 时该列车是否挡住了别人的申请（其运行预订），默认 false。
- `MotionState getState()`、`MotionTrack getTrack()`。
- `long getStopId(int rail)`：该路径轨道的站台或停车线 id，折返轨为 0。
- `boolean isContinuousMovement()`、`boolean usesSpeedCurve()`、`SpeedCurves getSpeedCurves()`。
- `int getTotalDwellTicks()`：下一停站的停站 tick（按 `getTotalDwellTicks(Train, int)` 计算）。
- `boolean isOvertakingTrainComing(long platformId, long arrivalMillis, long departureMillis, long currentMillis)`：本车停站期间计划通过该站的列车尚未经过（在该站另一站台）时为真。
- `default long getEarlyThresholdMillis()`：网络早到阈值（第 4 轮第 5 步），默认 0（准点到达）。
- `boolean isRunGranted(int fromIndex)`：申请从该轨道到其后下一停站的运行，持有后返回 true。
