# TimeSegment.java

路径上的一段"匀加速 / 匀减速 / 匀速"区间，用来求列车到达每个位置的时间和速度。时刻表/计划路径（`PathTimes`、游戏侧 `mtr.data.PathTiming`）生成一串 `TimeSegment`；游戏侧 `TrainServer`、`Train` 以及组件 `transitsim.sim.TrainService` 按位置查询时间。依赖 `transitsim.motion.MotionConstants`。

## 类 `TimeSegment`

### `public static int indexAt(List<TimeSegment> segments, double position)`
- 作用：二分查找第一个包含 `position`（含端点）的区间。
- 参数：`segments` 按位置有序；`position` 路径进度（rail progress，距离）。
- 返回值：区间下标；落在折返间隙（无区间覆盖）时为 -1。
- 主要调用者：`common/src/main/java/mtr/data/TrainServer.java:714`。

### 字段
- `endRailProgress`：区间终点的路径进度。
- `savedRailBaseId`：区间相关的站台/停车线 ID（由生成方填写，具体语义见宿主，未确定）。
- `pass`：是否为通过（不停车）区间（由生成方填写）。
- `routeId`、`currentStationIndex`：所属路线与当前站序（由生成方填写）。
- `endTime`：区间终点时间（与 `startTime` 同单位，未在此处声明；游戏中为 tick，见 `getSpeed` 注释"每 tick 方块数"）。
- `indexDistances`、`indexDistancesSize`、`indexProgress`、`index`（transient，不保存）：缓存 `endRailProgress` 在路径上的下标，以及计算时使用的距离表与其长度，避免列车每几 tick 写时刻时对每段都重新搜索路径。由 `common/src/main/java/mtr/data/Train.java:903`–`908` 使用。
- `startRailProgress`（final）：区间起点进度。
- `startSpeed`、`startTime`（private final）：起点速度（方块/tick）与时间。
- `speedChange`（final）：-1 减速、0 匀速、1 加速（构造时 `Integer.compare(speedChange, 0)`）。
- `accelerationConstant`（private final）：加速度绝对值，四舍五入到 3 位小数；≤0 时用 `MotionConstants.ACCELERATION_DEFAULT`。

### `public TimeSegment(double startRailProgress, float startSpeed, float startTime, int speedChange, float accelerationConstant)`
按上述规则赋值。主要调用者：`transitsim/plan/PathTimes.java:112`、`common/src/main/java/mtr/data/PathTiming.java:101`。

### `public double getSpeed(double railProgress)`
- 返回值：该进度处的速度，方块/tick。匀速返回 `startSpeed`；否则 `sqrt(max(0, 2·a·Δs + v0²))`。

### `public double getTime(double railProgress)`
- 作用：返回到达该进度的时间。匀速：`startTime + Δs / v0`；变速：`startTime + (sqrt(max(0, 2aΔs + v0²)) - v0) / a`，Δs 为 0 时直接 `startTime`。
- 说明（0.66.8 注释）：制动时加速度被舍入到 3 位小数，可能让列车略早于区间终点停下，原先根号内为负得到 NaN，导致停站列车"没有时间"、乘客显示屏无显示，因此加了 `max(0, …)`。
- 主要调用者：`transitsim/sim/TrainService.java:1303`、`common/src/main/java/mtr/data/Depot.java:3896`、`mtr/data/TrainServer.java:724`。

### `private static float round(double value, int decimalPlaces)`
四舍五入到指定小数位。
