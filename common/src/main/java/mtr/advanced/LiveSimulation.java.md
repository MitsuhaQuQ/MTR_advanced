# LiveSimulation.java

组件 `transit-sim` 的模拟器在游戏中实时运行它能运行的车厂的列车（G1，解耦文档 5.2）：使用速度曲线、非连续运动的车厂，无论按时刻表、按运用还是按频率发车。其它车厂的列车作为"外部列车"告诉它（`SimInputs`）。运行时，它的列车在游戏的运行预留（`RunReservations`）中持有和申请区间，与游戏自己的列车每 tick 一起处理，所以两类列车在单线上像游戏中任意两列车一样互相等待。

手动驾驶的列车（允许手动的停车线中的列车，在停车线内即为手动）被"接管"：由游戏自己移动，直到在线上交还给模拟器。

运行时模拟器拥有这些列车：每 tick 推进到现在，这些停车线的游戏列车只"显示"模拟器的列车（A06），并负责需要世界的事情（车厢、乘客、车门、为其它游戏列车和信号占用的轨道）。网络或计划变化时，从游戏列车（此时就在模拟器放置的位置）重新开始。它总是运行列车（R4）；与游戏规则的对比留给测试。由 `RailwayData` 持有（`RailwayData.java:594` 每 tick、`:252` 按时刻表放车、`:841/:852/:859/:1694` 调度命令）。

## final class LiveSimulation

### 常量
- `COMPARE_MILLIS = 60000`：统计报告间隔（原用于对比）。
- `CHANGE_CHECK_MILLIS = 300000`：运行中即使没人说有变化，也每 5 分钟检查网络或计划是否变化。
- `RESTART_MILLIS = 30000`：运行中被停止后多久再启动。
- `CHANGE_SETTLE_MILLIS = 2000`：游戏数据变化后等多久再查看（一串编辑只看一次）。
- `RETRY_MILLIS = 1000`：没有可运行的停车线时多久再找一次（车厂时刻表在世界加载后片刻才知道）。
- `FAILED_RETRY_MILLIS = 5 分钟`：失败后关闭多久。
- `COMPARING`：单线程守护执行器 "MTR simulator comparing"（低优先级），用于计算数据摘要。
- `DEBUG_PLACE = System.getProperty("mtr.debugPlace", "")`：调试用车厂名。
- `LEFT_OUT`（静态同步集合）：已报告过"不能模拟"的停车线（每条只报一次）。

### 字段
- `inputs`（`SimInputs`）、`random`、`simulator`（组件 `EventSimulator<SimDataset.DataTrack>`）、`driving`（运行而非对比）、`sidingIds`（运行的停车线）。
- `startedDataset` / `startedDigest`：启动时的游戏数据拷贝及其摘要（在独立线程计算，优化第 5 轮第 4 步：写出它曾在启动和每次检查时占服务器线程一秒多）；`checkingDataset` / `checkingDigest`：进行中的检查。
- `startedMillis`、`checkedMillis`、`checkedVersion`、`versionChangedMillis`、`lastMillis`。
- `placement`：下一 tick 要按时刻表放置的列车（见 `placeByTimetable`）。
- `started`、`restoredTrains`、`eventCursor`、`reportedMillis`、`departures`、`arrivals`、`recordedTrains`。
- `takenOver`：被接管（手动驾驶）的列车。
- `excludedSidings`：交给游戏的停车线（其部分列车无法在模拟器中继续，例如路径变了；运行它们会把这些列车拿走），网络或计划变化时重试；`excludedRetryMillis`；`excludedKey`：交出时这些停车线的状态（`Siding#getCarryOnKey`），变化时或每几分钟重试（0.66.2）。
- `failedMillis`：上次失败时间或 0。
- `debugPlaced`、`debugPrintedMillis`。

### 调度命令（返回 `false` 表示模拟器不运行该列车，由游戏自己处理）
均要求：模拟器存在、正在运行、列车未被接管且在模拟器中；以 `max(lastMillis, now)` 时间和新序号提交组件输入，在下一 tick 生效。
- `submitSkip(long trainId, Set<Long> platformIds)`：通过不停这些站台（D3，`HostInput.Skip`）。调用者：`RailwayData.java:841`。
- `submitTurnBack(long trainId, int mode, Set<Long> platformIds, int after)`：提前折返（D4，`HostInput.TurnBack`）。调用者：`RailwayData.java:852`。
- `submitHandOver(long trainId, boolean handOver)`：是否把运用交给备用车（D5，`HostInput.HandOver`）。调用者：`RailwayData.java:859`。
- `submitDispatch(long trainId, int command, long value, Set<Long> platformIds)`：调度员命令（D6，`HostInput.Dispatch`）。调用者：`RailwayData.java:1694`。

