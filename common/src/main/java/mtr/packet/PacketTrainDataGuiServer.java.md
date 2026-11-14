# PacketTrainDataGuiServer.java

服务端侧的大部分网络包处理：向玩家发送数据（`…S2C`）并接收玩家的修改（`receive…C2S`）。修改先检查权限并经 `EditGuard`（多人编辑冲突），写入 `RailwayData`，再转发给同一维度的其他玩家。C2S 处理在 `common/src/main/java/mtr/MTR.java:468`–`:502` 经 `PacketGuard`/`EditGuard` 注册；S2C 由方块、物品、`RailwayData`、`Depot` 等调用。客户端对应 `PacketTrainDataGuiClient`。本分支改动（新增约 975 行）：版本/编辑权限/线路用词/时刻配置/服务器时间的下发；车站区域工具、节点角度界面；车厂路径生成结果的扩展字段（失败运用、出入库段、绕行、折返时间）；线路删除后报告仍引用它的车厂/运用/直通；更新线路时修正越过时刻表边界的直通；网页地图（轨道、线路、增量摘要）；车站/线路布局；按时刻表放置列车、重新规划运用；时刻表最快时间（含草稿性能）与时刻冲突检查（后台线程）；节点角度与关键节点、折返停留、寻路边界；PIDS 显示通过列车；所有 C2S 都加了长度上限、`PacketGuard.readCount`、距离与区块检查、`PacketGuard.execute`。

## 类 `PacketTrainDataGuiServer`（extends `PacketTrainDataBase`）

### 常量与静态字段
- `MAX_FAILED_DUTIES = 16`（失败运用和绕行最多发送条数）、`MAX_TURN_TIMES = 4096`、`MAX_LIST_SIZE = 4096`、`MAX_STRING_LIST_SIZE = 256`、`MAX_EDIT_DISTANCE = 64`（编辑方块的最大距离，格）、`TICKET_MACHINE_DISTANCE = 6`。
- `PACKET_CHUNK_SIZE = 16384`：全量数据分块大小。
- `PLAYERS_CHECKING_CONFLICTS`：正在做冲突检查的玩家；`WAITING_CONFLICT_CHECKS`：玩家 → 排队等待的线路 id。

### 登录时下发（调用者 `common/src/main/java/mtr/MTR.java:550`–`:555`）
- `versionCheckS2C(ServerPlayer)`：`String IPacket.getNetworkVersion()`。
- `lineWordsS2C(ServerPlayer)`：`LineWords.getWordsJson()` 的 UTF-8 字节数组。
- `timetableConfigS2C(ServerPlayer)`：`String 时区`、`int 运营日开始小时`。
- `serverTimeS2C(ServerPlayer)`：`long 服务器毫秒`（G2：客户端据此计算列车位置与显示时间）。
- `editPermissionS2C(ServerPlayer)`：`boolean 权限 ≥ editPermissionLevel`、`boolean editorSimulation`、`int interlockColor 序号`、`boolean anteRunHooks`、`int stablingColor 序号`（运用补完第 4 步）。客户端只知道游戏模式，服务器会静默忽略无权修改，所以要告诉它。

### 打开界面（S2C）
- `openDashboardScreenS2C(ServerPlayer, TransportMode, boolean useTimeAndWindSync)`：先重发编辑权限（游戏中可能变化）。调用者：`common/src/main/java/mtr/item/ItemDashboard.java:28`。
- `openStationAreaToolScreenS2C(ServerPlayer, BlockPos pos1, BlockPos pos2)`：先重发权限。调用者：`common/src/main/java/mtr/item/ItemStationAreaTool.java:32`。
- `openRailwaySignScreenS2C`、`openTicketMachineScreenS2C(Level, ServerPlayer)`（附当前余额）、`openTrainSensorScreenS2C`、`openNodeAngleScreenS2C`（`common/src/main/java/mtr/block/BlockNode.java:198`）、`openLiftTrackFloorScreenS2C`、`openLiftCustomizationScreenS2C(long id)`、`openPIDSConfigScreenS2C(…, int maxArrivals, int linesPerArrival)`、`openArrivalProjectorConfigScreenS2C`、`openResourcePackCreatorScreenS2C`。

