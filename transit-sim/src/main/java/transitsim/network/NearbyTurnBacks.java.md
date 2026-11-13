# NearbyTurnBacks.java

站台附近列车能折返的地方（线路没列出折返点时用）：可达范围内折返线的尽头，最近的在前；之后每个留置区一个节点（运用补完第 4 步）。2026-11-13 从游戏的车厂路径构建（`mtr.data.DutyPathBuilder`，原 `Depot.MainPath`）原样搬进组件（拆分车厂第 2a 步），规则和结果不变，能在组件里测试。节点是按 Minecraft 方式打包的方块坐标（x 高 26 位、z 中间 26 位、y 低 12 位），距离是到站台中心的平方。

调用者：游戏 `DutyPathBuilder.nearbyOf`（`getNearbyTurnBackNodes`、`withStabling`）。

## 类 `NearbyTurnBacks`（final）

### 构造函数 `NearbyTurnBacks(List<long[]> turnBackRails, LongToIntFunction degree, ToLongBiFunction<Long, Long> stablingBlockOf)`
- `turnBackRails`：所搜轨道上的每条折返线（两端节点），按宿主列出的顺序。
- `degree`：从节点出去的轨道数，没有为 0；1 就是尽头。
- `stablingBlockOf`：两节点间的轨道属于哪个留置区，不属于返回 `NO_BLOCK`（`Long.MIN_VALUE`）。

### `synchronized Result near(long platformId, long center, int reach, int max)`
- 按站台 ID 只算一次并记下（第 5 轮第 3 步的缓存，原来在车厂里）。
- 规则：近端在 `reach` 格内的折返线；在留置区里的按区归组，否则它的尽头端点进候选；尽头按距离、同距离按打包坐标排序，取前 `max` 个；每个留置区取一个节点（`stablingNode`），不重复，同样排序取前 `max` 个，接在后面。
- 返回 `Result`：`nodes`（全部，折返尽头在前）、`stabling`（只是留置区的节点）。

### `static long stablingNode(List<long[]> blockRails, long center, LongToIntFunction degree)`
留置区里列车折返的地方：区内折返线的尽头（最近的），没有就是离站台最远的端点（列车从站台驶入）。

### `static boolean isBefore(long pos, long other, long center, boolean furthestFirst)`
近的在前（`furthestFirst` 时远的在前），一样远按打包坐标。

### `static double distSqr(long pos, long other)`、`x`、`y`、`z`、`pack`
打包坐标的平方距离（按整块坐标，和 Minecraft 1.20 的 `BlockPos.distSqr` 一样；旧版本 MC 的 `distSqr` 带 0.5 偏移，只影响等距时的先后，现在只构建 1.20.1）和拆包、打包。

## 测试
`NearbyTurnBacksTest`：打包坐标往返（含负数）、范围内尽头按近到远取前几个、留置区取最近尽头否则最远端点并按站台缓存、等距按打包坐标。
