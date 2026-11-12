# PathBoundaries.java

玩家为寻路切分轨道网络的边界（设计文档 5.13），节点以 `BlockPos#asLong()` 表示：即组件 `Boundaries` 的游戏侧子类，负责存档和发包。仪表盘和编辑器使用由它生成的分区（regions）。与站台的"时刻表边界"无关。被 `RailwayData`（保存与同步）、`Depot`（车厂自己的分区边界）、`PathRegionSync`、`RegionDataPackets` 使用。

## final class PathBoundaries extends Boundaries

### 常量
- `MAX_BOUNDARIES = 65536`：读包时节点数和轨道段数各自的上限（防恶意包）。

### 继承字段（来自组件 `Boundaries`）
- `nodes`：边界节点集合（long）。
- `segments`：边界轨道段集合（UUID，见 `segmentKey`）。

### `public static UUID segmentKey(long pos1, long pos2)`
- 作用：轨道段键，等价 `RailKeys.of(pos1, pos2)`（是否与方向无关见 `RailKeys`，此处未确定）。
- 主要调用者：`RailwayData.java:109`、`RailwayData.java:1299`、`NodeAngleScreen.java:270`。

### `public PathBoundaries copy()`
- 作用：浅拷贝节点与段集合到新对象。
- 主要调用者：`PathRegionSync.java:56`、`Depot.java:2758`、`Depot.java:2919`。

### `public void write(FriendlyByteBuf packet)` / `public static void write(Boundaries boundaries, FriendlyByteBuf packet)`
- 包字段顺序：`int 节点数, long×节点数, int 段数, UUID×段数`。
- 主要调用者：`RegionDataPackets.java:20`、`PathRegionSync.java:96`、`Depot.java:623`。

### `public static PathBoundaries read(FriendlyByteBuf packet)`
- 作用：按上面顺序读取；数量截断到 `MAX_BOUNDARIES`（注意：截断后剩余字节不会被跳过）。
- 主要调用者：`RegionDataPackets.java:32`、`Depot.java:473`。

## public static final class Entry extends SerializedDataBase
存档中的一条边界，要么是一个节点，要么是一段轨道。

### 字段与存档键
- `node`（long）：节点；段条目为 0。
- `segment`（UUID）：轨道段；节点条目为 `null`。
- 键：节点条目 `"pos" → long`；段条目 `"pos_1" → segment 高 64 位`、`"pos_2" → 低 64 位`。

### 构造函数
- `Entry(long node)`、`Entry(UUID segment)`：直接构造。
- `Entry(Map<String, Value> map)`：读存档：有 `pos_1` 则为段条目，`segment = segmentKey(pos_1, pos_2)`；否则节点条目。注意写入的是 UUID 的高低位、读回时又经 `segmentKey(...)` 计算；两者是否互逆取决于 `RailKeys.of` 的实现，未确定。
- 主要调用者：`RailwayDataFileSaveModule.java:306`、`:307`、`:349`。

### `public long getId()`
- 返回：存档中的 id：节点条目为 `node`，段条目为 `msb*31 + lsb`。只需在同类条目中唯一。
- 主要调用者：`RailwayDataFileSaveModule.java:307`、`:352`。

### `toMessagePack(MessagePacker)` / `messagePackLength()`
- 按上面的键写入；长度节点条目 1、段条目 2。

### `writePacket(FriendlyByteBuf)`
- 空实现（边界通过 `PathBoundaries.write` 整体发包）。