### 广播与轨道/信号/节点（S2C，多数发给世界内所有玩家）
- `announceS2C(ServerPlayer, String message, String soundString)`：单段声音的重载。
- `announceS2C(ServerPlayer, String message, List<AnnouncementClips.Clip> clips)`：`String 消息`、`String 第一段声音（兼容 0.72.0 前）`、`String 片段文本`（`AnnouncementClips.toText`）。调用者：`common/src/main/java/mtr/block/BlockTrainAnnouncer.java:129`。
- `createRailS2C(Level, TransportMode, BlockPos pos1, BlockPos pos2, Rail rail1, Rail rail2, long savedRailId)`。调用者：`cn/zbx1425/mtrsteamloco/network/PacketUpdateRail.java:65` 等。
- `createSignalS2C(Level, long id, DyeColor, UUID rail)` / `removeSignalS2C(...)`（数量固定为 1）。
- `updateRailActionsS2C(Level, List<Rail.RailActions>)`。
- `setKeyNodeS2C(Level, BlockPos, boolean isKeyNode)`。调用者：`common/src/main/java/mtr/data/RailwayData.java:1280`。
- `sendStaleDepotsS2C(ServerPlayer, Set<Long> depotIds)`。调用者：`RailwayData.java:1057`。
- `removeNodeS2C`、`removeLiftFloorTrackS2C`、`removeRailConnectionS2C`。

### 全量数据
- `sendAllInChunks(ServerPlayer, Set<Station>, Set<Platform>, Set<Siding>, Set<Route>, Set<Depot>, Set<LiftServer>, SignalBlocks, Set<BlockPos> keyNodes)`：依次序列化（每类 `int 数量` + 各 `writePacket`）车站、站台、停车轨、线路、车厂、升降梯、信号闭塞，再 `int 关键节点数` + 各 `BlockPos`，用随机传输 id 分 16 KB 块发送（`sendChunk`）。调用者：`RailwayData.java:901`。
- `private static <T> void serializeData(FriendlyByteBuf, Collection<T>)`。
- `private static boolean sendChunk(ServerPlayer, FriendlyByteBuf packet, long tempPacketId, int chunk)`：`long id`、`int 块号`、`boolean 已结束`（块起点超过数据末尾时为真，不再带数据）、数据片；返回是否已结束。注意：结束标志块本身不带数据，客户端以它的块号 + 1 作为块数。

### 线路删除报告
- `reportRouteReferences(ServerPlayer player, long routeId, Runnable delete)`：删除前记下仍引用该线路的车厂（运行它）、运用（有运用段用它）、其他线路的直通；执行 `delete`；删除是排在服务器线程下一个任务里的，所以检查放在其后的任务中（U96：之前立刻检查时线路还在，从未报告）。调用者：`common/src/main/java/mtr/MTR.java:502`。
- `private static void reportDeletedRoute(...)`：确认已删除后，分别提示 `gui.mtr.route_deleted_depots`/`_duties`/`_links` 并写日志。车厂会自行移除，但运用和直通保持原样且不再运行那一部分，需要手动修改。

### 通用数据更新
- `receiveUpdateOrDeleteC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf, ResourceLocation packetId, Function<RailwayData, Set<T>> dataSet, Function<RailwayData, Map<Long, T>> cacheMap, BiFunction<Long, TransportMode, T> createDataWithId, boolean isDelete)`：无权限忽略；更新包 id 为 0 拒绝（服务器和每个客户端会各自随机）；更新/删除后：把完整包转发给同维度其他玩家，`dataCache.sync()`；更新线路时用 `TimetableBoundaries.sanitizeLinks` 去掉越过时刻表边界的直通，有修正则把修正后的线路发给所有人；车站/车厂变化时尝试更新 Dynmap、BlueMap、squaremap（未安装时忽略）；修改写入日志模块。调用者：`EditGuard` 的处理函数登记（`MTR.java:491`、`:492`）。

