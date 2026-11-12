# RailwayDataPathGenerationModule.java

`RailwayData` 的子模块，负责启动各车厂的找路（path generation）：每个车厂在自己的任务上运行，同时运行的数量受机器空闲核心数限制（共享线程池 `transitsim.sim.WorkPool`）。同一 tick 启动的车厂共用一份在服务器线程上制作的轨道与数据拷贝，以及一张要搜索的网络（`Snapshot`）。由 `RailwayData.java:338` 构造；玩家请求经 `PacketTrainDataGuiServer`（`:486`、`:666`）调用 `generatePath`；`RailwayData` 用 `queue`（`:797`）排队、每 tick `startQueued`（`:802`）。实际找路在 `Depot.generateMainRoute`。

## class RailwayDataPathGenerationModule extends RailwayDataModuleBase

### 字段与常量
- `lastRequestMillis`：车厂 id → 上次请求时间，用于限制频率。
- `MIN_REQUEST_INTERVAL_MILLIS = 1000`：同一车厂两次请求的最小间隔。
- `MAX_THREADS = WorkPool.THREADS`（public）：同时运行的找路数（核心数减一，最多 8，或 `-Dmtr.workThreads`）。
- `generatingPathThreads`：车厂 id → 正在运行的 `Future`。
- `queued`（`LinkedHashMap`）：排队等待找路的车厂及其入队 tick（优化第 5 轮第 3 步）。
- `startingQueuedSince`：正在启动排队车厂时其入队 tick，此后制作的拷贝都可服务它；否则 −1。
- `snapshot`：最近一次的拷贝。

### `public RailwayDataPathGenerationModule(RailwayData railwayData, Level world, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 主要调用者：`RailwayData.java:338`。

### `public boolean canGenerate()`
- 作用：清除已完成的任务后，是否还有空闲名额。
- 主要调用者：本类 `startQueued`。

### `public void queue(long depotId)`
- 作用：排队等找路；已在队中则保持原位置与入队 tick。
- 主要调用者：`RailwayData.java:797`。

### `public void startQueued(MinecraftServer minecraftServer)`
- 作用：按入队顺序，在有空闲名额时出队并 `generatePath`；启动期间设置 `startingQueuedSince`。每 tick 调用。
- 主要调用者：`RailwayData.java:802`。

### `public void generatePath(MinecraftServer minecraftServer, long depotId)`
- 作用：清除已完成任务；若距上次请求不足 1 秒，或该车厂不在运行且名额已满：发 `generatePathS2C(world, depotId, 0)`（通知客户端未启动，0 的含义推测为失败/未开始，未确定）并返回。否则记录请求时间；车厂存在时：若已有任务则 `cancel(true)` 中断（搜索以 `CancellationException` 结束）并打印 "Restarting path generation"，否则打印 "Starting ..."；调用 `depot.generateMainRoute(..., getSnapshot(), sidings, task -> 登记)`，再 `railwayData.resetTrainDelays(depot)`。车厂不存在时发 0 并打印 "Failed to generate path, depot is null"。
- 副作用：发包、日志、启动后台任务、重置列车晚点。
- 主要调用者：`PacketTrainDataGuiServer.java:486`、`PacketTrainDataGuiServer.java:666`、本类 `startQueued`。

### `private Snapshot getSnapshot()`
- 作用：返回本 tick 找路用的拷贝。已有拷贝在本 tick 制作（或启动排队车厂时不早于其入队 tick），且数据缓存版本、轨道节点数、停留闭塞（`SecurityConfig.stablingColor` 的信号区，"运用补完"第 4 步，视为终点站的绕行路径）都未变时复用；否则新做：`dataCache.copyIdMaps()`、设置 `stablingRails`、`DataCache.copyRails(rails)`、`RoutePaths.getVehicles(railwayData)`、当前 `pathBoundaries`。

## public static final class Snapshot
找路开始时的轨道与数据，线程只读、没有任何写入。要搜索的网络在第一次需要时于服务器线程上生成，因为它限制搜索范围所用的寻路边界是实时的那份。

### 字段
- `dataCache`（public）：id 映射拷贝。
- `rails`（public）：轨道拷贝。
- `vehicles`（public）：线路 id → 运行车辆参数集合（`PathTiming.Vehicle`）。
- 私有：`tick`、`version`、`liveRailCount`、`boundaries`（引用实时的 `PathBoundaries`，非拷贝）、`graph`（惰性）。

### `private Snapshot(...)`
- 仅赋值。

### `public RailGraph<Rail> getGraph()`
- 作用：首次调用时 `DeterministicPathFinder.compile(rails, boundaries)`；只能在服务器线程调用。
- 主要调用者：`Depot.java:1140`。
