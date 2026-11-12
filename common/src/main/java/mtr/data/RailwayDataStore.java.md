# RailwayDataStore.java

铁路数据持久化存储的接口。两个实现：`RailwayDataSqliteStore`（默认，SQLite 数据库 `mtr.db`，实现全部功能）和 `RailwayDataFileStore`（SQLite 不可用时的分文件后备，只实现基本的按类型/id 读写）。大部分扩展功能为 `default` 空实现，只有 SQLite 版保存：编辑版本、轨道区段层、腿（leg）层、图路径、模拟数据、路径片段、运用分层表。由 `RailwayDataFileSaveModule`（保存/加载流程）、`GraphRecords`（轨道区段与腿层）、`PathPieces`（路径片段）调用。

## interface RailwayDataStore

### 基本读写
- `void forEach(String type, BiConsumer<Long, byte[]> consumer)`：遍历某类型的全部记录 `(id, bytes)`。调用者：`RailwayDataFileSaveModule.java:294`、`GraphRecords.java:133`。
- `void write(String type, long id, byte[] data)`：写/覆盖一条。调用者：`RailwayDataFileSaveModule.java:421`、`:596`。
- `void delete(String type, long id)`：删一条。调用者：`RailwayDataFileSaveModule.java:425`、`:600`。
- `void commit()`：提交自上次以来的写入。调用者：`RailwayDataFileSaveModule.java:395`、`:437`、`:608`。
- `default void rollback()`：在能做到的存储上丢弃自上次提交以来的写入。调用者：`RailwayDataFileSaveModule.java:402`、`:459`、`:629`。
- `void backup()`：备份。调用者：`RailwayDataFileSaveModule.java:316`。
- `void close()`：关闭。调用者：`RailwayDataFileSaveModule.java:265`、`:815`。

### 编辑版本（多人编辑冲突，见 `EditRevisions`）
- `default void writeEditRevision(String key, int revision, String editor, boolean deleted)`：保存一个部分的版本，使其在服务器重启后仍有效；`deleted` 表示对象已删除。调用者：`RailwayDataFileSaveModule.java:603`。
- `default void forEachEditRevision(EditRevisionConsumer consumer)`：读回所有版本。调用者：`RailwayDataFileSaveModule.java:209`。
- `interface EditRevisionConsumer { void accept(String key, int revision, String editor, boolean deleted); }`

### 轨道区段层（组件 `transitsim.network.TrackSections`）
- `default void publishTrackSections(long version, Map<Long, byte[]> added, Collection<Long> removed, Map<Long, byte[]> overlayPut, Collection<Long> overlayRemoved, long fingerprint, int count)`：写入一个版本：新增区段、移除的区段（保留到 `sweepTrackSections`，使旧版本仍可读）、信号层行，最后写发布该版本的根记录。调用者：`GraphRecords.java:379`。
- `default void sweepTrackSections(long version)`：删除在此已发布版本及之前被移除的区段行。调用者：`GraphRecords.java:357`。
- `default long[] loadTrackSections(BiConsumer<Long, byte[]> sections, BiConsumer<Long, byte[]> overlay)`：读已发布版本的区段与信号层；从未发布的版本（例如写一半停服）的行被丢弃，被它移除的行恢复。返回 `{version, fingerprint, count}`，从未发布返回 `null`。调用者：`GraphRecords.java:506`。

### 腿层
- `default void writeLegLayer(Map<Long, byte[]> legsPut, Collection<Long> legsRemoved, Map<Long, byte[]> ownersPut, Collection<Long> ownersRemoved, Map<Long, byte[]> ownerSetsPut)`：写入腿层的变化：腿、区段的所有者（按键）。腿读回时与区段核对，所以不需要自己的版本。调用者：`GraphRecords.java:432`。
- `default void loadLegLayer(BiConsumer<Long, byte[]> legs, BiConsumer<Long, byte[]> owners, BiConsumer<Long, byte[]> ownerSets)`：`owners` 为每个区段（按区段哈希）的所有者集合，`ownerSets` 为每个所有者集合（按 id）。调用者：`GraphRecords.java:522`。

### 其它
- `default void writeGraphPath(String owner, long fingerprint, byte[] data)`：保存一个所有者的图路径；`fingerprint` 为算出路径时的网络指纹，用来识别旧网络的路径。调用者：`GraphRecords.java:440`。
- `default void writeSimulationData(byte[] data)`：替换组件模拟器需要的世界数据（S4e，日模拟工具读取）。调用者：`RailwayDataFileSaveModule.java:435`。

### 路径片段（`PathPieces`）
- `default boolean storesPathPieces()`：是否能把路径存为对共享片段的引用；默认 `false`。调用者：`RailwayDataFileSaveModule.java:147`。
- `default void loadPathPieces(BiConsumer<Long, byte[]> pieces, BiConsumer<Long, Long> refs)`：读所有片段（按键）与引用（所有者 → 片段）。调用者：`PathPieces.java:452`。
- `default void writePathPieces(Map<Long, byte[]> pieces)`：添加片段，已有的保持不变。调用者：`PathPieces.java:491`。
- `default void writePathPieceRefs(long owner, Collection<Long> pieces)`：替换一个所有者引用的片段；空集合删除该所有者。调用者：`PathPieces.java:493`。
- `default void deletePathPieces(Collection<Long> pieces)`。调用者：`PathPieces.java:515`。

### 运用分层表（"运用表复用和索引"、"运用补完"第 1 步）
- `default boolean storesDutyLayers()`：是否把车厂运用另存为分层表（每个主体和组按内容只存一次，带索引）；读取仍以车厂条目为准。默认 `false`。调用者：`RailwayDataFileSaveModule.java:148`。
- `default void loadDutyFingerprints(BiConsumer<Long, Long> fingerprints)`：每个车厂及其分层写入时运用的指纹。调用者：`RailwayDataFileSaveModule.java:199`。
- `default void writeDutyLayers(long depotId, long fingerprint, DutyLayers user, DutyLayers auto)`：用玩家设置（user）与自动编排（auto）的运用替换车厂的分配，补存尚未存在的主体与组。调用者：`RailwayDataFileSaveModule.java:428`、`:607`、`:735`。
- `default void deleteDutyLayers(long depotId)`。调用者：`RailwayDataFileSaveModule.java:743`。
- `default void sweepDutyLayers()`：删除没有分配引用的组和没有组引用的主体。调用者：`RailwayDataFileSaveModule.java:748`。
- `default List<long[]> findDutiesRunning(long routeId, int service)`：运行某班次的运用，每项 `{depot, 0=玩家/1=自动, siding, slot, weekdays, duty 编号}`。默认空表。调用者：测试 `DutyTablesTest.java:68`。
- `default List<Long> findDepotsUsingGroup(long groupKey)`：使用某组的车厂。默认空表。调用者：测试 `DutyTablesTest.java:66`。
