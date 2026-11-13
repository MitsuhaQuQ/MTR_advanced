# DepotDispatcher.java

车厂发车时要决定的事：本车厂列车已占用了哪些服务、下一列车占用哪个服务、哪个槽位的运用下一个出发。路径和存档由宿主保存，并由宿主让列车出发。

在整体中的位置：游戏侧 `Depot` 持有一个 `dispatcher`（`Depot.java:1861` `startTick`、`:1922` `findDutyDeploy`、`:2292` `getNextDepartureMillis`、`:2306` `getMillisUntilDeploy`、`:1780`/`:1814` `chooseReturnSiding`，存档读写 `lastDeployedTrips`/`dutyRunDays`/`takeovers`，`:1536-1540` 从仿真结果复制）；组件 `DepotBoard.dispatcher`，`TickRunner`（`:1029` `startTick`、`:1303` `findDutyDeploy`、`:255` `requestTakeover`、`:2153`/`:2180` 站立服务）。使用 plan 包的 `DepotDuties`、`DutyShapes`、`OperatingDay`、`PlanRoute`、`PathVariants`、`PlanTrack`、`RouteTimetable`、`TripPath`。

## 类 DepotDispatcher（public，非 final）

### 公共字段（宿主保存）
- `lastDeployedTrips`（Map<Long, Long>）：每条首线路最后发出的服务，下一列车取更晚的。
- `dutyRunDays`（Map<String, Long>）：每个槽位的运用最后运行的运营日，键为 `"停车线:槽位"`（或按运用编号 `"#id"`，见 `runKey`）。
- `releaseIntervalMillis`（int）：车厂按运用发车的最小间隔，0 = 无（由宿主设置；解耦文档 D 步"车厂发车 2"）。
- `lateServices`（`LateServices`，0.78.0）：晚点车放弃的、预计赶不上的、已被接走的班次；宿主按 `toList()` 存。`giveUpService`、`expectLate`、`isServiceTaken`、`takeFreeService`（连同共用线路的 `peers` 一起找、认领后不再给别的车）。
- `takeovers`（List<String>）：由备用车从某段起接管的运用（D5），每项 `"运用停车线 槽位 dayStart 起始段"`，按申请顺序。
- `slotsOf`（`LongFunction<List<DepotDuties.Slot>>`）：停车线 → 槽位，用于按运用编号而非位置记录已运行（P2-D）；null 则按位置。

### 私有字段
- `reservedTrips` / `newReservedTrips`（Set<Long>）：上一 tick 与本 tick 被占用的服务（服务键见 `getTripKey`）；占用服务的列车每 tick 重新预订。
- `standingTrips`（Map<Long, Integer>）：不是每 tick 都被仿真移动的列车所预订的服务及预订次数（S4f）。
- `peers`（List<DepotDispatcher>）：共用线路的车厂；它们的列车占用的服务在本车厂也视为已占用。
- `lastReleaseMillis`：上次按运用发车时刻。
- `takeoverAffected`（Map<String, Set<UUID>>）：每个接管项申请时备用车出库路径不得经过的轨道；不保存。
- `lastReadySidings`（Set<Long>）：上次查看时有车可发的停车线，供两次查看之间申请备用车使用。
- `fromPaths`（Map<String, Object[]>）：为备用车和晚发列车生成的"从中途开始"的运用路径缓存，键 `"停车线|key"`，值 `{基础路径, 生成路径}`，基础路径变化才重算。
- `slotKeys`、`slotKeysMinute`、`slotKeysWeekday`、`slotKeysDuties`：槽位路径键缓存，按当日分钟、星期与运用表对象失效（端到端检查发现每 tick 为每个运用计算键占了大部分时间）。

### 常量
- `FROM_TOLERANCE_MILLIS = 30000`（private）：备用车或晚发列车可晚于某段计划多久仍去该段；再晚则改去下一段。
- `DUTY_DEPLOY_MARGIN_MILLIS = 15000`（private）：出库富余。
- `MAX_LEAD_MILLIS = 4 小时`（包内）：比任何出库到首站的时间都长。

### `void startTick()`
每 tick 列车预订服务前调用：交换两个集合并清空新集合，忘掉上一 tick 无人预订的服务。调用者：`Depot.java:1861`、`TickRunner.java:1029`。

