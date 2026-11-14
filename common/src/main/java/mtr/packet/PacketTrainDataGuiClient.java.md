# PacketTrainDataGuiClient.java

客户端侧的大部分网络包处理：把服务器发来的数据收进 `mtr.client.ClientData`（及编辑器缓存等），并把玩家在各界面上的修改发给服务器（`send…`/`…C2S`）。S2C 接收器在 `common/src/main/java/mtr/MTRClient.java:261`–`:355` 注册；C2S 发送由各界面和网页编辑器调用。对应的服务端是 `PacketTrainDataGuiServer`。本分支改动（相对 MTR 3.2.2 新增约 380 行）：版本检查改为只比较协议（前两段版本号）；编辑权限包附带服务器开关（网页仿真、ANTE 运行钩子、联锁/停放颜色）；缺少资源模组 MTM_Assets 时提示；服务器时间、时刻配置、线路名词、地图摘要/数据、关键节点、寻路区域、车站/线路布局、仿真结果、时刻表时间与冲突、节点角度、车站区域工具、过期车厂、车厂路径生成结果的扩展字段、多段广播声音、修改走 `ClientEditing`（多人编辑冲突检查）等。

## 类 `PacketTrainDataGuiClient`（extends `PacketTrainDataBase`）

### 常量与静态字段
- `MAX_CHUNKS = 1 << 16`：分块数据包的最大块序号。
- `TEMP_PACKETS_RECEIVER`（块序号 → 字节）、`tempPacketId`、`expectedSize`：分块重组状态。
- `assetsWarned`、`ASSETS_CHECK`（`mtr:textures/item/tunnel_wall_creator_6_9.png`，资源模组中的一张纹理，用于检测是否安装了 MTM_Assets）。

### 服务器设置与版本
- `receiveLineWordsS2C(Minecraft, FriendlyByteBuf)`：包为 UTF-8 字节数组，主线程中 `LineWords.setWordsFromServer`（线路名用词表）。
- `receiveEditPermissionS2C(Minecraft, FriendlyByteBuf)`：依次读 `boolean 有编辑权限`、可选 `boolean 网页仿真开关`、可选 `int 联锁颜色序号`（缺省品红）、可选 `boolean ANTE 运行钩子`、可选 `int 停放颜色序号`（缺省棕色），写入 `ClientData` 对应字段；然后 `warnIfAssetsMissing`。可选字段让旧服务器也能读。
- `private static void warnIfAssetsMissing(Minecraft)`：进入世界时只提示一次：找不到 `ASSETS_CHECK` 纹理则打印并在聊天栏提示 `gui.mtr.assets_missing`（逻辑模组与资源模组分开后，2026-11-09；不设为依赖，因为服务器不需要资源模组）。
- `receiveServerTimeS2C(FriendlyByteBuf)`：网络线程中立即 `ClientClock.INSTANCE.onServerTime(毫秒)`（等主线程会让时间显得更晚）。
- `receiveTimetableConfigS2C(Minecraft, FriendlyByteBuf)`：`String 时区`、`int 运营日开始小时` → `TimetableConfig.set`（异常打印）。
- `openVersionCheckS2C(Minecraft, FriendlyByteBuf)`：读服务器版本；`IPacket.getProtocol` 比较前两段，不同则断开连接并显示两边版本（用空格对齐）与 `gui.mtr.mismatched_versions`。

### 打开界面（S2C，主线程中且当前不是同类界面时打开）
- `openDashboardScreenS2C`：`String 交通方式`、`boolean 时间与风同步` → `DashboardScreen`。
- `openStationAreaToolScreenS2C`：`BlockPos ×2` → 有权限时打开 `StationAreaToolScreen`。
- `openRailwaySignScreenS2C`：`BlockPos` → `RailwaySignScreen`。
- `openTrainSensorScreenS2C`：`BlockPos` → 按方块实体类型打开 `TrainAnnouncerScreen` / `TrainScheduleSensorScreen` / `TrainBasicSensorScreen`。
- `openNodeAngleScreenS2C`：`BlockPos` → `NodeAngleScreen`。
- `openLiftTrackFloorS2C`：`BlockPos` → `LiftTrackFloorScreen`。
- `openLiftCustomizationS2C`：`long 升降梯 id` → 客户端有该升降梯时 `LiftCustomizationScreen`。
- `openTicketMachineScreenS2C`：`int 余额` → `TicketMachineScreen`。
- `openPIDSConfigScreenS2C`：`BlockPos ×2`、`int 最多到站数`、`int 每条行数` → `PIDSConfigScreen`。
- `openArrivalProjectorConfigScreenS2C`：`BlockPos` → `ArrivalProjectorConfigScreen`。
- `openResourcePackCreatorScreen(Minecraft)`：→ `ResourcePackCreatorScreen`。

