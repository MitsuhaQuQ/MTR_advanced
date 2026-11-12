# RailwayDataFileStore.java

铁路数据存储（`RailwayDataStore`）的"分文件"实现，以及选择存储实现的入口 `open`。默认使用 SQLite（`RailwayDataSqliteStore`，数据库文件 `mtr.db`）；只有 SQLite 类加载失败且数据库文件尚不存在时才退回到"每条记录一个文件"的旧格式。本类不能引用 SQLite 类，否则加载失败无法在这里处理。由 `RailwayDataFileSaveModule.java:145` 打开，存取由保存模块调用。

## class RailwayDataFileStore implements RailwayDataStore

### 字段与常量
- `savePath`：数据目录。
- `DATABASE_NAME = "mtr.db"`；`DATABASE_SUFFIXES = {"", "-wal", "-shm"}`：SQLite 主文件及 WAL/共享内存文件后缀。
- 文件布局：`savePath/<type>/<id % 100>/<id>`，文件内容为该记录的字节。

### `public RailwayDataFileStore(Path savePath)`
- 主要调用者：本类 `open`；`RailwayDataSqliteStore.java:671`。

### `public static RailwayDataStore open(Path savePath, String[] types)`
- 作用：尝试 `new RailwayDataSqliteStore(savePath, types)`。失败时：若 `mtr.db` 已存在，抛 `IllegalStateException`（宁可停服，也不悄悄从更旧的数据开始）；否则打印 "SQLite is unavailable..." 与堆栈，删除可能残留的 `mtr.db*` 文件，返回分文件存储。
- 参数：`types` 数据种类（表名/子目录名）列表。
- 主要调用者：`RailwayDataFileSaveModule.java:145`。

### `public void forEach(String type, BiConsumer<Long, byte[]> consumer)`
- 作用：遍历 `type` 目录下所有子目录中名字为整数的文件，以 `(id, bytes)` 回调。单个文件读取失败时打印 "Skipping invalid ... data file" 并跳过；目录不存在则直接返回。
- 异常：列目录失败时抛出 `Exception`。
- 主要调用者：`RailwayDataFileSaveModule.java:294-296`。

### `public void write(String type, long id, byte[] data)`
- 作用：建目录并覆盖写入 `type/<id%100>/<id>`。
- 主要调用者：`RailwayDataFileSaveModule.java:421`、`:596`、`:699`。

### `public void delete(String type, long id)`
- 作用：删除对应文件（不存在不报错）。
- 主要调用者：`RailwayDataFileSaveModule.java:425`、`:600`。

### `commit()` / `backup()` / `close()`
- 空实现：分文件存储每次写入即落盘，无事务、无备份、无连接。
- 调用者：`RailwayDataFileSaveModule.java:395`（commit）、`:316`（backup）、`:265`（close）。

### `private static boolean isDataFile(Path path)`
- 返回：是普通文件且文件名匹配 `-?\d+`（避免读到 macOS 的 `._123`、`.DS_Store` 等）。
