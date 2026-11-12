# MapDataCache.java（本分支新增）

客户端：把线网地图保存在磁盘上（`<游戏目录>/mtr_map_cache/<世界>/<维度>.db`，`MapCacheDatabase`）。服务器为每个地图条目列出一个哈希，只传输哈希不同的条目；比较的是哈希而不是版本号，所以错误或过期的文件只会多一次传输。文件中也保存 `MapTerrainCache` 的地形。数据库只在一个单独线程（"MTR Map Cache"）中访问，不占用游戏线程。调用者：`common/src/main/java/mtr/screen/DashboardScreen.java:100`（打开仪表板 → `open`）、`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:237`、`:257`（摘要与数据包）、`MapTerrainCache.scan`（`writeTiles`）、网页编辑器 `EditorNetwork.java:58`（`getVersion`）。

## 类 `MapDataCache`（final，私有构造）

### 常量与静态字段
- `MAX_WANTED_PER_PACKET = 3000`：每个"需要"包最多键数。
- `CACHE_DIRECTORY = "mtr_map_cache"`。
- `EXECUTOR`：单线程守护执行器。
- `ENTRIES`（按种类：键 → `Entry`）、`DIGESTS`（按种类：服务器摘要，收齐前累积）、`PENDING`（按种类：尚待接收数）、`cacheName`、`version`、`database`。种类数为 `MapSyncData.KIND_COUNT`（轨道、轨道形状、线路、停车轨等）。

### `static void open()`
- 作用：当前世界/维度的缓存名变化时 `load()`；以玩家所在区块开始地形扫描；请求服务器的地图摘要（`requestMapRailsC2S`）。

### `static void writeTiles(Map<Long, int[]> tiles)`
- 在数据库线程写瓦片并提交。

### `static void receiveDigest(int kind, boolean isLast, long[] keys, long[] hashes)`
- 作用：累积摘要；最后一批到达时：删除摘要中已没有的本地条目（同时删库）；对哈希不同或缺失的键分批发 `sendMapWantedC2S`；记下待收数；没有需要的就立即 `finish`。

### `static void receiveData(int kind, long key, long hash, long[] payload)`
- 作用：存入内存并写库；待收数减到 0 时（或不在等待状态时）`finish`。

### `private static void finish(int kind)`
- `publish` 后提交数据库。

### `static int getVersion()`
- 每次发布地图数据都会变的数字，编辑器网络视图据此决定是否重载。

### `private static void publish(int kind)`
- 作用：`version++`，把该种类数据转成客户端结构：轨道 → `ClientData.MAP_RAILS`（每 `MapSyncData.RAIL_LONGS` 个 long 一条：两节点、交通方式与其他属性）；轨道形状 → `ClientData.MAP_RAIL_SHAPES`（`节点1, 节点2, 点数, 打包坐标…`，经 `RailShapes.unpackX/Z`）；线路/停车轨 → `ClientData.MAP_ROUTES`/`MAP_SIDINGS`（键 → 节点数组）。

### `private static String getCacheName()`
- 每个世界一个文件夹、每个维度一个文件：本机世界按存档文件夹名（`saves-<文件夹>`，不同存档可能同名），服务器按地址（`server-<ip>`），都经 `getSafeName`。

### `private static File getCacheDirectory()`

### `private static void load()`
- 作用：清空内存与地形；在数据库线程（同步等待）关闭旧库、删除旧版直接放在根目录下的文件、打开新库并读出条目（失败则换成空库）；把读出的条目放入内存并全部发布（先显示磁盘上已有的，再问服务器，已有的不会重传）；地形瓦片随后在后台读入。

### `private static void replaceDatabase(Path path)`
- 删除读不了的文件并新建；没有 SQLite（LinkageError）时地图照常工作，只是不保存。

### `private static void deleteOldFiles(Path directory)`
- 删除缓存根目录下的普通文件（旧格式以哈希命名，同名世界会共用）。

### `private static void closeDatabase()` / `private static void runOnDatabase(DatabaseTask task)`
- 关闭；在数据库线程执行任务（异常打印）。

## 私有函数式接口 `DatabaseTask`
- `void run() throws Exception`。

## 私有内部类 `Entry`
- `hash`、`payload`（复制保存）。
