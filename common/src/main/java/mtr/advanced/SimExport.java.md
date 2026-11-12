# SimExport.java

把组件模拟器需要的世界数据导出成组件的 `SimDataset`（S4e），在世界保存时写进数据库（`RailwayDataFileSaveModule.java:432`），这样日模拟工具 `SimulateDay` 不需要游戏就能运行；同时带上列车当前状态（G0），可以从当前状态往下模拟。`LiveSimulation` 也用 `toDataset` 构建实时模拟器的输入，`SandboxRuns` 用它做沙盒运行。须在服务器线程调用。

## final class SimExport

### `private SimExport()`
工具类，禁止实例化。

### `public static byte[] build(RailwayData railwayData)`
- 作用：`toDataset(railwayData).toBytes()`，组件二进制格式。
- 主要调用者：`RailwayDataFileSaveModule.java:432`、`SandboxRuns.java:127`。

### `public static SimDataset toDataset(RailwayData railwayData)`
- 作用：组装数据集，步骤：
  1. `RunningSides.get(railwayData)`：先算好每条双线按其线路行车方向的走向（用户 2026-11-12），供下面路径使用。
  2. 线路（按 id 排序）：`SimDataset.RouteData(route)`，`lineKey = LineDuties.getLineKey(route)`；若 `RoutePaths.timesByPath(route)`，取已找到的线路路径的时刻表最快时分 `fastestTimes`（L1，使组件与游戏用相同时分编排）。
  3. 车厂（按 id 排序）：`DepotData(id, routeIds, cycleRoutes, runDuties 字节或空)`；`departures` = 支持时刻表的交通方式为空，否则 `getDeployTimes`（按频率发车）；`dutiesOnly` = 支持时刻表（R2：只按运用发车）；`sidingOrder` = 该车厂停车线按自然排序（名字）的 id；`releaseInterval`。
  4. 停车线（按 id 排序）：有所属车厂者 `siding.toSimulation(depotId, 停车线线路, path -> toTracks(path, rails), 是否有运用)`；非空时加入，并把车厂的调度器状态（每个车厂一次）与停车线列车记录进 `RunRecord trains`。
  5. 站台停车位置（非 `EXIT_END` 的）写入 `stopPositions` 并 `applyStopPositions()`（第 4 轮）。
  6. `earlyThresholdMillis` 取网络设置（第 4 轮第 5 步：早点多少时可以先全速再慢行）。
  7. `trains`、信号闭塞轨道 `signalBlocks`、按 `SecurityConfig.interlockColor` 的联锁闭塞 `interlockBlocks`。
- 返回：数据集。
- 副作用：调用 `depot.recordDispatcher`、`siding.recordTrains`（只写入 `trains`，不改游戏状态，按命名判断）。
- 主要调用者：`LiveSimulation.java:809`、本类 `build`。

### `public static List<SimDataset.DataTrack> toTracks(List<PathData> path, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：把一条路径转为组件轨道段列表。每段：起点、终点、长度、是否站台、是否停车线、`savedRailBaseId`、停站时间、停站下标、最高速、能否加速、是否有信号、反向是否有可通行轨道（`backwards != NONE`）、行车方向约定（`RunningSides.getConvention`）。若路径是 `IndexedPath`，用其 `map`（带停站下标偏移与签名）缓存转换结果。
- 主要调用者：`TrainServer.java:1471`、`SandboxRuns.java:242`；测试 `GameChainTest.java:132`。

### `private static long[] toArray(List<Long> values)`
- 作用：`List<Long>` → `long[]`。
