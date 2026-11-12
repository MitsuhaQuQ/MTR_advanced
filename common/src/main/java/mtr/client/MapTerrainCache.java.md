# MapTerrainCache.java（本分支新增）

客户端：记录看到过的区块的地图颜色，使仪表板地图不受客户端已加载区块的限制。打开仪表板时以玩家为中心由近及远每帧读取少量已加载区块，有变化的替换存储并写入 `MapDataCache` 的文件。调用者：`common/src/main/java/mtr/client/MapDataCache.java:64`（`startScan`）、`:214`（`clear`）、`:242`（读文件时 `addSavedTile`），`common/src/main/java/mtr/screen/WidgetMap.java:167`（`scan`）、`:176`（`getColor`）。

## 类 `MapTerrainCache`（final，私有构造）

### 静态字段与常量
- `TILES`（`ConcurrentHashMap<区块键, int[16×16 颜色]>`）、`SCAN_QUEUE`、`SCAN_RADIUS = 32`（区块）、`CHUNKS_PER_FRAME = 16`。

### `static void clear()`

### `static void addSavedTile(long key, int[] colors)`
- 来自文件的区块；若读文件期间已扫描到更新的数据则保留新的（`putIfAbsent`）。

### `static void startScan(int centerChunkX, int centerChunkZ)`
- 以中心区块起按方环由近到远排入扫描队列（半径 32）。

### `static void scan(Level world)`
- 作用：每帧绘制地图时调用；从队列中取，未加载的区块跳过（几乎无开销），最多读 16 个已加载区块，读每列顶部方块颜色；有变化的放入缓存，并批量 `MapDataCache.writeTiles` 保存。

### `static int getColor(Level world, int x, int z)`
- 已加载则直接读世界；否则用缓存；从未见过为 0。

### `private static boolean isLoaded(Level world, int chunkX, int chunkZ)`
- `getChunkSource().getChunkNow(...) != null`（客户端上 `hasChunk` 总为真，不能用）。

### `private static int readColor(Level world, int x, int z)`
- 运动阻挡高度图顶部方块的默认地图颜色。