### 网页地图与寻路
- `requestMapRouteC2S(long routeId)`：请求某线路的地图数据（`PACKET_REQUEST_MAP_ROUTE`）。调用者：`common/src/main/java/mtr/screen/DashboardScreen.java:612`。
- `receiveStaleDepotsS2C`：`int 数`、各 `long 车厂 id` → 替换 `ClientData.STALE_DEPOTS`（需要重新生成路径的车厂）。
- `requestMapRailsC2S()`：请求全网轨道地图（空包）。
- `receiveMapDigestS2C`：`byte 种类`、`boolean 最后一批`、`int 数`、各 `long 键, long 哈希` → `MapDataCache.receiveDigest`。
- `sendMapWantedC2S(int kind, boolean isLast, List<Long> keys)`：告诉服务器缺哪些键。调用者：`common/src/main/java/mtr/client/MapDataCache.java:116`。
- `receiveMapDataS2C`：`byte 种类`、`long 键`、`long 哈希`、`int 长度`、各 `long` → `MapDataCache.receiveData`。
- `setKeyNodeS2C`：`BlockPos`、`boolean 是关键节点` → 增删 `ClientData.KEY_NODES`。
- `receivePathRegionsS2C`：`RegionDataPackets.read` → `ClientData.PATH_REGIONS`。
- `sendPathBoundaryC2S(BlockPos pos1, BlockPos pos2, boolean isBoundary)`：`BlockPos`、`boolean 有第二点`、[`BlockPos`]、`boolean 设为边界` → 设置/取消寻路边界。调用者：`common/src/main/java/mtr/screen/NodeAngleScreen.java:213`、`client/editor/EditorActions.java:575`。
- `requestStationLayoutC2S(int[] box, TransportMode, long[] seeds)`：盒子各 int、`int 交通方式序号`、种子（`StationLayouts.writeSeeds`）。
- `receiveStationLayoutS2C`：`StationLayouts.read` → `EditorLayout.receive`。
- `requestRouteLayoutC2S(long routeId, int halfWidth)` / `receiveRouteLayoutS2C`：线路布局 → `EditorLayout.receiveRoute`。
- `receiveSimulateResultS2C`：`int 请求 id, 状态, 忽略的草稿数, 缺运用路径数, 车厂数, 全部车厂数, 分片序号, 分片数`、`byte[]` → `EditorSimulation.receive`（网页仿真，调试用）。

### 时刻表
- `requestTimetableTimesC2S(long routeId)`：`long 线路`、`int 请求号`（`EditorTimes.SAVED_TIMES.request(route)`）；线路不存在不发。调用者：`common/src/main/java/mtr/screen/TimetableScreen.java:251`、`client/editor/EditorData.java:807`。
- `receiveTimetableTimesS2C`：`long 线路`、`int 站数`、每站 `long 到, long 发`、`PathFailure`、可选 `int 请求号`、可选 `VarInt 列数`（默认 2，必须为 2 到 `RouteTimetable.MAX_TABLES×2` 的偶数，否则丢弃），每站补读其余列。主线程中：请求号 > 0 为草稿回答 → `EditorTimes.receiveDraft`；否则若 `SAVED_TIMES.accept` 则存入 `ClientData.TIMETABLE_TIMES` 并更新 `ROUTE_PATH_FAILURES`。
- `requestTimetableConflictsC2S(long routeId)`：先清本地结果再请求。调用者：`TimetableScreen.java:716`。
- `receiveTimetableConflictsS2C`：`long 线路`、`int 未检查线路数`、`int 冲突数`、每个 `int,int,int,long,int,long,long`（`TimetableConflicts.Conflict` 构造参数）→ `ClientData.TIMETABLE_CONFLICTS`。

### 节点
- `sendNodeAngleC2S(BlockPos pos, float angle, boolean isKeyNode, int turnBackDwell)`：节点角度（度）、是否关键节点、折返停留时间。调用者：`NodeAngleScreen.java:210`。

### 广播与轨道/信号同步
- `announceS2C`：`String 消息`、`String 声音`、可选 `String 片段文本`（0.72.0，没有则单段）→ 主线程中 `IDrawing.narrateOrAnnounce(消息)`，并在玩家位置 `AnnouncementPlayer.play(片段, …)`。
- `createRailS2C`：`String 交通方式`、`BlockPos ×2`、`Rail ×2`、`long 站台/停车轨 id` → 两个方向加入 `ClientData.RAILS`。
- `createSignalS2C`：`long id`、`int 颜色序号`、`UUID 轨道` → `ClientData.SIGNAL_BLOCKS.add`。
- `removeNodeS2C` / `removeLiftFloorTrackS2C` / `removeRailConnectionS2C`：从客户端数据中删节点/升降梯层轨/轨道连接。
- `removeSignalsS2C`：`int 数`、每个 `long id, int 颜色, UUID 轨道` → 逐个移除。

