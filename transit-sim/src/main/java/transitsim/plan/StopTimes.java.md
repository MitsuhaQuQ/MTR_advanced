# StopTimes.java

一条路径上列车以最快速度到达/离开每个停站的时间，以及由此按路线时刻表算出的计划时间（`getPlan`）。宿主（或 `PathTimes.curveTimes`）在计时时用 `add` 逐站填入；之后由 `TripPath` 设置路线截断/加入信息（包内字段），列车运行时向它查询计划（`transitsim.sim.TrainService`、游戏侧 `PidsSchedules`），冲突检查从中取折返点（`getTurnBacks`），时刻表界面从中取最快时间（`getFastestTimes*`）。处理的特殊情况：环线中途加入（join）、行程末尾环线的中途加入（tail join）、中途加入/离开的路线（entry/cut）、运用路径按段（D4）的截断与加入、站前折返、从可始发站台出库始发（0.76.0）。

## 类 `StopTimes`

### 常量
- `MILLIS_PER_TICK = 50`（私有）：输入时间以 tick 计，内部转为毫秒。

### 字段
- `firstIndex`（私有，-1 = 无）：第一个可以开始服务的停站的轨道下标。
- `firstRouteJoined`（包内）：路径是运用的，其第一条路线可以中途加入（车厂常规路径的第一条路线不行）。
- `firstArrival`（私有）：到达 `firstIndex` 的时间（tick，从离开停车线算起）。
- `plannedKey`（私有）：`plans` 缓存对应的路线修订 key（`PlanVersions.keyOf`）。
- `joinIndex`（私有）：环线中途加入时，绕到环线首站之后的第一段轨道下标；否则 0。
- `joinArrival`（私有）：第一个加入段停站的到达时间。
- `cutStops`（包内）：中途离开的路线 → 所跑的最后一站。
- `tailJoinStart`（默认 -1）、`tailJoinEnd`、`tailLapEnd`（包内）：行程末尾环线中途加入的轨道区间及一圈结束位置。
- `stops`（包内）：停站列表（`Stop`），时间为从首站到达起的毫秒。
- `leadingPasses`（私有）：首个停车站之前通过的站台（绝对时间），用于"只有路线本身"的计时。
- `joinStops`、`tailJoinStops`（私有）：绕到环线首站途中的停站，按一圈中相同的站来计划。
- `joinStopLaps`（私有）：加入段停站的轨道下标 → 一圈中对应停站的下标。
- `plans`（私有）：`planKey → (轨道下标 → StopPlan)` 缓存。
- `entryStops`、`entryTimes`（包内）：中途加入的路线 → 加入站序 / 从首站起的最快时间。
- `segmentCuts`、`segmentEntries`、`segmentEntryTimes`（包内）：运用路径按段的同类信息（D4），`segmentsKnown` 时代替按路线的版本。
- `segmentLeadSources`（包内）：提前折返路径（D4）上中途加入的段 → 常规路径的 `StopTimes`（其同一段从更早处开始），用来推算加入前的时间。
- `segmentsKnown`（包内）。

### `public static StopTimes of(long[]... stops)`
- 作用：用别处算好的停站时间构造（无世界运行服务用）。每个元素 `{轨道下标, 站台 ID, 到达 tick, 出发 tick}`，第一个视为首站。
- 主要调用者：测试 `common/src/test/java/mtr/data/RouteEntryTest.java:34`、`PidsSchedulesTest.java:74`（main 代码未见）。

### `public void setEntry(long routeId, int stop, long[][] times)`
- 作用：登记中途加入的路线（加入站序与从首站起的最快时间），清空计划缓存。无世界运行用。
- 主要调用者：测试 `common/src/test/java/mtr/data/RouteEntryTest.java:36`。

### `public void clear()`
重置停站与缓存（不清 `cutStops`/`entryStops` 等包内路线信息）。主要调用者：`common/src/main/java/mtr/data/PathTiming.java:42`。