### 车厂路径与列车
- `generatePathS2C(Level, long depotId, int successfulSegments[, PathFailure failure[, List<String> failedDuties[, Map<Long, DepotLegs> depotLegs[, List<String> detours[, Map<Long, Map<Long, Long>> turnTimes]]]]])`：完整格式：`long 车厂`、`int 成功段数`、`PathFailure`、`int 失败运用总数`、`int 发送数(≤16)` + 各 `String(截断到 200)`、`int 停车轨数` + 各 `long, DepotLegs`、`int 绕行数(≤16)` + 各 `String`（"from to 长度 相距"）、`int 折返时间条数（null 时 −1，客户端保留原值）` + 各 `long 从, long 到, long 毫秒`（≤4096）、`int 冲突进路数(≤16)` + 各 `String`（0.80.0，`OpposingMoves.Finding.toString()`：`turns 从 到 次数` 或 `against 从 到 次数 从 到 次数`，站台 id）。`int 轨道用法数(≤MAX_TRACK_USES=30000)` + 各 `long 起点, long 终点, long 线路`（0.80.0，车站股道运用图）。8 参数的重载发空列表。发给世界所有玩家。调用者：`common/src/main/java/mtr/data/Depot.java:1260`、`:1270`。
- `generatePathC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`：读 `long` 作为车厂 id，`railwayDataPathGenerationModule.generatePath`。注意：客户端 `PacketTrainDataGuiClient.generatePathC2S(long sidingId)` 的参数名写作停车轨 id，但所有调用者实际传的都是车厂 id（如 `common/src/main/java/mtr/screen/EditDepotScreen.java:90`），与这里一致，只是参数名不准确。
- `clearTrainsC2S(...)`：`long 车厂`、停车轨列表；重置该车厂列车晚点（`resetTrainDelays`）并清空各停车轨列车。
- `receivePlaceTrainsC2S(...)`：`long 车厂` → `railwayData.placeTrainsByTimetable(player, depotId)`（按时刻表直接把列车放到当前位置，而不是逐列出库）。
- `receiveReplanDutiesC2S(...)`：`long 车厂`、可选 `String 运用组`；按 `LineDuties.of` 找到规划单元内所有车厂，全部 `forgetAutoPlan()` 或 `replanGroup(组)`，然后都重新生成路径（第 4 轮第 4 步、运用组第 4 步）。

### 传感器与方块设置
- `receiveTrainSensorC2S(...)`：`BlockPos`、线路过滤、`boolean ×2`、`int`、字符串列表 → 可编辑时 `TileEntityTrainSensorBase.setData` 并记日志。
- `receiveLiftTrackFloorC2S(...)`、`receiveSignIdsC2S(...)`（铁路标志或线路标志，线路标志上下两格一起设置站台）、`receivePIDSMessageC2S(...)`（末尾 `boolean 显示通过列车`）、`receiveArrivalProjectorMessageC2S(...)`。
- `private static boolean canEditBlock(ServerPlayer, BlockPos)`：有权限、区块已加载、距离 ≤ 64 格。
- `@SafeVarargs private static <T> void setTileEntityDataAndWriteUpdate(ServerPlayer, Consumer<T> setData, T... entities)`：对每个方块实体执行设置，并把第一个的前后 NBT 写入日志模块。

### 网页地图与布局
- `receiveRequestMapRailsC2S` → `railwayData.sendMapRails(player)`；`receiveRequestMapRouteC2S` → `sendMapRoute(player, routeId)`；`receiveMapWantedC2S`（`byte 种类`、`boolean 最后`、键列表 ≤ 32767）→ `sendMapEntries`。
- `receiveStationLayoutC2S`：盒子（`StationLayouts.clampBox`）、交通方式、种子；限速（`StationLayouts.allowRequest`）后回发 `StationLayouts.write(...)`。
- `receiveRouteLayoutC2S`：`long 线路`、半宽（夹到 `RouteLayouts.MIN/MAX_HALF_WIDTH`）；限速后 `railwayData.sendRouteLayout`。

### 时刻表时间
- `receiveTimetableTimesC2S(...)`：`long 线路`、可选 `int 请求号`；请求号 > 0 为草稿：`VarInt 表数(1–MAX_TABLES)`，每表 `float 最小加速度, 最小减速度, 最小最高速度`（非负有限）、`VarInt 站数(≤4096)` + 各 `boolean 通过`。服务器线程中：线路不存在或草稿站数不符回空；草稿则复制线路、替换表性能与通过设置；正式请求先 `getKnownTimes`，没有则 `calculateTimetableTimes`（后台线程）。
- `private static long[][] getKnownTimes(RailwayData, Route, Map<Long, Set<PathTiming.Vehicle>>)`：用线路自身路径计时的线路取 `routePaths.getFound(...).getTimetableTimes`；否则若任一表设了最低性能返回 null，不然取车厂路径的时间（`getDepotTimes`）。
- `private static boolean usesRoutePath(Route)`：`RoutePaths.timesByPath(route)`（解耦文档 5.4、L1：线路最快时间按其自身路径、以最慢车辆计算；手画路径、飞机、连续运动仍按车厂路径或此处现找的路径）。
- `private static long[][] getDepotTimes(RailwayData, Route)`：第一个运行该线路且有停站时间的停车轨的 `getFastestTimes`；车厂用速度曲线时返回 null。
- `private static boolean canUseRailGraph(Route)`：非飞机、非连续运动。
- `private static void calculateTimetableTimes(MinecraftServer, ServerPlayer, RailwayData, Route, Map<…> vehicles, int requestId)`：准备 `getTimeFinder`，在新线程求时间（以及路径失败原因），回服务器线程发送。
- `private static Supplier<long[][]> getTimeFinder(RailwayData, Route, Map<…> rails, RailGraph<Rail> graph, TimeSearch search)`：自身路径计时的线路用 `search.getFastestTimes`；否则收集站台与途经点，找一个运行同一线路（或同交通方式）的停车轨作为车辆，返回"寻路（`PathFinder.findPath`，停靠序号从 1 起）→ 应用停站设置 → 按每表（含最低性能覆盖）`PathTiming.calculateStopTimes(...).getFastestTimesFromFirstStop` → `TimetableTimes.pack`"的函数；没有可用车辆或站台少于 2 返回 null。
- `private static void sendTimetableTimes(ServerPlayer, long routeId, long[][] times[, PathFailure failure[, int requestId]])`：`long 线路`、`int 站数`、每站 `long, long`、`PathFailure`、`int 请求号`、`VarInt 列数`、每站其余列。

