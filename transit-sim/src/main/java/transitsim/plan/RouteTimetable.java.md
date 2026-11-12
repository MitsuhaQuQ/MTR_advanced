# RouteTimetable.java

一条路线的时刻表。每个班次（service）是首站计划到达时刻，用一天中的 UTC 毫秒表示，与车厂的发车时刻同义；后续各站时间来自生成的路径。本对象是路线的第一张表，同时持有其它表（路线在不同日子可以跑不同的表），每张表有自己的班次与各站时间。S3a 时迁入组件，由宿主路线（游戏侧 `mtr.data.Route`）持有。另含嵌套类 `ServiceOrder`（中途出入库指令）、`ServiceLink`（直通接续）、`StopPlan`（单站时间计算的中间结果）。被组件 `StopRules`、`StopTimes`、`TimetableConflicts`、`TimetableBinary`、`PlanVersions`、`transitsim.sim.TrainService`/`DepotDispatcher`/`DayRun` 以及游戏侧 `Route`、`Depot`、`DataCache`、`PidsSchedules`、`RoutePaths`、编辑器广泛使用。

## 类 `RouteTimetable`

### 字段
- `enabled`：仍保存/发送，但不再开关时刻表（见 `isActive`）。
- `marginPercent`（默认 5）：旧的余量百分比；仍读写以兼容旧存档，但 `addMargin` 不再使用（优化第 3 轮）。
- `minAcceleration`、`minDeceleration`、`minMaxSpeed`：跑这张表的车辆至少要达到的性能（优化第 3 轮）；运行时分按它计算，因而不随实际车辆变化。加/减速度单位 m/tick²，最高速度 方块/tick；0 表示无；`minAcceleration > 0` 之前视为未设置。
- `services`：班次列表（一天中的 UTC 毫秒，`sanitize` 后有序去重）。
- `name`：表名（最长 32）。
- `entryPlatformId`：环线上车厂列车加入的站台（代替首站），0 为无。
- `weekdays`（默认全部）：本表运行的星期，最低位为周一，见 `OperatingDay.getWeekday`。
- `revision`：时间改变时递增，使据此做的计划可重建（谁递增未在本类中，由宿主负责）。
- `links`：本表中接续到另一路线某班的班次 → `ServiceLink`。
- `returnServices`：本表中跑完后回库的班次（用于列车否则留在线上的车厂）。
- `suspended`：本表中停运的班次，保留时间、例外、接续与指令以便恢复；不派车、不规划。
- `returnOrders`：本表中在某站中途结束、空车回库的班次（在运行图上设置）→ `ServiceOrder`。
- `deployOrders`：本表中由车厂派车在某站中途加入的班次 → `ServiceOrder`。
- `exceptions`：班次 → (站序 → `ServiceExceptions.StopException`)。
- `otherTables`（私有）：其它表。
- `allServices`（私有）：所有表的班次合集（有序去重），由 `sanitize` 计算。
- `linkedServices`（私有）：只作为其它路线某班的接续运行的班次，车厂不为它们派车。

### 常量
- `MAX_TABLES = 16`、`MAX_NAME_LENGTH = 32`、`ALL_WEEKDAYS = 0b1111111`、`MAX_SERVICES = 4096`。
- `MAX_STOP_SECONDS = 172800`：站点时间距首站到达的最大秒数（2 天）。
- `MAX_OVERTAKE_WAIT_MILLIS = 5 分钟`：待避列车最多超过出发时刻等这么久，避免严重晚点的车一直拖住它。
- `DEFAULT_MARGIN_PERCENT = 5`、`MAX_MARGIN_PERCENT = 100`。
- `MAX_STAY_MILLIS = 8 天`：留在线上的列车找下一班最多往后看多久。使用者：`common/src/main/java/mtr/data/LineDuties.java:571`、`mtr/client/editor/EditorDuties.java:391`、`transitsim/sim/DepotDispatcher.java:180`。
- `MAX_LAYOVER_MILLIS = 30 分钟`：到下一班等待更久的列车改为回库。
- `MAX_DEPLOY_DELAY_MILLIS = 10 分钟`：一个班次等车超过这么久就放弃，列车不会故意大幅晚点运行。
- `ALIGN_TOLERANCE_MILLIS = 1 分钟`：重启后离计划这么近的列车留在原地，因为略晚的车可以追回。
- `MAX_TRIP_LINKS = 7`：一列车在车厂路线之后最多再直通几条路线；也用来截断成环的接续。

