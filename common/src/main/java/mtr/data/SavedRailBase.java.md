# SavedRailBase.java

`Platform` 与 `Siding` 的基类：保存在两个节点之间轨道上的数据，带停站时间。本分支的改动主要是性能：两端位置数组和中点在首次请求时计算并缓存（位置不会变，而列车与车厂每 tick 都要查询）。继承 `NameColorDataBase`（id、名称、颜色、交通方式）。

## abstract class SavedRailBase extends NameColorDataBase

### 字段与常量
- `dwellTime`（protected）：停站时间，单位为 0.5 秒（推测自 `PathTiming` 中 `dwellTime × 10` tick；未确定）。连续运动交通方式恒为 1。
- `positions`：两端位置的集合（`HashSet`，大小 2）。
- `positionArray`、`midPos`、`midPosZeroY`：惰性缓存。`positionArray` 保持集合原有迭代顺序（与以前行为一致）。
- `MAX_DWELL_TIME = 1200`（public）；`DEFAULT_DWELL_TIME = 20`。
- 存档键：`pos_1`、`pos_2`（long）、`dwell_time`（int）。

### 构造函数
- `SavedRailBase(long id, TransportMode, BlockPos pos1, BlockPos pos2)` / `SavedRailBase(TransportMode, BlockPos, BlockPos)`：名称默认 `"1"`，停站时间默认 20（连续运动为 1）。
- `SavedRailBase(Map<String, Value> map)`：读 msgpack。
- `SavedRailBase(CompoundTag)`（已弃用）：读旧 NBT。
- `SavedRailBase(FriendlyByteBuf packet)`：读基类、两个 `BlockPos`、`int dwellTime`。

### `toMessagePack` / `messagePackLength` / `writePacket`
- 存档：基类 + `pos_1`、`pos_2`、`dwell_time`（长度 +3）。
- 包字段顺序：基类字段，`BlockPos pos(0), BlockPos pos(1), int dwellTime`。

### `protected final boolean hasTransportMode()`
- 返回 `true`（基类据此决定是否读写交通方式）。调用者：`NameColorDataBase.java:109`。

### `public boolean containsPos(BlockPos pos)`
- 是否为两端之一。调用者：`Depot.java:1394`、`RailwayData.java:1472`。

### `public BlockPos getMidPos()` / `getMidPos(boolean zeroY)`
- 返回：两端坐标和整除 2 的中点（`zeroY` 时 y=0），缓存。调用者：`PathRegionSync.java:116`、`ClientCache.java:510`。

### `public Direction.Axis getAxis()`
- 返回：x 差绝对值更大时 `X`，否则 `Z`。脚本未找到调用者。

### `public boolean isInvalidSavedRail(Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 返回：两个方向中任一方向的轨道不存在或其类型不允许保存（`!railType.hasSavedRail`）。
- 调用者：`RailwayData.java:1443`、`RailwayData.java:1778`。

### `public boolean isCloseToSavedRail(BlockPos pos, int radius, int lower, int upper)`
- 返回：`pos` 是否在两端包围盒水平外扩 `radius`、向下 `lower`、向上 `upper` 的范围内。
- 调用者：`RailwayData.java:679`、`RailwayData.java:1587`。

### `public List<BlockPos> getOrderedPositions(BlockPos pos, boolean reverse)`
- 返回：两端按到 `pos` 的距离排序（近的在前；`reverse` 时远的在前；距离相等时的顺序取决于 `d2 > d1 == reverse`）。常用 `BlockPos.ZERO` 作为固定参照得到稳定顺序。
- 调用者：`RailwayData.java:108`、`RailwayData.java:1202`、`PathRegionSync.java:102`。

### `public BlockPos getOtherPosition(BlockPos pos)`
- 返回：另一端（`pos` 不是 pos(0) 时返回 pos(0)）。调用者：`PathFinder.java:180`。

### `public int getDwellTime()`
- 作用：越界（≤0 或 >1200）时先改回默认值 20；连续运动返回 1。
- 调用者：`Depot.java:1438`、`Depot.java:1522`、`Depot.java:3249`。

### `protected void writeDwellTimePacket(FriendlyByteBuf packet, int newDwellTime)`
- 作用：规范化后写入本地 `dwellTime` 并写进包（连续运动为 1，越界为 20）。
- 调用者：`Platform.java:196`、`Siding.java:693`。

### `private BlockPos getPosition(int index)`
- 作用：按集合迭代顺序取第 index 个位置（缓存数组）；不足时返回 (0,0,0)。

### `public static boolean isInvalidSavedRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos1, BlockPos pos2)`
- 返回：`pos1→pos2` 轨道不存在或类型不允许保存。

### `public int compareTo(NameColorDataBase compare)`
- 排序：两者名称都是数字时按数值（相等再按基类）；数字名排在非数字名前；都不是数字时按基类。
