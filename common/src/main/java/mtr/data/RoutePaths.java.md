# RoutePaths.java

每条线路的路径只找一次并保存，而不是由每个运行它的车厂与停车线组各找一次（解耦文档 5.4，L1）：从首站台经途经点到末站台的轨道，每站按站台自己的停站时间，使用者再套用自己班次的停站（`RoutePath#getPath`）。线路的站台或途经点变化、或路径上某条轨道变化时重新找路。按时刻表运行的车厂由这些路径构建其路径与运用，其它车厂照旧搜索自己的线路。整个世界在服务器运行期间保存在内存中，不存档（按时刻表运行的车厂加载后总会重新算一次）。路径在找路线程上计算，同一线路同时只由一个线程计算。

另外负责：轨道区段的"所有者"（哪些运营线路可在该区段运行，阶段文档第 2 轮；由寻路边界与站台推算，或由逻辑线路决定，再叠加手动设置）、联络线自有部分、出入库路径（`DepotLine`、`findDepotWay`）、线路时分（`RoutePath.getTimetableTimes/getFastestTimes`）、车辆最低性能。由 `RailwayData` 持有（`RailwayData.java:227` 构造，`:652` 更新所有者，`:765/:783` 最低性能，`:1157` 联络线轨道），`Depot`（找路与运用）、`Siding`、`SimExport`、`FareData`、`RunningSides`、`SandboxRuns`、`PacketTrainDataGuiServer`、`TrackOwnersPackets`、`DepotWayPreview` 使用。组件侧：`TrackSections`、`LegRouter`、`RouteLegs`、`TrackOwnership`、`LogicalLines`、`StopTimes`。

## final class RoutePaths

### 常量
- `DEFAULT_CARS = 8`、`DEFAULT_CAR_LENGTH = 20`、`DEFAULT_ACCELERATION = 0.002F`（m/tick²，停车线界面显示 ×400 即 0.8 m/s²）：没有车厂运行时计时用的默认车辆。
- 轨道隔离状态：`RAIL_TAKEN = 1`（被联络线占用）、`RAIL_RELEASED = 2`（从联络线手动释放）、`RAIL_BOUNDARY = 3`（寻路边界：只把网络分区供搜索优先，从不阻止列车——用户决定；阻止列车是联络线的作用；地图上与联络线轨道分开绘制）。

### 字段
- `paths`（并发）：线路 id → `RoutePath`。
- `timingVersion`（`AtomicLong`）：每次有路径重新找到时递增；`getTimingVersion()` 返回它（`RunningSides`、`FareData`、`LiveSimulation` 用作缓存键）。
- `locks`：每条线路一个锁对象。
- `graphRecords`：轨道区段、所有者与腿（第 2 轮）。
- `ownersFingerprint`、`ownersWorking`（volatile）、`ownersReady`（volatile）。
- `manualSections`：所有者为手动设置的区段（网页分层第 2 步，供玩家查看）。
- `ownersVersion`：每次重算所有者递增（只在变化时发给玩家）。
- `sectionLogicalLines`：区段 → 逻辑线路（无逻辑线路时为空）。

## private static final class Owners
- 计算结果：`owners`（区段 → 所有者线键集合）、`manual`、`logical`。

## private static final class Logical
- 为计算所有者的线程拷贝的逻辑线路设置（2026-11-09）：`railLines`（只保留存在的逻辑线路）、`connecting`（联络逻辑线路）、`chosen`（运营线路 → 逻辑线路，只保留存在的）、`used`。
- `fingerprint()`：组合哈希。

### `public RoutePaths(GraphRecords graphRecords)`
- 调用者：`RailwayData.java:227`。

### `public RoutePath get(Route route, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Supplier<RailGraph<Rail>> graph)`
- 参数：`graph` 轨道网络，只用来说明找不到路径的原因（惰性构建）。
- 返回：线路路径；若其键不变、腿仍有效（`holds`）且未过时（`isStale`）则复用，否则重新找（`find`）并递增 `timingVersion`；线路有手绘路径时返回 `null`（直接用手绘的）。
- 副作用：在线路锁内更新 `paths`。
- 调用者：`RouteLayouts`/`RunningSides`/`Depot` 等经 `Search.get`。

