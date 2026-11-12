# DutyPlanner.java

从时刻表构建一周的列车分配（运用规划器的核心）。行程按时间顺序依次分配：每个行程取"在可达站台上最近才就绪"的在运列车，否则从最近的车厂（停车线）派车；在 `maxLayoverMillis` 内没事做的列车回自己的车厂。然后从这一周的结束状态重新跑一周，直到结束状态不再变化——这样把周日夜与周一早接起来。每个选择都有固定的平局规则，输入顺序不影响结果（确定性）。输入为 `TimetableService`（及直通 `Through`）与 `OperationNetwork`，输出 `OperationPlan`。由 `DutyGenerator.generate`（`DutyGenerator.java:229`）调用；测试见 `transit-sim/src/test/java/transitsim/planning/DeadEndTerminusTest.java`。

## 类 `DutyPlanner`（final）

### 常量
- `MAX_WEEKS = 16`（私有）：最多重跑多少周以等待稳定。
- `TURNING_LATE_WINDOW_MILLIS = 3 分钟`（包内）：行程到点后，在其终点折返的列车还能晚这么久接手它，而不是由后面开来的车厂列车接手。

### 字段
- `network`、`maxLayoverMillis`：规划所用网络与最长线上等待。
- `startsByFirstPlatform`：首站台 → 所有行程开始时刻（有序数组）。
- `issues`：展开阶段产生的问题。
- `noWayBack`：已报过 `NO_WAY_BACK` 的 "停车线 站台"，每对只报一次。
- `trips`：本周全部行程（按开始时刻、来源 ID 排序）。
- `ordered`：运行图上设置了指令（派车车厂、结束回库、回库车厂）的行程 → 其班次。
- `platformStops`：站台 → 有车运行的行程在此的停站（按到达排序），用于判断站台是否空闲；无车的班次不占站台。
- `returning`：按池，跑完运用、尚未分配停车线的列车。运行时在列车到达末站时把它送到池中"当天下一运用最早出发"的停车线（`DepotDispatcher.chooseReturnSiding`），这里即池下一次派车的停车线。随周推进填写。
- `holds`：站台 → 列车在其外折返线等待下一班的区间；随周推进填写。
- `startTrains`：各车厂本周开始时的列车数（用于统计在外列车）。
- `turningLate`：最近一次 `findTrainInService` 中，最先到达行程可达的另一站台、但来不及折返的列车；没有为 null。
- `shortTurnarounds`：站台 → {到达列车最少拥有的时间, 所需时间}（来不及折返的最坏情况）。
- `endingAt`：懒加载，站台 → 在此结束的行程的到达时刻。

### `private DutyPlanner(OperationNetwork network, long maxLayoverMillis)`
保存参数。

### `public static OperationPlan plan(List<TimetableService> services, List<TimetableService.Through> throughs, OperationNetwork network, long maxLayoverMillis)`
- 作用：展开行程（`expandTrips`）并求解（`solve`）。
- 参数：`services` 班次；`throughs` 直通行程；`network` 网络；`maxLayoverMillis` 列车在线上等下一行程的最长毫秒。
- 返回值：一周的计划。
- 主要调用者：`transitsim/planning/DutyGenerator.java:229`。

### `private void expandTrips(List<TimetableService> services, List<TimetableService.Through> throughs)`
- 作用：先按 ID 顺序展开每个直通行程在每个运行日的行程（`expandThrough`），记下被直通占用的 "班次/日"；再对每个班次每个运行日：已被直通占用则跳过；空白班次记 `UNUSED_BLANK`；否则生成行程（时间 = 日 × 一天 + 首站到达 + 各站偏移），有运行图指令的记入 `ordered`。最后排序并建立 `startsByFirstPlatform`。

### `private void expandThrough(TimetableService.Through through, int day, Map<Long, TimetableService> serviceMap, Set<String> takenByThrough)`
- 作用：把直通的各班次依次接成一个行程：后一段若显得早于前一段出发则视为跨过午夜（+1 天）；两段之间不可达或时间不够、班次缺失或当天不运行时记 `THROUGH_CONFLICT` 并放弃整个直通。成功则记下占用并加入行程。

### `private static void appendStops(List<TripStop> stops, TimetableService service, long startMillis)`
把班次各站加上 `startMillis` 后追加为 `TripStop`。

