# RailwayData.java

`RailwayData` 是服务端一个维度的全部铁路数据：轨道、车站、站台、停车线、线路、车厂、电梯，以及从它拆出的模块（存档 `RailwayDataFileSaveModule`、日志、冷却、手动驾驶、乘客寻路、建造工具、找路生成 `RailwayDataPathGenerationModule`）。每个维度在第一次 `getInstance(Level)` 时创建（`PersistentStateMapper`）。`simulateTrains(RailNearPlayer)` 是铁路的服务端 tick：给玩家发送附近轨道、移动各停车线的列车、车厂发车、运行电梯与各模块、向玩家同步附近列车与时刻、保存变化；步骤顺序见 `MTR-代码说明与流程.md` 第 4 节。ANTE 用 mixin 替换了无参 `simulateTrains()`，所以每 tick 要做的改动应写在带参版本里。它还持有组件侧的多个共享对象：`RunReservations`（单线占用仲裁）、`LockNetworks` 结果（咽喉锁开关）、`GraphRecords`（压缩网络与路径）、`RoutePaths`（线路路径，L1）、`Occupancy`（占用，解耦文档 3.7）、`LiveSimulation`（组件模拟器 G1）、`ContingencySystems`（D2）、`PathRegionSync`、`FareData`（0.68.0）。

## class RailwayData（extends PersistentStateMapper implements IPacket）

### 公共数据集合与对象
- `stations`、`platforms`、`sidings`、`routes`、`depots`、`lifts`（`LiftServer`）、`keyNodes`（关键节点）、`pathBoundaries`（寻路边界）。
- `dataCache`：基于上述集合的索引（`DataCache`）。
- `linePlans`（`LineDuties.PlanCache`）：按编排单元最近编排的运用，供各车厂找路生成共享。
- `contingencySystems`：每个轨道系统的应急预案（D2）。
- `contingenciesChanged` / `networkSettingsChanged`（volatile）：玩家修改后置位，tick 中发给所有玩家（D2 / 第 4 轮）。
- `ownersSentVersion`：上次发给玩家的轨道区段所有者版本（`TrackOwnersPackets`）。
- `graphRecords`：压缩网络与找到的路径，存档循环写入数据库。
- `runReservations`：使按运用运行的列车不迎面相遇（`RunReservations`）。
- `editRevisions`：玩家编辑各部分数据的版本（多人编辑冲突）。
- `routePaths`：每条线路的路径，只找一次，供按时刻表运行的车厂使用（解耦文档 5.4，L1）。
- 模块：`railwayDataLoggingModule`、`railwayDataCoolDownModule`、`railwayDataPathGenerationModule`、`railwayDataRailActionsModule`、`railwayDataDriveTrainModule`、`railwayDataRouteFinderModule`。

### 私有字段（要点）
- `world`、`rails`（起点 → 终点 → `Rail`）。
- `railInfo`（`BlockSections.RailInfo`）：按轨道键给出长度与限速（`TrainServer.cutSpeed`），用于把信号闭塞切成闭塞分区。
- `signalBlocks`、`railNodeRepair`、`fareData`、`pathRegionSync`、`railwayDataFileSaveModule`。
- `throatLocksOffDepots` / `lockNetworksState` / `lockNetworksSignature`：咽喉锁关闭的车厂及其计算依据。
- `slowerRoutes`、`tooShortStops`：上次报告过的"车辆慢于时刻表最低性能"的线路与"站台短于列车"的停站。
- `occupancy`（`Occupancy`）与 `trainPositions = occupancy.getTicks()`（两个 tick 的占用）。
- `liveSimulation`、`motionEvents`、`lastMotionEvents`、`deadlockedTrains`（D2）、`failedDepots`。
- `tickCompensator`、`playerLastUpdatedPositions`、`playersToSyncSchedules`、`updateNearbyTrains`、`updateNearbyLifts`。
- `schedulesForPlatform` / `lastSchedulesForPlatform`、`stationCalls`、`plannedPidsArrivals`、`pidsSlowToldMillis`、`trainDelays`。
- 地图同步：`lastMapRailsRequestMillis`、`lastMapRouteRequestMillis`、`pendingMapEntries`（玩家 → 种类 → 键 → 载荷）、`mapGraph`。
- `prevPlatformCount`、`prevSidingCount`、`useTimeAndWindSync`。

