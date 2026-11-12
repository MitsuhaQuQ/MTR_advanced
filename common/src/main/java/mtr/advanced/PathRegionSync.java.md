# PathRegionSync.java

保持玩家手中的 `RegionData`（组件 `RegionComputer` 算出的寻路分区与站台图）为最新。每隔几秒在服务器线程上计算一个廉价指纹（轨道、边界、站台、停车线、线路），只有变化时才开始真正的计算，计算在单独的守护线程上进行，完成后回到服务器线程发给该维度所有玩家。由 `RailwayData` 持有（`RailwayData.java:267`），每 tick 调用 `tick`（`:642`），玩家加入时 `send`（`:902`），`getLatest().platformGraph` 供服务端使用（`:897`）。

## final class PathRegionSync

### 字段与常量
- `boundaries`：玩家设置的寻路边界（与 `RailwayData` 共享的对象）。
- `fingerprint`：上次计算时的指纹。
- `computed`：是否至少成功算过一次。
- `computing`：是否正在后台计算。
- `latest`：最新结果，初始 `RegionData.EMPTY`。
- `CHECK_INTERVAL_TICKS = 40`：检查间隔（2 秒）。

### `public PathRegionSync(PathBoundaries boundaries)`
- 主要调用者：`RailwayData.java:267`。

### `public void tick(Level world, RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：非计算中且到检查间隔时，算指纹；已算过且未变则返回。否则在服务器线程上准备输入：`DeterministicPathFinder.compile(rails)` 得到轨道图、边界拷贝、站台（带所属车站 id）与停车线的 `SavedRail` 列表；然后起守护线程 `RegionComputer.compute(...)`，完成后通过 `minecraftServer.execute` 回到服务器线程：清 `computing`，成功则更新 `latest`、置 `computed`，并向 `world.players()` 逐个 `send`。
- 副作用：起线程；发包；计算异常只打印堆栈，`computing` 照常清除、`latest` 不变。由于 `fingerprint` 已更新：若此前从未算成（`computed == false`）下次检查会重算；若之前算成过且指纹不再变化，则不会重试，保持旧结果。
- 主要调用者：`RailwayData.java:642`。

### `public RegionData getLatest()`
- 返回：最近一次算出的分区与网络。
- 主要调用者：`RailwayData.java:897`。

### `public void send(ServerPlayer player)`
- 作用：已算过时，用 `RegionDataPackets.write(latest, packet)` 编码，以 `IPacket.PACKET_PATH_REGIONS` 发给该玩家。
- 主要调用者：`RailwayData.java:902`、`RailwayData.java:1004`。

### `private static RegionComputer.SavedRail toSavedRail(SavedRailBase savedRail, long stationId)`
- 作用：站台/停车线 → 组件 `SavedRail(id, 两端节点, stationId, transportMode.ordinal())`。

### `private long getFingerprint(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：对每项求哈希后相加（与集合遍历顺序无关）：每条轨道（两端、类型、两端朝向角度）、边界节点、边界段、站台（id、中点、车站 id）、停车线（id、中点）、线路（id、停站序列）。

### `private static long mix(long value1, long value2, long value3)`
- 作用：三值混合哈希（SplitMix 风格常数）。