### `public boolean isActive()`
- 返回值：任一表有班次即为 true（用户 2026-11-09：有班次的路线不会"什么都不跑"）。临时停运应把班次设为停运。
- 主要调用者：`common/src/main/java/mtr/client/editor/EditorData.java:458`、`mtr/data/Depot.java:1079`。

### `public RouteTimetable copy()`
深拷贝本表与其它表（例外逐条复制；`links`、`ServiceOrder` 为不可变对象，共享引用），修订号相同。主要调用者：`transitsim/plan/PlanVersions.java:49`、`PlanVersions.java:99`。

### `public void sanitize()`
- 作用：班次取模一天后去重排序并截到 `MAX_SERVICES`；余量夹到 `[0, 100]`；`weekdays` 与全周按位与；名称截断；`links`/`returnServices`/`suspended`/`returnOrders`/`deployOrders` 只保留现存班次；删去站号为负的指令；删去加入站 ≥ 结束站的派车指令（必须先加入再离开）；`ServiceExceptions.sanitize`；递归处理其它表；重算 `allServices`。
- 副作用：就地修改。
- 主要调用者：`transitsim/plan/TimetableBinary.java:131`、`common/src/main/java/mtr/client/editor/EditorActions.java:602`、`EditorActions.java:803`。

### `public List<RouteTimetable> getTables()`
新列表：本表 + 其它表。

### `public int getTableCount()`
表数（其它表数 + 1）。

### `public RouteTimetable getTable(int index)`
下标对应的表；越界或 0 时返回本表。

### `public void addTable(RouteTimetable table)`
未达 `MAX_TABLES` 时追加，否则忽略。主要调用者：`transitsim/plan/TimetableBinary.java:107`、`common/src/main/java/mtr/data/Route.java:539`。

### `public void removeTable(int index)`
删除第 `index`（≥1）张表；宿主路线负责同时删除该表的站点时间。主要调用者：`common/src/main/java/mtr/data/Route.java:642`。

### `public void clearOtherTables()`
删除除本表外所有表。主要调用者：`common/src/main/java/mtr/data/Route.java:1101`。

### `public void clearLinkedServices()` / `public void addLinkedService(int service)`
维护 `linkedServices`；由拥有全部路线的一方标记。主要调用者：`common/src/main/java/mtr/data/DataCache.java:134`、`DataCache.java:138`、`transitsim/sim/SimDataset.java:577`。

### `public ServiceLink getLink(long serviceMillis)`
- 参数：班次的完整时间戳（用于确定当天所用的表）。
- 返回值：该班在当天所用表中的接续；不离开本路线路径时 null。
- 主要调用者：`transitsim/sim/TrainService.java:2165`。

### `public ServiceOrder getReturnOrder(long serviceMillis)` / `public ServiceOrder getDeployOrder(long serviceMillis)`
- 参数：完整时间戳或一天中时刻。
- 返回值：中途结束回库 / 中途加入的指令；没有时 null（照常从首站跑到末站）。
- 主要调用者：`transitsim/sim/TrainService.java:2033`（return）；`getDeployOrder` 在本类 `getFirstStop` 中使用。

### `public int getFirstStop(long serviceMillis)` / `public int getLastStop(long serviceMillis, int stopCount)`
该班开始/结束载客的站序：无派车指令为 0；无回库指令为 `stopCount - 1`，否则 `min(order.stop, stopCount-1)`。

### `public boolean servesStop(long serviceMillis, int stopIndex, int stopCount)`
该站序是否在载客区间内。main 代码中未 grep 到外部调用（未确定）。

### `public boolean isReturnService(long serviceMillis)`
该班跑完后是否回库。主要调用者：`transitsim/sim/TrainService.java:1582`、`TrainService.java:1608`。