### 常量
- `AUTO_DUTIES_CHECK_TICKS = 100`、`TRACK_SECTIONS_CHECK_TICKS = 40`、`PIDS_SLOW_MILLIS = 50`。
- `RAIL_UPDATE_DISTANCE = 128`、`PLAYER_MOVE_UPDATE_THRESHOLD = 16`、`SCHEDULE_UPDATE_TICKS = 60`。
- `DATA_VERSION = 1`、`NAME = "mtr_train_data"`。
- `MAP_RAILS_REQUEST_INTERVAL_MILLIS = 5000`、`MAP_ROUTE_REQUEST_INTERVAL_MILLIS = 50`、`MAP_DIGEST_BATCH_SIZE = 20000`、`MAX_MAP_ENTRY_LONGS = 100000`。
- 旧存档键：`raw_message_pack`、`mtr_data_version`、`stations`、`platforms`、`sidings`、`routes`、`depots`、`lifts`、`rails`、`signal_blocks`、`use_time_and_wind_sync`（仅最后一个仍写入 NBT）。

### `public boolean isThroatLocksOff(long depotId)`
- 返回：该车厂的列车是否不锁车站咽喉（其轨道与不按运用运行的车厂共用）。调用者：`TrainServer.java:1110`。

### `private static UUID getSavedRailKey(SavedRailBase savedRail)`
- 站台/停车线两端节点的区段键（`PathBoundaries.segmentKey`）。

### `private void updateLockNetworks()`
- 作用：重新判断哪些车厂与不按运用运行的车厂共用轨道。先按各停车线的车厂、运输方式、是否按运用/时刻表、路径签名计算签名，未变则跳过（大网络上连接所有路径节点很慢）。否则构建 `LockNetworks`：连续移动的车厂不加入；按运用（或支持时刻表的运输方式）的为新逻辑（R2、R3）；各停车线路径节点加入。结果的 `offDepots` 存入；状态变化时打印"咽喉锁对……关闭：轨道与不按运用运行的……共用"或恢复的日志。
- 调用者：`simulateTrains`（每 `AUTO_DUTIES_CHECK_TICKS`）。

### `private String depotNames(Set<Long> depotIds)`
- 日志用车厂名列表，无名用 id，空为 "(none)"。

### `public transitsim.fare.FareNetwork getFareNetwork()`
- 按距离计费的网络（0.68.0），线路变化时重建。调用者：`TicketSystem.java:280`、`FarePackets.java:43`。

### `getSignalBlocks()` / `getWorld()` / `getRails()`
- 简单读取。调用者如 `LiveSimulation.java:729`、`SimExport.java:61`、`RoutePathCreator.java:114`。

### `public void placeTrainsByTimetable(ServerPlayer player, long depotId)`
- 作用：把组件模拟器运行的车厂的列车按其时刻表此刻应在的位置（从运营日开始算出）放置，而不是从车厂逐一发出；结果告知玩家。委托 `liveSimulation.placeByTimetable`。调用者：`PacketTrainDataGuiServer.java:630`。

### `public void markEdited(String kind, long id)`
- 作用：让玩家编辑的对象在 tick 末保存（多人编辑冲突）；`kind` 为 `EditGuard` 的命名。调用者：`EditGuard.java:231`。

### 构造函数 `RailwayData(Level world)`
- 作用：存档路径为 `<世界>/mtr/<命名空间>/<维度路径（".." 换成 "_"）>`；创建各模块；`updateNearbyTrains` 用删除/更新/状态更新三个包 ID（G2），电梯用前两个。调用者：`getInstance`。

