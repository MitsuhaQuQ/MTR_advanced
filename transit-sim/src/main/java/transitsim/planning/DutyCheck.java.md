# DutyCheck.java

检查已规划的运用在新的时间下是否仍然成立（优化第 4 轮第 4 步）：运行时间、出库/回库路程、换站台以及最短换端时间，按规划器（`DutyGenerator`）取用的方式。用规划器自己的规则（`DutyPlanner`）检查：每列车及时折返赶上下一个行程；只在同组没有其它列车停靠时在站台等待，否则在站台外折返线等，且同一时刻折返线上只有本组一列车（每组单独规划，只知道自己的列车）；在线上等待不超过线路允许的时长；有出库与回库的路。成立的运用被保留——时间的小变化不会改动它们；任何一条规则被破坏（不论多小）就重新规划。只检查时间：运用的构成（时刻表、停车线及其列车）由调用方比较。由游戏侧 `mtr.data.LineDuties`（`LineDuties.java:639`、`:983`）调用。

## 类 `DutyCheck`（final，工具类）

### 枚举 `Kind`
- `NO_SERVICE`：运用跑的班次（或其一部分）在时刻表里已不存在。
- `NO_WAY_OUT`：停车线到首站台没有出库路。
- `NO_WAY_BACK`：从末站台回不到本停车线，也回不到同池其它停车线。
- `NO_CONNECTION`：下一个行程从一个没有路可达的站台出发。
- `SHORT_TURNAROUND`：到达与下一行程之间时间不够换端或换站台。
- `PLATFORM_TAKEN`：列车在站台等待时另一列车要停这个站台，且无法改在折返线等。
- `TURN_BACK_RAIL_TAKEN`：两列车同时在同一折返线等待（超过容量）。
- `LONG_LAYOVER`：在线上等待超过线路允许时长。
- `OVERNIGHT`：在线上过夜的列车第二天早上在停下的地方没有运用。

### 内部类 `Break`（public static final）
被破坏的一条规则：`sidingId`、`slotId`（运用编号）、`day`（0 = 周一）、`kind`、`platformId`、`givenMillis`（实际有的时间）、`neededMillis`（需要的时间；无则 0）。私有构造；`toString()` 生成可读文本。

### 内部类 `Group`（public static final）
一起规划的停车线（规划器一次调用的范围）：`services`（班次输入）、`sidings`（停车线输入）、`slots`（各停车线的槽位，`DutyGenerator.Result.sidings` 格式）。构造原样赋值。主要创建者：`common/src/main/java/mtr/data/LineDuties.java:632`、`:979`。

### `private DutyCheck()`
禁止实例化。

### `public static List<Break> check(List<Group> groups, Map<Long, Long> stationOf, long maxLayoverMillis, DutyGenerator.PlanInputs inputs)`
- 作用：
  1. 每组用 `readGroup` 读出每个运用每天的行程（`Piece`）。
  2. 对留在线上过夜的 `Piece`，在同组同停车线次日、首站等于其末站、尚未被接续的 `Piece` 中找接续（优先同一槽位）；找不到记 `OVERNIGHT`。
  3. 各组分别统计站台停靠（按到达排序）。
  4. 对每个 `Piece`：未被接续且出库不可达记 `NO_WAY_OUT`；相邻行程逐个 `checkTurn`；有接续则检查到次日首个行程的折返（周日到周一时次日行程平移一周）；否则不留线上时若回不去记 `NO_WAY_BACK`。
  5. 折返线容量（运用补完第 4 步）：每个站台外等待区间，统计在其开始时已占用的区间数（考虑前后一周；同时开始时列表靠后者算作放不下），≥ 容量记 `TURN_BACK_RAIL_TAKEN`。
- 参数：`groups` 共享站台与折返线的各组；`stationOf` 站台 → 车站；`maxLayoverMillis` 列车在线上等下一行程的最长时间；`inputs` 路径生成测得的输入。
- 返回值：被破坏的规则；运用成立时为空。
- 主要调用者：`common/src/main/java/mtr/data/LineDuties.java:639`、`LineDuties.java:983`。

### `private static void readGroup(Group group, int groupIndex, Map<Long, Long> stationOf, DutyGenerator.PlanInputs inputs, List<Piece> pieces, List<Break> breaks)`
- 作用：像规划器一样把班次组成链（`DutyGenerator.buildChains`），构建网络（`networkOf(timetableServicesOf(...))`）；对每个槽位的每个当日运用、每个它运行的星期，用 `readTrips` 读成行程；停车线不在网络中或读不出时记 `NO_SERVICE`，否则加入 `pieces`。
- 副作用：追加 `pieces`、`breaks`。

### `private static List<Trip> readTrips(List<DepotDuties.Segment> segments, int day, Map<String, List<DutyGenerator.Chain>> chainsByFirst)`
- 作用：从段序列的当前位置找一条首段相同且后续段完全相同的链；并在链运行的星期中找"首站到达落在 `day` 这天"的那一天作为运行日，生成 `Trip`（时间从周一 00:00 起）。
- 返回值：行程列表；任一段对不上链时 null。

### `private static void checkTurn(Piece piece, Trip from, Trip to, long maxLayoverMillis, Map<Long, List<long[]>> platformStops, Map<Long, List<long[]>> holds, List<Break> breaks)`
- 作用：按规划器对在运列车的规则检查从一个行程折返到下一个：
  - 站台间不可达 → `NO_CONNECTION`；
  - 末站出发 + 折返时间 + 连接时间 > 下一行程开始 → `SHORT_TURNAROUND`（给出实际与所需时间）；
  - 下一行程开始晚于就绪 + `maxLayoverMillis` → `LONG_LAYOVER`；
  - 同站台且站台空闲且等待未达 `TrainService.LONG_TURN_BACK_WAIT_MILLIS`：通过；
  - 否则尝试站台外折返线（混合折返）：时间放得下且返回时目标站台空闲，则把等待区间 `{开始, 结束, 停车线, 槽位, 日, 容量}` 记入 `holds`；
  - 仍不行且站台被占 → `PLATFORM_TAKEN`。

### `private static boolean isPlatformFree(Map<Long, List<long[]>> platformStops, long platformId, long fromMillis, long toMillis)`
在 (from, to) 之间（考虑前后一周）没有任何运用行程到达该站台时为 true。

### `private static boolean canGoBack(OperationNetwork network, OperationNetwork.Depot siding, long platformId)`
像规划器送车回库那样：能回本停车线，或能回同池任一停车线。

### `private static String segmentKey(DepotDuties.Segment segment)`
`routeId@service@fromStop`。

## 内部类 `Piece`（private static final）
一个运用某一天的行程：`group`（组下标）、`sidingId`、`slotId`、`day`、`trips`、`stayOnLine`、`network`、`next`（次日接续的 `Piece`）。

## 内部类 `Trip`（private static final）
一条链在某天的运行：`stops` 为 `{站台, 到达, 出发}`（从周一 00:00 起的毫秒）。
- `Trip(DutyGenerator.Chain chain, long start)`：链各站偏移加 `start`。
- `Trip(List<long[]> stops)`：直接复制。
- `shifted(long millis)`：时间整体平移的新行程。
- `startMillis()`（首站到达）、`endMillis()`（末站出发）、`firstPlatform()`、`lastPlatform()`。