### `public boolean isSuspended(long serviceMillis)`
当天所属表把该班标为停运时 true；不属于任何当天的表时 false。

### `private boolean runsAt(long serviceMillis)`
当天有表包含该班且未停运。

### `public ServiceLink getLinkOfService(int service)`
任一表中该班的接续（不需要日期，因为路径在班次运行前生成）；没有为 null。主要调用者：`transitsim/sim/TrainService.java:2234`、本类 `getLinkChain`。

### `public static List<ServiceLink> getLinkChain(ServiceLink firstLink, LongFunction<RouteTimetable> getTimetable)`
- 作用：从 `firstLink` 起沿各路线的 `getLinkOfService` 追加，直到无后续或达到 `MAX_TRIP_LINKS` 条。
- 参数：`getTimetable` 路线 ID → 时刻表，无此路线返回 null。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:2981`、`transitsim/sim/TrainService.java:2166`。

### `public boolean runsOn(int weekday)`
本表是否在该星期运行。主要调用者：`common/src/main/java/mtr/data/PidsSchedules.java:72`、`mtr/screen/TimetableScreen.java:598`。

### `public int getTableIndex(long serviceMillis)`
- 返回值：该班当天所属表的下标（多个时取第一个）；找不到时 0。
- 主要调用者：`common/src/main/java/mtr/data/PidsSchedules.java:70`、`transitsim/sim/TrainService.java:1900`、`TrainService.java:2425`。

### `public int getServiceOrdinal(long serviceMillis)`
- 返回值：该班在其所属表当天班次中的序号（从 0 起，相邻两班奇偶不同）；时刻落在两班之间（中途接入的列车）时算前一班。
- 主要调用者：`transitsim/sim/TrainService.java` 的 `getServiceOrdinal`（站后双折返轮流用折返轨）。

### `private int findTableIndex(long serviceMillis)`
只有一张全周表时直接 0；否则按 `OperatingDay.getWeekday` 与二分查找找第一张当天运行且含该班的表；找不到 -1。

### `public List<Long> getServicesBetween(long fromMillis, long beforeMillis)`
- 返回值：`[fromMillis, beforeMillis)` 内实际运行的所有班次完整时间戳（按日、按时刻有序），包括只作为接续运行的班次。
- 主要调用者：`transitsim/sim/DayRun.java:483`。

### `private List<Integer> getAllServices()`
只有一张表时为 `services`，否则 `allServices`。

### `public boolean hasMinPerformance()`
最低加速度与减速度都 > 0。主要调用者：`common/src/main/java/mtr/data/RoutePaths.java:350`、`:371`、`:1523`。

### `public long addMargin(long millis)`
原样返回：不再加余量（优化文档 5.6，第 3 轮），运行时分按计划速度计算，低于列车物理极限，晚点列车因此能追回。

### `public void applyPlan(List<StopPlan> stops)`
- 作用：计算整个行程各站的时间（毫秒，从首站到达算起）。逐站：
  - 到达：若有例外运行时间，= 上站出发 + 例外时间；否则若非首站、例外不是"通过"且设了到达秒数，= 路线起点 + 设定秒；否则自动 = 上站出发 + （本站最快到达 − 上站最快出发）。
  - 若 `startsRoute`（下一路线从此开始；中途加入时减去 `routeStartOffsetMillis`），更新路线起点 `routeStart`。
  - 出发：有例外停站时间则到达 + 该时间；例外为通过且是中间站则 = 到达；否则设了出发秒则 `max(路线起点 + 设定秒, 到达)`，不然到达 + 自动停站（例外加停且最快时间中无停站时为 `ADDED_STOP_MILLIS`，否则最快出发 − 最快到达）。
  - 记录 `routeStartMillis`；`isTimed`（守时）= 首站、待避、设了出发、等待本路线班次或有例外停站时间。
- 注释：未设时间的站跟随前一站，所以改一个时间会推动其后所有自动时间；设定时间从各自路线起点计算，因为折返后的路线按自己的班次运行，不知道行程何时开始。
- 副作用：修改各 `StopPlan` 的输出字段。
- 主要调用者：`transitsim/plan/StopRules.java:50`、`transitsim/plan/StopTimes.java:367`。

### `public static void linkTurnBacks(List<StopPlan> stops, List<Boolean> isTurnBacks)`
- 作用：对紧接在"有自己班次的路线"之前的折返线停点（`isTurnBacks[i]` 且下一站 `startsRoute && waitsForService`），设 `nextRouteStart = 下一站` 并 `isTimed = true`，使列车在折返线上等待以准点进入那条路线。
- 主要调用者：`transitsim/plan/StopTimes.java:368`。

### `public long getNextService(long fromMillis, long beforeMillis, LongPredicate isTaken)`
- 返回值：`[fromMillis, beforeMillis)` 内第一个未被占用、非接续、实际运行的班次完整时间戳；没有为 0。
- 主要调用者：`transitsim/sim/DepotDispatcher.java:180`、`DepotDispatcher.java:194`、`transitsim/sim/TrainService.java:1348`。

### `public long getLatestService(long afterMillis, long latestMillis, LongPredicate isTaken)`
- 返回值：`(afterMillis, latestMillis]` 内最后一个未被占用、非接续、实际运行的班次；没有为 0。
- 主要调用者：`transitsim/sim/TrainService.java:1369`、`TrainService.java:1642`。

### `public static long getClosestService(long plannedMillis, int service)`
- 返回值：在前一天、当天、后一天中，最接近 `plannedMillis` 的该班完整时间戳。
- 主要调用者：`transitsim/sim/TrainService.java:1097`、`TrainService.java:2248`。

## 内部类 `ServiceOrder`（public static final）
在运行图上手工设置的、班次不在首/末站开始或结束：车厂派出的车在某站加入，或在某站结束后空车回库。
- `stop`：路线站序（从 0 起）。
- `depotId`：列车来自/回到的车厂，0 交给规划器决定。
- `equals`/`hashCode` 按两个字段。
- 创建者：`TimetableBinary.readOrders`、`common/src/main/java/mtr/data/Route.java:477`、`mtr/client/editor/EditorActions.java:1025`。

## 内部类 `ServiceLink`（public static）
某班接续到另一路线的某班（如环线 07:00 直通到另一线 07:32）。
- `routeId`、`service`：目标路线与班次。
- `fromStop`：列车离开本路线的站序，-1 为末站。
- `toStop`：行程末尾环线中列车加入的站序，0 为首站（`TimetableBoundaries.checkLink` 也对非环线检查 `toStop > 0`）。
- 构造 `ServiceLink(routeId, service, fromStop)`（`toStop = 0`）与四参数版本。
- 创建者：`TimetableBinary.read`、`common/src/main/java/mtr/data/Route.java:441`、`mtr/client/editor/EditorActions.java:1012`。

## 内部类 `StopPlan`（public static）
一个停站的时间，毫秒，从首站到达算起。
- 输入：`fastestArrivalMillis`、`fastestDepartureMillis`（最快）、`setArrivalSeconds`、`setDepartureSeconds`（设定，-1 为无）、`overtake`（可被例外改变）、`exceptionRunMillis`/`exceptionDwellMillis`（-1 为无）、`exceptionPass`（1 通过、0 停、-1 跟随表）、`routeStartOffsetMillis`（中途加入路线时，从该路线首站到此处的计划时间）、`startsRoute`（下一路线从此开始）、`route`（从此开始的路线，仅服务器知道）、`waitsForService`（该路线有自己的班次，列车在此等它）。
- 输出：`routeStartMillis`、`arrivalMillis`、`departureMillis`、`isTimed`（守出发时刻；其它站准备好就走，停站时间可变）、`nextRouteStart`（本处是折返线且下一站开始一条有自己班次的路线时指向下一站）。
- 构造 `StopPlan(fastestArrival, fastestDeparture, setArrivalSeconds, setDepartureSeconds, overtake)`。创建者：`transitsim/plan/StopRules.java:45`、`transitsim/plan/StopTimes.java:351`。