### `void reserveTrip(long routeId, long tripMillis)`
`tripMillis != 0` 时把服务键加入本 tick 预订。调用者：`DepotBoard.java:152`；本类 `findNextService`。

### `boolean isTripReserved(long routeId, long tripMillis)`
本车厂或任一同伴是否已占用该服务。调用者：`DepotBoard.java:167`。

### `private boolean isReservedHere(long tripKey)`
在上 tick、本 tick 或站立预订中。

### `long getLastDeployedTrip(long routeId)`
本车厂及同伴在该首线路最后发出的服务（取最大），使一方已跑完的服务不会被再发一次。调用者：`TickRunner.java:1355`、`TickRunner.java:2217`。

### `void shareServicesWith(DepotDispatcher other)` / `void clearPeers()`
互相登记为同伴 / 清空同伴（重新计算前）。调用者：`DepotBoard.java:89`、`Depot.java:2245`；`Depot.java:2250`。

### `void addStandingTrips(Collection<Long> tripKeys)` / `void removeStandingTrips(Collection<Long> tripKeys)`
对不是每 tick 移动的列车，持续预订其服务（计数加/减）。调用者：`TickRunner.java:2180`、`TickRunner.java:2153`。

### `long findNextService(PlanRoute route, long readyMillis, boolean staysOnLine)`
- 作用：该线路从 `readyMillis` 起第一个无人占用的服务，并立即预订。可等待上限：留在线上为 `MAX_STAY_MILLIS`，否则 `MAX_LAYOVER_MILLIS`。
- 返回：服务时刻，等太久则 0。调用者：`DepotBoard.java:147`。

### `int getNextDepartureMillis(RouteTimetable timetable, long firstRouteId, long currentMillis, long leadMillis, int count)`
- 作用：第 count 个（从 1 起）无人占用的服务，列车需要多久之后离开停车线（服务时刻 − 出库用时 − 现在）。一天内没有返回 −1。
- 调用者：`Depot.java:2292`。

### `private String runKey(long sidingId, int slot)`
有 `slotsOf` 且槽位有非 0 运用 id 时为 `"#id"`，否则 `"停车线:槽位"`。

### `boolean hasDutyRun(long sidingId, int slot, long day)` / `void markDutyRun(long sidingId, int slot, long day)`
查询/标记某槽位某运营日已运行；标记时删除早于 day−2 的记录。调用者：`DepotBoard.java:182`、`:187`。

### `DutyDeploy<T> findDutyDeploy(long currentMillis, Collection<Long> sidingIds, DepotDuties duties, LongFunction<Map<String, TripPath<T>>> tripPaths, LongFunction<? extends PlanRoute> routes)`
每条停车线自成一池的便捷形式。调用者：`DutyRunTest.java:801`。

### `DutyDeploy<T> findDutyDeploy(..., LongFunction<Object> pools)`
停车线按池（同车型可互相顶替运用）的形式，不能生成中途路径。调用者：测试。

### `DutyDeploy<T> findDutyDeploy(long currentMillis, Collection<Long> sidingIds, DepotDuties duties, LongFunction<Map<String, TripPath<T>>> tripPaths, LongFunction<? extends PlanRoute> routes, LongFunction<Object> pools, LongFunction<PathVariants<T>> variants)`
- 作用：按运用运行的车厂为今天从停车线出发的每个槽位发一列车，在首个服务到期时、每天一次（设计文档 6.12 E2）。由过夜留线列车接管的槽位留给那列车。每 tick 只发一列（最早到期的），并标记已运行。
  1. 记录 `lastReadySidings`；距上次发车不足 `releaseIntervalMillis` 返回 null。
  2. 先看接管（`findTakeoverDeploy`），有到期的备用车则发它。
  3. 遍历每个停车线的每个槽位：跳过今天无运用、已运行、由过夜车接管、或首服务距今超过 `MAX_LEAD_MILLIS` 的；求路径键（`getSlotKey`）。
  4. 选出发停车线（S8）：自己的停车线有车且不是 `START_ANY` 时优先；否则（非 `START_ONLY`）在同池其他有车的停车线中找有该路径的；`START_ANY` 时取出库最快的（同样快按 id）。
  5. 发车时刻 = 首服务 − `outLeadMillis(duty, wayOutMillis(停车线, 运用路径))`（0.75.0 起出库用时含车厂采用的出库余量）。晚了超过 `MAX_DEPLOY_DELAY_MILLIS` 且运用仍在运行时：不让列车凭空出现在线上（D5），用 `findFrom` 找它出库后仍赶得上的第一段，找不到则标记今天不运行并打印日志；赶得上且已到期则候选。
  6. 正常到期的候选中：不晚的优先于晚的，再取发车时刻更早的。
