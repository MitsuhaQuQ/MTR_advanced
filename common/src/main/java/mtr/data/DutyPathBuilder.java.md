# DutyPathBuilder.java

车厂的路径构建：把线路拼成各存车线运用的路径，找班次之间的连接和折返、附近的折返轨和留置位，量换站台和折返用时，车厂没设运用时从时刻表排运用。2026-11-13 从 `Depot` 的内部类 `MainPath` 原样移出（拆分车厂第 1 步，行为不变：yel 世界开服导出的全部运用路径和移动前逐行相同，见下）；字段 `depot` 指向所属车厂，原来隐式用到的车厂成员都改成 `depot.` 前缀。下一步是把其中纯计算的部分逐块搬进组件：附近折返轨和留置位已搬（0.75.2，`transitsim.network.NearbyTurnBacks`），接着是折返和换站台用时、连接段拼接、整份运用路径组装。

## private static final class Connection（原在 `Depot`，只有这里用）
找到的两班次间连接，以拷贝发出，保留连接与其折返线绕行共用哪些轨道。
- 字段：`path`、`section`（折返区段或 null）。
- `copyInto(List<PathData> connectingPath)`：把连接拷到参数中，返回绕行的拷贝（共用的段用同一拷贝）或 null。
- `static copyOf(PathData, Map<PathData, PathData> copies)`：按身份去重拷贝并保留站序。

## final class DutyPathBuilder（原 `Depot.MainPath`）
一组停车线（车辆运行同一组线路）共享的、经过车厂部分线路的路径，以及为其找运用路径、折返、连接所需的全部搜索。
### 字段
- `turnBackRailsOf` / `nearbyTurnBacks`：找附近折返轨时所搜的轨道，和组件里为每个站台找附近折返轨的 `transitsim.network.NearbyTurnBacks`（0.75.2 起，所搜轨道换了才重建）。
- `connectionsOf` / `connections`：本次搜索中找到的班次间连接（按所搜轨道）。
- `failure`、`sidings`、`lineSidings`（编排单元其他车厂的停车线）。
- `repeatPath`、`closesPath`（从末条线路折返回第一条线路首站，列车不经车厂循环）、`dutiesOnly`（按运用运行：每条线路只检查，列车走运用路径，线路顺序无关、无需首尾相接）。
- `loopRoute` / `loopEntryStop`：列车中途加入的环线及加入站。
- `mainRoutes`、`dataCache`、`routeSearch`（按时刻表运行时线路自己的路径，或 null 照旧逐线搜索）。
- `platformsMerged`、`platformsInRoute`、`pathsInRoute`（手绘路径的拷贝）、`waypointsInRoute`、`stopSettingsInRoute`。

### `DepotDuties generateAutoDuties(MinecraftServer minecraftServer, LineDuties.Plan linePlan)`
- 作用（解耦文档步骤 D）：车厂按时刻表运行且没有设定运用时，从线路时刻表算出这些停车线的运用，并在服务器线程交给车厂。不按时刻表时清除这些停车线的自动运用（并通知客户端，否则编辑器继续显示不再运行线路的运用），返回 null。有单元计划时取本组停车线的槽位、`applyRules`，在服务器线程写入并保存计划记录、`lineShortfall`（含未设入口线路，打印）并发送。否则（单独编排）：线路最快时分取线路自己的（`RoutePath#getFastestTimes`，按跑它的最慢车辆，与编辑器显示一致），手绘路径沿本车厂路径计时；收集停车线输入、出入库连接、池车队、缓冲、最短折返时间、等待阈值，`AutoDuties.generate`，`applyRules`，打印结果并在服务器线程写入。
- 返回：运用或 null。调用者：`Depot.java（行号为移动前）:3563`、`:4196`。

### 构造函数 `DutyPathBuilder(Depot depot, List<Route> routes, List<Siding> sidings, DataCache dataCache)`
- 作用：拷贝每条线路的手绘路径；无手绘路径时收集站台、途经点、停站设置；合并站台（相邻同站台合一）。`dutiesOnly = runsByDutiesWith(routes)`；`closesPath`：非按运用、循环线路、首尾站台不同时追加首站；`repeatPath`：重复、闭合，或有时刻表且首尾站台相同；单条按时刻表的环线且无手绘路径时可中途加入（`getLoopEntryStop`）。
- 调用者：`Depot.java（行号为移动前）:1135`、`:1581`、`:1602`。