### `public RoutePath getFound(Route route, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 返回：已为当前站台找到且腿仍有效、未过时的路径，不做任何查找；否则 `null`。
- 调用者：`RunningSides.java:90`、`SandboxRuns.java:179`、`SimExport.java:52`、`FareData.java:188`。

### `public void updateOwners(DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, PathBoundaries boundaries, Supplier<RailGraph<Rail>> graph)` / `updateOwners(..., Executor serverThread)`
- 作用（第 2 轮）：若来源变化则重算区段所有者：寻路边界把网络切成分区，分区归有站台在其中的线路所有；每条联络线拥有自己的部分（手动释放的轨道除外）。在服务器线程上、区段更新之后调用；联络线可能经过任何地方，所以在这里找它们的路径。指纹由区段快照指纹、边界节点与段、每条线路（键、是否联络线、线键、释放轨道）、手动所有者、逻辑线路组成；未变且已就绪则返回。`serverThread` 为空时立即计算；否则在独立守护线程 "MTR track owners" 上对数据拷贝计算（大网络上找联络线路径可能很久），完成后回到服务器线程 `setOwners`，并打印耗时。
- 调用者：`RailwayData.java:652`；测试 `RoutePathsTest`。

### `public boolean isOwnersReady()`
- 是否已算出过一次所有者（线路因此守在自己线的轨道上）。调用者：`RailwayData.java:793`、`TrackOwnersPackets.java:89`。

### `private void setOwners(Owners owners)`
- 写入 `graphRecords.setOwners`（会丢弃受影响的腿），更新手动区段、逻辑线路，版本 +1，就绪。

### `getSectionLogicalLines()` / `getManualSections()` / `getOwnersVersion()`
- 调用者：`TrackOwnersPackets.java:66`、`:92`。

### `private Owners computeOwners(DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, PathBoundaries boundaries, Supplier<RailGraph<Rail>> graph, GraphRecords.Legs legs, Map<UUID, List<String>> overrides, Logical logical)`
- 作用：收集每个站台上停靠的运营线路（非联络线）；联络线（无逻辑线路时、按路径计时）：其自有部分中未释放轨道所在区段归该联络线（`forEachLineRail`）。有逻辑线路时由 `LogicalLines.compute` 决定（联络线的自有轨道就是它所在的逻辑线路，不需要其路径）；否则 `TrackOwnership.compute(快照, 边界节点, 边界段, 站台线路, 联络线区段, 手动)`，手动区段为 `TrackOwnership.getOverrides` 的键。

### `public static List<Route> fillMinPerformance(Collection<Route> routes, Map<Long, Set<PathTiming.Vehicle>> vehicles)`
- 作用（第 3 轮）：为按时刻表运行、按路径计时、且某些表没有最低性能的线路设置最低性能：取运行该线路所在整条线的车辆中最慢的加速度、减速度、最高速（车厂常只列一个方向而运行两个方向），线上无车辆时用该线路的车辆（或默认车辆）。被设置的线路 `revision++`。服务器线程调用。
- 返回：改变的线路。
- 调用者：`RailwayData.java:765`。

### `public static List<PathTiming.Vehicle> getSlowerVehicles(Route route, Map<Long, Set<PathTiming.Vehicle>> vehicles)`
- 返回：运行该线路、性能低于其某表最低性能的车辆（用于提示）。
- 调用者：`RailwayData.java:783`。

### `public static Set<UUID> getReleasedRails(DataCache dataCache)`
- 返回：所有联络线手动释放的轨道（其它线路可经过）。调用者：`RailwayData.java:648`。

### `private static long mixKey(long value1, long value2)`
- 混合哈希。

### `public void retain(Set<Long> routeIds)`
- 释放已不存在的线路的路径与锁。调用者：`Siding.java:1234`、`:1300`。

