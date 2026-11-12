# RailwayDataSqliteStore.java

铁路数据存储（`RailwayDataStore`）的 SQLite 实现，数据库文件为存档目录下的 `mtr.db`（WAL 模式、`synchronous=FULL`：提交即落盘，断电不丢已提交的数据）。实现接口的全部功能：普通条目、元数据、轨道区段层与信号层、腿层、图路径、模拟数据、路径片段、运用分层表、编辑版本；并负责启动时的备份、损坏检测与自动恢复、按管理员请求恢复备份、从旧的分文件格式迁移、空间回收。由 `RailwayDataFileStore.open`（`RailwayDataFileStore.java:25`）创建，`RailwayDataFileSaveModule` 驱动读写与提交；备份逻辑在 `RailwayDataBackups`。

## class RailwayDataSqliteStore implements RailwayDataStore

### 字段与常量
- `hasPendingChanges`：自上次提交以来是否有写入（无则 `commit` 不提交）；`closed`。
- `savePath`、`databasePath`（`mtr.db`）、`backupsPath`（`backups/`）。
- `connection`：JDBC 连接（自动提交关闭）；`selectStatement` / `writeStatement` / `deleteStatement`：`entries` 表的预编译语句。
- `backupRunning`、`backupExecutor`（单线程守护 "MTR Data Backup"）。
- `BACKUPS_FOLDER = "backups"`、`LEGACY_FOLDER = "legacy-files"`、`BACKUP_EXTENSION`、`TEMPORARY_EXTENSION`（未在本文件使用）。
- 元数据键：`migrated`、`schema_version`（`SCHEMA_VERSION = 1`；更新版本 jar 写的数据库不打开）、`track_version`、`track_fingerprint`、`track_count`。
- `COMPACT_MIN_FREE_BYTES = 64 MiB`、`BACKUP_INTERVAL_MILLIS = 1 小时`、`RESTORE_REQUEST = "restore.txt"`、`BACKUP_WAIT_SECONDS = 60`。

### 表结构（构造时 `CREATE TABLE IF NOT EXISTS`）
- `entries(type, id, data)`（主键 type+id）：普通数据条目。
- `meta(key, value)`。
- `graph_sections(id, data)`。
- `track_sections(id, data, added_in, removed_in)`：按内容哈希的区段层，记录加入和删除的版本，使某版本即使后一版本只写了一部分也能完整读出。
- `signal_overlay(id, data)`、`track_legs(id, data)`、`track_ownership(id, data)`、`track_owner_sets(id, data)`。
- `graph_paths(owner, graph_fingerprint, data)`、`sim_data(key, data)`。
- `path_pieces(id, data)`、`path_piece_refs(owner, piece)` + 索引（优化第 5 轮第 2 步）。
- 运用分层（"运用表复用和索引"）：`duty_bodies(id, data)`、`duty_groups(id, pool, data)`、`duty_group_bodies(grp, position, body)`、`duty_assignments(depot, kind, siding, slot, weekdays, duty, grp, body, data)`、`duty_body_services(route, service, body)`、`duty_depots(depot, fingerprint)` 及相关索引。
- `edit_revisions(key, revision, editor, deleted)`。

### `public RailwayDataSqliteStore(Path savePath, String[] types)`
- 作用：建备份目录；处理管理员的恢复请求（`restoreRequested`）；数据库存在但损坏时隔离（`quarantineDatabase`）并从最新可用备份恢复；存在且健康时先做一次备份（失败只打印）。然后连接、设 PRAGMA、建表、预编译语句；检查版本（`checkSchemaVersion`）、迁移（`migrate(types)`）、提交；在事务外按需 `VACUUM`（`compactIfMostlyFree`）。
- 参数：`types` 普通条目的类型列表（用于迁移旧分文件数据）。
- 异常：SQLite 不可用、数据库为更新版本等抛出（`RailwayDataFileStore.open` 据此决定是否回退）。
- 调用者：`RailwayDataFileStore.java:25`；测试 `DutyTablesTest.java:58`。

### `private void compactIfMostlyFree()`
- 作用：空闲页 ≥ 64 MiB 且超过一半时 `VACUUM`，打印前后大小与耗时（例如路径首次改为共享片段后）；失败打印并照常使用。

### `private static long readPragma(Statement statement, String name)`
- 读取一个 PRAGMA 数值。

### `private void checkSchemaVersion()`
- 作用：已有版本号大于本 jar 时回滚、关闭连接并抛 `IllegalStateException`（数据保持原样）；否则若无版本号则写入并提交。

### 普通条目
- `forEach(String type, BiConsumer<Long, byte[]>)`：查询该类型全部条目。
- `write(String type, long id, byte[] data)`：`INSERT OR REPLACE`。
- `delete(String type, long id)`。
- 写/删置 `hasPendingChanges`。调用者：`RailwayDataFileSaveModule`（见 `RailwayDataStore.java.md`）、本类 `migrate`。

### `public void publishTrackSections(long version, Map<Long, byte[]> added, Collection<Long> removed, Map<Long, byte[]> overlayPut, Collection<Long> overlayRemoved, long fingerprint, int count)`
- 作用：新增区段插入或更新（以前删除但尚未清除的行在本版本重新出现，`removed_in` 置空，`added_in` 设为本版本）；被删区段标 `removed_in = version`（仅当尚未标记）；信号层增删；最后写根记录（`track_version`、`track_fingerprint`、`track_count`）——在它写入前读到的仍是前一版本。
- 调用者：`GraphRecords.java:379`。