### `List<TripTail<PathData, SavedRailBase>> getTripTails(List<PathData> mainPath, rails, graph, boolean useFastSpeed)`
- 作用：找末条线路的班次接续到其他线路的地方；每种接续只需一条路径（无论多少班次用它）；不可达的线路略去，其班次留在主路径上。按时刻表链接链（`RouteTimetable.getLinkChain`）与键去重。调用者：`Depot.java（行号为移动前）:4194`。

### `TripTail<PathData, SavedRailBase> getTripTail(Route lastRoute, List<RouteTimetable.ServiceLink> links, List<PathData> mainPath, rails, graph, boolean useFastSpeed)`
- 作用：沿链接逐条：在离开站（截断时为链接起站，否则末站）离开；不同站台时找连接（直通班次在线路列出的第一条折返线折返，不做选择；截断时直接找）；取下一线路自己的路径（或搜索并套停站设置）；站序接着之前所有线路完整计数；处理中途加入（环线：从加入轨道复制；否则记入加入站与引导路径）；按下一链接截断；末尾为环线时记圈起点与是否反向；`addToTail`。
- 返回：`TripTail`（键、线路、截断站、离开轨道、尾路径、末站台、圈起点、反向、加入轨道、加入站与引导），任何部分缺失为 null。

### `List<DutyPlan<PathData, SavedRailBase>> getDutyPlans(DepotDuties dutiesToPlan, Siding siding, rails, graph, boolean useFastSpeed, Consumer<String> failed)`
- 作用（设计文档 6.12、E2）：停车线车池中每条停车线的槽位所运行的每种运用形状各一次（其列车可代替其他停车线的列车运行）：按 `DutyShapes.getSlotKey` 去重，`getDutyPlan`；失败时设 `failure` 为 DUTY 并告知 `failed`。
- 参数：`dutiesToPlan` 为车厂运用或其草稿。调用者：`Depot.java（行号为移动前）:1604`、`:1627`。

### `List<Siding> getPoolSidings(Siding siding)`
- 返回：自己在前，然后同组同车辆的停车线、编排单元其他车厂同车辆的停车线，再加车厂中同池的其他停车线（无论指定什么线路，因为计划把列车送回最近的，第 4 轮第 3 步），各按 ID。调用者：`Depot.java（行号为移动前）:1629`、`:3147`、`:4207`。

### `DutyPlan<PathData, SavedRailBase> getDutyPlan(String key, List<DepotDuties.Segment> segments, int weekday, boolean startsOnLine, boolean staysOnLine, Siding siding, rails, graph, boolean useFastSpeed)`
- 0.76.0：从车厂出来的第一段（不在线上过夜、从首站开始），若 `Siding.DEPOT_WAYS.startAt(siding, 首站)` 给出别的站台（单独出库站台），该段从那个站台开始：首站换成它（停站设置随之），不用线路自己的路径而在轨道上找到第二站，模板键带上它。`siding` 为跑这些运用的停车线。
- 作用：把运用各段连起来：每个班次从加入线路处到离开处，按该班次的通过/停站（单个班次的跳站由此成真），再按车厂路径的折返方式接到下一段。每段模板（线路、起止站、限速、通过标志）缓存：线路自己路径的切片（切片从停着开始，运用列车从出库路或上一班次来到首站，要在那里停车等发车，否则会直接通过首站出库，U-L05），或搜索；套停站设置并按线路限速封顶。段间站台不同时找连接（`findConnection`），记录绕远（长于 max(300, 距离×5)），多种折返方式时记折返区段；与上一段在同一轨道上衔接时合并（从另一站台来到本线路首站的列车在此停车等发车），在站台上反向折返时该段首段只作出站；站前折返（`addFrontTurnBack`）；记首末站台、线路、截断与加入站；中途加入时为计划计时取引导路径。
- 返回：`DutyPlan`（含索引边界、折返区段、各段截断与加入），任何部分找不到为 null。
- 0.78.1（拆分车厂第 2d 步）：拼接本身在组件 `transitsim.plan.DutyAssembly.assemble`；这里只实现它的 `Host`：`part`（解析线路和站台、单独出库站台、切片或找路、停站设置和限速，模板缓存同前）、`connect`（`findConnection` 加绕远提示 `pathDetours`）、`frontTurnBack`（`addFrontTurnBack`）、`entryLead`（中途加入时从首站到加入站的引入段）。每段的线路、站台、路标和线路自身路径记在 `SegmentSource` 里供后面几步用；轨道比较和带停站复制用 `PATH_TRACKS`。

