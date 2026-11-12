# GraphRecords.java

存档中的"轨道区段层"（阶段文档第 1 轮）以及每条已找到路径经过的区段，保存在服务器自己的 SQLite 表中；另外维护"腿（leg）层"（第 2 轮）：区段快照、每个区段的所有者，以及在区段上找到的腿（站间的路径片段，由组件 `LegRouter` 计算、`LegCache` 缓存）。

- 区段层（组件 `transitsim.network.TrackSections`）在服务器线程上维护：每隔几秒检查轨道、关键节点、寻路边界节点、站台与停车线的廉价指纹，变化时把差异交给区段层，只重新切分变化所在或相接的区段。信号层（`SignalOverlay`）在区段或信号闭塞变化时重算，只写不同的条目。
- 保存循环把变化写成一个新版本：新增的行、被删的行（保留到下一版本写完）、最后写根记录，全部在一个事务中。加载时读取已发布版本并与轨道核对，不符的部分重新切分并写入。
- 路径可以在任何线程找到，所以排队等下一个版本一起写。

由 `RailwayData` 持有 `graphRecords`（`:648` 更新、`:793` 取腿、`:1070` 加路径），`RailwayDataFileSaveModule`（`:206` 加载、`:394-400` 写入/提交/失败），`RoutePaths`（`:185`、`:582`、`:739`、`:1135` 取腿，`:211-254` 设所有者），`Siding.java:1560`（加路径）使用。

## final class GraphRecords

### 字段（区段层）
- `trackSections`：组件区段层。
- `overlay`：当前信号层（区段哈希 → `SignalOverlay.Entry`）。
- `pendingPaths`（并发队列）：待写的路径；`MAX_PENDING_PATHS = 65536`。
- `loadedSections`、`loadedOverlay`、`loadedRoot`（`{version, fingerprint, count}`）：存档读出的内容，第一次检查时取回。
- `restored`：是否已完成第一次检查。
- `railsFingerprint`：上次的输入指纹；`overlayVersion`：上次信号层对应的闭塞版本。
- `version`：最近写入的版本；`sweepVersion`：可以清除其删除行的版本。
- `pendingAdded` / `pendingRemoved` / `pendingOverlay`：待写的变化（区段哈希）。
- `flushedAdded` / `flushedRemoved` / `flushedOverlay` / `flushedPaths` / `flushedVersion`：上次 flush 写出的内容，提交前保留，失败时重写。

### 字段（腿层）
- `snapshot`（volatile）：区段快照；`owners`（volatile）：区段 → 所有者集合；`maxChordPerCost`（volatile）：寻路启发参数。快照与所有者在服务器线程上先替换，再通知缓存，因此在别的线程找到的腿不会被用于它不属于的网络。
- `legCache`：腿缓存；`loadedLegs`、`loadedOwners`：存档读出的腿与所有者。
- `writtenLegs`、`writtenOwners`：已写出的内容（只写差异）；`flushedLegs`、`flushedOwners`、`flushedLegRemovals`、`flushedOwnerRemovals`：本次 flush 写出的差异。
- `writtenLegModifications`、`writtenOwnersMap`：上次写出并提交时的腿缓存修改计数与所有者映射对象；`flushingLegLayer`、`flushingLegModifications`、`flushingOwnersMap`：进行中的 flush 的对应值。

### 常量 `COORDINATES`（public `LegRouter.Coordinates`）
- 节点就是方块位置：`getX(node) = BlockPos.getX(node)`、`getZ(node) = BlockPos.getZ(node)`。

### `public void update(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<BlockPos> keyNodes, Set<Long> boundaryNodes, Set<UUID> boundaryRails, Set<UUID> releasedRails, Map<UUID, Long> savedRails, Map<Long, ? extends Collection<UUID>> blocks, long blocksVersion)`
- 参数：`savedRails` 每条轨道上的站台/停车线（键 `RailKeys.of`）；`blocks` 每个信号闭塞的轨道；`blocksVersion` 闭塞变化时改变的数。
- 作用：指纹（轨道、关键节点、边界节点、站台/停车线、边界轨道、释放轨道、`PlatformSpeeds.fingerprint()`）变化或首次时：转换轨道（`toTrackRails`）与标记（`toMarks`）；首次 `restore`（打印核对耗时），否则 `trackSections.sync`；把变化合并进待写集合（新增又删除的不写；新增总是重写，因为同内容哈希的区段可能换主）；有变化时打印并 `updateLegs`（首次也做）。区段或闭塞变化时用 `SignalOverlay.build` 重算信号层并记录不同的条目。
- 副作用：日志。
- 调用者：`RailwayData.java:648`；测试 `RoutePathsTest.java:409`。

### `private void updateLegs(TrackSections.Change change)`
- 作用：生成新快照与 `maxChordPerCost`。第一次：取回存档的所有者，以及其触及的区段都还在的腿（放入缓存；未取回的存档行记为待删除），打印取回数量。然后让缓存丢弃触及新增/删除区段、或从新区段连接的节点继续的腿，打印需重找的数量。

### `public Set<Long> setOwners(Map<Long, Set<String>> newOwners)`
- 作用：替换区段所有者，丢弃触及所有者改变的区段的腿，打印数量。服务器线程调用。
- 返回：所有者改变的区段。
- 调用者：`RoutePaths.java:211`、`:237`、`:254`。

