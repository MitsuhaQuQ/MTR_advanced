# LineDepotWays.java

线路中任意位置车厂的出库与回库路径（2026-11-09，用户：出库到离车厂最近的车站，此后按规则运行）。列车出库开到离停车线最近的某个车站（两个方向都可进入，必要时在该站折返）；此后只按线路的运行方向沿线路各路线运行，只在线路本身折返的地方折返（从一条路线的终点到下一条路线的起点）。回库反过来：沿线路从最后一站开到靠近停车线的车站，必要时折返，再进停车线。

只按线路方向运行，可使出库列车不占用对向列车的轨道：仅凭轨道找路时，曾经沿线路另一条轨道逆向开到首站，与对向列车迎面相遇。

停车线与车站之间，列车可逆线路方向运行，但绝不能逆向穿过线路的其他站台（用户 2026-11-09：逆向进站台须检查对头死锁）：停在车站准备回库的列车占着该站、请求前一站台，而停在那里的本线列车占着它、请求该站，互相永远等待。有多条路径时优先顺向（用户：有多路径时优先顺向）：逆向区段在代价之外再计 `AGAINST_WEIGHT` 倍。

确无其他路径时（如终点站后紧接车厂、前方只有渡线；用户 2026-11-12：放宽规则，但需在游戏内显式表示寻路路线），仍会逆向穿过本线站台，但每个计 `AGAINST_STOP_PENALTY`，使其他路径优先；`againstStops` 给出逆向经过的站台，供游戏显示给玩家和车厂检查。

在整体中的位置：组件 `RoutePaths`（`RoutePaths.java:787-800`）调用 `throughDepotLeg`、`planOut`、`planBack`；游戏/测试用 `againstStops`。依赖 `RouteLegs`（`Plan`、`LegSource`、`planBetween`、`turnStep`、`railWaypoints`）、`LegRouter`、`TrackSections.Snapshot`、`RailRouter` 的代价常量。

## 类 LineDepotWays（final，不可实例化）

### 常量
- `AGAINST_WEIGHT = 2`：逆线路方向运行的区段在比较时额外计入的倍数（乘区段代价）。
- `AGAINST_STOP_PENALTY = 1e9`：出/回库路径每逆向经过一个本线站台额外加的代价，大于任何跨网路径，只在别无他路时采用。
- `MAX_AVOID_ROUNDS = 4`：`Graph.freeLeg` 为避开逆向站台而重新求 leg 的最多轮数。

### `static RouteLegs.LegSource throughDepotLeg(long siding, long[] waypoints, TrackSections.Snapshot snapshot, RouteLegs.LegSource source)`
- 作用：包装 leg 来源：只在停车线与入口站台之间（请求起点或终点为 `siding`）套用车厂指定的途经点（`RouteLegs.railWaypoints` 转换），线路其余部分原样转发。
- 参数：`siding` 停车线区段；`waypoints` 车厂设定的关键节点；`snapshot` 区段快照；`source` 原 leg 来源。
- 返回：新的 `LegSource`（lambda）。副作用：无。
- 主要调用者：`RoutePaths.java:787`、`MidLineDepotTest.java:148`。

### `static RouteLegs.Plan planOut(long siding, long platform, Boolean platformAlong, Collection<Long> stations, List<RouteLegs.Plan> routes, List<String> lines, TrackSections.Snapshot snapshot, RouteLegs.LegSource legSource)`
- 作用：从停车线出库到 `platform`，并以线路方向停在该站台。
  1. 用 `routes` 建线路图 `Graph`（节点 = 各路线的每个停站）。
  2. 在 `stations` 中按"停车线→车站"自由 leg 代价（`freeLeg`+`freeCost`，两种出发方向 × 两种到达方向）选出最近的若干站（`nearest`）。
  3. 对图中位于最近车站的每个节点，计算直接以线路到达方向进站的代价，或逆向进站再折返（+`TURN_BACK_PENALTY`）的代价，取最小作为该节点初值（`Arrival`）。
  4. 在线路图上 Dijkstra（`Graph.search`）到第一个满足"区段 = platform 且方向符合 `platformAlong`（null 为任意）"的节点。
  5. 找不到时退回 `RouteLegs.planBetween(siding, null, platform, platformAlong, legSource)`（直接路径）。
  6. 否则拼接步骤：停车线起步 → 入站 leg 步骤 →（折返时加折返步并把该站记为停站）→ 沿线路的步骤（`addSteps`）→ 终点停站。
