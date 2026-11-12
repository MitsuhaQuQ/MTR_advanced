# DataCache.java

由 `RailwayData`（服务端）或 `ClientData`（客户端，子类 `ClientCache` 覆盖 `syncAdditional`）中的集合构建的查询索引：按 id 查对象，以及对象之间的关系（站台所属车站、停车线所属车厂、车站之间的连接等）。这里的内容都不存档；集合变化后必须调用 `sync()`。本分支加了：版本号、网络设置 `networkSettings`、按 id 映射的只读拷贝（供后台找路）、站台归属车站的确定性规则（区域重叠时按高度和最近站台）、多矩形/高度区域、按网格加速的停车线归属车厂、线路颜色/联运班次/车厂共享班次的同步，以及 `PlatformSpeeds`/`PlatformStops` 的更新。`RailwayData.java:186` 构造；`sync()` 在 `RailwayData.java:458`、`:748`、`:1333` 等处调用。

## class DataCache

### 字段
- `lastRefreshedTime`：上次 `sync` 的时间（毫秒）。
- 按 id 的映射（public final）：`stationIdMap`、`platformIdMap`、`sidingIdMap`、`routeIdMap`、`depotIdMap`、`liftsServerIdMap`。
- `networkSettings`（public final）：本维度整个网络的设置（优化第 4 轮）：服务端自己的，或服务端发给客户端的。
- 关系：`platformIdToStation`、`sidingIdToDepot`、`routeIdToOneDepot`（每条线路取一个车厂）、`stationIdToConnectingStations`（区域相交的车站）、`blockPosToStation`（方块 → 车站的缓存）。
- `overlappingPlatforms`：处于多个车站区域（在其高度上）且未手动指定车站的站台，及按最近站台给出的车站；`platformsByIdAlone`：其中没有任何候选车站有可参考站台、只能按 id 选择的站台。
- `blockPosToPlatformId`：方块 → 站台 id 缓存（`sync` 时清空）。
- `platformConnections`：站台中点 → 站台中点 → `ConnectionDetails`（路线查找用）。
- 源集合（protected）：`stations`、`platforms`、`sidings`、`routes`、`depots`，私有 `liftsServer`。
- `version`：每次 `sync` 递增，持有拷贝者据此知道要重新查看。
- `stablingRails`：停留闭塞的轨道 → 闭塞 id（"运用补完"第 4 步，`SecurityConfig.stablingColor`），只在找路用的拷贝中设置，其它地方为空映射。
- 常量：`HEIGHT_WEIGHT = 4`（public，找最近站台时高度差的权重：上下叠放的车站水平位置相近而高度不同）；`AREA_CELL_BITS = 8`（网格 256 方块）；`MAX_AREA_CELLS = 1024`（超过此格数的区域不进网格，单独检查）。

### `public DataCache(Set<Station> stations, Set<Platform> platforms, Set<Siding> sidings, Set<Route> routes, Set<Depot> depots, Set<LiftServer> lifts)`
- 作用：保存源集合引用（不复制）。调用者：`RailwayData.java:186`。

### `public int getVersion()`
- 调用者：`Depot.java:2537`、`RailwayData.java:648`、`RailwayDataPathGenerationModule.java:113`。

### `public DataCache copyIdMaps()`
- 作用：当前按 id 映射与站台→车站的拷贝（对象本身不复制），供服务器线程外的工作（找路）使用，避免看到 `sync` 中途被清空的映射；拷贝从不 `sync`。也带上 `stablingRails`。
- 调用者：`RailwayData.java:1063`、`:1138`、`SandboxRuns.java:272`。

### `public static Map<BlockPos, Map<BlockPos, Rail>> copyRails(Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：两层映射的浅拷贝（轨道对象相同——轨道总是重建而不是修改）。
- 调用者：`RailwayData.java:1170`、`:1181`、`SandboxRuns.java:271`。

### `public final void sync()`
- 作用：版本 +1，然后（异常只打印堆栈）：
  1. 重建按 id 映射；`PlatformSpeeds.update(this, platforms)`（第 3b 轮）、`PlatformStops.update(this, platforms)`（第 4 轮）；
  2. `Route.applyLineColors(routes)`；清空并重新登记各线路被其它线路时刻表"联运"（links）的班次；
  3. 删除线路中已不存在的站台；车厂删除不存在的线路，建立 `routeIdToOneDepot`；清空并重新计算车厂间共享班次（有共同线路的车厂 `shareServicesWith`，使每个班次只由一列车运行）；
  4. 由车厂的 `platformTimes` 建立 `platformConnections`（相邻两站、时间 > 0；末站补空条目）；
  5. 车站相交关系：有角点的车站按最小 x 排序后扫描；
  6. `mapPlatformsToStations()`；停车线 → 车厂（`mapSavedRailIdToStation`）；
  7. 清空方块缓存；`syncAdditional()`。
  最后记录 `lastRefreshedTime`。
