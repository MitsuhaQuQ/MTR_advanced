# DisplayArrivals.java

乘客显示屏上每列车当前班次之后的班次（0.83.16）：按运用和时刻表，只给有车会跑的。分两步：`dutyStops` 由路径和时刻表列出一份运用的计划停站（慢，只随路径/时刻表变化）；`compute` 把运用和车的现状放在一起（快）。纯组件，游戏端由 `mtr.data.PidsSchedules` 调用。测试 `DisplayArrivalsTest`。

## 常量
- `PER_PLATFORM` = 16：每站台保留的条数，多于任何显示屏一页能显示的。

## static final class Stop
一个计划停站或通过：站台、线路、站序、计划到发（完整时间戳）、是否通过、最小停站（不超过计划停站，准点车保持准点；通过为 0）。

## static final class Duty
一份运用某天的情况：键、车池、车厢数、`overnightStart`（由前一天过夜的车接着跑）、`nextKey`（过夜时次日接续的运用键，不过夜为 null）、出车与回库时刻、各段停站（没路径为空）。`hasPath()`：有任何停站。

## static final class Running
一列在跑运用的车：运用键、列车号、所在段、从哪一段起由计划给（在线为下一段，在存车线里为当前段）、晚点起点（晚点开始时那站的计划发车，不晚点为 0）。

## static final class Arrival / Result
`Arrival`：一个停站与预计到站、运用、列车号（没出车为 0）。`Result`：站台 → 前 `PER_PLATFORM` 条（按预计到站、线路、运用键排序），以及没算的运用数 `noPath`、`noOvernightTrain`、`noTrain`。

## `static List<List<Stop>> dutyStops(DayDuty duty, long dayStart, TripPath<T> path, List<? extends PlanRoute> routes)`
原 `PidsSchedules.addDuty` 的算法：每段线路须一致、至少两站，时刻表当天运行、含该班次、未停运；首末站、通过标志；线路起点偏移（首段：首站非 0 时为负的入口偏移；后续段：下一线路起点的 `routeStartMillis`）；每个在路径上有轨道、有停站计划的站：到站 = 班次 + 计划到站 − 偏移，发车同理（通过时等于到站），最小停站 = 轨道停站时间（半秒）× 500。路径与运用不符时空列表。

## `static long leaveMillis(segments, leadMillis)` / `static long backMillis(segments, returnMillis)`
出车 = 首站计划到站 − 出库时间；回库 = 末站计划发车 + 回库时间。

## `static Result compute(Collection<Duty> duties, Collection<Running> running, Map<Object, Integer> idleTrains, Predicate<Duty> hasRun, long now, long untilMillis)`
1. 在跑的车（按列车号）：从所在段起第一个计划发车不早于晚点起点的站开始，发车 = 现在（晚点时），之后逐站：到站 = max(计划到站, 上站发车 + 计划运行时间)，发车 = max(计划发车, 到站 + 最小停站)；只给 `fromSegment` 起的段。过夜的把 `nextKey` 记为有车；回库的在回库时刻 + 带出的晚点后回到车池。
2. 没出车（不在跑、`hasRun` 为假）的按出车时刻、键排序：没路径不算；末站已过的（如前一天没车跑的）跳过、不计入没算的数；过夜起头的只有被记为有车才算（照计划给，过夜的再往下传）；其余从车池取最早空出来的车（空闲车为现在），出车 = max(计划出车, 现在, 车空出来)，晚了就整份从首站按晚的时间推后；晚过 `RouteTimetable.MAX_DEPLOY_DELAY_MILLIS` 时只从还赶得上的那一段起按计划给，一段都赶不上不算、不占车。用了车的同 1 回库或过夜。
3. 预计到站在 `(now, untilMillis]` 内的按站台归组、排序、截断。