- 参数：`siding` 停车线区段；`platform` 目标站台区段；`platformAlong` 目标站台的运行方向（null = 任意）；`stations` 可用作出库站的车站（路线停站中离停车线最近的几个）；`routes` 车厂各路线的已求得计划；`lines` 每条路线所属线路（用于其折返，可 null）；`snapshot` 区段快照；`legSource` leg 来源。
- 返回：`RouteLegs.Plan`：停站为停车线、途中每个折返站台、目标站台。
- 副作用：无（通过 `legSource` 可能触发 leg 计算/缓存）。
- 主要调用者：`RoutePaths.java:788`、`RoutePaths.java:798`、`MidLineDepotTest.java:153`。

### `static RouteLegs.Plan planBack(long platform, Boolean platformAlong, long siding, Collection<Long> stations, List<RouteLegs.Plan> routes, List<String> lines, TrackSections.Snapshot snapshot, RouteLegs.LegSource legSource)`
- 作用：从 `platform`（以线路方向停着）回到停车线。以符合方向的 platform 节点为初值 0，在线路图上搜索，终止代价函数为"在最近车站离开（原方向或折返）到停车线"的自由 leg 代价（`search` 的 `finish` 版本，取总代价最小的节点）。找不到时退回 `RouteLegs.planBetween(platform, platformAlong, siding, null, legSource)`。步骤：站台起步 → 沿线路 →（折返时加折返步）→ 回库 leg 步骤。
- 参数与返回同 `planOut`（方向相反）。
- 主要调用者：`RoutePaths.java:789`、`RoutePaths.java:800`、`MidLineDepotTest.java:154`。

### `static double againstLength(RouteLegs.Plan way, List<RouteLegs.Plan> routes, TrackSections.Snapshot snapshot)`（0.80.1）
- 作用：出入库路逆着线路方向跑本线轨道的长度（格）。只算线路各路线只按另一方向走的区段；路的两端和途中掉头的站台（`stopSteps`）、线路不走的区段（渡线、车厂轨道）、两个方向都走的区段（折返线）不算。超过常量 `LONG_AGAINST = 200` 时游戏提示。
- 主要调用者：`RoutePaths.findDepotWay`、`LongAgainstWayTest`。

### `static List<long[]> againstSpans(RouteLegs.Plan way, List<RouteLegs.Plan> routes)`（0.80.1）
- 作用：出入库路逆着线路方向跑完的站间：线路某条路线相邻两个停站之间的所有区段，这条路都按相反方向走过（且没有按路线方向走）。返回每个站间两端的停站区段 `{from, to}`（路线顺序），去重。用户 2026-11-14：逆向跨站不论长短都提示。
- 主要调用者：`RoutePaths.findDepotWay`、`LongAgainstWayTest`。

### `static List<Long> againstStops(RouteLegs.Plan way, List<RouteLegs.Plan> routes)`
- 作用：出/回库路径中逆线路方向经过的本线站台（不含路径自身两端，那里允许逆向进入再折返）。合规路径返回空；否则按经过顺序、去重列出，用于显示给玩家。
- 参数：`way` `planOut`/`planBack` 的结果（null 或未找到返回空）；`routes` 当时传入的路线计划。
- 主要调用者：`DepotBehindTerminusTest.java:142`、`DepotBehindTerminusTest.java:151`。

### `private static Set<Long> nearest(Collection<Long> stations, ToDoubleFunction<Long> cost)`
- 作用：按代价函数选出离停车线最近的车站：代价有限且 ≤ 最小值 + `TURN_BACK_PENALTY`（同一车站两个方向的站台通常都在此范围内）；更远的会让列车跑站间轨道、可能逆向。调用者：`planOut`（第 65 行）、`planBack`（第 140 行）。

## 内部类 Arrival（private static final）
进站（或离站）方式：`cost`（代价）、`fromWay`（leg 起点离开方向，1 = 顺序）、`leg`、`way`（到达方向）、`turned`（是否在该站折返）。（源码中其上方有一段孤立 javadoc 描述它。）

## 内部类 Graph（private static final）
线路图：节点为各路线的各停站；路线内相邻停站相连，路线末站连到每条路线首站（按线路在该处的折返方式）。

### 字段
- `routes`、`lines`：仅保留已找到（`isFound`）的路线及其线路名。
- `snapshot`、`legSource`。
- `offsets`（int[]）：第 i 条路线首节点下标；最后一项为节点总数。
- `consulted`（List<Leg>）：本次查询用过的所有 leg（放入结果 Plan，供缓存失效判断）。
- `turns`（Map<Long, Turn>）：(fromRoute<<32 | toRoute) → 折返方式缓存。
- `lineWays`（Map<Long,Integer>）：区段 → 线路运行该区段的方向位（2 = 顺序方向，1 = 逆序方向）。
- `stopSections`（Set<Long>）：路线停站所在区段。