- 调用者：`RailwayData.java:458`、`:748`、`:1333`。

### `public boolean needsRefresh(long cachedRefreshTime)`
- 返回：自给定时间以来是否 `sync` 过。调用者：`IPIDS.java:86`、`BlockPSDTop.java:196`。

### `protected void syncAdditional()`
- 空钩子，客户端子类扩展。

### 嵌套映射辅助（静态）
- `tryGet(Map<T, Map<T, U>> map, key1, key2, defaultValue)` / `tryGet(map, key1, key2)`：两层查找。
- `tryGet(Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<U>>, long, long)`：同上（fastutil）。
- `tryGet2(Long2ObjectOpenHashMap<Long2IntOpenHashMap>, long, long, int defaultValue)`。
- `put(Long2ObjectOpenHashMap<...>, long, long, Function<U, U>)` / `put2(...)`：两层写入，以旧值计算新值。
- 调用者：`SimExport.java:102`、`RailwayData.java:916`、`RailwayDataRouteFinderModule.java:126/171`。

### `protected static <U extends NameColorDataBase> void mapIds(Map<Long, U> map, Set<U> source)`
- 清空并按 id 填入。调用者：本类、`ClientCache.java:124`。

### `private void mapPlatformsToStations()`
- 作用：给每个站台确定车站：手动设置的（无论在哪）；否则区域包含它的车站（区域有高度时按其高度）；多个候选时取"已知站台（手动设置或只在一个车站区域内的）离它最近"的车站（高度乘 `HEIGHT_WEIGHT`），不会循环依赖；与数据顺序无关（按 id 排序）。多候选的站台记入 `overlappingPlatforms`；候选都没有已知站台的记入 `platformsByIdAlone`。

### `private static List<Station> getCandidates(List<Station> sortedStations, BlockPos pos)`
- 返回：水平包含该位置的车站（按 id）；多于一个时，进一步取在其高度上包含的；都不包含高度则返回水平候选。

### `private Station nearestByPlatforms(List<Station> candidates, BlockPos pos, Map<Station, List<BlockPos>> platformsOf)`
- 返回：拥有离该位置最近站台的车站；无可参考或距离相同取 id 最小者（无参考时取第一个候选）。

### `private void noteByIdAlone(long platformId, boolean byIdAlone)`
- 为真时加入 `platformsByIdAlone`。

### `public static double weightedDistance(BlockPos a, BlockPos b)`
- 返回：高度差 × 4 的欧氏距离。

### `public Station getStationAt(BlockPos pos)`
- 作用：方块所属车站（车站名牌、标志、PIDS、闸机）：若旁边有站台则用该站台的车站（使站台旁的方块显示站台所设车站，上下叠放的车站各显示自己的）；否则区域包含它的车站中站台最近的；无则 `null`。结果按位置缓存到下次 `sync`。
- 调用者：`RailwayData.java:1567`；测试 `PlatformStationTest.java:100`。

### `private static <U extends SavedRailBase, V extends AreaBase> void mapSavedRailIdToStation(Map<Long, V> map, Set<U> savedRails, Set<V> areas)`
- 作用：为每条停车线找所属车厂（名字沿用原版"Station"）。把区域的矩形放入 256 方块网格（每格按集合顺序列出覆盖它的区域，保证选中的总是同一个）；过大的区域单独列出。对每条停车线中点：先找第一个"有高度且在其高度上包含"的区域（上下叠放的车厂，2026-11-09），否则第一个水平包含的；须同交通方式。

### `private static <...> int getFirstMatch(List<V> areaList, List<Integer> indices, U savedRail, BlockPos pos, boolean atHeight)`
- 返回：候选下标中第一个匹配的；无则 `Integer.MAX_VALUE`。

### 其它私有辅助
- `addOnce(List<Integer>, int)`：与末尾不同时追加。
- `getCellKey(int, int)`：格坐标打包。
- `getMinX(AreaBase)` / `getMaxX(AreaBase)`：所有矩形的 x 范围。
