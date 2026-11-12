# RailwayDataFileSaveModule.java

`RailwayData` 的子模块：在一个维度的数据库（`mtr.db`，经 `RailwayDataFileStore.open` 得到 `RailwayDataStore`，通常是 `RailwayDataSqliteStore`）中保存和加载铁路数据。服务器运行时，变化的对象分批写入（每 tick 一点）；玩家改动的轨道与对象在 tick 结束时立即单独保存。加载没有完成时什么都不保存，失败的加载不会覆盖数据。本分支的主要改动：从每对象一个文件改为数据库；一次保存整体在一个事务中写入（中途被杀则保留上一次完整保存）；玩家编辑即时保存（含编辑版本）；路径片段（`PathPieces`）与运用分层表的写入；日模拟数据（S4e）定期写入；读不出的条目不删除任何数据；轨道图（`GraphRecords`）独立事务。由 `RailwayData` 构造（`RailwayData.java:336`）并调用 `load`（`:455`）、`fullSave`（`:488`）、`autoSave`（`:490`）、`saveNow`/`autoSaveTick`（`:883-884`）、`markObjectChanged`（`:261`）、`markRailsChanged`（`:913` 等）。

## class RailwayDataFileSaveModule extends RailwayDataModuleBase

### 字段
- `canAutoSave`、`dataLoaded`、`loadStarted`：加载状态；加载开始但未完成时不保存。
- `skippedEntries`：读不出的条目数；大于 0 时运行期间不删除任何条目（读不出的留在数据库中，依赖它的东西——如读不出的轨道上的停车线——也不删）。
- `filesWritten`、`filesDeleted`、`autoSaveStartMillis`、`snapshotMillis`（上次保存时一次性取除轨道外全部数据的耗时）。
- `lastSaveFailed`：上次保存是否失败（失败时数据库保持上一次保存）。
- `lastSimulationHash`、`lastSimulationMillis`、`stopping`、`SIMULATION_DATA_INTERVAL_MILLIS = 30 分钟`：日模拟数据只在停服时和至多每 30 分钟计算一次（大网络上每次保存计算要占服务器线程数秒，优化第 5 轮第 4 步），内容不变不重写。
- `saveInProgress`：一次保存正在进行（每 tick 写一点）。
- `signalBlocks`。
- 脏列表：`dirtyStationIds`、`dirtyPlatformIds`、`dirtySidingIds`、`dirtyRouteIds`、`dirtyDepotIds`、`dirtyLiftIds`、`dirtyRailPositions`、`dirtySignalBlocks`、`dirtyKeyNodes`、`dirtyBoundaryNodes`、`dirtyBoundarySegments`（autoSaveTick 逐项处理；目前 `autoSave` 只把轨道放进去，其它全部在 `autoSave` 时一次性 `stage`）。
- `existingFiles`：数据库中已有条目键 `type:id` → 内容哈希。
- `checkFilesToDelete`：本次保存后若未再写到就删除的键。
- `pendingWrites`：本次保存计算出的待写条目，保存结束时与删除一起在一个事务中写入。
- `railsToSaveNow`、`objectsToSaveNow`：本 tick 玩家改动、在 tick 结束时立即保存的轨道与对象（`type:id`）。
- `dataStore`、`pathPieces`（存储支持时的 `PathPieces.Store`，否则 `null`）、`dutyFingerprints`（车厂 → 上次写入运用分层时的指纹，不支持时 `null`）、`pathPieceWriter`。
- `openMillis`、`loadTimes`、`loadMarkMillis`：打开存储（含启动备份）与各加载阶段耗时，加载完成时打印一行。

### 常量
- `KEY_SEPARATOR = ':'`。
- 类型（`entries.type` 值）：`stations`、`platforms`、`sidings`、`routes`、`depots`、`lifts`、`rails`、`signal-blocks`、`key-nodes`、`boundary-nodes`、`boundary-segments`、`contingency-systems`、`network-settings`；`TYPES` 为全部（用于迁移旧分文件数据）。

### `public RailwayDataFileSaveModule(RailwayData railwayData, Level world, Map<BlockPos, Map<BlockPos, Rail>> rails, Path savePath, SignalBlocks signalBlocks)`
- 作用：建目录，打开存储（计时），按存储能力创建片段存储、运用指纹表和片段写入器。
- 调用者：`RailwayData.java:336`。

