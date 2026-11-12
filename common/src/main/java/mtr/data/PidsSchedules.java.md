# PidsSchedules.java

乘客显示屏用的"计划到站"：根据车厂的运用（duty）计划，算出每个站台下一班尚未出车的计划列车的到站时刻，与用于实际运行列车的实时到站表分开保存。由 `RailwayData` 调用（`RailwayData.java:704` 计算、`:715` 合并显示）。时间计算依赖组件的 `DutyShapes`、`OperatingDay`、`RouteTimetable` 与列车路径的 `TripPath.stopTimes`。包内可见。

## final class PidsSchedules

### `private PidsSchedules()`
工具类，禁止实例化。

### `static Map<Long, PlannedArrival> nextArrivals(Set<Depot> depots, DataCache dataCache, long now)`
- 作用：对每个有运用（`getRunDuties() != null`）的车厂、其每条属于本车厂且车厢数 > 0 的停车线、从今天运营日起到 `now + 1 天` 的每一天、每个运用槽（slot）：取该星期几的运用；跳过空运用和当天已经出车的（`depot.hasDutyRun`）；按 `DutyShapes.getSlotKey` 找到停车线的 `TripPath`，要求是运用路径、有停站时间、分段数一致，然后 `addDuty`。
- 返回：站台 id → 该站台最早的计划到站。
- 副作用：无（只读）；用 `IdentityHashMap` 缓存每条路径各分段的停站时间。
- 主要调用者：`RailwayData.java:704`；测试 `PidsSchedulesTest.java:76`。

### `private static void addDuty(Map<Long, PlannedArrival> arrivals, Map<Integer, long[][]> segmentTimes, Depot depot, Siding siding, int slot, long dayStart, DepotDuties.DayDuty duty, TripPath<PathData> path, DataCache dataCache, long now)`
- 作用：对运用的每个分段（一个线路班次）：线路须与分段一致、至少两站，时刻表当天运行且含该班次、未停运；求首末站与通过标志；计算该线路在路径中的起点偏移（首段：首站非 0 时为负的入口偏移；后续段：取停站计划中下一线路起点的 `routeStartMillis`）。对每个非通过、在路径上有对应轨道的停站，若站台存在且有停站计划：到站 = 班次时刻 + 计划到站 − 起点偏移；在 `(now, now+1天]` 内且比已有的更早时，记录 `ScheduleEntry(arrival, 车厢数, routeId, stop, trainId=0, pass=false, scheduled=arrival, planned=true, standingForDuty=false)`。
- 参数：`segmentTimes` 本路径各分段的停站时间缓存（`times[stop] = {..., ..., 轨道下标}`，第 3 列为轨道下标，−1 表示无；前两列含义未确定）。

### `static List<ScheduleEntry> forDisplay(List<ScheduleEntry> live, PlannedArrival planned, long now)`
- 作用：组合显示列表：实时条目中去掉 `standingForDuty` 的（车厂估计被计划替代）；计划条目在未来、对应运用当天尚未出车、且没有实时条目与它线路、站序、计划时刻都相同时加入。
- 返回：新列表；两者都为 `null` 时空列表。
- 主要调用者：`RailwayData.java:715`。

## static final class PlannedArrival
- 字段：`entry`（计划到站条目）、`depot`、`sidingId`、`slot`、`day`（运营日编号，`DutyShapes.getDayNumber`）。用于在显示时再次检查该运用是否已出车。
- 构造函数仅赋值。