### 私有内部类 `TimeSearch`
- 在服务器线程复制轨道与 id 映射，供后台线程寻找线路路径。
- `getFastestTimes(Route)`：无结果返回空数组。
- `getFailure(Route)`：已找过的路径的失败原因，或 `NONE`。

### 时刻冲突检查
- `receiveTimetableConflictsC2S(...)` → `checkTimetableConflicts`。
- `private static void checkTimetableConflicts(MinecraftServer, ServerPlayer, long routeId)`：一个玩家同时只能有一个检查，正在检查时记为等待，检查结束后再跑（时间可能已变）。参与比较的线路：本线路，加上共用同一折返线的线路（折返前的线路可能与本线没有共同站台），以及停靠相同站台或相同车站（共用咽喉，L2）且时刻表启用的线路。准备：下一线路（`getNextRouteIds`）、运用中确定的下一班（`TerminalPairs.fixedNext`）、各线路咽喉道岔（`routePath.getThroats`，联锁区按 `SecurityConfig.interlockColor`）、已知时间或时间求解器。然后在新线程中为每条线路构造 `TimetableConflicts.RouteTimes`（去掉停运班次；有站未到达的线路计为"部分未检查"），`TimetableConflicts.check`，回服务器线程 `sendTimetableConflicts`。
- `private static Map<Long, Long> getNextRouteIds(RailwayData)`：列车按车厂线路顺序运行，末线终点与首线起点相同时回到首线。
- `private static Map<Long, Set<TimetableConflicts.TurnBack>> getTurnBacks(RailwayData)`：各停车轨停站时间中的折返及其候选折返轨。
- `private static void sendTimetableConflicts(ServerPlayer, long routeId, List<Conflict>, int uncheckedRoutes)`：`long 线路`、`int 未检查数`、`int 冲突数`，每个 `int 表, int 站序, int 班次, long 对方线路, int 对方班次, long 站台, long 道岔节点`。

### 节点与边界
- `receiveNodeAngleC2S(...)`：`BlockPos`、`float 角度（度）`、`boolean 关键节点`、`int 折返停留`；可编辑、角度有限、方块为自由角度节点时：设关键节点；角度变化时 `rotateNodeRails` 旋转相连轨道（失败把错误发到聊天栏，因为节点渲染每帧覆盖动作栏），成功则设自定义角度并在动作栏显示；折返停留 ≥ 0 时设置（上限 `Rail.MAX_TURN_BACK_DWELL`）。
- `receivePathBoundaryC2S(...)`：设置/清除节点或节点间轨道的寻路边界（`railwayData.setPathBoundary`）。

### 其他
- `receiveDriveTrainC2S(...)`：手持钥匙时交给驾驶模块。
- `receivePressLiftButtonC2S(...)`。
- `receiveAddBalanceC2S(...)`：客户端给的金额忽略；绿宝石数必须是 2 的幂且档位有效、6 格内有售票机、背包够数，扣绿宝石、加余额（防溢出）、播放声音。
- `private static boolean isNearTicketMachine(Level, BlockPos)`。
- `receiveUseTimeAndWindSyncC2S(...)`：需要编辑权限且权限等级 ≥ 1；设置并记日志。
- `receiveRemoveRailAction(...)`。
- `receiveUpdateTrainPassengerPosition` / `receiveUpdateLiftPassengerPosition` → `relayPassengerPosition`：只转发发送者本人（UUID 一致）且数值有限的位置，重建包后转发给同世界其他玩家。
- `receiveUpdateEntitySeatPassengerPosition(...)`：坐标有限时移动座位实体。