### `void addFrontTurnBack(...)` / `void addFrontTurnBackTimed(...)`
- 作用（站前折返）：线路在列出其他站台可供折返的车站结束、下一段线路从同一车站开始时，加入从倒数第二站经每个这样的站台到下一线路第二站的走法，按下一线路偏好的始发站台排序（种别优先）。列车通常在末站台折返时通常走法是其中之一；站后折返时通常走法排最后（先试站台，都不行才走站后）。找不到某站台的走法时记失败 `TURN_BACK_UNUSED`。至少两种可行走法时加入折返区段。`ConnectionStats.ON` 时计时。
- 参数：`previousStart` / `nextStart` 为主体中上一/下一线路开始的轨道。
- 0.76.1 起（拆分车厂第 2c 步，规则不变）：可折返站台的筛选与排序在组件 `transitsim.plan.FrontTurnBacks.order`，走法的组装与"至少两种才算一个区段"在 `FrontTurnBacks.section`；这里只判断前置条件（线路列出了可折返站台、两段都至少两站、同一车站）、找主体中的轨道下标、切出通常走法，并提供找路（`findFrontWay`）。

### `List<PathData> findFrontWay(SavedRailBase from, Platform platform, SavedRailBase to, fromWaypoints, toWaypoints, int dwellTime, List<PathData> usualWay, rails, graph, boolean useFastSpeed)`
- 返回：从一站进入该站台并在站台上折返出来到下一线路第二站的路（到达段带末站停站时间，反向段只作出站）；须与通常走法在相同轨道上离开与到达，否则 null。`usualWay` 为 null 时不比较（0.75.4，折返用时的 `turnsInto` 用它判断能否站前折返）。

### `static int findStopRail(List<PathData> body, int from, long platformId)`
- 从 `from` 起第一条为该站台的站台轨道，或 −1。

### `boolean usesRoutePaths()`
- 线路路径取自线路：有搜索、按时刻表、非连续移动、非飞机（这些照旧搜索全程）。

### `boolean isCut(Route route, RouteTimetable.ServiceLink link)`
- 链接起站在线路末站之前（班次在中途截断）。

### `int getStopCount(List<Route> routes)`
- 最后一条线路之前的站数（相邻线路首末站台相同时减一）。

### `void addToTail(List<PathData> tailPath, PathData lastMainRail, List<PathData> newPath)`
- 接到尾路径：首段与上一段反向时只作出站（停站 0、站台 id 0）；同一轨道时去掉首段。

### `int generateDutiesOnly(MinecraftServer minecraftServer, rails, graph, boolean useFastSpeed, LineDuties.Plan linePlan)`
- 作用：按运用运行的车厂：检查每条线路自己的路径（或搜索，失败时 `PathFailure.diagnose`），记下第一个找不到的；取运行运用（设定的或 `generateAutoDuties`）；每条停车线 `getDutyPlans`、`setTurnLinks`、`generateDutyRoute`（停车线本身为路径）。
- 返回：全部找到为 `DUTIES_ONLY_FOUND`，否则 0。调用者：`Depot.java（行号为移动前）:4103`。

### `boolean addConnection(List<PathData> tempPath, SavedRailBase from, SavedRailBase to, int routeBefore, List<TurnBackSection<PathData>> turnBackSections, List<DepotReturn<PathData, SavedRailBase>> depotReturns, rails, graph, boolean useFastSpeed)`
- 作用：加入从一条线路末站到下一线路首站的路，若该线路在末站后列出折返线则绕经它们（再加上站台附近的留置线作为更多走法，运用补完第 4 步）；记折返区段；循环线路时为留在线上的列车记录回库点（`DepotReturn`）。
- 参数：`routeBefore` 为末站所属线路下标，无站为 −1。
- 返回：所有折返线都不可达时 false（设失败 TURN_BACK，清空路径）。调用者：`Depot.java（行号为移动前）:4114`、`:4163`。

### `List<BlockPos> getTurnBackNodes(Route route, RailGraph<Rail> graph)`
- 线路在末站后列出的节点是可用折返线的端点，按列车尝试顺序；旧搜索（无图）不能跟随节点，为空。

