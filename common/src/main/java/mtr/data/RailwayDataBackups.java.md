# RailwayDataBackups.java

铁路数据库（SQLite `mtr.db`）的小体积备份。备份按批次组织：每批以一份完整拷贝（base）开始，之后的备份只保存与本批 base 的差异——每张表新增或改变的行，以及被删除行的键。恢复时用 base 加上该备份自己的差异重建。

文件名：`mtr-<time>-base.db` 为 base，`mtr-<time>-inc.db` 为差异备份（其 base 是在它之前最新的 base），`mtr-<time>.db` 为分批之前的完整拷贝（视为 base）。名字保持很短，因为 Windows 上 SQLite 打不开超过 260 字符的路径，而服务器常放在很深的目录里；早期差异备份的名字 `mtr-<time>-inc-<base 时间>.db` 仍可读取。由 `RailwayDataSqliteStore`（`:713-786`：创建备份、列出、恢复、检查）使用。

## final class RailwayDataBackups

### 常量
- `TABLES`：参与差异备份的表，每项 `{{表名}, {键列}, {其它列}}`：`entries(type,id | data)`、`meta(key | value)`、`graph_sections(id | data)`、`track_sections(id | data, added_in, removed_in)`、`signal_overlay`、`track_legs`、`track_ownership`、`track_owner_sets`（均 `id | data`）、`graph_paths(owner | graph_fingerprint, data)`、`path_pieces(id | data)`、`path_piece_refs(owner, piece | —)`、`edit_revisions(key | revision, editor, deleted)`。不含 `sim_data`：日模拟工具用的数据会在停服保存时从其余数据重算，而它在大网络上占每个备份的大部分（优化第 5 轮第 4 步）。
- `BATCH_SIZE = 6`（public）：一批（base + 之后的备份）的数量。
- `BATCHES_TO_KEEP = 4`（public）：保留的批数。
- `PREFIX = "mtr-"`、`EXTENSION = ".db"`、`BASE = "-base"`、`INCREMENT = "-inc"`、`TEMPORARY_NAME = "next.tmp"`、`TIME_LENGTH = 15`（`yyyyMMdd-HHmmss`）。
- `DELETED_TABLE = "backup_deleted"`：差异备份中被删除行的键表（`name, k1, k2`）。
- `BASE_TABLE = "backup_base"`：差异备份中记录其 base 时间，保证不会用另一个 base 重建。

### `private RailwayDataBackups()`
工具类，禁止实例化。

### `public static Path createBackup(Path databasePath, Path backupsPath, String time)`
- 作用：最新一批未满且其 base 完好时做差异备份（`writeDifferences`），否则 `VACUUM INTO` 做新 base。先写到 `next.tmp`，`isHealthy` 通过后改名为正式名；之后删除超出保留数的旧批次的全部文件。
- 参数：`time` 时间字符串（`now()` 格式）。
- 返回：生成的备份路径；检查失败返回 `null`（临时文件被删除）。
- 异常：数据库/文件错误时抛出。
- 调用者：`RailwayDataSqliteStore.java:718`；测试 `RailwayDataBackupsTest.java:37`。

### `public static boolean restore(Path backup, Path target)`
- 作用：把备份还原成 `target` 处的数据库：先在 `<target>.restoring` 构建。完整备份直接复制；差异备份先复制其 base，再附加差异库，在事务中对每张表删除差异中记录删除的键，再 `INSERT OR REPLACE` 差异行（表不存在则按差异表结构创建）。最终 `quick_check` 通过后移动到 `target`。
- 返回：base 缺失或损坏、备份损坏、或结果不健康时 `false`。
- 调用者：`RailwayDataSqliteStore.java:745`、`RailwayDataSqliteStore.java:773`。

### `public static List<Path> getBackups(Path backupsPath)`
- 返回：目录中名字形如备份的文件，按时间（再按文件名）倒序（最新在前）。
- 调用者：`RailwayDataSqliteStore.java:713`、`:725`、`:772`。

### `public static List<List<Path>> getBatches(Path backupsPath)`
- 返回：批次列表，最新在前；每批内部备份最新在前、base 在最后。base 已不存在的差异备份不属于任何批次。

### `public static String now()`
- 返回：当前时间 `yyyyMMdd-HHmmss`（本机时区）。调用者：`RailwayDataSqliteStore.java:718`。

### `private static void writeDifferences(Statement statement, Path base, Path temporaryPath)`
- 作用：在当前数据库连接上附加 base 和新差异库（差异库 `journal_mode=MEMORY`，避免更长的日志路径），在一个事务中（保证各表一致）：建 `backup_base` 写入 base 时间、建 `backup_deleted`；对每张存在的表：建同列空表；base 中无此表则全部复制；否则插入"base 中没有或任一非键列不同（`IS NOT`）"的行，并把"base 有而当前没有"的键记入删除表（单键时 k2 为 NULL）。失败回滚并抛出；最后分离两个库。

### `private static Path findBase(Path backup)`
- 返回：差异备份的 base：优先用备份内记录的 base 时间，否则按文件名推断（`getBaseTime`）；找不到为 `null`。

### `private static String getRecordedBaseTime(Path backup)`
- 返回：`backup_base` 表中的时间；无此表为 `null`。

### `private static boolean isIncrement(Path backup)` / `private static String getTime(Path backup)`
- 文件名含 `-inc` 即为差异备份；时间为前缀后的 15 个字符。

### `private static String getBaseTime(Path backup, NavigableSet<String> baseTimes)`
- 返回：base 本身返回自身时间；旧命名 `-inc-<time>` 返回其中的时间（存在于集合中时）；否则返回不晚于其时间的最新 base 时间（制作时最新的 base）；无则 `null`。

### SQL 辅助
- `allColumns(table)`：键列 + 其它列。
- `prefixed(alias, columns)`：`alias.col, ...`。
- `joinOn(keys)`：`b.k = m.k AND ...`。
- `hasTable(statement, schema, name)`：查 `sqlite_master`。
- `quote(text)`：SQL 单引号字符串转义。
- `connect(Path)`：用 SQLite JDBC 驱动直接打开。

### `public static boolean isHealthy(Path path)`
- 返回：文件存在且 `PRAGMA quick_check` 返回 `ok`；数据库损坏或不是数据库时 `false`；其它 SQLite 错误抛出。
- 调用者：`RailwayDataSqliteStore.java:76`、本类。
