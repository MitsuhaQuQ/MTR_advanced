# RouteLayouts.java

高级编辑器中整条线路的股道图（设计文档 6.12，D 块）：沿线路路径的轨道，由组件的 `Straightener` 拉直，使线路从左向右延伸。在服务器线程之外、基于轨道快照计算。服务端 `RailwayData.java:1159` 调 `write`（`PacketTrainDataGuiServer.java:610` 先 `allowRequest` 限流），客户端 `PacketTrainDataGuiClient.java:311` 调 `read` 后交给 `EditorLayout.receiveRoute`。

## final class RouteLayouts

### 常量与字段
- `DEFAULT_HALF_WIDTH = 24`、`MIN_HALF_WIDTH = 8`、`MAX_HALF_WIDTH = 64`（public）：路径两侧绘制轨道的半宽（方块）。
- `MAX_POINTS = 60000`：点数上限（也作为各计数的上限）。
- `MAX_RAIL_POINTS = 48`：每条轨道的分段上限。
- `POINT_SPACING = 3`：取样间距。
- `MIN_REQUEST_INTERVAL_MILLIS = 1000`；`LAST_REQUESTS`：玩家 → 上次请求时间。

### `private RouteLayouts()`
工具类，禁止实例化。

### `public static boolean allowRequest(UUID player)`
- 返回：距上次请求不足 1 秒时 `false`，否则记录并 `true`。调用者：`PacketTrainDataGuiServer.java:610`。

### `public static void write(long routeId, RailGraph<Rail> graph, long[] nodes, List<long[]> stops, List<long[]> platforms, int halfWidth, Map<UUID, Integer> statuses, Set<Long> boundaryNodes, FriendlyByteBuf packet)`
- 参数：`nodes` 线路路径的节点序列（未找到路径时为空）；`stops` 每个停站的站台 `{id, 端点, 端点}`；`platforms` 该交通方式所有站台 `{id, 端点, 端点}`；`halfWidth` 半宽；`statuses` 轨道键 → 隔离状态（`RoutePaths.RAIL_TAKEN`/`RAIL_RELEASED`/`RAIL_BOUNDARY`）；`boundaryNodes` 寻路边界节点。
- 作用：
  1. 沿路径（每段取图中连接的轨道取样，找不到连接则直线）拼成路径折线并记录每个节点的累计距离；构造 `Straightener(path, halfWidth)`。
  2. 每个停站的位置：按顺序在路径上找到其站台轨道，取离开站台处的距离（换算到拉直长度）；找不到为 −1。
  3. 只保留与路径首节点沿轨道连通的轨道（排除上方或下方穿过的线）；每条轨道只取一次，中点在路径附近（半宽 + 半长）时，取样并拉直成若干片段（累计点数超过上限则截断），记录其隔离状态与两端。
  4. 三条及以上轨道相交的节点投影为道岔点（按距离排序）。
  5. 连通站台的两端投影，跨度小于 4 倍半宽时记为站台线。
  6. 连通的寻路边界节点投影为点。
- 包字段顺序：`long routeId, int halfWidth, boolean found`；未找到路径时到此为止。否则：`float length, boolean truncated, int n + float×n 停站距离, int 片段数 + (short 点数 + float×点数×2)×片段数, int 道岔数 + (float, float)×道岔数, int 站台数 + (long id, float×4)×站台数, (byte 隔离, long 节点, long 节点)×片段数, int 边界点数 + (float, float)×边界点数`。所有坐标为（沿线距离, 横向偏移）。
- 调用者：`RailwayData.java:1159`；测试 `RouteLayoutsTest.java:42`。

### `public static Layout read(FriendlyByteBuf packet)`
- 作用：按上述顺序读取；计数越界（停站 > 4096、片段/点数/道岔/站台/边界 > 60000、每片点数 > 49）抛 `IllegalArgumentException("Bad count")`。未找到路径时返回 `found = false` 的空布局。

### `public static float[] sample(Rail rail)`
- 返回：沿轨道每约 3 方块一点（1…48 段）的 `x, z, ...`。

### `private static Set<Long> connectedNodes(RailGraph<Rail> graph, long start)`
- 作用：广度优先，沿连接两个方向（单向轨道物理上仍相连）可达的节点。

### 私有辅助
- `straight(long node1, long node2)`：两节点中心的直线 `{x1, z1, x2, z2}`。
- `x(long node)` / `z(long node)`：节点方块中心坐标（+0.5）。
- `toArray(List<Float>)`：转数组。
- `limit(int count, int maximum)`：校验计数。

## public static final class Layout
- `routeId`、`halfWidth`、`found`（未找到路径为 false）、`length`、`truncated`。
- `stops`：每个停站沿路径的距离，或 −1（路径不经过其站台）。
- `pieces`：轨道片段 `距离, 偏移, ...`。
- `turnouts`：道岔 `(距离, 偏移)`。
- `platforms`：站台 id → 两端 `(距离, 偏移, 距离, 偏移)`。
- `keys`：每个片段对应的轨道 `{隔离状态, 节点, 节点}`，用于从股道图修改隔离。
- `boundaryNodes`：寻路边界节点 `(距离, 偏移)`。
- 构造函数私有，仅 `read` 使用。