- 参数：`sidingIds` 有车可发的停车线（有序）；`duties` 运用表；`tripPaths` 停车线 → 路径键 → 路径（停车线不存在为 null）；`routes` 线路查询；`pools` 停车线 → 池；`variants` 停车线按需生成路径的能力，null 为不能。
- 返回：要发的 `DutyDeploy`，或 null。副作用：标记已运行、更新 `lastReleaseMillis`、可能打印日志。
- 调用者：`Depot.java:1922`、`TickRunner.java:1303`。

### `public volatile Buffers addedBuffers`（0.75.0）
车厂采用的余量（D1、第 4 轮第 6 步），由宿主设置：游戏 `Depot` 每次改 `addedBuffers` 时同步，组件 `SimDataset.buildRunner` 从 `DepotData.addedBuffers`（数据第 20 版）设置。

### `<T> long wayOutMillis(long sidingId, TripPath<T> dutyPath)`（0.75.0）
出库用时：`dutyPath.stopTimes.getLeadMillis()` 加上 `addedBuffers.out(停车线, 路径上第一个站台停站的站台 ID)`；没有采用的余量时就是计划的。用户 2026-11-13 定"出库提前量按实际跑的记下来自动校正"：采用的出库余量当场让列车提前发车，不等重新排运用。调用者：本类 `findDutyDeploy`、`TickRunner.placeDutyTrains`、`DutyRunTest.anAdoptedWayOutSendsTheTrainOutThatMuchEarlier`。

### `static long outLeadMillis(DepotDuties.DayDuty duty, long wayOutMillis)`
运用列车在首服务前多久离开停车线：按计划（运用补完第 1 步）但不少于出库用时；旧计划（`outLeadMillis < 0`）为出库用时 + 15 秒。调用者：`TickRunner.java:734`、`DutyItemsTest.java:80`。

### `private String getSlotKey(long currentMillis, DepotDuties duties, List<DepotDuties.Slot> slots, int slotIndex, int weekday, LongFunction<? extends PlanRoute> routes)`
带缓存的 `DutyShapes.getSlotKey`（一分钟内、同星期、同运用表对象有效）。

### `int requestTakeover(long currentMillis, long dutySidingId, int slot, long dayStart, int fromSegment, String fromKey, long serviceMillis, Set<UUID> affected, LongFunction<Map<String, TripPath<T>>> tripPaths, LongFunction<PathVariants<T>> variants, LongFunction<Object> pools)`
- 作用：申请备用车从某段起接管运用（D5）：在上次有车可发的停车线中按"运用自己的停车线优先，再同池其他"顺序，找出库能及时到达该段首站、且出库路径不经过 `affected` 轨道的。找到则替换该运用已有的接管项并登记，到期时由 `findDutyDeploy` 发出。
- 参数：`fromKey` 从该段开始的运用路径键；`serviceMillis` 该段服务时刻；`affected` 备用车出库不得经过的轨道。
- 返回：`TrainService.TAKEOVER_OK`；只有经过这些轨道才行时 `TAKEOVER_CROSSES`；否则 `TAKEOVER_NO_SPARE`。
- 调用者：`TickRunner.java:255`（经 `DepotBoard.takeoverRequests`）、`Depot.java:1758`。

### `private DutyDeploy<T> findTakeoverDeploy(...)`
- 作用：查找到期的备用车：解析每个接管项（格式错误的删除）；求从该段起仍能赶上的第一段（`findFrom`，只看有车的同池停车线）；找不到时，只有当整个池子所有停车线都无法赶上剩余任何段、且所有段都已过去，才删除并打印日志；段后移时更新接管项；到期则移除并返回 `DutyDeploy`。
- 调用者：`findDutyDeploy`（第 262 行）。

### `private static boolean isPastAll(long currentMillis, long dayStart, DayDuty duty, int fromSegment)`
从该段起所有段的服务时刻都已过去。

### `private static Collection<Long> getAllSidings(DepotDuties duties)`
运用表中所有停车线。