### `public void load()`
- 作用：依次读：车站、站台；（支持片段时）读全部片段建 `Reader` 并设为本线程读取器；停车线（路径从片段拼出）；停止读取并把记住的路径交给写入器（`seed`）；线路、车厂、升降机、轨道（`RailEntry`）、信号闭塞、关键节点、边界节点、边界段、应急系统、网络设置（`copyFrom`）；运用指纹（失败则清空，下次保存重写）；`graphRecords.load`；编辑版本（`editRevisions.restore`）。打印总耗时与各阶段耗时；有读不出的条目时打印"保持原样、不删除"。最后允许自动保存。
- 异常：读片段失败或某类型整体读失败抛 `IllegalStateException`。
- 调用者：`RailwayData.java:455`。

### `private void markLoaded(String part)`
- 记一个加载阶段耗时。

### `public void fullSave()`
- 作用（停服时）：标记 `stopping`，清空所有脏列表与待写，`autoSave()` 后循环 `autoSaveTick()` 直到完成；失败则再试一次（数据库可能短暂忙）；仍失败打印"无法保存……上次保存后的改动丢失，除了已即时保存的轨道和编辑"。随后禁止自动保存并关闭存储。
- 调用者：`RailwayData.java:488`。

### `public void autoSave()`
- 作用：加载开始但未完成时打印"未加载成功，不保存"并返回；从未加载（新世界）则视为已加载。可保存且没有进行中的保存时开始一次保存：没有读不出的条目时把所有已有键放入待删；一次性 `stage` 除轨道以外的全部数据（车站、站台、停车线〔前后 `pathPieceWriter.endRound()` 释放已不存在的停车线路径〕、线路、车厂、升降机、信号闭塞、关键节点、边界节点/段、应急系统〔每次都存，D2〕、网络设置拷贝〔第 4 轮〕），记录耗时；所有轨道节点放入脏列表（每 tick 计算一点）；`dataStore.backup()`（到期时后台备份）。
- 调用者：`RailwayData.java:490`。

### `public boolean autoSaveTick()`
- 作用：在每 tick 约 2 ms 的预算内依次处理各脏列表（`writeDirtyDataToFile`）。全部处理完且有进行中的保存时：`finishSave()`；清除过期的列车晚点记录；有写/删时打印保存耗时、改动数、删除数、清除的晚点数。然后无论如何在独立事务中写轨道图：`graphRecords.flush` + `commit` + `flushCommitted`；失败打印、`flushFailed` 并回滚（下一 tick 再写）。
- 返回：是否已处理完（不可保存时为 `true`）。
- 调用者：`RailwayData.java:884`、本类 `fullSave`。

### `private void finishSave()`
- 作用：在一个事务中写入全部 `pendingWrites`、删除 `checkFilesToDelete`、写片段（`writePathPieces`）、写运用分层（`writeDutyLayers`）、到期时计算日模拟数据（`SimExport.build`）且内容变化时写入；提交后通知片段存储与运用指纹，更新 `existingFiles`、计数。失败时打印"保持上次保存，下次再试"，片段存储回滚、事务回滚。最后清空待写与待删。

### `private void stage(SerializedDataBase data, long id, String type)`
- 作用：计算一个条目（`writeMessagePackToFile`），并把它从待删中移除。

### `public void markObjectChanged(String kind, long id)`
- 参数：`kind` 为 `EditGuard` 的种类名（`station`/`platform`/`siding`/`route`/`depot`）。
- 作用：标记玩家编辑的对象在本 tick 末立即保存（而不是等下一次保存）。
- 调用者：`RailwayData.java:261`。

### `private static String getType(String kind)` / `private SerializedDataBase getObject(String type, long id)`
- 种类名 → 类型；按类型与 id 取当前对象。

### `public void markRailsChanged(Collection<BlockPos> positions)`
- 作用：标记玩家改动的轨道节点在本 tick 末立即保存。
- 调用者：`RailwayData.java:913`、`:953`、`:968`。