### `public Map<UUID, Integer> getConnectingRails(TransportMode transportMode, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Supplier<RailGraph<Rail>> graph)`
- 返回：某交通方式联络线的轨道状态（股道图用）：被联络线占用 `RAIL_TAKEN`、手动释放 `RAIL_RELEASED`（同一轨道取较小值）。`graph` 为空时只取已找到的路径。
- 调用者：`RailwayData.java:1157`。

### `private static Set<UUID> getReleased(Route connectingLine)`
- 联络线释放轨道的键集合。

### `private static void forEachLineRail(Route connectingLine, RoutePath connectingPath, Map<BlockPos, Map<BlockPos, Rail>> rails, BiConsumer<Long, Long> consumer)`
- 作用：对联络线自有部分中每条非站台轨道回调：从第一个途经点（关键节点）——没有则首站台后的第一个道岔——到其后的最后一个途经点——没有则末站台前的最后一个道岔。沿正线离站、之后才分岔的联络线只拥有分岔支线，不拥有出发时的正线（解耦文档 5.4：接正线或站台的两端不算）；完全没有道岔时首末站台之间全部归它（与以前相同）。

### `private static int[] getOwnRange(...)` / `private static int lastForkEnd(...)` / `private static boolean isFork(Map, BlockPos)`
- 求自有部分的 `[起, 止)` 轨道下标；最后一个道岔结束处；节点连出轨道多于 2 条即为道岔。

### `public static double getBlocksBetweenStops(List<PathData> path, int firstStop, int lastStop)`
- 参数：线路路径（找到的或手绘的，停站从 1 编号 `PathData#stopIndex`），停站从 0 计。
- 返回：两站之间的轨道长度（方块），路径没有这两站时 −1；票价按它计算（设计文档 6.13）。
- 调用者：`FareData.java:200`。

### `private static int getStopRail(List<PathData> path, int stop)`
- 返回：该停站（`stopIndex == stop + 1` 且在站台上）的路径下标，无为 −1。

### `public static boolean timesByPath(Route route)`
- 返回：线路时分是否来自其自己的路径：没有手绘路径，且不是飞机或连续运动（这些仍按车厂路径计时）。
- 调用者：`SimExport.java:52`、`FareData.java:189`、`RailwayData.java:1215`。

### `public static PathTiming.Vehicle getDefaultVehicle(TransportMode transportMode)`
- 返回：没有车厂运行时计时用的车辆：8 节 20 米、加减速 0.002（0.8 m/s²）、使用速度曲线（新车厂默认）。
- 调用者：`Depot.java:3850`、本类。

### `public static Map<Long, Set<PathTiming.Vehicle>> getVehicles(RailwayData railwayData)`
- 返回（服务器线程读取）：每条线路 → 运行它的车厂各停车线的车辆（按车厂是否使用速度曲线 `siding.getTimingVehicle(depot.speedCurves)`）。
- 调用者：`SimExport.java:47`、`SandboxRuns.java:191`、`RailwayDataPathGenerationModule.java:121`。

### `public static Collection<PathTiming.Vehicle> getVehicles(Map<Long, Set<PathTiming.Vehicle>> vehicles, Route route)`
- 返回：计时该线路用的车辆：运行它的，或默认车辆。

### `private RoutePath find(Route route, String key, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Supplier<RailGraph<Rail>> graph)`
- 作用（第 2 轮）：由腿拼出线路路径：每站的轨道区段、其后的关键节点、线路的线（守在其轨道上；联络线可经任何地方）。区段尚未就绪时返回空路径（`NO_ROUTE`，就绪后会重找）；站台不存在同样。`RouteLegs.plan(...)` 找到则 `expand` 成轨道段；展开失败（轨道拷贝与区段暂时不一致）为 `NO_ROUTE`；没找到且至少 2 站则 `diagnose`。套用线路限速（`PathData.capSpeed`）。打印找路结果、段数、腿数、耗时与原因。
- 返回：`RoutePath`（含参考过的腿，用于判断是否仍有效）。