### `Map<String, List<PathData>> setTurnLinks(Siding siding, List<DutyPlan<PathData, SavedRailBase>> dutyPlans, Map<String, List<PathData>> cache, rails, graph, boolean useFastSpeed)`
- 作用：找停车线运用可提前折返（D4）的地方：从一段除末站外的每个停站，绕到下一段在同一车站停靠（除末站外）的每个站台，按运用在两段间折返的方式（`findConnection`），不超过 `MAX_TURN_LINK_BLOCKS`（按 "from>to" 缓存）。并为交接运用（D5）收集：除最后一段外每段结束处的回库目标，除第一段外每段开始处的出库目标（以折返开始的段为其到达的站台）。交给 `siding.setTurnLinks`。
- 返回：绕行表。调用者：`Depot.java（行号为移动前）:1628`、`:1643`、`:3569`、`:4206`。

### `List<Long> getSegmentStops(DutyPlan, int segment)`
- 运用某段依次停靠的站台（去掉连续重复）。

### `TurnBackSection<PathData> findConnection(List<PathData> connectingPath, SavedRailBase from, SavedRailBase to, Route routeBefore, rails, graph, boolean useFastSpeed)`
- 作用：两班次间的路，从一个结束处到下一个开始处，按运用所跑：绕经前一线路在终点后列出的折返线，或（未列出时）找到的路与绕经车站附近折返线（站后折返）中较短者。计划的站台间时间沿它测量（`turnTimesFor`），使计划与运行走同一条路。每次搜索中按"from>to>前线路>高速"缓存，每次请求得到自己的拷贝（运用路径会给站编号并改停站；大网络上 1900 条路被请求 13000 次，第 5 轮第 5 步）。
- 参数：`connectingPath` 填入路径（无则留空）；`routeBefore` 为跑到终点的前一线路或 null。
- 返回：有折返线时其走法，否则 null。

### `TurnBackSection<PathData> findConnectionTimed(...)`
- 上述逻辑本体。0.76.1 起规则在组件 `transitsim.plan.TurnBackWays.connection`（列出的折返线加留置线优先；否则直接搜索的路与绕经附近折返线的走法比长度，短的赢，一样长用直接搜索的），这里只列出节点并找路（`waysRound`）。

### `TurnBackWays.Ways<BlockPos, PathData> waysRound(SavedRailBase from, SavedRailBase to, rails, graph, boolean useFastSpeed)`
- 交给组件的找路：经某节点从一站到下一站的路（`PathFinder.findPath`）、两段是否同一轨道同方向（`isSameRail`）、不可用时记 `TURN_BACK_UNUSED` 失败。

### `Map<Long, Map<Long, Long>> turnTimesFor(List<Route> routes, rails, graph, boolean useFastSpeed)`
- 作用：站内每次换站台按运用所跑（`findConnection`）需要多久：从每条线路末站台到同站其他线路首站台；无路的对略去（规划视为无路）。另外每个站台一次：绕经站台外折返线再回到本站台（列车可在那里等下一班次而让出站台，混合折返），记 `from→from` 总时间、`HOLD_IN_KEY`（进入段）、`HOLD_COUNT_KEY`（可同时等待的列车数，每条可用走法一列，运用补完第 4 步）、`HOLD_RAIL_KEY`（哪条折返线，同一折返线的站台共用，8 号线 2026-11-12）。换站台离站台超过 `MAX_PLATFORM_CHANGE_REACH` 的不算（会沿线跑出又回来与对向列车迎面，2026-11-09）。时间为两条线路车辆中最慢者的进入段 + 出来段（不含在折返处停留，第 4 轮，由计划加上网络最短换端时间）。`-Dmtr.debugTurnTimes` 时打印。
- 0.75.3 起（拆分车厂第 2a 步之后的 2b）：上面这些规则（每站台一次的折返线等待、跳过同站台/他站/已有的对、超出范围不算、取最慢车辆、三个键）在组件 `transitsim.planning.TurnTimes.of`，这里只是宿主：`holdWay` 找绕经折返线的走法（可等待数、折返轨两节点），`changeWay` 走 `findConnection`，`turnsInto`（0.75.4）在线路列出该站台为可结束站台时用 `findFrontWay` 判断能否站前折返，两者都给每辆车的进入/出来段（`legsByVehicle`）和离站台最远距离；`kept`/`tooFar` 只用于 `-Dmtr.debugTurnTimes` 打印。
- 返回：从站台 → 到站台 → 毫秒。调用者：`Depot.java（行号为移动前）:1181`、`:1581`。

### `List<long[]> legsByVehicle(Route before, Route after, List<PathData> way)`
- 两条线路各车辆（`vehiclesOf` 的顺序）沿这条路的 {进入, 出来} 毫秒（`legMillis`）。