### `public void saveNow()`
- 作用（tick 末）：在独立事务中立即保存本 tick 的变化，使服务器在下次保存前停止也不丢失：编辑过的对象（不存在则删除）、改动的轨道节点（无连接则删除），随轨道新增的站台/停车线，以及随轨道消失的站台/停车线（删除），编辑版本（`editRevisions.takeUnsaved`）。有读不出的条目时不删除。写片段时保留进行中保存已算出的片段；写运用分层；提交后更新 `existingFiles`，并从进行中保存的待写/待删中去掉这些键（已过时）。失败时打印"下次保存再存"，编辑版本放回待存，回滚。
- 调用者：`RailwayData.java:883`。

### `private void addWrite(Map<String, PendingWrite> writes, SerializedDataBase data, long id, String type)`
- 计算条目并加入给定映射。

### `private PendingWrite toPendingWrite(SerializedDataBase data, long id, String type)`
- 作用：条目的保存形式。非停车线（或不支持片段）直接序列化；车厂且支持运用分层时附上分层（`addDutyLayers`）。停车线：开始一个片段所有者、设为本线程写入器后序列化（路径写成片段引用），结束后附上 `Owner`。
- 返回：`PendingWrite`，序列化失败 `null`。

### `private void writePathPieces(Collection<PendingWrite> writes, Collection<String> deletes, Collection<PendingWrite> kept)`
- 作用：在当前事务中写入写出的停车线的片段及引用，删除被删停车线的引用，清除无人引用的片段；`kept`（已算出未写出的）引用的片段保留。不支持时什么都不做。

### `private void addDutyLayers(PendingWrite pendingWrite, Depot depot)`
- 作用：车厂运用（玩家设置与自动编排）自上次写入分层以来有变化（指纹 = 两者字节哈希组合）时，计算 `DutyLayers.of(duties, 停车线→车池键)` 两份附到待写条目。

### `private Map<Long, Long> writeDutyLayers(Collection<PendingWrite> writes, Collection<String> deletes)`
- 作用：在当前事务中写入有分层的车厂（`writeDutyLayers`），删除被删车厂的分层；有变化时 `sweepDutyLayers()`。
- 返回：车厂 → 新指纹（删除的为 `null`），提交后记录。

### `private void committedDutyLayers(Map<Long, Long> written)`
- 作用：提交后更新 `dutyFingerprints`。

### `private <T extends SerializedDataBase> void readMessagePackFromFile(String type, Function<Map<String, Value>, T> getData, Consumer<T> callback, boolean skipVerify)`
- 作用：读某类型全部条目：解析 msgpack map，构造对象；`skipVerify` 为假时名称为空的 `NameColorDataBase` 被丢弃；记录 `existingFiles`。单条失败打印 "Skipping invalid ..." 并计入 `skippedEntries`。
- 异常：整体读取失败抛 `IllegalStateException`。

### `private String writeMessagePackToFile(SerializedDataBase data, long id, String type)`
- 作用：计算条目；内容哈希与数据库中不同（或新条目、或车厂需要写分层）时放入 `pendingWrites`（条目保存的一切都算，所以列车位置、车厂最近发车等每次都会写）。
- 返回：键，或 `null`（无法序列化）。

### `public static byte[] toBytes(SerializedDataBase data)`
- 作用：`packMapHeader(messagePackLength())` + `toMessagePack`。失败打印并返回 `null`。
- 调用者：本类；`EditGuard.java:181`。

## private static final class PendingWrite
- `type`、`id`、`data`、`hash`（`Arrays.hashCode(data)`）、`pathPieces`（停车线的片段所有者，完整写出时为 `null`）、`dutyLayers`（车厂运用变化时的 `{user, auto}`）、`dutyFingerprint`。

### `private <T, U> boolean writeDirtyDataToFile(List<U> dirtyData, Function<U, T> getId, Function<U, Long> idToLong, String type)`
- 作用：从列表末尾逐个取出、计算条目并从待删中去掉；超过 2 ms 返回 `false`（本 tick 时间用完）。
- 返回：列表是否已处理完。

### `private static String getKey(String type, long id)`
- `type + ":" + id`。

## private static class RailEntry extends SerializedDataBase
- 一个节点及其出发的全部轨道。
- 存档键：`node_pos`（long）、`rail_connections`（数组，每项为 `Rail` 的 map 再加 `node_pos` 终点）。
- `RailEntry(BlockPos pos, Map<BlockPos, Rail> connections)`、`RailEntry(Map<String, Value> map)`。
- `toMessagePack` / `messagePackLength()`（= 2） / `writePacket`（空）。