### `private static PathFailure diagnose(RouteLegs.Plan plan, String line, List<Long> stopSections, List<long[]> waypoints, List<List<BlockPos>> waypointPositions, List<SavedRailBase> platforms, GraphRecords.Legs legs, List<LegRouter.Leg> consulted, Supplier<RailGraph<Rail>> graph)`
- 作用：找不到路径的原因：途经点已不是关键节点（节点仍有轨道但不在区段交界处 → `NOT_KEY_NODE`，否则 `MISSING_WAYPOINT`）；不限制线路时能找到，则第一个其线不拥有的区段：属于联络线（`CONNECTING_LINE`，带联络线 id 与区段两端）或其它线（`OTHER_LINE`）；某段不经其关键节点可走、经过则不行 → `WAYPOINT_UNREACHABLE`（指出卡住的节点和两站）；否则交给 `PathFailure.diagnose`（网络本身）。

### `static String describe(PathFailure failure, DataCache dataCache)`
- 返回：日志用的简短原因（含站名和节点坐标）。
- 调用者：`Depot.java:1213`；测试 `RoutePathsTest.java:353`。

### `findDepotWay` 公共重载
- `findDepotWay(from, to, rails, out, platformEnd)`：出库（停车线 → 站台）或回库（站台 → 停车线）路径（第 4 轮第 3 步），在轨道区段上像线路的腿一样找并保存：按线路运行方向经过站台，起点从不折返（用户 2026-11-08：出入库列车按线路方向运行）。`platformEnd` 为线路驶向的站台端节点（出库在首站、回库在末站），0 表示任意。
- `findDepotWay(..., DepotLine depotLine)`：线路中途的车厂（2026-11-09，用户：出到离车厂最近的车站，再按规则）：出到离停车线最近的车站（任意方向进入，必要时在那里折返），此后只按线路方向沿其线路运行、在线路折返处折返；回库反之（组件 `LineDepotWays`）。`depotLine` 为空时两者间直接找。
- `findDepotWay(..., DepotLine depotLine, long[][] why)`：无路时把原因写入 `why[0]`（格式同 `DepotLegs#outReasons`）。
- `findDepotWay(..., DepotLine depotLine, long[] waypoints)`：经过运用自带的命名回送路径的节点（"运用补完"第 3 步），代替车厂规则。
- 返回：轨道段列表；无路为空列表；区段尚未就绪为 `null`。
- 调用者：`Depot.java:1231`、`:1237`。

### `private List<PathData> findDepotWay(SavedRailBase from, SavedRailBase to, Map<BlockPos, Map<BlockPos, Rail>> rails, boolean out, long platformEnd, boolean keep, DepotLine depotLine, long[][] why)`
- 参数：`keep` 找到的腿是否保存（仅为日志测量时不保存，否则会保留它尝试过的每一条路，第 4 轮第 4 步）。
- 作用：
  1. 两端的区段；站台按线路方向（`platformAlong`）。
  2. 车厂规则（"运用补完"第 2 步，`wayRuleFor`）：命名回送路径，或出/回经过的车站（停车线在多个车站接入线路时必须设置）。没有路径节点时：规则指定车站则只保留该车站（无则 `REASON_RULE`）；未指定而停车线接入多于一个车站时（0.75.0 起）只报告（`reportEntranceAuto`）并照常按最方便的方向找（线路方向优先、逆向过站台最后才用），不再返回 `REASON_ENTRANCE_UNSET`；候选为离停车线直线距离最近的 `NEARBY_STATIONS` 个车站（检查全线所有站曾在大网络上占满内存）；0.80.1 起先只留 `entrancesOf` 给出的入口站的站台（入口站里没有时照旧），免得挑中直线最近、但车要绕全线才开得到的车站（六线图 6 号线车厂在四站旁、道岔朝西）。
  3. 规划：规则指定了入口站台且在线上 → `LineDepotWays.planOut/planBack` 经该站（停车线一侧经命名路径节点 `throughDepotLeg`）；有路径节点 → 按节点顺序（编辑器点选的轨道覆盖其整个区段，2026-11-09）`RouteLegs.plan`；无候选车站 → `RouteLegs.planBetween`；否则 `LineDepotWays.planOut/planBack` 经最近车站。
  4. `-Dmtr.debugDepotWays=true` 时打印每条出入库路及途中折返的站台。
  5. 没找到：`diagnoseDepotWay` 写原因，返回空。找到：每个停靠区段对应停车线、途中折返的站台、目标站台（无法对应则空），`expand` 展开；途中折返的站台不是停站：只换端（停站时间改为折返时间、站台 id 清零），否则回库列车会在那里等待一个没有计划的发车。