### `public Legs getLegs()`
- 返回：当前可用的腿来源；第一次检查前为 `null`。先取版本再取快照（快照在其后替换只会让找到的腿"比变化旧"，不会更新）。
- 调用者：`RailwayData.java:793`、`RoutePaths.java:185`。

## public static final class Legs implements RouteLegs.LegSource
某一时刻的腿：已缓存的，或在该时刻快照上找到并缓存的。任意线程可用。
- 字段：`snapshot`、`owners`（public），`version`、`maxChordPerCost`、`legCache`。
- `get(LegRouter.Request request)`：先查缓存；否则 `LegRouter.find(...)` 并 `keep`（另一线程同时找到时使用已保存的那个，使各线路一致）。
- `withoutKeeping()`：同样的腿但新找到的不缓存——用于测量缓存不需要的路，避免其搜索域占用缓存和存档。调用者：`RoutePaths.java:754`、`:1139`。
- `holds(Collection<LegRouter.Leg> legs)`：这些腿是否仍是缓存中的。调用者：`RoutePaths.java:152`、`:167`。

### `private static long getOwnerSetId(Set<String> sectionOwners)`
- 返回：按排序后的所有者内容计算的集合 id。

### `public LegCache getLegCache()`
- 调用者：`RoutePaths.java:1382`。

### `private TrackSections.Change restore(Map<UUID, TrackSections.Rail> trackRails, Map<Long, Integer> marks)`
- 作用：用存档区段与轨道核对（`trackSections.restore`）；无存档时打印"由轨道生成"；有则采用其版本，若数量或内容不符打印重新切分的信息。信号层采用存档内容（只写差异）。清除加载缓冲。

### `public void addPath(String owner, List<long[]> moves)`
- 参数：`owner` 如 `route:<id>` 或 `siding:<id>`；`moves` 见 `getMoves`。
- 作用：未满且非空时排队。调用者：`RailwayData.java:1070`、`Siding.java:1560`。

### `public void flush(RailwayDataStore store)`
- 作用：写出变化（在调用方的事务内）：首次检查前不写（路径继续等待）；先清除上一版本已提交的删除行；有待写区段/信号层时以 `version + 1` 发布（`publishTrackSections`，含新增区段字节、删除列表、信号层增删、指纹与总数）；腿层仅在腿缓存修改计数或所有者映射对象变化时比较（第 5 轮：否则每 tick 比较所有腿与所有者曾占服务器线程三分之一），写出变化的腿、删除的腿、变化的区段所有者（每个不同的所有者集合只写一次，区段记其集合 id）、删除的所有者；最后写出所有排队的路径（`writeGraphPath(owner, 区段指纹, toSectionPath(moves).toBytes())`）。
- 异常：存储错误抛出。
- 调用者：`RailwayDataFileSaveModule.java:394`。

### `public void flushCommitted()`
- 作用：上次 flush 已提交：记下腿层已写状态；若写过区段则版本成为已发布版本，其删除行在下次清除；清空 flushed 集合。
- 调用者：`RailwayDataFileSaveModule.java:396`。

### `public void flushFailed()`
- 作用：上次 flush 未提交：腿层下次重新比较即可；区段/信号层变化与路径放回待写（新增与删除相互抵消）。
- 调用者：`RailwayDataFileSaveModule.java:400`。

### `public void load(RailwayDataStore store)`
- 作用：读已发布版本的区段与信号层（读失败的条目打印后忽略，检查时重新切分），以及腿层（腿、区段 → 所有者集合 id、所有者集合）。异常打印堆栈。
- 调用者：`RailwayDataFileSaveModule.java:206`。

### `public TrackSections getTrackSections()`
- 返回区段层。脚本未找到调用者。

### `public static Map<UUID, TrackSections.Rail> toTrackRails(Map<BlockPos, Map<BlockPos, Rail>> rails, Map<UUID, Long> savedRails)`
- 作用：把轨道转为区段层的输入：每条轨道只取一次（两个方向都有时从较小节点），记录两端、长度、从较小节点看的两端朝向（`shape`）、限速标签（`SpeedRules.labelKmh`）、能否加速、区段类型（取列车可通行方向的类型）、正/反向是否可通行、站台/停车线 id。
- 调用者：测试 `GraphRecordsTest.java:43`。

### 私有辅助
- `getRail` / `hasRail`：查轨道。
- `getType(RailType)`：`PLATFORM`/`SIDING`/`TURN_BACK`/其它 `NORMAL`。
- `toMarks(...)`：节点标记：关键节点 `MARK_KEY`、边界节点 `MARK_BOUNDARY`、边界轨道两端 `MARK_BOUNDARY_RAIL`、从连接线释放的轨道两端 `MARK_RELEASED`（后两者自成区段）。
- `hashRails(Set<UUID>, long salt)`：顺序无关的轨道集合哈希。
- `fingerprint(...)`：顺序无关的输入指纹（各项哈希相加）。
- `mix(long, long, long)`：混合哈希。

### `public static List<long[]> getMoves(List<PathData> path)` / `getMoves(long[] nodes)`
- 返回：每段 `{起点节点, 终点节点, 停站时间, 站台或 0}`；节点序列版本停站与站台为 0。
- 调用者：`Siding.java:1560`、`RailwayData.java:1070`。

## private static final class PendingPath
- `owner`、`moves`。