### `public void writeLegLayer(...)` / `private void writeRows(String table, Map<Long, byte[]> put, Collection<Long> removed)`
- 写 `track_legs`、`track_ownership`（增删）、`track_owner_sets`（只增）。调用者：`GraphRecords.java:432`。

### 路径片段
- `storesPathPieces()` → `true`。
- `loadPathPieces(pieces, refs)`：读两表。
- `writePathPieces(Map<Long, byte[]>)`：`INSERT OR IGNORE`（已有保持不变）。
- `writePathPieceRefs(long owner, Collection<Long>)`：删除该所有者全部引用后重新插入。
- `deletePathPieces(Collection<Long>)`：只删除确实无人引用的片段（无论调用方怎么算）。
- 调用者：`PathPieces.java:452/491/493/515`。

### 运用分层
- `storesDutyLayers()` → `true`。
- `loadDutyFingerprints(BiConsumer<Long, Long>)`：读 `duty_depots`。调用者：`RailwayDataFileSaveModule.java:199`。
- `writeDutyLayers(long depotId, long fingerprint, DutyLayers user, DutyLayers auto)`：先 `deleteDutyLayers`，再对 user（kind 0）与 auto（kind 1）：插入主体（`INSERT OR IGNORE`）及其班次索引、组及组内主体顺序、分配（空槽的组为 0，主体键由分配计算）；最后记录车厂指纹。调用者：`RailwayDataFileSaveModule.java:428/607/735`。
- `deleteDutyLayers(long depotId)`：删该车厂的分配与指纹。调用者：`RailwayDataFileSaveModule.java:743`。
- `sweepDutyLayers()`：依次删除无分配引用的组、无组的组内主体行、无组引用的主体、无主体的班次索引。调用者：`RailwayDataFileSaveModule.java:748`。
- `findDutiesRunning(long routeId, int service)`：经班次索引与分配联查，返回 `{depot, kind, siding, slot, weekdays, duty}` 列表（排序）。
- `findDepotsUsingGroup(long groupKey)`：使用某组的车厂（去重排序）。

### `public void loadLegLayer(legs, owners, ownerSets)`
- 读三张腿层表。调用者：`GraphRecords.java:522`。

### `public void sweepTrackSections(long version)`
- 删除 `removed_in <= version` 的区段行。调用者：`GraphRecords.java:357`。

### `public long[] loadTrackSections(BiConsumer<Long, byte[]> sections, BiConsumer<Long, byte[]> overlay)`
- 作用：无已发布版本返回 `null`。否则先撤销从未发布的版本写入的内容（删除 `added_in > 已发布`、恢复 `removed_in > 已发布`，有变化时打印并提交），再读该版本有效的区段与全部信号层。
- 返回：`{version, fingerprint, count}`（缺失时 fingerprint 0、count −1）。
- 调用者：`GraphRecords.java:506`。

### `private String readMeta(String key)`
- 读元数据，无则 `null`。

### `writeGraphPath(String owner, long fingerprint, byte[] data)` / `writeSimulationData(byte[] data)`
- `graph_paths` 按所有者替换；`sim_data` 写入键 `'world'`。调用者：`GraphRecords.java:440`、`RailwayDataFileSaveModule.java:435`。

### `public void commit()`
- 有待提交写入时提交。

### 编辑版本
- `writeEditRevision(String key, int revision, String editor, boolean deleted)`：替换。调用者：`RailwayDataFileSaveModule.java:603`。
- `forEachEditRevision(EditRevisionConsumer)`：读全部。调用者：`RailwayDataFileSaveModule.java:209`。

### `public void rollback()`
- 回滚并清除待提交标志。

### `public void backup()`
- 作用：未关闭、距最新备份已满 1 小时、且没有备份在运行时，在后台线程 `createBackup()`。异常只打印。调用者：`RailwayDataFileSaveModule.java:316`。

### `public void close()`
- 作用（只执行一次）：提交；关闭备份线程并最多等 60 秒；关闭语句；回滚残余事务；打开自动提交执行 `PRAGMA wal_checkpoint(TRUNCATE)`（把 WAL 合并回主文件）；关闭连接。

### `private void migrate(String[] types)`
- 作用：已迁移（有 `migrated` 元数据）则跳过。否则把 `legacy-files/` 和存档目录下旧的分文件数据（按 `types`）写入 `entries`，写 `migrated` 时间戳并提交；把旧目录移到 `legacy-files/` 保留以备恢复；打印迁移条数。

### 备份与恢复
- `private boolean isBackupDue()`：无备份或最新备份文件修改时间已过 1 小时。
- `private void createBackup()`：`RailwayDataBackups.createBackup(..., now())`，检查失败打印。
- `private List<Path> getBackups()`：委托 `RailwayDataBackups.getBackups`。
- `private void restoreRequested()`：管理员在停服时把备份文件名写入 `backups/restore.txt`；启动时校验名字（不含路径分隔符、文件存在），先还原到 `mtr.db.restored`，成功后把当前数据库（及 -wal/-shm）改名为 `…-before-restore-<时间>` 保留，再把还原结果放到 `mtr.db`，请求文件改名为 `restore-done.txt`，并打印。还原失败则什么也不改。
- `private void quarantineDatabase()`：把损坏的数据库文件（含 -wal/-shm）改名为 `…-corrupted-<时间>`。
- `private void restoreLatestBackup()`：按新到旧尝试 `RailwayDataBackups.restore`，成功即停；都失败打印"尝试旧数据文件"（随后迁移会读旧分文件，如果有）。
- `private static Connection connect(Path)`、`private static boolean isHealthy(Path)`：同 `RailwayDataBackups` 中的同名方法（`quick_check`；损坏/非数据库为 `false`，其它错误抛出）。