### `private static void setWhy(long[][] why, int reason)`
- `why` 非空时写 `{reason}`。

### `private static long[] diagnoseDepotWay(long fromSection, long toSection, boolean out, Boolean platformAlong, DepotLine.Plans linePlans, DepotLine.WayRule wayRule, RouteLegs.LegSource source)`
- 返回（0.65.26）：完全不连通 `REASON_NO_TRACK`；路径中逆线路方向经过线路停站的车站 → `{REASON_AGAINST, 车站 id...}`；有车厂规则 → `REASON_RULE`；否则 `REASON_WRONG_WAY`（只能以错误方向到达站台）。

### `private static TrackSections.Section sectionOf(SavedRailBase savedRailBase, GraphRecords.Legs legs)`
- 站台/停车线所在区段。

## public static final class DepotLine
车厂出入库所用的线路：其站台按区段、以及其路径在轨道区段上作为线路在各处的方向，随区段重算。

### 字段
- `routes`（非联络线）、`dataCache`、`plans`（缓存的 `Plans`）、`rules`（车厂出入库规则，"运用补完"第 2 步，或无）、`entrances`（停车线 → 接入车站集合）、`entrancesSnapshot`、`reported`（已报告的"未设入口"停车线/站台）、`entrancesUnset`（接收缺少规则的线键，或 `null`）、`override`（编辑器正在选择的规则）。

### `public DepotLine withWay(long entrance, long[] waypoints)`
- 作用：所有出入库都经此车站或这些节点（编辑器中正在选择的规则），代替车厂规则；返回自身。调用者：`DepotWayPreview.java:111`。

### `DepotLine through(long[] waypoints)`
- 返回：同样线路与规则、所有路都经这些节点的新对象（共享已算出的线路规划）。

### 构造函数
- `DepotLine(Collection<Route> routes, DataCache dataCache)` / `(…, DepotRules rules)` / `(…, rules, Set<String> entrancesUnset)`：只保留非联络线。
- 调用者：`Depot.java:1227`、`Depot.java:1368`、`DepotWayPreview.java:111`。

### `long stationOf(long platformId)`
- 站台所属车站，无为 0。

### `WayRule wayRuleFor(long platformId, boolean out)`
- 返回：出到该站台（或从该站台回）的路按车厂规则怎么走：有 `override` 用之；否则从该站台出发（回库为在该站台结束）的第一条线路，其线在任一星期匹配的规则若设置了车站或命名路径即用之（各天的路相同）；否则 `NONE`。命名路径的入口站台为出库 `path.to`、回库 `path.from`。

### `synchronized Set<Long> entrancesOf(SavedRailBase siding, GraphRecords.Legs legs, Plans linePlans, RouteLegs.LegSource source)`
- 返回：停车线接入线路的车站（出入口）：对离停车线最近的 `2 × NEARBY_STATIONS` 个车站，停车线两种离开方向、到达两种方向的腿上遇到的第一个线路站台所属车站。按区段快照缓存；`-Dmtr.debugDepotWays` 时打印。

### `synchronized void reportEntranceAuto(SavedRailBase siding, SavedRailBase platform)`
- 作用（0.75.0 由 `reportEntranceUnset` 改名）：把以该站台为首/末站的线路键加入 `entrancesUnset`（网页只作灰色说明）；每对停车线/站台只打印一次"停车线在多于一个车站接入线路，车厂没有规则说明该站台的列车经哪个车站；按最方便的走"。

### `synchronized void reportAgainstUnlocked(SavedRailBase siding, List<Long> platforms)`
- 作用（0.75.0）：唯一的出入库路要逆着线路方向过本线站台、而这些站台不在联锁区时，把 `AGAINST_UNLOCKED`（`"against:"`）+ 站台 ID 加进 `entrancesUnset`（随缺车说明发给网页，网页单独警告），每对停车线/站台组只打印一次说明（照常使用，但刷联锁色之前可能对撞）。