### `public void copyFrom(StopTimes stopTimes)`
`clear()` 后复制另一实例的全部字段（列表内容浅复制）。主要调用者：`common/src/main/java/mtr/data/Siding.java:1226`、`Siding.java:1294`、本类 `getFastestTimesFromFirstStop`。

### `public void setJoinIndex(int joinIndex)`
设置 `joinIndex`，须在添加停站前调用。主要调用者：`common/src/main/java/mtr/data/PathTiming.java:43`、`transitsim/sim/SimDataset.java:615`。

### `public void add(int index, long platformId, UUID turnBackRail, float arrival, float departure, boolean isFirstStop)`
- 作用：宿主计时时添加路径的下一个停站：
  - `index < joinIndex`：作为环线加入段停站（记录首个到达为 `joinArrival`），返回。
  - 在 tail join 区间内：作为末尾环线加入段停站，返回。
  - 首个 `isFirstStop` 确定 `firstIndex`/`firstArrival`。
  - 首站之前的站台（`platformId != 0`）记入 `leadingPasses`（绝对毫秒）。
  - 首站及之后记入 `stops`，时间转为相对首站到达的毫秒。
- 参数：`index` 轨道下标；`platformId` 站台，折返线为 0；`turnBackRail` 折返轨 key 或 null；`arrival`/`departure` 从离开停车线起的 tick；`isFirstStop` 列车能否在此开始服务。
- 主要调用者：`transitsim/plan/PathTimes.java:93`、`common/src/main/java/mtr/data/PathTiming.java`（逐步计时，具体行号未逐一列出）。

### `public void linkJoinStops(int lapEndIndex)` / `private void linkJoinStops(List<Stop> newJoinStops, int lapEndIndex)`
- 作用：从第一圈末尾往回，按站台把加入段的每个停站对应到一圈中的同一站（写入 `joinStopLaps`），因为加入环线的那些站是一圈中最后的几站。分别处理 `joinStops`（用 `lapEndIndex`）和 `tailJoinStops`（用 `tailLapEnd`）。
- 主要调用者：`common/src/main/java/mtr/data/PathTiming.java:48`、`PathTiming.java:148`、`transitsim/sim/SimDataset.java:620`。

### `public boolean isEmpty()`
没有首站（`firstIndex < 0`）。

### `public boolean hasJoinStops()`
列车是否中途加入环线（有加入段停站且已对应到一圈中的站）。

### `public int getFirstJoinStopLapIndex()`
加入环线后到达的第一站在一圈中的轨道下标（用于取该站时间）；无加入段时为 `firstIndex`。主要调用者：`transitsim/sim/TrainService.java:2336`。

### `public int getFirstIndex()`
主要调用者：`transitsim/sim/TrainService.java:1146`、`:1151`、`:2277`。

### `public long getLeadMillis()`
从停车线到第一个站台所需毫秒（有加入段时到第一个加入段停站）。主要调用者：`common/src/main/java/mtr/data/RoutePaths.java:1160`、`mtr/data/TrainServer.java:710`。

### `public long getDepartureLeadMillis()`
- 作用：从离开停车线到离开第一站台的时间 = `getLeadMillis()` + 首站停站时长；用于规划运用列车何时出库，因为班次按点从该站出发（L05：只算到达时间时，列车在班次该出发后还在站台停满整个停站时间）。中途加入环线时仍按加入站计时，返回 `getLeadMillis()`。
- 主要调用者：`common/src/main/java/mtr/data/DepotLegs.java:171`。

### `public long getRunMillis(int fromIndex, int toIndex)`
两停站（按轨道下标）之间最快运行时间 = 到达(to) − 出发(from)，≥0；任一不是停站返回 -1。主要调用者：`transitsim/sim/TrainService.java:1755`。

### `public long getMovingMillis(int fromIndex, int toIndex)`
同上，但减去中间各停点（如折返线）的停留时间（D1）。主要调用者：`transitsim/sim/BufferRecorder.java:117`。