### `public void load(CompoundTag compoundTag)`
- 作用：读旧存档：若有 `raw_message_pack`（临时代码），按 msgpack 读各集合，数据版本大于 `DATA_VERSION` 抛 `IllegalArgumentException`；否则读旧 NBT 各集合。异常打印堆栈。之后 `railwayDataFileSaveModule.load()`（新存储）、`validateData()`、`railNodeRepair.start(rails)`、`dataCache.sync()`、`signalBlocks.writeCache()`、读时间与风同步并执行，再尝试更新 Dynmap/BlueMap/Squaremap（未加载时打印提示）。
- 调用者：`PersistentStateMapper` 加载流程（`MTR.java:587` 等）。

### `public void save(File file)` / `public CompoundTag save(CompoundTag)`
- 作用：服务器停止时全量保存，否则自动保存；保存日志；`setDirty()` 后写 NBT。NBT 中只写 `use_time_and_wind_sync`。

### `public RailGraph<Rail> getPreviewGraph()`
- 返回：预览线路走向用的搜索图；地图的图，没有则现在编译。调用者：`RoutePreview.java:113`。

### `public void simulateTrains()`
- 以 `isRailNearInBox` 调用带参版本。调用者：`MTR.java:533`。

### `public static boolean isRailNearInBox(Rail rail, BlockPos startPos, BlockPos endPos, Vec3 playerPos)`
- 返回：玩家是否在轨道两端构成的盒子外扩 `RAIL_UPDATE_DISTANCE` 内。ANTE 的 mixin 在其运行钩子关闭时也用它（`SecurityConfig.anteRunHooks`）。调用者：`RailwayDataMixin.java:33`、`AnteRunHooksTest.java:56`。

### `public void simulateTrains(RailNearPlayer isNearPlayer)`
- 作用：一 tick 的铁路，按顺序：
  1. 移动超过 16 格的玩家收到附近轨道（`PACKET_WRITE_RAILS`，超过 `MAX_PACKET_BYTES` 不发）。
  2. 开始同步 tick、占用进入下一 tick、轮换时刻表、清空站停、重置信号占用、按轨道切闭塞分区、`tickCompensator` 算经过的 tick。
  3. 按 id 排序停车线（固定顺序，F1、R03）；停车线取车厂设置（`setSidingData`）；车厂清空并重建车池（步骤 D）；`rehomeDuties`（运用补完第 5 步）。
  4. `liveSimulation.tick`（G1，模拟器先走，看到上 tick 末的游戏列车）。
  5. 各停车线 `simulateTrain`；然后 `keepArrived`（P2-C）。
  6. 发送改变的应急预案与网络设置；`sendBuffersIfChanged`（D1）；`handleMotionEvents`；`runReservations.resolve`；记录死锁（D2）。
  7. 各车厂 `deployTrain`（失败只记一次日志、不影响其他）。
  8. 电梯、冷却/驾驶/轨道动作/寻路模块、`pathRegionSync`。
  9. 每 `TRACK_SECTIONS_CHECK_TICKS`：`graphRecords.update`（第 1 轮）、`routePaths.updateOwners`（第 2 轮）、`TrackOwnersPackets.sendAllIfChanged`（网页图层第 2 步）。
  10. 同步附近列车/电梯；每 `SCHEDULE_UPDATE_TICKS` 把所有玩家加入时刻同步队列，每 tick 处理一人：附近站台的显示时刻（`PidsSchedules`，慢于 50ms 时每分钟最多报告一次）、全部信号闭塞状态、附近轨道占用、已被占的闭塞分区（`PACKET_UPDATE_SCHEDULE`）。
  11. 站台/停车线数量变化时 `dataCache.sync()`。
  12. 每 `AUTO_DUTIES_CHECK_TICKS`：`updateLockNetworks`；填入时刻表最低性能（第 3 轮，打印并发送线路）；报告站台短于列车的停站（第 3b 轮）；报告车辆慢于时刻表的线路；区段与所有者就绪后按 `LineDuties.unitsOf` 让车厂排队找路（第 5 轮第 3 步）。
  13. `railwayDataPathGenerationModule.startQueued`。
  14. 每 `AUTO_DUTIES_CHECK_TICKS`：清理死锁列车、`contingencySystems.keepAnchors`、各车厂延误告警（`DelayAlarms`）及其跳站（D3）、提前折返（D4）、交给备车（D5）——经模拟器提交，失败则直接设到列车服务上；发送告警与滞留列车/回库命令未达（每 12 次重发）。
  15. `railNodeRepair.tick`；`saveNow` 与 `autoSaveTick`。