## 逆向过站台与联锁区（0.75.0，用户 2026-11-13）
- 字段 `interlockZones`（`Supplier<Map<UUID, Long>>`，默认空表）：轨道 → 联锁区编号，由 `RailwayData` 设为 `getSignalBlocks().getZones(SecurityConfig.interlockColor)`（联锁色，默认品红）。
- `findDepotWay` 在 `expand` 之后：`LineDepotWays.againstStops(plan, linePlans.plans)` 列出逆着线路方向经过的站台；非空时 `unlockedAgainst` 检查每个站台本身的轨道以及路径上和站台两端相接的轨道都在联锁区里；有不在的就 `reportAgainstUnlocked`，路照常使用。联锁区让进路一次锁住整段，对向列车进不来。起初定为没刷就不用，但六线测试图的 1、3、4、5、6 号线（站前折返+站后车库、车厂只接单边等常见布局）都因此没了运用，用户改定"没刷照样走，只提示风险"。
- 0.80.1：之后再算 `LineDepotWays.againstLength`（超过 `LONG_AGAINST` 时 `reportAgainstLong(siding, 入口站台, 长度)`）和 `againstSpans`（每个逆向跑完的站间 `reportAgainstSpan(siding, 站台, 站台)`）；两者都只打一次日志，并以 `AGAINST_LONG = "againstlong:"`+站台+`:`+格、`AGAINST_SPAN = "againstspan:"`+站台+`:`+站台记进 `entrancesUnset`，网页各出一条警告。
- 常量 `DepotLine.AGAINST_UNLOCKED = "against:"`：`entrancesUnset` 里以它开头的是逆向未联锁的站台，其余是线路键；`Depot` 打日志时把它们滤掉。

## static final class WayRule
- 出/回经过的车站（0 为最近的）、命名路径的节点、入口站台；`NONE` 为空规则。

### `private synchronized Plans plansFor(GraphRecords.Legs legs, RouteLegs.LegSource source)`
- 作用：区段快照变化时重算：每条线路的停站区段（任一站台无区段则略过该线路）与途经点，`RouteLegs.plan(..., 线键, source)`；`stations` 记录区段 → 第一个站台。

## private static final class Plans
- `snapshot`、`stations`（区段 → 站台）、`plans`（各线路规划）、`lines`（对应线键）。

### `public List<PathData> previewDepotWay(SavedRailBase from, SavedRailBase to, Map<BlockPos, Map<BlockPos, Rail>> rails, boolean out, DepotLine depotLine)`
- 返回：按编辑器中正在选择的规则（`DepotLine#withWay`）找出的出入库路径，像找路时那样找但不保存；区段未就绪为 `null`。调用者：`DepotWayPreview.java:117`。

### `public Set<Long> findEntrances(SavedRailBase siding, DepotLine depotLine)`
- 返回：停车线接入线路的车站（出入口，"运用补完"第 2 步）；区段未就绪为空。调用者：`DepotWayPreview.java:112`。

### 常量 `NEARBY_STATIONS = 4`
- 出入库路可在离停车线直线距离最近的这几个车站折返。

### `public static long timeDepotWay(List<PathData> path, PathTiming.Vehicle vehicle, boolean out)`
- 返回：车辆走出入库路所需毫秒：出库直接进站台时为到停在站台（`getLeadMillis`）；经折返的车站或在站台本身折返时为到路径结束；回库为从停在站台到路径结束（以车头起点重新设定起始长度）；无路为 −1。
- 调用者：`Depot.java:1391`、`Depot.java:1423`、`DepotWayPreview.java:128`。

### `public long measureDepotLeg(SavedRailBase from, SavedRailBase to, Map<BlockPos, Map<BlockPos, Rail>> rails, PathTiming.Vehicle vehicle, boolean out, long platformEnd)`
- 返回：`findDepotWay`（不保存）再 `timeDepotWay`；只用于日志。调用者：`Depot.java:1504`、`:1514`。

