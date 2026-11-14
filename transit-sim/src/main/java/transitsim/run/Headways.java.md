# Headways.java

两列车在一条路径上最近能跟多近（B，2026-11-14）：每个闭塞分区上前后两车之间的最短时间，给时刻表冲突检查用。六线图 7 号线 30 秒一班，冲突检查说可行，实际只能 35–39 秒一列。

规则和运行时一致：前车占着分区，直到车尾再加它身后留的间距（一节车厢，0.69.0）离开分区终点；后车要在开始制动、停在分区起点前同样间距处之前拿到这个分区。所以分区 [b, e] 上，后车到达"为 b 制动的那一点"必须晚于前车车头到达 e + 车长 + 间距，再加批准要的 2 tick（`GRANT_TICKS`）。后车本来就要在分区前停站（站台在分区前）时，它只在离站时才需要这个分区，按离站时刻算。前车停在路径最后一站、车尾还在分区里时（终点站前的那一段），按它在最后一站的离站时刻（到站 + 停站）算；最后一站的站台本身由站台检查管，这里略去。分区就是列车申请的那些组（`PathClaims.between`：闭塞分区、涂色闭塞区整块、单线区间一整段）。时间用两车各自在路径上的计划时刻（`TimeSegment`）。

校准：六线图世界离线（`simulateDay --headways` 对照 `--events` 的到站），7 号线算出最多 40.5 秒，实际最近 39.8 秒。

## 类 Headways（final，工具类）

### 常量
- `GRANT_TICKS = 2`：分区空出后批下来要的 tick（tick 末统一决定，下一 tick 才动）。

### `static <T extends RunTrack> List<Gap> find(List<T> path, BlockSections sections, Train ahead, Train behind)`
- 作用：路径上每个分区两车最少要隔多久（毫秒）；在两车时刻范围两端外的分区略去。路径经过同一段轨道不止一次时（运用的多圈、折返），按每段轨道只出现一次切成几截分别求组：列车只申请前方，一次求组时已申请过的会并进下一组，把第二圈的几片并成一整段（2026-11-14 7 号线算出 120 秒就是这个原因）。
- 调用者：游戏 `RoutePaths.RoutePath.getHeadways`、工具 `SimulateDay --headways`；测试 `HeadwaysTest`。

### `static long[] byStop(List<Gap> gaps, double[] stopPositions)`
每一站到下一站之间各分区所需时间的最大值（分区按起点归到起点之前最近的一站），没有为 0。

### 私有
- `brakingPoint(train, stopAt)`：以计划速度能在 `stopAt` 前停下的最远位置（二分）。
- `stopBetween(times, from, to)`：两点之间计划停站的位置（速度为 0 且下一段时间晚于这一段结束），没有为 NaN；`departureAt` 取那一站的离站时刻。
- `topSpeedBraking`、`last`、`segmentAt`、`timeAt`、`speedAt`：在时刻段上取时间和速度（超出两端取端点）。

## 内部类 Train（public static final）
`times`（计划时刻段）、`length`（车长）、`gap`（身后留的间距，车厢长）、`deceleration`（方块/tick²，0 及以下用默认）。

## 内部类 Gap（public static final）
`start`、`end`（沿路径的位置）、`millis`（最少要隔的时间）、`singleTrack`（这一组带方向声明：单线区间或按行车方向的复线）。