### `public long getDurationMillis()`
首站到末站到达的时间。主要调用者：`transitsim/sim/DepotDispatcher.java:321`、`transitsim/sim/TrainService.java:1369`。

### `public RouteTimetable.StopPlan getPlan(List<? extends PlanRoute> routes, RouteTimetable timetable, int index, long routeId, int table, int service)`
- 作用：路径上某停站的计划时间。路线修订 key 变化时清空缓存；按 (路线, 表, 有例外的班次或 -1) 缓存一整套计划（`makePlans`）；加入段停站映射到一圈中对应的站。
- 参数：`routes` 路径依次经过的路线；`timetable` 当前路线不在 `routes` 中时使用的时刻表；`index` 停站轨道下标；`routeId` 列车当前所在路线；`table` 该路线所用表（其它路线在到达前用第 0 表）；`service` 班次（用于例外），-1 为无。
- 返回值：`StopPlan`；路径没有该停站时 null。
- 主要调用者：`transitsim/sim/TrainService.java:1547`、`common/src/main/java/mtr/data/PidsSchedules.java:84`、`PidsSchedules.java:101`。

### `private static Map<Integer, StopException> getStopExceptions(List<? extends PlanRoute> routes, long routeId, int table, int service)`
找到 `routeId` 对应路线的该表该班例外；`service < 0` 或无此路线时 null。

### `private Map<Integer, StopPlan> makePlans(List<? extends PlanRoute> routes, RouteTimetable timetable, long routeId, int table, Map<Integer, StopException> stopExceptions)`
- 作用：用 `matchRouteStops` 把路径停站与路线停站对应，逐站构造 `StopPlan`：
  - 若路线有 `timingBaseline`（只与路线有关的计时），以每段首次出现的停站为原点，用基准时间替换路径最快时间；并累计 `timingShift` 使后续站整体平移。
  - 到达用到达所属路线的设定、出发用出发所属路线的设定（只有当前路线用 `table`，其它用 0 表）。
  - 套用当前路线的例外；设置 `startsRoute`、中途加入时的 `routeStartOffsetMillis`（`getEntryOffset`）、`route`、`waitsForService`（该路线有班次）。
  - 用当前路线该表（找不到则 `timetable`）`applyPlan`，再 `linkTurnBacks`。
- 返回值：轨道下标 → `StopPlan`。

### `private static long[] baselineAt(PlanRoute route, int stop, int table)`
路线基准计时中该站该表的 `{到达, 出发}`；无数据或为 -1 时 null。

### `public long getEntryOffsetMillis(PlanRoute route, int entryStop, int table, int service)`
- 作用：运用第一段中途加入路线时，从路线首站到加入站的计划时间（毫秒）。
- 主要调用者：`transitsim/sim/TrainService.java:1900`、`common/src/main/java/mtr/data/PidsSchedules.java:82`。

### `private long getEntryOffset(List<? extends PlanRoute> routes, PlanRoute route, int segment, int entryStop, int table, Map<Integer, StopException> stopExceptions)`
- 作用：中途加入的路线仍按其班次（从首站起算）运行，因此求从首站到加入站的计划到达时间。优先用路线基准计时；否则用 `getSegmentLead`（按段）或 `entryTimes`（按路线）的最快时间（不足处补 -1），经 `StopRules.plan` 计算。
- 返回值：毫秒；无数据时 0。

### `public long[][] getFastestTimes(PlanRoute route, List<? extends PlanRoute> routes)`
- 返回值：该路线每站的最快 `{到达, 出发}`，以第一个找到的站为 0；未找到的站为 -1。
- 主要调用者：`common/src/main/java/mtr/data/RoutePaths.java:1327`、`mtr/advanced/SandboxRuns.java:193`（经宿主包装）。