### `Set<PathTiming.Vehicle> vehiclesOf(Route before, Route after)`
- 两条线路计时用的车辆；无搜索时为运输方式默认车辆。

### `long[] legMillis(PathTiming.Vehicle vehicle, List<PathData> connectingPath)`
- 作用（第 4 轮）：把绕行在折返处切开：进入段从出发到停在折返处，出来段从再出发到到达；不含停留。从停在站台（车头在站台末端）起算。无折返时全为进入段。这里用 `PathTiming` 计时，切开在组件 `TurnTimes.legs`（0.75.3）。
- 返回：{进入毫秒, 出来毫秒}。

### `void debugTurnTime(Platform from, Platform to, Route before, Route after, List<PathData> way, long[] legs)`
- `-Dmtr.debugTurnTimes`：打印第 4 轮前的算法（30 秒 + 全程 + 30 秒）与现在（最短换端 + 进入 + 出来）。

### `double farthestFrom(SavedRailBase platform, List<PathData> path)` / `int countReversals(List<PathData> path)`
- 路径离站台中点最远的距离；路径折返次数。

### `long connectionMillis(PathTiming.Vehicle vehicle, List<PathData> connectingPath)`
- 从停在站台起走完整条路的毫秒（只在调试中使用）。

### `List<BlockPos> getNearbyTurnBackNodes(SavedRailBase platform, rails)` / `getNearbyTurnBackNodesTimed(...)`
- 返回：站台附近的折返节点（线路未列出时用于折返），由组件 `NearbyTurnBacks` 找（0.75.2，拆分车厂第 2a 步，规则不变）：200 格内折返线的尽头，最近的在前，最多 6 个；然后每个留置区一个节点，最近的在前，最多 6 个。`ConnectionStats.ON` 时计时。

### `NearbyTurnBacks.Result nearbyOf(SavedRailBase platform, rails)`
- 所搜轨道换了（或第一次）时列出全部折返线（两端打包成长整数，`BlockPos.asLong`），建一个 `NearbyTurnBacks`：节点度数取 `rails.get(节点).size()`，留置区取 `dataCache.stablingRails` 按轨道（`PathData.getRailProduct`）；然后按站台 ID 查（站台中心 `getMidPos().asLong()`，范围 `Depot.NEARBY_TURN_BACK_BLOCKS`，上限 `Depot.MAX_NEARBY_TURN_BACKS`）。

### `List<BlockPos> withStabling(List<BlockPos> listed, SavedRailBase platform, rails)`
- 线路列出的折返节点之后加上站台附近的留置区节点（`nearbyOf(...).stabling`，玩家把它们标为该处折返的任何线路都可用，运用补完第 4 步）。

### `TurnBackSection<PathData> findTurnBackSection(SavedRailBase from, SavedRailBase to, List<BlockPos> nodes, int start, rails, graph, boolean useFastSpeed)`
- 作用：经每个折返节点找从一站到下一站的路。规则在组件 `transitsim.plan.TurnBackWays.section`（0.76.1，拆分车厂第 2c 步，规则不变）：每种走法都必须有轨道可走、且与第一条可用走法从同样的轨道离开第一站并到达下一站，使列车可从该站选任何一种；不可用的记 `TURN_BACK_UNUSED` 失败。
- 参数：`start` 为走法在主路径上开始处。
- 返回：走法（不可用为 null 项）与默认走法；都不可用为 null。

### `List<PathData> getLoopJoinPath(List<PathData> mainPath)`
- 列车加入环线的站到路径末尾的部分（拷贝），从首站加入为空。

### `int generate(MinecraftServer minecraftServer, rails, graph, boolean useFastSpeed, LineDuties.Plan linePlan)`
- 作用：按运用运行时 `generateDutiesOnly`。否则依次连接各线路：线路间 `addConnection`；手绘路径、线路自己的路径（找不到则失败）或搜索（失败 `diagnose`，套停站设置与限速）；与上一段反向时首段只作出站、同一轨道时去掉重复；闭合路径时从末站接回首站（无路时失败 `CYCLE_BACK`）。编站序（通过的站台无停站时间但仍计数，因为线路下标包含它们）；折返区段编站；闭合时圈尾站序同首站；回库点找站。取环线加入路径、班次尾、运行运用；每条停车线 `getDutyPlans`、`setTurnLinks`、`Siding.generateRoute(...)`。
- 参数：`linePlan` 为编排单元的运用或 null。
- 返回：各停车线成功段数的最小值。调用者：`Depot.java（行号为移动前）:1243`。

