# SpeedRules.java

游戏中一个限速标签代表的两种速度（`SpeedLimits`，优化文档 5.6）：列车实际运行到"物理极限速度"，时刻表按"计划速度"编排。缆车和飞机保持标签原值（优化文档 5.12）。另外规定停车线、折返线和站台（可单独设置通过速度，见 `PlatformSpeeds`）的标签速度。被 `TrackViews`、`PathData`、`PathTiming`、`Train`、`GraphRecords`、蓝图地图 `UpdateBlueMap` 使用；组件侧的 `SpeedLimits` 提供换算。

## final class SpeedRules

### 常量
- `SIDING_KMH = 25`：停车线的标签速度（km/h，第 3b 轮，用户给定）。
- `TURN_BACK_KMH = 65`：折返线的标签速度（km/h）。

### `private SpeedRules()`
工具类，禁止实例化。

### `public static int labelKmh(RailType railType, TransportMode transportMode)`
- 等价 `labelKmh(railType, transportMode, 0, 0)`（不查站台单独设置）。
- 主要调用者：`GraphRecords.java:559`；测试 `PlatformSpeedsTest.java:52`。

### `public static int labelKmh(RailType railType, TransportMode transportMode, long node1, long node2)`
- 作用：一段轨道的标签速度（km/h）。若该交通方式适用两种速度（`applies`）：站台且 `node1 != node2` 时取 `PlatformSpeeds.getKmh(node1,node2)`；停车线取 25；折返线取 65；否则取 `railType.speedLimit`。
- 参数：`node1/node2` 轨道两端节点（`BlockPos.asLong()`），用于查站台通过速度；不需要时传 0。

### `public static float label(RailType railType, TransportMode transportMode)` / `label(..., long node1, long node2)`
- 作用：同上，但返回方块/tick：若 km/h 等于轨道类型自身标签则直接用 `railType.maxBlocksPerTick`，否则 `SpeedLimits.toBlocksPerTick(kmh)`。
- 主要调用者：`PathData.java:137`、`UpdateBlueMap.java:42`。

### `public static boolean keepsLimitBefore(RailType railType, TransportMode transportMode)`
- 作用：此类轨道是否沿用前一段轨道的限速（旧 MTR 中站台、停车线、折返线如此）。第 3b 轮起只对不适用两种速度的交通方式成立：`!railType.canAccelerate && !applies(mode)`。信号闭塞仍不切断它们。
- 主要调用者：`TrackViews.java:60`、`PathTiming.java:82`、`Train.java:951`。

### `public static boolean applies(TransportMode transportMode)`
- 返回：两种速度是否适用：非连续运动（缆车等）且非飞机。

### `public static float run(float blocksPerTick, TransportMode transportMode)`
- 作用：标签速度 → 列车可实际运行的速度：适用时 `SpeedLimits.physicalBlocksPerTick`，否则原值。参数 0 表示无。
- 主要调用者：`TrackViews.java:49`、`Train.java:998`、`PathData.java:146`。

### `public static float plan(float blocksPerTick, TransportMode transportMode)`
- 作用：标签速度 → 时刻表编排速度：适用时 `SpeedLimits.planBlocksPerTick`，否则原值。
- 主要调用者：`TrackViews.java:49`、`PathTiming.java:193`、`PathData.java:153`。