### `public long[][] getFastestTimesFromFirstStop(PlanRoute route)`
- 作用：同上，但路径是"已停在路线首站开始"的路线自身路径（没有车厂按其路径运行时）：首站不是路径的停站（列车并未到达它），保持在路径起点，时间从那里算起；否则若从路径第一个停站算，会丢掉到第二站的运行时间，后面每站都会提前。若有 `leadingPasses`（首个停车前通过的站台），先构造一个把它们并入 `stops` 的完整副本再计算，避免这些通过站消失。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:2843`、`mtr/data/RoutePaths.java:1508`。

### `public long[][] getSegmentTimes(int segment, List<? extends PlanRoute> routes)`
- 作用：运用路径中某一段（D4）的路线各站 `{到达, 出发, 轨道下标}`（从路径开始起的毫秒），该段不跑的站为 -1。
- 主要调用者：`common/src/main/java/mtr/data/PidsSchedules.java:79`、本类 `getSegmentLead`。

### `private long[][] getSegmentLead(int segment, List<? extends PlanRoute> routes)`
- 作用：段的路线从首站到加入站的最快时间：优先本路径自己的 `segmentEntryTimes`；否则从 `segmentLeadSources` 中的常规路径推算（递归取源路径的加入前时间，再按源路径中该段的时间平移）。
- 返回值：时间数组；无法得到时 null。

### `public int getSegmentEntry(int segment)`
按段已知时返回该段加入站（默认 0），否则 -1。主要调用者：`transitsim/sim/TrainService.java:498`。

### `public long[][] getSegmentEntryTimes(int segment)`
按段已知时返回该段的加入前时间，否则 null。

### `private long[][] matchFastestTimes(PlanRoute route, List<? extends PlanRoute> routes)`
通过 `matchRouteStops` 为指定路线每站取第一次匹配到的路径时间（出发站取 {到达, 出发}，仅作为到达站时出发 = 到达）。

### `public List<TimetableConflicts.TurnBack> getTurnBacks(List<? extends PlanRoute> routes)`
- 作用：找出路径上每个折返线停点（`turnBackRail != null` 且前一站能对应到路线停站），生成 `TimetableConflicts.TurnBack(前一站所属路线, 折返轨, 前一站站序, 下一路线 ID 或 0, 前站出发到折返线到达的时间, 在折返线停留时间, 离开折返线到下一路线首站的时间或 0)`。
- 主要调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:905`。

### `private void matchRouteStops(List<? extends PlanRoute> routes, BiConsumer<Stop, RouteStop> consumer)`
- 作用：把路径的停站按顺序与路线停站对应。先展开所有路线的停站为 `RouteStop` 序列：按 `segmentCuts`/`cutStops` 截断，按 `segmentEntries`/`entryStops` 从加入站开始（第一条路线只有 `firstRouteJoined` 时才可中途加入）；一条路线终点与下一条起点为同一站台时合并为一个 `RouteStop`（到达取前者、出发取后者）。然后遍历路径停站：若在期待的末站所在车站的其它站台折返（站前折返，`getTerminalPlatforms` 包含此站台），把它当作该末站（若下一路线也从这里开始则同时作为下一路线首站）；期待的是路线首站而列车停在路线列出的可始发站台（`getStartPlatforms`，出库车从单独出库站台始发，0.76.0），把它当作该首站；否则向后按站台 ID 匹配。折返线停点（站台 0）匹配为 null。
- 副作用：对每个路径停站调用 `consumer(stop, routeStop 或 null)`。

## 内部类 `Stop`（包内 static）
路径上的一个停点：`index`（轨道下标）、`platformId`（折返线为 0）、`turnBackRail`、`arrivalMillis`、`departureMillis`（相对首站到达，`leadingPasses` 中为绝对时间）。

## 内部类 `RouteStop`（private static）
路径停点对应的路线停站：`arrivalRoute`/`arrivalSegment`/`arrivalIndex`（到达所属路线、它在路线序列中的位置即运用段号、站序），`departureRoute`/`departureSegment`/`departureIndex`（出发所属），`arrivalStop`/`departureStop`（对应 `PlanStop`），`platformId`（取到达站），`startsRoute`（下一路线从此开始）。
