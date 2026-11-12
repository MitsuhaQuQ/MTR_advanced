# RunningSides.java

世界中双线轨道每条线按"行车方向（左行/右行）"所走的方向（用户 2026-11-12；计算在组件 `RunningDirections`，左右行设置在 `NetworkSettings#runningSides`）：对线路路径经过的、双向可通行的轨道，结合其上运行的线路，算出每条轨道的通常运行方向。轨道、线路路径或设置变化时重算，供列车路径使用（`SimExport#toTracks` 把方向约定写进组件轨道段；`PathClaims` 也读取）。

## final class RunningSides

### 字段
- `CACHE`：`WeakHashMap<RailwayData, Cached>`（同步包装），每个维度的铁路数据一份缓存。
- `BY_RAILS`：`WeakHashMap<轨道表, 方向表>`，按轨道表对象记录最新结果，供只拿得到轨道表的地方使用。

### `private RunningSides()`
工具类，禁止实例化。

### private static final class Cached
- `key`：缓存键字符串；`directions`：结果。构造函数仅赋值。

### `public static Map<UUID, Integer> get(RailwayData railwayData)`
- 作用：取得（必要时重算）方向表。缓存键为 `轨道节点数:routePaths.getTimingVersion():线路数:runningSides.hashCode():lineTracks.hashCode()`（注意：只用节点数而非轨道内容，轨道变化但节点数不变时依赖 `timingVersion` 变化，未确定是否总能覆盖）。
- 返回：轨道键（`RailKeys.of`）→ 1（列车从较小节点走向较大节点）或 −1（反向）；不可变映射。
- 副作用：更新 `CACHE`、`BY_RAILS`。
- 主要调用者：`SimExport.java:46`。

### `public static Map<UUID, Integer> get(Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 返回：为该轨道表最近算出的结果；没有则空映射。
- 主要调用者：`SimExport.java:99`。

### `public static int getConvention(Map<UUID, Integer> directions, BlockPos from, BlockPos to)`
- 返回：路径沿 `from→to` 走这条轨道时与线路行车方向的关系（组件 `RunTrack#getConvention()`）：无记录 `CONVENTION_NONE`；方向一致 `CONVENTION_WITH`；相反 `CONVENTION_AGAINST`。
- 主要调用者：`SimExport.java:107`、`PathClaims.java:206`。

### `private static Map<UUID, Integer> compute(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails, NetworkSettings settings)`
- 作用：遍历所有线路的路径（已找到的 `RoutePath` 优先，否则 `route.getPathData()`），收集每条轨道上运行的线路键及其两端；只保留两个方向都存在且非 `NONE` 的轨道（双向轨道）；该轨道上所有线路的行车方向一致时取之，否则为 0；生成 `RunningDirections.Rail(railKey, 两端, 采样点, 线路集合, side)` 交给 `RunningDirections.compute`。

### `private static double[] points(Rail rail)`
- 作用：按 `RunningDirections.SAMPLE_SPACING` 间距在轨道上取样，返回 `x,z,x,z,...`。
