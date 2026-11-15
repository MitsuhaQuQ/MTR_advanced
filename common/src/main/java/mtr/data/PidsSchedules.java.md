# PidsSchedules.java

乘客显示屏与到站表里每列车当前班次之后的班次（0.83.16）：按车厂运用和时刻表给出，只给有车会跑的运用，由组件 `transitsim.plan.DisplayArrivals` 计算。列车自己只报当前班次（`TrainServer`），这里补其余的。运用的计划停站在共享工作线程上每分钟或路径/时刻表变了才重算；每 3 秒和列车现状合成一次。`RailwayData` 持有一个实例（`pidsSchedules`）。包内可见。

## final class PidsSchedules

### 字段
- `planned`（volatile，不可变）：站台 id → 计划到站（`ScheduleEntry`，按时刻），网页线程也读。
- `built`（volatile）：工作线程算好的运用（`Built`）；`building` 正在算的任务；`builtMillis`/`builtSignature` 上次重建的时刻与签名；`stale` 是否该重新合成；`leftOutTold` 上次写日志的没算运用数。
- `REBUILD_MILLIS` = 60 秒。

### `void markStale()` / `boolean isStale()`
`RailwayData` 每 `SCHEDULE_UPDATE_TICKS` 标记过期；用时才合成。

### `void refresh(Set<Depot> depots, Collection<Siding> sidings, DataCache dataCache, long now)`
- 签名（各存车线 `getPathSignature`、各线路时刻表 `revision`）变了、超过一分钟或还没算过且没有任务在跑时：`gather` 后交给 `WorkPool.submit` 跑 `build`，异常时写日志、保留上次结果。
- 用上次算好的运用：从各存车线 `indexTrains` 收集列车：跑运用的（`TrainService.isOnDuty`）记成 `DisplayArrivals.Running`（键 = 运用存车线:槽位:运营日编号，所在段 `getDutyIndex`，在线时从下一段起给、晚点起点 `getLateSinceMillis`；在存车线里时从当前段起给、不算晚点）；不在线、不跑运用的按车池（车厂 id + `Siding.getPoolKey`）数空闲车。已出车用 `Depot.hasDutyRun`。调 `DisplayArrivals.compute`（截止现在 + 1 天），转成 `ScheduleEntry`（在跑运用的带列车号、`planned=false`；没出车的 `trainId=0`、`planned=true`；`scheduledMillis` 为计划到站）。
- 没算的运用数（无路径 / 无过夜车 / 无车）变化且不为 0 时写一条服务端日志。

### `List<ScheduleEntry> getPlanned(long platformId)` / `Set<Long> getPlannedPlatforms()`
上次合成的结果。

### `static List<ScheduleEntry> forDisplay(List<ScheduleEntry> live, List<ScheduleEntry> planned, long now)`
- 实时条目去掉 `standingForDuty`（在存车线等运用的估计）；计划条目在 `now` 之后、且没有实时条目与它线路、站序、计划时刻都相同时加入（同一班次只出现一次）。两者都为 null 时空列表。
- 调用者：`RailwayData` 发包、`getAllSchedulesAtPlatform`、`getSchedulesForStation`。

### `static String getKey(long sidingId, int slot, long day)`
运用键 `存车线:槽位:运营日编号`。

### `static List<Job> gather(Set<Depot> depots, DataCache dataCache, long now)`
服务端线程上：线路固定版本（`PlanVersions.fix`）；每个按运用运行的车厂、属于它且车厢数 > 0 的存车线、前一天到明天三天、每个当天有运用的槽位：运用拷贝（`DayDuty.copy`）、是否过夜起头（`DutyShapes.getOvernightSlot`）、过夜时次日接续的运用键（`nextDutyId` 用 `findSlot`，否则 `getNextSlot`；找不到为空串）、车池、车厢数、本存车线和同车池存车线的路径表拷贝。

### `static List<Built> build(List<Job> jobs)`
工作线程上：`DutyShapes.getSlotKey` 求路径键，依次在本存车线、同车池存车线找运用路径；`DisplayArrivals.dutyStops` 列停站；出车 = 首站计划到站 − 出库时间（`stopTimes.getLeadMillis`），回库 = 末站计划发车 + 出库时间（过夜的为 0）。没路径的运用停站为空。

## static final class Job / Built
`Job`：`gather` 取的一份运用。`Built`：算好的 `DisplayArrivals.Duty` 与车厂、存车线、槽位、运营日编号（查 `hasDutyRun` 用）。
