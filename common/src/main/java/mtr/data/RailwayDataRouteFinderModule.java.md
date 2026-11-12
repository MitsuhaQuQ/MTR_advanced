# RailwayDataRouteFinderModule.java

`RailwayData` 的子模块：原 MTR 的乘客路线查找（网站地图的"路线规划"）与连接使用密度统计。有请求时为请求在服务器线程上分时搜索站台之间的乘车方案；没有请求时在随机站台对之间搜索，只为统计每个连接被使用的次数（网站地图的密度显示）。本分支的改动：空闲时每 10 tick 才运行一次（原来每 tick 都运行，占用服务器线程很大一部分）。由 `RailwayData.java:342` 构造，每 tick 调 `tick()`；`RouteFinderServletHandler.java:47` 调 `findRoute`；`DataServletHandler.java:116` 调 `getConnectionDensity`；`DataCache.java:176-180` 构建 `ConnectionDetails`。

## class RailwayDataRouteFinderModule extends RailwayDataModuleBase

### 字段与常量
- `IDLE_TICK_INTERVAL = 10`、`idleTicks`：空闲时的运行间隔与计数。
- `connectionDensityOld` / `connectionDensity`：两个"起点 → 终点 → 次数"表，满 `getMaxCount()` 次搜索后轮换。
- `startPos`、`endPos`：当前搜索的起点、终点。
- `currentRouteFinderRequest`：当前请求（随机统计时为 `null`）。
- `totalTime`：目前找到的最好方案总时长（tick）；`count`：本轮统计的搜索次数；`startMillis`：搜索开始时间。
- `tickStage`：状态机阶段（`GET_POS`、`START_FIND_ROUTE`、`FIND_ROUTE`、`END_FIND_ROUTE`）。
- `globalBlacklist` / `localBlacklist`：位置 → 已到达该位置的最早时间（全局跨尝试、局部本次尝试），用于剪枝。
- `tempData`：本次尝试的路径；`data`：最好路径；`platformPositions`：候选位置（所有站台连接起点 + 终点）。
- `routeFinderQueue`：请求队列，最多 `MAX_REQUESTS = 10`。
- `WALKING_SPEED_TICKS_PER_METER = 5`：步行速度。
- `DEBUG_DISABLE_DENSITY = false`：调试开关。

### `public RailwayDataRouteFinderModule(RailwayData railwayData, Level world, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 调用者：`RailwayData.java:342`。

### `public void tick()`
- 作用：站台连接少于 4 个时不做事。无请求时每 10 tick 运行一次。在时间预算内（无请求 2 ms，有请求为其 `maxTickTime` ms）推进状态机：
  - `GET_POS`：无请求时从 `min(200, n²/10000)+1` 对随机站台中取曼哈顿距离最短的一对；有请求则取队首。清全局黑名单，`totalTime = MAX`。
  - `START_FIND_ROUTE`：清本次路径与局部黑名单，重置候选位置。
  - `FIND_ROUTE`：反复 `findRoutePart()`，到达终点或无解时进入下一阶段。
  - `END_FIND_ROUTE`：本次尝试为空（不能再改进）时：把最好路径的每段计入密度表，达到上限时轮换；有请求则把最好路径合并同线路的连续段（`RouteFinderData.append`）后回调 `(结果, 耗时毫秒)`；回到 `GET_POS`。否则若本次总时长更短则记为最好，重新开始一次尝试。
- 副作用：回调请求者（网站线程等待的结果）。

### `public boolean findRoute(BlockPos posStart, BlockPos posEnd, int maxTickTime, BiConsumer<List<RouteFinderData>, Integer> callback)`
- 作用：加入请求队列（未满时），并把状态机重置到 `GET_POS`（中断当前的随机统计）。
- 参数：`maxTickTime` 每 tick 搜索毫秒数，限制在 2–20。
- 返回：是否接受。

### `public int getConnectionDensity(BlockPos posStart, BlockPos posEnd)`
- 返回：该连接的使用密度：优先用上一轮的完整计数；没有时按本轮计数 × 上限 / 已搜索次数外推。

### `private boolean findRoutePart()`
- 作用：贪心一步：从当前位置出发，对每个候选位置，时长默认为步行时间；若有站台连接且时间可行，查起点站台的到站表，对停站列车计算"等待 + 乘车"时长（等待 > −100 tick），取最短；若所有列车都已过去，则按该线路所属车厂下一次发车（`depot.getMillisUntilDeploy`）估算。时间可行时记入全局黑名单，并按"向终点靠近的曼哈顿距离 / 时长"选最优。找不到则回退一步（本次路径为空时返回 `true` 表示结束）；找到则加入路径与局部黑名单。
- 返回：到达终点或无法继续时 `true`。

### 私有辅助
- `verifyTime(long posLong, int time)`：时间小于当前最好总时长，且不晚于两个黑名单中该位置的记录（局部严格小于，全局小于等于）。
- `getMaxCount()`：站台连接数 × 100。
- `compareBlacklist(...)`：见上。

## public static class ConnectionDetails
- 两站台之间的直达连接：`platformStart`（起点站台）、`durationInfo`（线路 id → 乘车时长 tick）、`shortestDuration`。
- `ConnectionDetails(Platform platformStart)`；`addDurationInfo(long routeId, int duration)`：记录并更新最短。调用者：`DataCache.java:176-180`。

## public static class RouteFinderData
- 路径的一段：`pos`（到达位置）、`duration`（tick）、`routeId`（0 为步行）、`waitingTime`、`stationIds`（经过的车站）。
- 私有构造；`private static void append(List, RouteFinderData, RailwayData)`：与上一段同线路时合并（时长相加、车站累加），否则追加；车站由位置查得（无则 0）。

## private static class RouteFinderRequest
- `startPos`、`endPos`、`maxTickTime`（限制在 2…`MAX_TICK_TIME = 20`）、`callback`。

## private enum TickStage
`GET_POS, START_FIND_ROUTE, FIND_ROUTE, END_FIND_ROUTE`。
