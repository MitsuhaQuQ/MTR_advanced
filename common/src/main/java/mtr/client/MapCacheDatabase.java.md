# MapCacheDatabase.java（本分支新增）

客户端：仪表板地图背后的 SQLite 文件，每个世界每个维度一个（路径由 `MapDataCache` 决定）。里面的一切都能重新发送或重新扫描得到，所以 `MapDataCache` 读不了的文件会被换成空文件。存两类数据：服务器发来的地图条目（轨道、线路路径等，按种类与键，带哈希用于增量同步）和地形颜色瓦片（每区块 16×16 颜色）。调用者：`common/src/main/java/mtr/client/MapDataCache.java:223`、`:254`；`MapTerrainCache` 使用其瓦片键与常量；测试 `common/src/test/java/mtr/client/MapCacheDatabaseTest.java`。

## 表结构
- `entries(kind INTEGER, key INTEGER, hash INTEGER, payload BLOB, PRIMARY KEY(kind, key)) WITHOUT ROWID`：`payload` 为 long 数组的大端字节。
- `tiles(key INTEGER PRIMARY KEY, colors BLOB)`：每色 3 字节 RGB，共 256 色。
- `PRAGMA journal_mode=WAL`、`synchronous=NORMAL`；关闭自动提交（调用方显式 `commit`）。

## 类 `MapCacheDatabase`（final，implements `AutoCloseable`）

### 常量
- `TILE_SIZE = 16`（瓦片边长 = 一个区块）、`TILE_COLORS = 256`、`BYTES_PER_COLOR = 3`。

### 构造函数 `MapCacheDatabase(Path path) throws SQLException, IOException`
- 建目录、连接、建表、准备语句。

### `void readEntries(EntryConsumer consumer)` / `void readTiles(TileConsumer consumer)`
- 读出全部条目/瓦片（颜色数据长度不对的瓦片跳过）。

### `void writeEntry(int kind, long key, long hash, long[] payload)` / `void deleteEntries(int kind, Collection<Long> keys)` / `void writeTiles(Map<Long, int[]> tiles)`
- 插入或替换 / 删除 / 写瓦片（需随后 `commit`）。

### `void commit()` / `void close()`
- 提交；关闭前也提交。

### `static String getSafeName(String name)`
- 作用：把世界/服务器名变成可用的文件名：非法字符与末尾的点/空格换成 `_`；若有改动，后缀 `-<原名哈希十六进制>` 以区分最终字符相同的不同名字。调用者：`MapDataCache.java:201`。

### `static long getTileKey(int chunkX, int chunkZ)` / `static int getTileX(long key)` / `static int getTileZ(long key)`
- 区块坐标与 64 位键互转（高 32 位 x，低 32 位 z）。

### `private static Connection connect(Path path)`
- 打开并初始化；建表失败关闭连接后上抛。

### `private static byte[] toBytes(long[])` / `private static long[] toLongs(byte[])` / `private static byte[] toBytes(int[] colors)` / `private static int[] toColors(byte[])`
- 编解码（颜色长度不对返回 null）。

## 函数式接口
- `EntryConsumer.accept(int kind, long key, long hash, long[] payload)`。
- `TileConsumer.accept(long key, int[] colors)`。