### `static List<PathData> expand(RouteLegs.Plan plan, TrackSections.Snapshot snapshot, List<SavedRailBase> platforms, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：把规划的区段序列展开成列车实际运行的轨道段（与 `DeterministicPathFinder` 布局路径的方式相同）：第一段属于第一个站台/停车线；停站区段的最后一条轨道（非折返）带该站台的停站时间（停车线为 0）和站序；折返步骤带上一站的 id、停站 0；折返线上折返的轨道带折返停留时间；其余为普通轨道。
- 返回：路径；区段或轨道缺失时 `null`。
- 调用者：`RoutePaths.java:606`（`find`）、`RoutePaths.java:820`（`findDepotWay`）。

### `static String getKey(Route route, DataCache dataCache)`
- 返回：找线路路径的依据：限速（新限速会重找并带上限速）、联络线或线键、各站台 id 与站台停站时间、各站途经点。线路的停站设置不影响路径（由使用者套用）。
- 调用者：`Depot.java:373`、`SandboxRuns.java:207`。

### `static boolean isStale(List<PathData> path, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 返回：路径上某轨道已不存在或不再相同（类型、两端朝向、长度差 > 0.01），与停车线路径规则相同。调用者：`Siding.java:1234`、`:1836`、`RoutePaths.java:1544`。

### `static long[][] slowest(long[][] times1, long[][] times2)`
- 返回：逐站取较晚的到达与离开；任一未到达该站为 `{-1, -1}`。

### `static List<PathData> copy(List<PathData> path)`
- 深拷贝每段。调用者：`RoutePaths.java:1434`（`getPath`）、`:1450`（`getSlice`）。

## public static final class Search
一个找路线程看到的线路路径：自己的轨道拷贝，以及只在需要找路时才构建一次的搜索网络。
- 字段：`routePaths`、`rails`、`boundaries`、`vehicles`、`graph`（惰性）。
- `Search(RoutePaths, rails, RailGraph<Rail> graph, PathBoundaries boundaries, Map<Long, Set<PathTiming.Vehicle>> vehicles)`：`graph` 为车厂已构建的网络或 `null`。调用者：`Depot.java:1073`、`Depot.java:1142`、`RailwayData.java:1170`、`PacketTrainDataGuiServer.java:943`。
- `getVehicles(Route)`：计时该线路的车辆。
- `getConnectingRails(TransportMode, DataCache)`：见 `RoutePaths#getConnectingRails`。
- `getTimetableTimes(Route, DataCache)` / `getFastestTimes(Route, DataCache)`：必要时找路径后返回时分；手绘路径为 `null`。调用者：`getTimetableTimes` — `Depot.java:1075`、`:1202`、`PacketTrainDataGuiServer.java:948`；`getFastestTimes` — `PacketTrainDataGuiServer.java:1011`。
- `get(Route, DataCache)`：见 `RoutePaths#get`。
- `private synchronized RailGraph<Rail> getGraph()`：首次需要时 `DeterministicPathFinder.compile(rails, boundaries)`。

## public static final class RoutePath

- `getHeadways(stopSettings, pools, rails, directions, sections, transportMode)`（B，0.82.0）：线路路径（`getPath`，`SimExport.toTracks(path, rails, directions)` 转成组件轨道，行车方向显式传入：按轨道表缓存的那份在模拟器还没导出时是空的，复线会被当成单线整段，7 号线一开始就是这样算成 0）上每对车型（池键>池键，没有车池时用默认车型 ""）每一站到下一站所需的最短间隔（组件 `Headways.find` 按各车型的计划时刻段 `PathTiming.generateTimeSegments`，再 `byStop`；站的位置取各站台轨道末端），外加所有组合的最大值 ">"。调用者：`PacketTrainDataGuiServer.checkTimetableConflicts`。
找到的线路路径；创建后不变，并且只发出拷贝（使用者会修改自己那份的轨道段）。