### 构造函数 `Graph(List<Plan> routes, List<String> lines, Snapshot snapshot, LegSource legSource)`
过滤已找到的路线，计算 offsets、lineWays、stopSections。`snapshot`/`legSource` 可为 null（仅用于 `againstStops`）。调用者：`planOut`、`planBack`、`againstStops`。

### `private boolean isAgainst(long[] step)`
该步骤的区段属于线路且线路从不按该方向运行它时为真。调用者：`againstStops`、`weighted`、`freeLeg`、`freeCost`。

### `private double weighted(LegRouter.Leg leg, boolean toAlong)`
leg 代价 + `AGAINST_WEIGHT` × 其逆向区段代价之和；无路为无穷。调用者：`freeCost`、`turn`。

### `private LegRouter.Leg freeLeg(LegRouter.Request request, boolean toAlong, long station)`
- 作用：停车线与车站之间的 leg，若最便宜路径逆向经过本线其他停站（如经渡线上到另一轨道，0.65.26），则把这些区段+方向加入 `avoid` 重新求，最多 4 轮，尽量让列车保持在线路顺向的轨道上。
- 返回：最后一次请求得到的 leg（`toAlong` 方向若存在合规路径则为合规的）。调用者：`planOut`、`planBack`。

### `private double freeCost(LegRouter.Leg leg, boolean toAlong, long station)`
`weighted` 代价 + 逆向经过除 `station` 外的本线站台数 × `AGAINST_STOP_PENALTY`；无路为无穷。调用者：`planOut`、`planBack`。

### `private double[] newCosts()`
节点数大小、全为正无穷的数组。

### `private int route(int node)` / `private long[] stopStep(int node)` / `private long section(int node)` / `private int arrivalWay(int node)`
节点 → 所属路线下标；该停站在路线计划中的步骤 `{区段, 方向, 折返}`；其区段；其到达方向（1 = 顺序）。

### `private LegRouter.Leg leg(LegRouter.Request request)`
从 `legSource` 取 leg 并记入 `consulted`。

### `private double sectionCost(long[] step)`
区段代价（区段不存在为 0），折返步骤再加 `TURN_BACK_PENALTY`。调用者：`search`。

### `private Turn turn(int fromRoute, int toRoute)`
- 作用：从一条路线末站到另一条路线首站的折返方式（缓存）：同区段同方向代价 0；同区段反向为原地折返（`TURN_BACK_PENALTY`，在站台折返）；否则依次尝试按该路线线路、再按联络线（null）求 leg，离开方向为原方向或折返，取 `weighted` 最小者。都无路时代价无穷。
- 调用者：`search`（第 517 行）、`addSteps`（第 552 行）。

### `private int[] search(double[] best, IntPredicate target)` / `private int[] search(double[] best, IntPredicate target, IntToDoubleFunction finish)`
- 作用：从 `best` 初值出发的朴素 O(n²) Dijkstra。`finish == null` 时到达第一个满足 `target` 的节点即返回；否则对每个出队节点计算 `best + finish(node)`，取总代价最小者，当出队代价 ≥ 当前最佳总代价时停止。路线内相邻停站边代价 = 两停站之间步骤 `sectionCost` 之和；路线末站到每条路线首站边代价 = `turn().cost`。
- 返回：`previous` 数组（长度 n+1，最后一项为目标节点），未达到时 null。
- 副作用：修改 `best`。调用者：`planOut`（第 102 行）、`planBack`（第 152 行）。

### `private static void relax(double[] best, int[] previous, int from, int to, double cost)`
标准松弛。调用者：`search`。

### `private List<Integer> nodesTo(int[] previous, int target)`
回溯出从起点到 target 的节点序列。

### `private void addSteps(List<Integer> nodes, List<long[]> steps, List<Integer> stops, List<LegRouter.Request> requests)`
把节点序列展开为步骤：同路线相邻停站拷贝路线计划中的步骤；跨路线时加 `turn` 的步骤与请求，在站台折返时把当前位置记为停站。

### `private RouteLegs.Plan plan(double cost, List<long[]> steps, List<Integer> stops, List<LegRouter.Request> requests)`
组装 `RouteLegs.Plan`（停站数、代价、步骤、停站下标、请求、0、`consulted`）。

## 内部类 Turn（private static final）
路线末站到另一路线首站的折返：`cost`、`steps`（末站之后的步骤）、`request`（所用 leg 请求，可 null）、`turnedAtPlatform`（是否在该站台折返）。