### `public void tick(RailwayData railwayData, List<Map<UUID, Long>> trainPositions)`
- 作用：在游戏停车线移动其列车之前把模拟器推进到现在。失败后 5 分钟内不运行。`run(..., true)`；任何异常都不能中断游戏 tick：打印 "failed and is off for five minutes"，记录失败时间并 `stop`（游戏从模拟器放置的位置继续移动列车）。
- 参数：`trainPositions` 游戏的轨道占用（上一 tick 与本 tick）。
- 调用者：`RailwayData.java:594`。

### `private void run(RailwayData railwayData, List<Map<UUID, Long>> trainPositions, boolean drive)`
- 作用：
  1. 尚未启动且停车线归属未知或有停车线尚未安定（`hasSettled`）时等待（游戏在下一 tick 才按计划放车，模拟器从那里继续）。
  2. 有被交出的停车线时：满 5 分钟，或满 30 秒且其状态键变化，则清空并重新 `start`（只看管理停车线的摘要发现不了被交出停车线的路径已可用）。
  3. 运行中：停顿超过 `EventSimulator.RECOVERY_MILLIS`（契约文档 6.3）、有待放置、或 `hasChanged` 时重新 `start`（停顿时从 `lastMillis` 记录）；`debugPlace`。未运行且（首次或到了重试间隔）时 `start`。
  4. 时间倒退时不动（契约文档 A02）。
  5. 遍历运行中停车线的列车：手动的提交 `TakeOver`（首次），恢复自动的提交 `Release`（带进度与速度）；`inputs.update(now, 上一 tick 占用, 非本模拟器列车)` 提交外部列车。
  6. `advanceTo(now)`；运行时 `renewReservations()`（与游戏共享预留，在游戏 tick 末与游戏列车的申请一起处理）。
  7. 读取事件统计发车/到站并遗忘；运行时 `show`，每分钟打印统计（发车、到站、在线列车数）。

### `private void start(RailwayData railwayData, long now, boolean drive, long recordMillis)`
- 参数：`recordMillis` 游戏列车状态的时刻（超过一分钟前则按计划放到现在应在的位置）。
- 作用：求可运行停车线（变化时打印 "runs N of M sidings"）；`stop` 旧模拟器；重置状态与 `inputs`；无停车线则返回。否则取两份数据集（一份给模拟器，一份作对比基准并在后台算摘要）；把车厂调度器状态与停车线列车记入 `RunRecord`；若有待放置则 `Placement.takeInto` 替换相应列车；`EventSimulator.resume(...)`（构建运行器、设列车 id 生成器、运行时共享预留）并 `setLive()`；打印启动耗时。若记录中有列车没能在模拟器中继续，把其停车线交给游戏并递归重新 `start`。运行时 `show`。按时刻表放置完成时告诉玩家（`gui.mtr.place_trains_done`）并打印。
- 副作用：日志、修改游戏停车线与车厂的显示状态。

### `private static void tell(ServerPlayer player, Component text)`
- 向请求的玩家发消息（调试放置时没有玩家）。

### `private void debugPlace(RailwayData railwayData, long now)`
- 作用（2026-11-09，排查按时刻表放置的列车看不见）：`-Dmtr.debugPlace=车厂名` 时，启动 10 秒后对该车厂 `placeByTimetable` 一次，并每 5 秒打印运行中停车线的游戏列车（`describeShown`）。

### `private static String getDepotName(RailwayData railwayData, long depotId)`
- 返回：车厂名第一部分（`||` 之前，`|` 换成空格），不存在为 "?"。