### 字段
- `routeId`、`failure`（未找到的原因或 `PathFailure.NONE`）、`key`、`rawPath`（不可变；每站按站台自己的停站时间，第 i 站在 `stopIndex == i + 1` 的轨道上）、`times`（车辆 + 停站设置 + 停车位置指纹 → `StopTimes` 缓存）、`consulted`（求出它时参考过的所有腿，被选中与否；`null` 表示要立即重找）。

### 构造函数
- `RoutePath(long routeId, String key, List<PathData> rawPath, PathFailure failure)` / `(…, List<LegRouter.Leg> consulted)`。

### `private boolean holds(GraphRecords graphRecords)`
- 它依赖的每条腿是否仍是缓存中的（没有任何依赖改变）。

### `public boolean isFound()`
- 至少 2 段。

### `public List<ThroatRoutes.StopThroat> getThroats(int stopCount, Map<BlockPos, Map<BlockPos, Rail>> rails, Map<UUID, Long> zones)`
- 返回：各站列车进出站经过的道岔（组件 `ThroatRoutes`，L2），节点上相交的轨道数按当前轨道计数；`zones` 为联锁区（`SignalBlocks.getZones`）。调用者：`PacketTrainDataGuiServer.java:809`。

### `public long[] getNodes()`
- 路径依次经过的节点（地图与股道图用）。调用者：`RailwayData.java:1216`、`:1271`。

### `int getStopCount()`
- 最后一段的 `stopIndex`。

### `public List<PathData> getPath(List<Route.RoutePlatform> stopSettings)`
- 返回：从首站到末站、套用这些停站设置（`PathFinder.applyStopSettings`）的拷贝。调用者：`RunningSides.java:91`、`Depot.java:1344`、`Depot.java:1489`。

### `public List<PathData> getSlice(int firstStop, int lastStop)`
- 返回：两站之间的拷贝（各站按站台停站时间），从首站停着开始（第一段停站时间置 0），使班次停站可同样套用；没有这两站为空。调用者：`Depot.java:3244`、`:3336`。

### `public double getBlocksBetweenStops(int firstStop, int lastStop)` / `public int getStopRail(int stop)`
- 见同名静态方法；`getStopRail` 返回到达该站的第一条轨道下标（首站为第一段）或 −1。调用者：`RoutePaths.java:446`、`:1399`（游戏侧 `Depot.java:2996` 等调用的是 `Siding.getStopRail`，不是这里）。

### `public long[][] getTimetableTimes(Route route, Collection<PathTiming.Vehicle> vehicles)`
- 返回：每张表（按其最低性能）的最快时分，用 `TimetableTimes.pack` 打包；并设为线路的时分基线。调用者：`SimExport.java:54`、`PacketTrainDataGuiServer.java:929`、`RoutePaths.java:1322`。

### `public long[][] getFastestTimes(Route route, Collection<PathTiming.Vehicle> vehicles)` / `getFastestTimes(..., RouteTimetable table)`
- 作用：线路最快时分的唯一来源（解耦文档 5.4，L1）：沿自己的路径、按自己的停站，逐站取所有车辆（换成该表的最低性能后）中最慢的，使由此编排的时刻表每列车都能守住。按车辆与停站缓存。
- 返回：每站从离开首站起算的最快到达与离开（未找到的站 −1，同 `StopTimes#getFastestTimesFromFirstStop`）；无路径为空数组。
- 调用者：`SandboxRuns.java:193`、`RoutePaths.java:1327`、`:1491`。

### `static Collection<PathTiming.Vehicle> withMinPerformance(Route route, ...)` / `withMinPerformance(RouteTimetable table, ...)`
- 返回：用时刻表要求的最低性能（加减速、最高速）代替车辆自身性能的车辆（第 3 轮），使时分不随运行车辆变化；表未要求时原样返回。

### `private StopTimes getStopTimes(PathTiming.Vehicle vehicle, List<Route.RoutePlatform> stopSettings)`
- 缓存键：车辆、各站通过与停站覆盖、`PlatformStops.fingerprint()`（第 4 轮停车位置变化立即生效）；值为 `PathTiming.calculateStopTimes(vehicle, getPath(stopSettings))`。

### `private boolean isStale(Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 委托静态 `isStale`。