- 调用者：`simulateTrains()`、`RailwayDataMixin.java:33`、`:38`。

## interface RailNearPlayer
- `boolean test(Rail rail, BlockPos startPos, BlockPos endPos, Vec3 playerPos)`：轨道是否在玩家附近。

### `public transitsim.network.PlatformGraph getPlatformGraph()`
- 返回：最近为玩家区域算出的站台网络（`pathRegionSync.getLatest()`）。

### `public void onPlayerJoin(ServerPlayer serverPlayer)`
- 作用：分块发送全部数据、区域、应急预案、网络设置，冷却模块处理加入。调用者：`MTR.java:560`。

### `public long addRail(Player player, TransportMode transportMode, BlockPos posStart, BlockPos posEnd, Rail rail, boolean validate)`
- 作用：加入一条单向轨道（静态 `addRail`），标记轨道改变；`validate` 时以随机 id 创建站台/停车线、反向轨道存在时记日志（类型、是否单向）并 `validateData()`。
- 返回：新 id（不校验时 0）。调用者：`CompoundCreator.java:199`、`:200`、`PacketUpdateRail.java:63`。

### `public boolean isNodeFacing(BlockPos pos, RailAngle angle)`
- 返回：节点所有轨道起始朝向是否与该角度平行。调用者：`PacketTrainDataGuiServer.java:1112`。

### `public String rotateNodeRails(Player player, BlockPos pos, RailAngle angle)`
- 作用：把节点的轨道转到新角度（`Rail.rotateNode`），双向更新、标记改变、发送 `createRailS2C`、记日志。
- 返回：问题的翻译键（节点上有站台/停车线为 `gui.mtr.platform_or_siding_exists`，或旋转错误），成功或无轨道为 null。调用者：`PacketTrainDataGuiServer.java:1112`。

### `public void setTurnBackDwell(BlockPos pos, int turnBackDwell)`
- 作用：设置节点上折返轨道（任一方向为 `TURN_BACK`）两向的折返停留时间，变化时标记并发送。调用者：`PacketTrainDataGuiServer.java:1121`。

### `public long addSignal(Player, DyeColor, BlockPos posStart, BlockPos posEnd)` / `removeSignal(...)`
- 记日志并在 `signalBlocks` 中加/删该颜色的轨道。返回信号闭塞 id。调用者：`ItemSignalModifier.java:28`、`:36`。

### `public void removeNode(Player player, BlockPos pos, TransportMode transportMode)`
- 作用：记日志；标记节点与其相邻节点改变；删除节点（保留 `railNodeRepair` 记住的缺失节点）；取消关键节点；删去该节点的寻路边界与以它为端的区段边界（轨道上的边界保留，因为常被重铺）；`validateData()`；信号闭塞失效时发 `PACKET_REMOVE_SIGNALS`。调用者：`BlockNode.java:81`。

### `public void sendMapRails(ServerPlayer player)`
- 作用（地图同步，每玩家至少间隔 5 秒）：发轨道所有者；按区域打包轨道（每连接一次：起、终、运输方式 | 限速 km/h << 16）与曲线形状（`RailShapes.pack`；无曲线的区域也发空，以便客户端删去形状）；停车线路径节点；发送"路径过期的车厂"；重编译 `mapGraph`；在后台线程按 `MapRouteQuery` 求每条线路节点，加入 `graphRecords`，再发线路摘要。每类先发摘要（`storeAndSendDigest`），客户端再按需请求。调用者：`PacketTrainDataGuiServer.java:549`。

### `private void storeAndSendDigest(ServerPlayer player, int kind, Map<Long, long[]> payloads, Map<Long, Long> hashes)`
- 作用：把载荷存为待取，按每批 `MAP_DIGEST_BATCH_SIZE` 发送 `PACKET_MAP_DIGEST`：byte 种类、boolean 是否最后、int 数量、各（long 键、long 哈希）。

