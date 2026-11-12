# RouteLegs.java

由 leg 组成的线路路径（优化阶段文档第 2 轮）：每个停站都可以两个方向运行，而某站之后的 leg 只依赖列车在该站的运行方向，所以最佳路径就是逐站选择每站方向的最优组合（动态规划）。这与一次搜索整条线路（`RailRouter`）代价相同；代价相等时保留沿区段顺序方向的、再保留更早的，结果总是一样。

在整体中的位置：游戏侧 `RoutePaths`（`common/.../mtr/data/RoutePaths.java:604`、`:633`、`:794`、`:1098`）调用 `plan`/`planBetween`/`railWaypoints`/`getStopSections`；`LineDepotWays` 使用 `Plan`、`LegSource`、`planBetween`、`turnStep`、`railWaypoints`。leg 由 `LegRouter` 计算，经 `LegSource`（游戏侧接 `LegCache`）获取。

## 类 RouteLegs（final，不可实例化）

### 内部接口 LegSource
leg 来源（新求得或取自缓存）：`LegRouter.Leg get(LegRouter.Request request)`。实现：游戏侧 `GraphRecords` 的 `Legs`、`LineDepotWays.throughDepotLeg` 返回的包装。

### `static Plan planBetween(long fromSection, Boolean fromAlong, long toSection, Boolean toAlong, LegSource legSource)`
- 作用：出库到站台或从站台回库的路径（优化第 4 轮第 3 步）：按给定方向离开与到达（站台处与线路方向一致），起点不折返（`turnAtStart = false`）；未给定方向（停车线一端）则两种都试。不限于任何线路的轨道（`line = null`）。取所有组合中代价最低的 leg。
- 参数：`fromSection`/`toSection` 起终区段；`fromAlong`/`toAlong` 离开第一个区段/到达最后一个区段的方向（true = 顺序），null = 任意。
- 返回：找到时 `Plan(2 站, 代价, 步骤, {0, 最后}, [请求], 0, consulted)`；找不到时 `reachedStops = 1`、代价无穷、步骤空。
- 副作用：经 `legSource` 可能计算 leg。
- 主要调用者：`RoutePaths.java:796`、`RoutePaths.java:850`、`LineDepotWays.java:105`。
- 注：源码在此方法上方另有一段孤立 javadoc（`stopSections`/`waypoints`/`line` 参数），描述的是 `plan`。

### `static long[] turnStep(long section, int way)`（包内可见）
- 作用：表示"在该区段上立即折返、以 way 方向运行"的步骤 `{section, way, 1}`（与以折返开始的 leg 相同；同一区段到自身的 leg 不会产生折返）。
- 调用者：`LineDepotWays.java:118`、`LineDepotWays.java:191`、`LineDepotWays.java:441`。

### `static long[] getStopSections(Plan plan)`
按顺序返回计划中每个停站所在区段。调用者：`RoutePaths.java:804`、`RoutePaths.java:814`。

### `static long[] railWaypoints(long[] nodes, TrackSections.Snapshot snapshot, long firstSection, long lastSection)`
- 作用：把编辑器中点选轨道得到的节点（2026-11-09）转换为搜索用的关键节点：每对相邻节点若构成某区段中的一条轨道，就表示"以该方向运行整个区段"，转为离开该区段的节点（按两节点在区段中的先后决定是区段终点还是起点）；属于首/末停站区段的轨道略去（路径本就从那里开始或结束）；不与下一节点构成轨道的节点（旧式逐节点选择）原样保留；连续重复的关键节点只保留一个。
- 参数：`nodes` 选择的节点序列；`snapshot` 区段快照；`firstSection`（如出库所离开的停车线）、`lastSection` 首末停站区段。
- 返回：关键节点数组。副作用：无。
- 调用者：`RoutePaths.java:793`、`LineDepotWays.java:47`、`RouteLegsTest.java:148`。

### `static Plan plan(List<Long> stopSections, List<long[]> waypoints, String line, LegSource legSource)`
- 作用：逐站动态规划求线路路径。`best[i][way]` 为到达第 i 站且以 way 方向停站的最小代价；每站先试顺向（使平局保留顺向），对每个可达方向取 leg（请求带该站之后的关键节点和线路），更新下一站两方向。记录首个出现的 `badWaypoint`。某站两方向都不可达则返回失败计划（`reachedStops` = 已到达站数）。最后从末站代价较低（相等取顺向）的方向回溯各站方向，拼接步骤与停站下标。
- 参数：`stopSections` 各停站区段；`waypoints` 每站之后、下一站之前的关键节点（可短于站数或含 null）；`line` 线路名，null 为联络线；`legSource` leg 来源。
- 返回：`Plan`。少于 2 站时返回 `reachedStops = 0`、代价 0 的空计划。
- 主要调用者：`RoutePaths.java:604`、`RoutePaths.java:633`、`RoutePaths.java:1098`。

## 内部类 Plan（public static final）
一条线路的路径。
- `reachedStops`（int）：在无路之前按顺序到达的停站数；找到时为全部。
- `cost`（double）：总代价。
- `steps`（不可修改 List<long[]>）：从首站起运行的每个区段 `{区段, 1 = 顺序方向, 1 = 折返进入}`。
- `stopSteps`（int[]）：每个停站对应的步骤下标。
- `legs`（不可修改 List<Request>）：组成路径的 leg 请求，按顺序。
- `badWaypoint`（long）：列在非区段交汇处的关键节点，否则 0。
- `consulted`（不可修改 List<Leg>）：查看过的所有 leg（无论是否选中）；它们都仍有效时计划仍成立（配合 `LegCache.holds`）。
- 构造函数（包内可见）：按字段赋值，列表包成不可修改。调用者：`plan`、`planBetween`、`LineDepotWays.java:569`。
- `boolean isFound()`：`reachedStops == stopSteps.length` 且至少 2 站。调用者：`RoutePaths.java:167`、`LineDepotWays.java:297`。