### `private static List<Long> getPoolOrder(long dutySidingId, Collection<Long> sidingIds, LongFunction<Object> pools)`
这些停车线中：运用自己的停车线在前，然后同池其他（按 id）。

### `private Object[] findFrom(long currentMillis, long dayStart, DayDuty duty, String key, int fromSegment, List<Long> sidingIds, LongFunction<...> tripPaths, LongFunction<PathVariants<T>> variants, Set<UUID> affected)`
- 作用：从 `max(1, fromSegment)` 起，找这些停车线中某列车出库后能及时到达（现在 ≤ 段服务 − 出库用时 − 15 秒 + 30 秒容差）的第一段，出库路径不经过 `affected`。
- 返回：`{停车线, 路径, 发车时刻, 段}`，没有为 null。

### `private TripPath<T> getFromPath(long sidingId, String fromKey, LongFunction<...> tripPaths, LongFunction<PathVariants<T>> variants)`
从停车线常用路径（`TripPath.getTurnBase(fromKey)`）生成"从后面某段开始"的运用路径（`TripPath.withFrom`），按基础路径缓存。基础路径或变体能力缺失返回 null。

### `private static boolean crosses(TripPath<T> path, int segment, Set<UUID> affected)`
路径在到达该段首站之前（不含首站本身——那是晚点列车交接处，备用车在它离开后才到）是否经过任一受影响轨道。

### `private static TripPath<T> getPath(LongFunction<...> tripPaths, long sidingId, String key)`
取停车线的某路径，停车线不存在为 null。

### `long chooseReturnSiding(long ownSidingId, Collection<Long> poolSidingIds, DepotDuties duties, long currentMillis, LongPredicate hasRoom)`
同车厂池的便捷形式（用本车厂运用与 `hasDutyRun`）。

### `static long chooseReturnSiding(long ownSidingId, Collection<Long> poolSidingIds, LongFunction<List<Slot>> slotsOf, DutyRuns runs, long currentMillis, LongPredicate hasRoom)`
- 作用：池内列车运用结束后回哪条停车线（解耦文档 D 步"车厂发车 5"）：在有空位（无车停放或正驶往）的池内停车线中，选今天下一个尚未出发的运用最早开始的那条；同样早时自己的停车线优先；都不需要车则回自己的。支持池内停车线属于运营线路的不同车厂（各用自己的运用表和 dispatcher）。
- 调用者：`Depot.java:1780`、`Depot.java:1814`。

### `static int getMillisUntilDeploy(List<Integer> departures, long lastDeployedMillis, long currentMillis, int offset)`
- 作用：按频率发车的车厂：当天时间越过某个发车时刻、且上次发车早于该时刻时发一列。
- 参数：`departures` 一天中的发车时刻（有序）；`lastDeployedMillis` 上次发车时刻；`offset` 第几个发车（1 = 当前到期或下一个）。
- 返回：offset = 1 时，现在该发返回 0，否则返回到下一个发车时刻的毫秒；offset > 1 时返回到该发车时刻的毫秒；没有返回 −1。
- 调用者：`Depot.java:2306`。

### `private static long wrapTime(long time, long mustBeGreaterThan)`
把一天中的时刻按整天前移直到大于另一时刻。

### `static long getTripKey(long routeId, long tripMillis)`
服务键 = `routeId × 31 + tripMillis`。调用者：`DepotBoard.java:154`。

## 内部接口 DutyRuns（public，函数式）
`boolean hasDutyRun(long sidingId, int slot, long day)`：某停车线某槽位某天是否已运行（由该停车线所属车厂的 dispatcher 判断）。

## 内部类 DutyDeploy<T extends PlanTrack>（public static）
要从某停车线发出的槽位运用。
- `sidingId`：列车出发的停车线。
- `dutySidingId`：其槽位所属停车线（代替同池他线发车时不同）。
- `slot`：槽位下标。
- `dayStart`：运用所属运营日开始（完整时间戳）。
- `path`：运用路径。
- `departureMillis`（private）：发车时刻。
- `late`：比运用所需晚得多才发出，列车被放到仍能赶上的首站（见 `TrainService.joinDutyLate`）。
- `startSegment`：列车从停车线出发后开始执行的段（备用车或晚发列车为后面的段，D5）。
- 两个私有构造函数（后者带 `startSegment`）。