### `public void sendMapEntries(ServerPlayer player, int kind, boolean isLast, long[] keys)`
- 作用：发送客户端请求的条目，每条取出即删（不能重复请求）；`isLast` 时删去该种类。调用者：`PacketTrainDataGuiServer.java:575`。

### `private static void sendMapEntry(ServerPlayer player, int kind, long key, long[] payload)`
- `PACKET_MAP_DATA`：byte 种类、long 键、long 哈希（轨道用 `hashRails`，其余 `hashRoute`）、int 长度（超过 `MAX_MAP_ENTRY_LONGS` 写 0 不发内容）、各 long。

### `public void sendRouteLayout(ServerPlayer player, long routeId, int halfWidth)`
- 作用：在后台线程用轨道快照求线路节点与拉直的股道图（`RouteLayouts.write`），一层隔离：联络线占用、已释放的轨道与寻路边界区段标为 `RAIL_BOUNDARY` 等状态；发 `PACKET_ROUTE_LAYOUT`。调用者：`PacketTrainDataGuiServer.java:616`。

### `private RoutePaths.Search getMapSearch(RailGraph<Rail> graph)`
- 当前轨道拷贝上的线路路径搜索，用刚编译的图。

### `public void findRoutePathsInBackground(Collection<Route> routesToFind, Runnable whenDone)`
- 作用（按距离计费，2026-11-12）：在独立线程 "MTR fare route paths" 用轨道拷贝找这些线路的路径（否则未被需要过的线路按直线计费）；每找到一条 `RoutePaths#getTimingVersion` 增加，计费网络重建；单条异常打印；最后运行 `whenDone`。调用者：`FareData.java:168`。

### `private static long[] ends(SavedRailBase savedRail)`
- `{id, 端点1, 端点2}`（排序后的两端）。

### `public void sendMapRoute(ServerPlayer player, long routeId)`
- 作用：在后台线程发一条线路的节点（间隔 ≥50ms，需要已有 `mapGraph`）；地图的图可能旧于轨道，所以只用已找到的线路路径（按路径计时的线路），不在其中搜索。调用者：`PacketTrainDataGuiServer.java:559`。

## private static class MapRouteQuery
地图与股道图用的线路节点：线路自己的路径（列车实际走的、避开联络线，L1）；对按其他方式计时或找不到路径的线路沿轨道照旧追踪，使被联络线切断的线路显示它要走的地方。可在其他线程安全搜索。
- 字段：`routeId`、`route`、`search`（仅按路径计时时保留）、`dataCache`（拷贝）、`canTrace`（非飞机且非连续移动）、`platforms`（去掉连续重复）与 `sectionWaypoints`、`foundNodes`（服务端线程读到的已有路径或 null）。
- 构造：调用者 `RailwayData.java:1064`、`:1142`、`:1214`。
- `findNodes(RailGraph<Rail> graph)`：已有节点 → 搜索到的路径节点 → `DeterministicPathFinder.findNodes` 追踪 → 空。

### `public void setKeyNode(BlockPos pos, boolean isKeyNode)`
- 加/删关键节点，变化时 `setKeyNodeS2C`。调用者：`PacketTrainDataGuiServer.java:1109`、`removeNode`。

### `public void setPathBoundary(Player player, BlockPos pos1, BlockPos pos2, boolean isBoundary)`
- 作用：把节点（`pos2` 为 null）或两节点间的轨道设/取消为寻路边界；轨道不存在或已达 `PathBoundaries.MAX_BOUNDARIES` 时不做；变化时记日志。玩家在下次 `PathRegionSync` 检查时收到新区域。调用者：`PacketTrainDataGuiServer.java:1141`。

### `public void removeRailConnection(Player player, BlockPos pos1, BlockPos pos2)`
- 作用：两向都存在时记日志（类型、是否单向）；删除连接（保留修复中的节点）、标记改变、`validateData()`、必要时发信号删除包。调用者：`ItemRailModifier.java:106`。