### `private OperationPlan solve()`
- 作用：从满员开始跑一周，然后反复用上一周的结束状态（`nextStart`）重跑，直到相邻两周的结束状态签名相同（稳定），再跑一周作为"下一周"，用 `buildPlan(week, following, true)` 生成计划。`MAX_WEEKS` 内不稳定（如永不回库的列车每周结束在线路另一端）时，单独从满员规划一周，周末仍在外的列车届时回库，使每周相同，并记 `NOT_PERIODIC`。

### `private WeekResult runWeek(WeekStart start)`
- 作用：模拟一周的分配：
  - 清空站台占用、回库中列车、折返线等待；
  - 每个车厂的就绪列车：上周留下的库存，否则按 `network.getStartingTrains()`（池共享列车数，R3）满员（就绪时刻为 `Long.MIN_VALUE`）；
  - 上周带入的在运运用平移一周加入 `open`；
  - 依时间顺序对每个行程：先 `retireIdleTrains`、`landBeforeDay`；若指令要求回到没有停车线的车厂，则该行程无车（P1-B）；无派车要求时先找在运列车（`findTrainInService`）；若没找到，且有"迟到折返"的列车，并且所有能到该站台的车厂出库都要经过那个站台的折返（6号线，2026-11-12：一条折返路时先到先走），就让迟到折返列车晚一点接手（记等待方式 `WAIT_CHANGE_PLATFORM`，并记录短折返）；仍没有则从车厂派车（`takeFromDepot`），派不出则记为未覆盖；指令要求结束运用时 `retire`；更新高峰。
  - 周末：再次 `retireIdleTrains`、全部 `landBeforeDay`；仍在外的运用带到下周；库存中周内回来的就绪时刻减一周，其它为 `Long.MIN_VALUE`。
- 返回值：`WeekResult`。

### `private void updatePeak(WeekResult result, Map<Long, List<Long>> depotReady, long timeMillis)`
在外列车 = Σ(车厂开始列车数 − 已就绪于 `timeMillis` 之前的库存数)，更新最大值。

### `private void retireIdleTrains(List<OpenDuty> open, Map<Long, List<Long>> depotReady, long nowMillis, WeekResult result)`
- 作用：列车等待超过线路允许（`readyMillis + maxLayoverMillis < now`），或在允许留线的线路上（`maxLayoverMillis > MAX_LAYOVER_MILLIS`）从它能跑的第一个行程起已等了正常折返时长（`MAX_LAYOVER_MILLIS`），则结束其运用回库。这样过夜列车等到早上，但没有行程能接的列车（终点到达多于发出、或次日行程从别的站台出发）不会停好几天，也不会让车厂逐周越派越多。

### `private long waitFrom(OpenDuty duty, List<OpenDuty> open)`
- 返回值：按 `findTrainInService` 的规则列车最早能跑行程的时刻（考虑下一周的行程，以便列车跨周日夜）；过夜后只能从停下的站台出发；没有行程能接时为就绪时刻。结果按 (就绪时刻, 站台) 缓存在 `OpenDuty` 中。

### `private static long firstStart(long[] sortedStarts, long fromMillis)`
在本周或下周（+1 周）找第一个 ≥ `fromMillis` 的开始时刻，没有为 `Long.MAX_VALUE`。

### `private void retire(List<OpenDuty> open, OpenDuty duty, WeekResult result, Set<Long> allowed)`
- 作用：结束列车运用：从 `open` 移到 `finished`，加入其池的 `returning`。回哪条停车线按运行时规则决定（P1-B）：池下一次派车的停车线（`takeFromDepot`），当天没有则回自己的。`allowed` 为运行图指定的停车线，只保留同池的（含自己）；都不同池则视为未指定。`fallback` 为自己（若允许）或允许集合中第一个。

### `private void land(Returning train, long depotId, Map<Long, List<Long>> depotReady)`
列车从末站跑回该停车线（`backMillis`，至少 0）后就绪：加入就绪列表并排序，记录回库时刻与停车线。

### `private void landBeforeDay(long nowMillis, Map<Long, List<Long>> depotReady)`
在比 `nowMillis` 所在日更早的日子到达末站、当天池中没有停车线在其之后派车的列车，回到 `wayBack` 决定的停车线。`nowMillis == Long.MAX_VALUE` 时全部回库。