### 分块数据与通用更新
- `receiveChunk(Minecraft, FriendlyByteBuf)`：`long 传输 id`、`int 块序号`、`boolean 最后一块`、其余字节。新 id 时清空；收齐（数量等于最后一块序号 + 1 且无缺）后拼接，在主线程 `ClientData.receivePacket`（全量数据）。块序号越界忽略。调用者：`MTRClient.java:266`、`fabric/src/main/java/mtr/mixin/UnknownPacketMixin.java:41`。
- `receiveUpdateOrDeleteS2C(Minecraft, FriendlyByteBuf, Set<T>, Map<Long,T>, BiFunction<Long,TransportMode,T> createDataWithId, boolean isDelete)`：用基类 `updateData`/`deleteData` 处理后 `DATA_CACHE.sync()` 并刷新动态资源。

### 发送修改（C2S）
- `sendUpdate(ResourceLocation packetId, FriendlyByteBuf packet)`：数据修改先交给 `ClientEditing.send`（附带版本号，用于多人编辑冲突检查）；不归它管的包直接发送；之后本地同步缓存。调用者：`client/editor/EditorActions.java:154` 等。
- `sendDeleteData(ResourceLocation packetId, long id)`：删除包（只含 id），走 `sendUpdate`。
- `sendTrainSensorC2S(BlockPos, Set<Long> filterRouteIds, boolean stoppedOnly, boolean movingOnly, int number, String... strings)`：`BlockPos`、`int 线路数`、各 `long`、`boolean ×2`、`int number`、`int 字符串数`、各 `String`。调用者：`common/src/main/java/mtr/screen/TrainSensorScreenBase.java:158`。
- `sendLiftTrackFloorC2S(BlockPos, String floorNumber, String floorDescription, boolean shouldDing)`。
- `placeTrainsC2S(long depotId)`：按运用放置列车。调用者：`client/editor/EditorServer.java:426`。
- `replanDutiesC2S(long depotId)` / `replanDutiesC2S(long depotId, String group)`：重新规划运用，`group` 为规划单元中某个运用组的键，空为全部（运用组第 4 步）。
- `generatePathC2S(long sidingId)`：请求生成路径；参数名写作停车轨，实际调用者都传车厂 id（`common/src/main/java/mtr/screen/EditDepotScreen.java:90`），服务端也按车厂 id 处理。
- `clearTrainsC2S(long depotId, Collection<Siding> sidings)`：`long 车厂`、`int 数`、各 `long 停车轨`。调用者：`common/src/main/java/mtr/screen/EditDepotScreen.java:138`。
- `sendUpdateEntitySeatPassengerPosition(double x, double y, double z)`。
- `sendSignIdsC2S(BlockPos, Set<Long> selectedIds, String[] signIds)`。
- `sendDriveTrainC2S(boolean accelerate, boolean brake, boolean doors)`：只在手持钥匙且有按键时发送。
- `sendPressLiftButtonC2S(long id, int floor)`、`addBalanceC2S(int addAmount, int emeralds)`。
- `sendPIDSConfigC2S(BlockPos pos1, BlockPos pos2, String[] messages, boolean[] hideArrival, Set<Long> filterPlatformIds, int displayPage, boolean showPassingTrains)`：末尾新增 `boolean 显示通过列车`。
- `sendArrivalProjectorConfigC2S(BlockPos, Set<Long>, int displayPage)`、`sendUseTimeAndWindSyncC2S(boolean)`。

### 车厂路径生成结果
- `generatePathS2C(Minecraft, FriendlyByteBuf)`：`long 车厂`、`int 成功段数`、`PathFailure`、`int 失败运用总数`、`int 列出数(≤64)` + 各 `String(≤256)`、`int 停车轨数(≤1024)` + 每个 `long 停车轨, DepotLegs`、`int 绕行说明数(≤64)` + 各 `String`、`int 折返时间条数`（−1 表示本次未测量，车厂保留原值）+ 每条 `long 从, long 到, long 毫秒`（最多 4096）、`int 冲突进路数(≤64)` + 各 `String(≤256)`、`int 轨道用法数` + 各 3 个 `long`（0.80.0）。主线程中写入车厂 `clientConflicts`，有用法或寻路成功时写 `clientTrackUses` 并让 `Depot.clientTrackUsesVersion` 加一、 `clientPathGenerationSuccessfulSegments`、`clientPathFailure`、`clientFailedDuties`、`clientFailedDutyCount`、`clientDetours`、`clientTurnTimes`（并 `turnTimesMeasured = true`），各停车轨 `setDepotLegs`，并把车厂从 `STALE_DEPOTS` 移除。
