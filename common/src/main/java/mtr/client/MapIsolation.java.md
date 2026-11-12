# MapIsolation.java（本分支新增）

客户端：仪表板地图和网页编辑器全网视图所用的"轨道隔离"状态：寻路边界（`ClientData.PATH_REGIONS`），以及联络线沿其地图路径（`ClientData.MAP_ROUTES`，站台轨道除外）从其他线路占用或已释放的轨道。状态值与轨道示意图相同（`RoutePaths.RAIL_*`）。调用者：`common/src/main/java/mtr/client/editor/EditorNetwork.java:75`、`:81`，`common/src/main/java/mtr/screen/WidgetMap.java:622`。

## 类 `MapIsolation`（final，私有构造）

### `static Map<UUID, Integer> getStatuses(TransportMode transportMode)`
- 作用：对该交通方式的每条联络线，在其路径的"自身部分"（`getOwnRange`）中每段非站台轨道记为 `RAIL_RELEASED`（在其已释放列表中）或 `RAIL_TAKEN`，多条线路取较小值；最后寻路边界段一律记为 `RAIL_BOUNDARY`（边界优先）。
- 返回：轨道键（`RailKeys.of(节点1, 节点2)`）→ 状态。

### `private static int[] getOwnRange(Route route, long[] nodes)`
- 作用：联络线自身部分（与 `RoutePaths` 的取法一致）：有途经点时从第一个途经点到其后的最后一个途经点（没有则到最后一个岔口）；没有途经点时从首站后的第一个岔口到末站前的最后一个岔口。
- 返回：`{起始下标, 结束下标}`。

### `private static int lastFork(long[] nodes, int from)` / `private static boolean isFork(long node)`
- 最后一个岔口下标（没有则末节点）；节点相连轨道多于 2 条即岔口。

### `static Set<UUID> getPlatformRails()`
- 所有站台轨道的键。

### `private static Set<UUID> getReleased(Route route)`
- 联络线已释放轨道（`route.connectingReleased`）的键。