### `private long wayBack(Returning train)`
- 作用：没有被派车停车线收走的列车回哪里：`fallback` 能回则回；否则允许范围内（未指定时同池）第一个能回的停车线；都回不去时仍回 `fallback`，并（每对一次）记 `NO_WAY_BACK`。

### `private boolean maySendTo(Returning train, OperationNetwork.Depot depot, long leaveMillis, Map<Long, List<Long>> depotReady)`
按运行时规则判断回库列车能否送到该停车线：停车线需要列车（派车时刻）在列车到达末站之后且同一天；在允许范围内；能回得去；有空位（自己的停车线总有，容量 ≥ `MAX_SLOTS` 视为不限，相当于运行时车队为 -1）。

### `private boolean isOnlyReachedBehind(long passedPlatformId, long platformId)`
所有能到 `platformId` 的车厂，其出库路都在折返进入它之前经过 `passedPlatformId`，且至少有一个这样的车厂时为 true。

### `private OpenDuty findTrainInService(List<OpenDuty> open, Trip trip)`
- 作用：为行程找在运列车：
  - 可达：非"过夜换站台"（过夜列车次日只能从停下的站台出发，换站台只在同一运营日内），连接可达且就绪 + 连接 ≤ 行程开始。
  - 同站台时站台须空闲（期间无其它有车行程到达），否则或等待达到 `network.waitThreshold` 时可改在站台外折返线等（`isHoldFree`，混合折返）。
  - 满足条件者中取就绪最晚的（最近才就绪的）。
  - 同时记录 `turningLate`（在另一可达站台、到达不晚于行程开始 + 3 分钟、但来不及折返，取最早到达者；2号线，2026-11-12：快车的出库列车跟在慢车后面跑完全线，又等了 3 分钟站台）和 `missed`（同站台到达在行程开始前但来不及折返，取最晚到达者）。
  - 选中列车时记录等待方式（`WAIT_TURN_BACK_RAIL` / `WAIT_PLATFORM` / `WAIT_CHANGE_PLATFORM`，运用补完第 1 步），用折返线时登记等待区间。
  - 没选中但有 `missed` 时更新 `shortTurnarounds`。
- 返回值：选中的运用，或 null。
- 副作用：修改 `turningLate`、`holds`、`shortTurnarounds`、所选运用的 `waits`。

### `private OpenDuty takeFromDepot(Map<Long, List<Long>> depotReady, Trip trip, Set<Long> allowed)`
- 作用：在允许的车厂中（`allowed` 为 null 表示任意）选出库时间最短、且在出发时刻（行程开始 − 出库时间）有列车的：库中已有就绪列车，或池中有回库列车可送来且按时回到。选中后，池中所有在此之前可送来的回库列车都送到这里（按到达末站顺序），然后取出一列，创建新的 `OpenDuty`（出库提前量 = 出库时间）。
- 返回值：新运用；没有车厂能派时 null。

### `private void occupy(Trip trip)`
把有车行程的各停站按到达时刻插入 `platformStops`，使其它列车不能在此期间占用这些站台等待。

### `private boolean isHoldFree(OpenDuty duty, Trip trip)`
- 作用：列车能否在其站台外折返线等待该行程：有折返线；时间够驶入再驶出（同站台按测得的驶回时间，换到其它站台则按换站台时间，`holdOutMillis`）；驶出时目标站台空闲；等待区间内同时等待的列车数始终 < 容量（运用补完第 4 步，检查进入时刻及区间内他人进入的时刻，含前后一周）；并且期间没有其它列车要在同一折返轨上折返（`isTurnedOnMeanwhile`，8号线，2026-11-12）。
- 源码中该方法前有两段描述其它方法（`isPlatformFree`）的孤立 Javadoc，属注释错位。

### `private boolean isTurnedOnMeanwhile(long rail, OpenDuty duty, long fromMillis, long toMillis)`
在 (from, to) 内（含前后一周），是否有其它行程在"使用该折返轨的站台"或"同一车站的任一站台"结束（结束于站台的列车可能倒入其后方不属于自己的折返轨，如 8号线三站西行站台），自身那次到达除外。

### `static int holdsAt(List<long[]> holds, long millis)`（包内）
`millis` 时刻（含前后一周）正在进行的等待区间数。

