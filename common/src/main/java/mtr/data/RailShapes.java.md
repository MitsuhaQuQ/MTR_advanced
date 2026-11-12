# RailShapes.java

地图用的轨道粗略形状：沿曲线取几个点，使长弯道在地图上不会画成两节点间的一条直线，而地图本身不必计算曲线。点在地图坐标系中（节点画在其方块中心）。服务端 `RailwayData` 把点打包进 `MapSyncData.KIND_RAIL_SHAPES` 条目（`RailwayData.java:1024`），客户端 `MapDataCache` 解包（`:178-179`）；`Rail.java:447` 也调用 `of`。

## final class RailShapes

### 常量
- `SAMPLE_SPACING = 2`：简化前的取样间距（方块）。
- `TOLERANCE = 0.5`（public）：画出的折线偏离轨道的最大距离（方块）。
- `MAX_POINTS = 64`（public）：每条轨道最多的点数。
- `MAX_SAMPLES = 4096`：取样数上限。
- `PACK_SCALE = 4`：打包精度为 1/4 方块。

### `private RailShapes()`
工具类，禁止实例化。

### `public static float[] of(Rail rail)`
- 作用：取样后用 Douglas–Peucker 简化，返回两节点之间（不含两端）的点。
- 返回：`x, z, x, z, ...`（从起点方向排序）；轨道长度不大于取样间距、或在容差内为直线时返回 `null`。点数超过 `MAX_POINTS` 时在保留点中均匀抽取。
- 细节：所有点加上 `BlockPosOffset`（把轨道起点平移到其方块中心），以对齐地图画节点的位置。
- 主要调用者：`Rail.java:447`、`MapDataCache.java:165`、`MapDataCache.java:181`。

### `private static void simplify(double[] xs, double[] zs, int start, int end, boolean[] kept)`
- 作用：Douglas–Peucker 递归：区间内离首尾连线最远的点若距离大于 `TOLERANCE` 就保留，并对两侧递归。结果写在 `kept`。

### `static double distanceToLine(double x, double z, double x1, double z1, double x2, double z2)`
- 返回：点到线段（不是直线）的距离；线段退化时为到端点距离。
- 主要调用者：本类；测试 `RailShapesTest.java:49`。

### `public static long pack(float x, float z)` / `unpackX(long)` / `unpackZ(long)`
- 作用：把一个点打包成 long：高 32 位 `round(x*4)`，低 32 位 `round(z*4)`；解包除以 4。
- 主要调用者：`RailwayData.java:1024`（pack）、`MapDataCache.java:178-179`（unpack）。

### private static final class BlockPosOffset
- 字段 `x`、`z`：从轨道第一个位置到其所在方块中心的偏移（`floor(v) + 0.5 - v`）。