### `public void removeLiftFloorTrack(BlockPos pos)`
- 删除含该楼层的电梯并 `dataCache.sync()`。调用者：`BlockLiftTrackFloor.java:47`。

### `public boolean hasSavedRail(BlockPos pos)` / `public boolean containsRail(BlockPos pos1, BlockPos pos2)`
- 节点是否有站台/停车线类轨道；是否有这条连接。调用者：`ItemRailModifier.java:52`、`RailwayDataRailActionsModule.java:29`。

### `public void disconnectPlayer(Player player)`
- 作用：同步器忘记玩家；从各停车线列车与电梯乘客中移除；冷却模块处理；删除位置记录。调用者：`MTR.java:566`。

### `public void requestTrainSnapshot(ServerPlayer player)`
- 让附近列车同步器下次给该玩家发完整快照。调用者：`MTR.java:481`。

### `public void getSchedulesForStation(Map<Long, List<ScheduleEntry>> out, long stationId)`
- 把该车站各站台的时刻填入。调用者：`ArrivalsServletHandler.java:42`。

### `public List<ScheduleEntry> getSchedulesAtPlatform(long platformId)`
- 返回（0.65.31）：其他模组得到的到站：只有在此停车且尚未到达的列车（`ScheduleEntry.forOtherMods`）。本模组自己用 `getAllSchedulesAtPlatform`。调用者：本仓库内无（对外 API）。

### `public List<ScheduleEntry> getAllSchedulesAtPlatform(long platformId)`
- 所有到站，含通过与计划的。调用者：`BlockTrainScheduleSensor.java:173`、`RailwayDataRouteFinderModule.java:192`。

### `void addStationCall(StationCall call)` / `public List<StationCall> getStationCalls(BlockPos pos)`
- 记录本 tick 的站停；按位置找附近站台（半径 4）返回其站停。调用者：`TrainServer.java:778`、`BlockTrainScheduleSensor.java:162`。

### `getTrainDelays()` / `resetTrainDelays(Depot depot)`
- 列车延误表；删除车厂各线路的延误。调用者：`RailwayDataFileSaveModule.java:360`、`RailwayDataPathGenerationModule.java:100`。

### `private void handleMotionEvents()`
- 作用：把本 tick 运动事件排序（契约 2.1 规则 5），报告紧急制动，保留到下一 tick。

### `public List<MotionEvent> getLastMotionEvents()`
- 上一 tick 的事件（只读）。调用者：本仓库内未找到。

### `getUseTimeAndWindSync()` / `setUseTimeAndWindSync(boolean)`
- 读写是否同步现实时间，设置后立即执行。调用者：`ItemDashboard.java:28`、`PacketTrainDataGuiServer.java:1314`、`:1315`。

### `private void validateData()`
- 删除无效站台/停车线并通知玩家；删除端点上站台/停车线无效的轨道连接。

### `private void runRealTimeSync()`
- 开启时运行命令：`/gamerule doDaylightCycle true`、`/taw set-cycle-length <维度> 864000 864000`、`/taw reload`、按当前现实时间 `/time set`。