### `private long holdOutMillis(OpenDuty duty, Trip trip, long[] hold)`
同站台为 `hold[1]`（驶回时间），否则为换站台连接时间（至少 0）。

### `private boolean isPlatformFree(long platformId, long fromMillis, long toMillis)`
二分查找：在 (from, to) 之间（含前后一周）该站台没有有车行程到达时为 true。

### `private Map<Long, long[]> turnBackCapacity(WeekResult week)`
- 作用：对列车到达后要转到其它站台折返的终点站台（取到任一起点站台的最短正连接时间作为折返时间），若两次到达的最短间隔小于折返时间，返回 {最短间隔, 折返时间}（7号线情况）。包括回库列车经同一折返的到达。
- 返回值：站台 → {least, needed}。

### `private OperationPlan buildPlan(WeekResult week, WeekResult following, boolean settled)`
- 作用：汇总问题（展开阶段问题、`NOT_PERIODIC`、每个未覆盖行程的 `NO_TRAIN`、`SHORT_TURNAROUND`、`TURN_BACK_CAPACITY`）；把完成与带出的运用（去掉空的）按首行程开始与来源排序编号；用"下一周"判断跨周接续：下一周中从本周带出的运用若无行程，则把回库时刻（+1 周）与停车线写回原运用；否则按首行程 key 对应到本周的运用，建立 next/previous 关系。为每个运用生成 `Duty`（等待方式只保留其自身行程之间的最后几项，出库提前量、回库停车线默认为自己）和阶段（`buildPhases`），最后 `checkPlatformOccupancy`。
- 返回值：`OperationPlan`。

### `private List<Phase> buildPhases(OpenDuty duty, boolean hasPrevious, boolean hasNext, long nextStartMillis, long nextPlatform)`
- 作用：无前续且有出库提前量时加 `LEAVE_DEPOT`；每个行程每站 `STOP`，站间 `RUN`；行程之间 `addLayover`；有后续时到下一周首行程的 `addLayover`，否则 `LAYOVER`（到就绪）和 `RETURN_TO_DEPOT`（到回库时刻，未知时即就绪时刻）。

### `private void addLayover(List<Phase> phases, Trip trip, long nextStartMillis, long nextPlatform)`
加 `LAYOVER`（从到达到下一行程开始减连接时间）；连接时间 > 0 时再加 `CONNECT`。

### `private void checkPlatformOccupancy(List<Duty> duties, List<Issue> planIssues)`
把各运用在站台上的阶段（`STOP`/`LAYOVER`）及其平移一周的副本按开始排序，不同运用重叠时记 `PLATFORM_CONFLICT`（详情：站台、周内时刻、两运用序号）。

## 内部类 `OpenDuty`（private static final）
规划中的运用（一列车）：`depotId`、`leaveMillis`（出库提前量，接续上周的为 0）、`carriedFrom`（从上周哪一运用带入）、`trips`、`platformId`（当前所在站台）、`endMillis`（最后到达/出发时刻）、`readyMillis`（加上折返时间后的就绪时刻）、`returnDepotAtMillis`、`waits`、`returnDepotId`（0 表示尚未回库）、`waitFrom`/`waitFromReady`/`waitFromPlatform`（`waitFrom` 的缓存）。
- `add(Trip, OperationNetwork)`：追加行程并更新站台、结束与就绪时刻。
- `shiftedToNextWeek()`：带入下周的副本，时间减一周，`carriedFrom` 指向自己。

## 内部类 `Returning`（private static final）
跑完运用正在回库、尚未分配停车线的列车：`duty`、`allowed`（允许的停车线，null 为池内任意）、`fallback`（默认回的停车线）。

## 内部类 `WeekResult`（private static final）
一周的结果：`finished`（已结束运用）、`carried`（周末仍在外的运用）、`stock`（各车厂周末的列车及下周就绪时刻）、`uncovered`（无车行程）、`peakTrains`。`nextStart()` 生成下一周的起始状态。

## 内部类 `WeekStart`（private static final）
一周的起始状态：`carried`、`stock`（无则各车厂满员）。`signature()`：由带入运用的 (车厂, 站台, 就绪) 和各车厂库存数量与非初始就绪时刻组成的有序字符串，用于判断是否稳定。
