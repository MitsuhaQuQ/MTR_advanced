# EditorLayout.java

高级编辑器股道图的数据（设计文档 6.12，D 块）：一个车站周围的轨道，以及一条线路的"拉直"股道图。车站方框围绕股道选择器提供的该站股道（它们在同一组轨道上）而建，因此车站区域内另一条线的站台不会把方框撑大。由于客户端只有玩家附近的完整轨道，方框内轨道的真实形状由服务器发送（`StationLayouts`）；线路股道图由服务器的 `RouteLayouts` 生成。页面会反复请求，直到数据到达。客户端代码，`EditorServer.java:626`（`build`）、`EditorServer.java:586` 与 `EditorData.java:130`（`buildRoute`）调用；`PacketTrainDataGuiClient`（`:300`、`:312`）收到回应时调用 `receive`/`receiveRoute`。

## final class EditorLayout

### 常量与静态状态
- `DEFAULT_MARGIN = 48`、`MIN_MARGIN = 16`、`MAX_MARGIN = 256`：方框外扩（方块）。
- `REQUEST_AGAIN_MILLIS = 3000`：未收到回应时重发请求的间隔。
- `latest`：最近收到的车站布局；`requested`、`requestedMillis`：最近请求的键与时间。
- `latestRoute`、`latestRouteMillis`：最近收到的线路布局与收到时间；`requestedRoute`、`requestedRouteMillis`：最近线路请求。

### `private EditorLayout()`
工具类，禁止实例化。

### `public static void receive(StationLayouts.Layout layout)`
- 作用：保存服务器对最近一次方框请求的回应。调用者：`PacketTrainDataGuiClient.java:300`。

### `public static void receiveRoute(RouteLayouts.Layout layout)`
- 作用：保存线路布局并记下收到时间。调用者：`PacketTrainDataGuiClient.java:312`。

### `public static JsonObject buildRoute(long routeId, int requestedHalfWidth, boolean fresh)`
- 作用：线路的拉直股道图（`RouteLayouts`），每次页面打开时重新请求，显示当前轨道。
- 参数：`requestedHalfWidth` 半宽，限制在 `[RouteLayouts.MIN_HALF_WIDTH, MAX_HALF_WIDTH]`，≤0 用默认；`fresh` 页面刚打开。
- 请求：`fresh`、键（`routeId|halfWidth`）变化、或尚无回应且超过 3 秒时发 `requestRouteLayoutC2S`。
- 返回：回应不匹配或早于请求时 `{ready: false}`；否则 `{ready, found, halfWidth, length, truncated, stops[] (距离), rails[] ([距离, 偏移, ...]), railKeys[] ([节点1, 节点2, 隔离状态])，boundaryNodes[], turnouts[] ([距离, 偏移]), platforms[] ({id, name, station, stationName, passingTrack, line: [d, o, d, o]})}`。数值四舍五入到 0.1。隔离状态：0 普通，1 被连接线占用，2 已释放，3 寻路边界。

### `private static JsonArray round(float[] values)`
- 每个值四舍五入到 0.1。

### `public static JsonObject build(long stationId, int requestedMargin, List<Long> trackIds)`
- 参数：`stationId` 车站；`requestedMargin` 外扩（限制在 16–256，≤0 为 48）；`trackIds` 选择器提供的股道（站台 id），为空则用车站全部站台。
- 作用：用这些站台端点求包围盒并外扩，经 `StationLayouts.clampBox` 限制；种子 = 这些站台的端点（去重、排序、最多 `MAX_SEEDS`）。回应的方框、交通方式、种子都匹配时返回数据，否则（每 3 秒或键变化时）发 `requestStationLayoutC2S` 并返回 `{ready: false}`。
- 返回：没有该车站或无站台时 `null`；否则 `{ready: true, box[4], truncated, rails[] ([x, z, ...] 0.1 精度), turnouts[] ([x, z]，三条及以上轨道端点相遇处，按半方块精度合并), platforms[] (方框内同交通方式的所有站台 {id, name, station, stationName, passingTrack, line: [x1+.5, z1+.5, x2+.5, z2+.5]})}`。

### `private static List<BlockPos> ends(Platform platform)`
- 站台两端（`getOrderedPositions(BlockPos.ZERO, false)`）。

### `private static long key(float x, float z)`
- 端点按半方块取整打包成 long（高 32 位 `round(2x)`，低 32 位 `round(2z)`），用于判断端点相遇。