### 静态方法
- `getPlatformByPos(Set<Platform>, BlockPos)`：含该位置的站台，异常为 null。调用者：本仓库内未找到。
- `addRail(rails, platforms, sidings, transportMode, posStart, posEnd, rail, savedRailId)`：加入单向轨道；`savedRailId != 0` 且轨道为站台/停车线且两端不在已有对象上时新建 `Platform`/`Siding`（长度向下取整）。调用者：`PacketTrainDataGuiClient.java:475`、`:476`。
- `removeNode(Level, rails, pos)` 及私有带 `kept` 版本：删节点及所有指向它的轨道，空节点重置方块；有世界时 `validateRails`。调用者：`PacketTrainDataGuiClient.java:491`。
- `removeLiftFloorTrack(Level, Set<T>, pos)`：删含该楼层的电梯，并删无效电梯。
- `removeRailConnection(Level, rails, pos1, pos2)` 及私有带 `kept` 版本。调用者：`PacketTrainDataGuiClient.java:502`。
- `containsRail(rails, pos1, pos2)`。调用者：`SavedRailBase.java:205`、`SignalBlocks.java:220`。
- `getStation(Set<Station>, DataCache, BlockPos)`：见 `DataCache#getStationAt`（旁边站台的车站，否则区域包含该位置、同高度且站台最近的车站）。调用者：`RailwayDataRouteFinderModule.java:297`、`MinecraftClientUtil.java:140`。
- `getClosePlatformId(platforms, dataCache, pos[, radius, lower, upper])`：先查缓存；否则从半径 1 到 radius 逐步找最近（曼哈顿距离到中点）的站台；结果（含 0）写入缓存。默认 5/0/4。调用者：`BlockPSDTop.java:197`、`BlockTrainScheduleSensor.java:168`。
- `useRoutesAndStationsFromIndex(int stopIndex, [long actualPlatform,] List<Long> routeIds, DataCache, RouteAndStationsCallback)`：把跨多条线路的停站序号换成（本线内序号、本线、下一可见线路、本站、下一个不通过的站、终点站）并回调；相邻线路首末站台相同时只算一次；给出实际站台时先用 `AnnouncementStops.nearest` 校正序号。返回是否找到。调用者：`TrainServer.java:741`、`TrainClient.java:333`。
- `isBetween(value, v1, v2[, padding])`、`round(value, decimalPlaces)`。
- `writeMessagePackDataset(MessagePacker, Collection<? extends SerializedDataBase>, String key)`：写键与数组，每项为 map。调用者：`Siding.java:474`、`PathPieces.java:106`。
- `castMessagePackValueToSKMap(Value)`：msgpack map 值转为字符串键 map（null 为空）。调用者：`NetworkSettings.java:228`、`Siding.java:414`。
- `hasNoPermission(ServerPlayer)`：非创造/生存或权限低于 `SecurityConfig.editPermissionLevel`。调用者：`SandboxRuns.java:112`、`AudioSpeakerPackets.java:37`。
- `hasPermission(GameType)`、`chunkLoaded(Level, BlockPos)`、`prettyPrint(JsonElement)`、`newBlockPos(...)` ×3、`offsetBlockPos(...)`、`benchmark(Runnable, float)`（超阈值秒数时打印）。
- `getInstance(Level)`：每维度单例。调用者：极多，如 `PacketRoutePathCreator.java:42`。

### `public boolean dispatch(long trainId, int command, long value, Set<Long> platformIds)`
- 作用（D6）：给列车调度命令：组件模拟器运行的列车经模拟器提交，否则直接交给列车服务。
- 返回：是否有这列车。调用者：`DispatchConsole.java:153`。

### 私有静态
- `validateRails(world, rails, kept)`：已加载区块中方块不是 `BlockNode` 且不在 `kept` 中的节点删除，无连接的节点删除。`kept` 为世界丢失但轨道已保存的节点（交给 `RailNodeRepair` 放回或报告），避免删一条轨道时把它们及其上的站台/停车线一起删掉。
- `validateLifts(world, lifts)`、`removeSavedRailS2C(world, set, rails, packetId)`（删除无效对象并发 long id）、`runCommand(...)`（打印并执行）、`readMessagePackSKMap(MessageUnpacker)`（临时代码）。

## @Deprecated private static class RailEntry（extends SerializedDataBase）
旧存档中的一个节点及其连接（临时代码）。
- 字段 `pos`、`connections`；键 `node_pos`、`rail_connections`。
- 构造：`(pos, connections)`、`(CompoundTag)`、`(Map<String, Value>)`。调用者：`load`。
- `toMessagePack`：`node_pos`、`rail_connections` 数组（每项为 `node_pos` + `Rail` 字段）；`messagePackLength()` = 2；`writePacket` 不写任何东西。

## interface RouteAndStationsCallback
- `routeAndStationsCallback(int currentStationIndex, Route thisRoute, Route nextRoute, Station thisStation, Station nextStation, Station lastStation)`。
