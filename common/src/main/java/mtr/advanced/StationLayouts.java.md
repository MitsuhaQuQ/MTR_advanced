# StationLayouts.java

车站周围一个方框内轨道的真实形状，供高级编辑器的股道图使用（设计文档 6.12，D 块）。客户端只保存玩家附近的完整轨道，仪表盘地图上的轨道线是两端间的直线，会把弯曲的渡线画错，所以由服务器沿曲线取样每条轨道。请求与回应：客户端 `PacketTrainDataGuiClient`（`:294` 写种子、`:299` 读回应），服务端 `PacketTrainDataGuiServer`（`:588-610` 限流、裁剪方框、读种子、写回应）；`EditorLayout.java:163` 用 `clampBox`；`RouteLayouts` 是线路级的对应物。

## final class StationLayouts

### 常量与字段
- `MAX_SIZE = 1024`（public）：方框最大边长（方块）。
- `MAX_RAILS = 4000`：最多返回的轨道条数，超出标记截断。
- `MAX_POINTS = 48`：每条轨道最多分段数（点数 = 段数 + 1）。
- `MAX_SEEDS = 256`（public）：一次请求最多可给的站台端点数。
- `POINT_SPACING = 3`：取样间距（方块）。
- `MIN_REQUEST_INTERVAL_MILLIS = 150`：每个玩家请求间隔下限。
- `LAST_REQUESTS`：玩家 UUID → 上次请求时间（同步访问）。

### `private StationLayouts()`
工具类，禁止实例化。

### `public static boolean allowRequest(UUID player)`
- 返回：距该玩家上次请求不足 150 ms 时 `false`；否则记下时间返回 `true`。
- 主要调用者：`PacketTrainDataGuiServer.java:591`、`:610`。

### `public static void write(Map<BlockPos, Map<BlockPos, Rail>> rails, int[] box, TransportMode transportMode, long[] seeds, FriendlyByteBuf packet)`
- 作用：写回应：先求从种子出发沿轨道（双向）可达的节点；对每条轨道（每对节点只取一次：起点 long 值 ≤ 终点）：交通方式一致、起点可达、包围盒放宽轨道长度后与方框相交，按间距取样（1…48 段），至少一个点在方框内才保留；超过 `MAX_RAILS` 时截断。这样只画与车站相连的轨道，不画从上方或下方穿过的线。
- 参数：`box = {minX, minZ, maxX, maxZ}`；`seeds` 车站站台端点（已排序，`BlockPos.asLong()`）。
- 包字段顺序：`int×4 box, int transportMode.ordinal, int n + long×n seeds, boolean truncated, int 轨道数`，每条轨道 `short 点数, float×(点数×2)`（x, z 交替）。
- 主要调用者：`PacketTrainDataGuiServer.java:598`。

### `public static Layout read(FriendlyByteBuf packet)`
- 作用：按上面顺序读取。
- 异常：轨道数 <0 或 >`MAX_RAILS`、点数 <0 或 >`MAX_POINTS+1`、种子数越界时抛 `IllegalArgumentException`。
- 主要调用者：`PacketTrainDataGuiClient.java:299`。

### `public static void writeSeeds(long[] seeds, FriendlyByteBuf packet)` / `public static long[] readSeeds(FriendlyByteBuf packet)`
- 格式：`int n, long×n`；读取时 n 必须在 `[0, MAX_SEEDS]`。
- 主要调用者：`PacketTrainDataGuiClient.java:294`（write）、`PacketTrainDataGuiServer.java:590`（read）。

### `private static Set<Long> connectedNodes(Map<BlockPos, Map<BlockPos, Rail>> rails, long[] seeds)`
- 作用：广度优先，从种子沿轨道两个方向（单向轨道在物理上仍相连）可达的节点集合（含种子本身）。
- 主要调用者：本类 `write`。（`RouteLayouts` 有自己基于 `RailGraph` 的同名私有方法。）

### `public static int[] clampBox(int minX, int minZ, int maxX, int maxZ)`
- 作用：把方框限制到最大 `MAX_SIZE`，以原中心为中心；负宽度视为 0。
- 返回：`{minX, minZ, maxX, maxZ}`。
- 主要调用者：`EditorLayout.java:163`、`PacketTrainDataGuiServer.java:588`。

## public static final class Layout
- `box`、`mode`（交通方式序号）、`seeds`（请求时的站台端点，用于把回应对应到请求）、`truncated`、`lines`（每条轨道 `x, z, x, z, ...`）。
- 构造函数私有，仅 `read` 创建。（`RouteLayouts` 有自己的同名 `Layout` 类。）