### `public void placeByTimetable(RailwayData railwayData, ServerPlayer player, long depotId)`
- 作用：请求把一个车厂的列车放到其时刻表此刻应在的位置；与它共享线路的车厂（递归）一并放置（同一条线、同一时刻表的班次，分开算会重叠）。模拟器未运行或该车厂停车线被交出时先清空交出并重新启动（失败则记失败并停止）。该车厂不由模拟器运行时提示 `gui.mtr.place_trains_not_run`；已有放置进行中时提示 `place_trains_busy`。否则构造只含这些车厂与停车线、无列车的数据集字节，提示 `place_trains_working`，并记下 `Placement`（全部按运用运行时直接放到运用位置）。实际计算在下一 tick 的 `start` 中。
- 调用者：`RailwayData.java:252`。

## private static final class Placement
- 字段：`player`、`depotIds`、`sidingDepots`（停车线 → 车厂）、`dataset`（字节）、`byDuties`。
- `takeInto(RailwayData railwayData, RunRecord record, long now, LongSupplier trainIds)`：
  - 按运用：从数据集在 `now` 启动运行器，`placeDutyTrains()` 为此刻在外的每个运用放一列车（解耦文档 D 步），取记录；
  - 否则：从运营日开始运行一个 `EventSimulator` 推进到 `now`，取记录。
  - 用结果替换记录中这些停车线的列车（玩家正在手动驾驶的保留），以及已发出的行程、运用运行日、最近发车时间、发车下标（避免已在外的再发一次）。打印耗时与列车数。
  - 返回：每个车厂的列车数；失败时提示 `place_trains_failed` 并返回 `null`。

### `private void stop(RailwayData railwayData)`
- 作用：运行中时让由模拟器运行的停车线 `stopSimulated()`、车厂 `runBySimulator = false`（游戏从模拟器放置的位置继续）；清除模拟器。

### `private void show(RailwayData railwayData)`
- 作用：让运行中停车线的游戏列车显示模拟器的列车，并把模拟器车厂的调度记录复制到游戏车厂以便保存。非有限速度/进度或停站下标越界的列车抛 `IllegalStateException`（关闭模拟器，而不是保存错误状态）。先按 id 索引所有游戏列车（列车被移到同池另一停车线时仍是同一列车，P2-C），再对每条停车线 `showSimulatedTrains`、`showSimulatedShed`，并置车厂 `runBySimulator`、`copyDispatcher`。

### `private boolean hasChanged(RailwayData railwayData, long now)`
- 返回：可运行停车线、其路径或计划自启动以来是否变化。
  - 有停车线重新找到了路径（`hasTrainsRelocated`）立即为真；
  - 进行中的检查摘要算好后比较，不同则打印变化内容（`describeChange`）、清空交出列表并返回真；
  - 游戏数据/信号闭塞版本变化后等 2 秒，或每 5 分钟：停车线集合不同为真；否则在后台计算新摘要，下次再看。

### 摘要与版本
- `digestOf(SimDataset)`：在比较线程上计算 `dataset.toBytes()` 的 SHA-256 十六进制。
- `getDigest(Future<String>)`：取结果，失败为空串（视为变化）。
- `getExcludedKey(RailwayData)`：被交出停车线的 `getCarryOnKey()` 拼接。
- `getRunSidingIds(RailwayData)`：可运行停车线减去交出的。
- `getVersion(RailwayData)`：数据缓存版本、信号闭塞版本、`routePaths.getTimingVersion()` 组合。

### `private static String describeChange(SimDataset before, SimDataset after)`
- 返回：用于日志的变化部分：`routes`、`depots`、某车厂的发车时刻/停车线顺序/线路（按频率发车时线路有顺序，其它为集合）/循环/运用、`sidings`、某停车线的服务路径数/路径长度/折返/设置/其它、`signal blocks`；无法定位时 "something"。
- 辅助：`single(SidingData)`（只含一条停车线的数据集）、`part(SimDataset, int which)`（只含线路(0)或停车线(2)的字节）、`sortedCopy(long[])`。

### `private SimDataset getDataset(RailwayData railwayData)`
- 返回：`SimExport.toDataset` 去掉列车、只保留运行中的停车线及其车厂。

### `private static Set<Long> getSidingIds(RailwayData railwayData)`
- 返回：使用速度曲线（`depot.speedCurves`）、非连续运动车厂中 `canBeSimulated()` 的停车线；不能的每条打印一次原因（`whyNotSimulated`，2026-11-09）。

### `private void forEachTrain(RailwayData railwayData, Consumer<TrainServer> consumer)`
- 对运行中停车线（排序）的每列游戏列车调用。
