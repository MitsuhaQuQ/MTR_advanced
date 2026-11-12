# ClientData.java（上游文件，本分支有较多改动）

客户端：客户端所知的铁路数据——车站、站台、停车轨、线路、车厂、玩家附近的列车（`TRAINS`）、电梯，以及索引（`DATA_CACHE`）。由服务器发来的包填充，供界面和渲染读取。`tick()` 每客户端 tick 运行（手动驾驶按键等）。包接收注册在 `common/src/main/java/mtr/MTRClient.java:328`–`:353`（fabric 另有 `fabric/src/main/java/mtr/mixin/UnknownPacketMixin.java:45`–`:55` 处理部分包），`tick` 在 `MTRClient.java:575`。

本分支新增的内容：信号闭塞、关键节点、寻路区域、应急预案、地图数据（`MAP_*`）、时刻表时间与冲突、分段占用、列车状态增量包与快照请求、到站信息按时间重算、服务器权限与开关（`hasEditPermissionLevel`、`editorSimulation`、`anteRunHooks`、联锁/留置颜色）。

## 类 `ClientData`（final）

### 搜索框文本（界面之间保留）
- `DASHBOARD_SEARCH`、`ROUTES_PLATFORMS_SEARCH`、`ROUTES_PLATFORMS_SELECTED_SEARCH`、`TRAINS_SEARCH`、`EXIT_PARENTS_SEARCH`、`EXIT_DESTINATIONS_SEARCH`。

### 私有输入状态
- `pressingAccelerate`、`pressingBrake`、`pressingDoors`（上一 tick 按键状态，用于检测按下沿）；`shiftHoldingTicks`（按住潜行的累计时间，单位为帧时长累加，见 `MTRClient.getLastFrameDuration`）。

### 数据集合
- `STATIONS`、`PLATFORMS`、`SIDINGS`、`ROUTES`、`DEPOTS`、`LIFTS`：完整数据包（`receivePacket`）替换。
- `SIGNAL_BLOCKS`（`SignalBlocks`）：信号闭塞及其占用状态。
- `KEY_NODES`：关键节点位置。
- `PATH_REGIONS`（`RegionData`）：寻路边界与其划分的区域，变化时由服务器发送。
- `CONTINGENCY_SYSTEMS`：各铁路系统的应急预案（D2）。
- `MAP_RAILS`（`MapRail` 列表）、`MAP_ROUTES`、`MAP_SIDINGS`（键 → 节点数组）、`MAP_RAIL_SHAPES`（轨道键 `RailKeys.of` → 曲线上若干点 `x, z, …`，相对轨道较低节点）：由 `MapDataCache.publish` 填写。
- `STALE_DEPOTS`：数据已过期的车厂 id（`PacketTrainDataGuiClient.java:218` 写入，网页编辑器读）。
- `TIMETABLE_TIMES`：线路 id → 时刻（`long[][]`，格式见 `EditorTimes`）。
- `ROUTE_PATH_FAILURES`：网页编辑器请求时刻的线路为何没有路径。
- `TIMETABLE_CONFLICTS`：时刻表冲突结果。
- `OCCUPIED_RAILS`：轨道键 → 是否被占用。
- `OCCUPIED_SECTIONS`：全世界被列车占用的闭塞小段（轨道键 → 小段序号集合，从 0 开始，`BlockSections`）；旧服务器不发送时为 null。
- `RAILS`：节点 → (节点 → `Rail`)，玩家附近的轨道。
- `TRAINS`：玩家附近的 `TrainClient`。
- `RAIL_ACTIONS`：进行中的轨道操作（`DataConverter`：id、显示文本、颜色）。
- `SCHEDULES_FOR_PLATFORM`：给其他模组读取的到站（0.65.31：只含在此停站且尚未到达的列车，`ScheduleEntry.shownToOtherMods`）。
- `ALL_SCHEDULES_FOR_PLATFORM`：本模组显示用的全部到站（含通过与计划中的）。
- `DATA_CACHE`：`ClientCache` 实例。
- `PLAYER_RIDING_COOL_DOWN`（私有）：乘车玩家 → 冷却 tick。
- `RECEIVED_SCHEDULES`（私有）：最后收到的原始到站数据，据此生成上面两表，并在有已知发车时间的列车停站时每 tick 重算（0.67.0）。
- `nextTrainSnapshot`、`trainSnapshotConnection`（私有）：快照请求节流。

### 服务器开关（由 `PacketTrainDataGuiClient.java:68`–`:70` 等写入）
- `hasEditPermissionLevel`：服务器权限等级是否允许本玩家编辑（`SecurityConfig.editPermissionLevel`），默认 true。
- `editorSimulation`：服务器是否允许网页编辑器做日模拟（调试工具），默认 false。
- `anteRunHooks`：服务器是否运行 ANTE 的运行钩子（使停车轨界面提供 ANTE 的手动驾驶速度），默认 false。
- `interlockColor`（默认 MAGENTA）：联锁区的信号闭塞颜色；`stablingColor`（默认 BROWN）：留置闭塞颜色（运用补完第 4 步）。

### `private static void requestTrainSnapshot(Minecraft client)`
- 作用：向服务器发 `PACKET_REQUEST_TRAIN_RESYNC`（空包）请求完整列车快照；同一连接内至少间隔 2000 ms（连接变化时重置）。无连接时不发。

### `private static void updateSchedules(boolean received)`
- 作用：对每个站台，用当前时间与站台停站时间（`platform.getDwellTime() * 500L` ms，即停站时间以 0.5 秒为单位）把收到的条目换算为当时的到站（`ScheduleEntry.atTime`）写入 `ALL_SCHEDULES_FOR_PLATFORM`，再用 `ScheduleEntry.forOtherMods` 生成 `SCHEDULES_FOR_PLATFORM`。`received` 为 false（每 tick）时只重算有条目 `departureMillis > 0` 的站台；为 true 时全部重算并删除不再出现的站台。

### `static void tick()`
- 作用：重算到站；乘车冷却递减并移除到期者；读取驾驶按键（加速、制动、开关门），把"本 tick 刚按下"发给服务器（`PacketTrainDataGuiClient.sendDriveTrainC2S`）；累计按住潜行时间。

### `static void writeRails(Minecraft client, FriendlyByteBuf packet)`
- 包格式：`int 起点数`，每个：`BlockPos 起点, int 数量`，每个 `BlockPos 终点, Rail`。主线程替换 `RAILS`。

### `static void updateTrains(Minecraft client, FriendlyByteBuf packet)`
- 包：连续的 `TrainClient` 直到读完。主线程：新列车加入；同 id 但车辆不同（停车轨换了车辆而保留列车 id，`isSameVehicle` 为 false）时旧车标记移除、清屏蔽门、用新车替换；否则 `copyFromTrain`。调用者：`MTRClient.java:329`、`common/src/main/java/mtr/data/TrainClient.java:27`。

### `static void updateTrainStates(Minecraft client, FriendlyByteBuf packet)`
- 运行中变化的状态（G2），只对客户端已有且路径相同的列车：连续的 `TrainClient.State`；找不到列车或 `applyState` 失败时请求快照。调用者：`MTRClient.java:330`。

### `static void deleteTrains(Minecraft client, FriendlyByteBuf packet)`
- 包：`int 数量, long id…`（要保留的列车）；其他列车标记移除并清屏蔽门后删除。

### `static void updateLifts(…)` / `static void deleteLifts(…)`
- 同上，电梯（`LiftClient`）；新增或删除后 `syncLiftIds`。

### `static void updateTrainPassengers(…)` / `updateTrainPassengerPosition(…)` / `updateLiftPassengers(…)` / `updateLiftPassengerPosition(…)`
- 包：`long 列车/电梯 id, float percentageX, float percentageZ, UUID 玩家`。分别 `startRidingClient` / `updateRiderPercentages`（乘客在车内的相对位置，0–1 比例）。

### `static void updateRailActions(…)`
- 包：`int 数量`，每个 `long id, String 玩家, float 长度, String 方块翻译键, String 文本键后缀, int 颜色`；显示文本为 `gui.mtr.<后缀>`(玩家, 长度, 方块)。主线程替换 `RAIL_ACTIONS`。调用者：`MTRClient.java:352`。

### `static void updateSchedule(Minecraft client, FriendlyByteBuf packet)`
- 包：`int 站台数`，每个 `long 站台 id, int 条数, ScheduleEntry…`；`int 闭塞数`，每个 `long id, boolean 占用`；`int 轨道数`，每个 `UUID 轨道键, boolean 占用`；若剩余 ≥ 4 字节：`int 小段数`，每个 `UUID 轨道键, int 小段序号`（否则 `OCCUPIED_SECTIONS = null`）。主线程更新到站、闭塞状态与占用。调用者：`MTRClient.java:353`。

### `static void receivePacket(FriendlyByteBuf packet)`
- 完整数据包：通知 `EditorTimes.beginConnection`；按序读 车站、站台、停车轨、线路、车厂、电梯、信号闭塞（每类 `int 数量` + 对象），再 `int 数量` + 关键节点 `BlockPos`；清空列车（并清屏蔽门）并请求快照；`DATA_CACHE.sync()`、刷新动态纹理、`SIGNAL_BLOCKS.writeCache()`。调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:556`。

### `static <T extends NameColorDataBase> Set<T> getFilteredDataSet(TransportMode, Set<T>)`
- 返回：交通方式相符的子集。调用者：`common/src/main/java/mtr/screen/DashboardScreen.java:302`、`:323`。

### `static void updatePlayerRidingOffset(UUID uuid)` / `static boolean isRiding(UUID uuid)`
- 设置乘车冷却 2 tick；是否在冷却中（玩家渲染偏移用）。调用者：`common/src/main/java/mtr/data/VehicleRidingClient.java:110`；`fabric/src/main/java/mtr/mixin/PlayerRendererOffsetMixin.java:18`。

### `static boolean hasPermission()`
- 返回：有玩家、连接与玩家信息，`hasEditPermissionLevel` 为 true，且游戏模式允许（`RailwayData.hasPermission`）。调用者：`common/src/main/java/mtr/client/editor/EditorActions.java:62`、`:238`。

### `static float getShiftHoldingTicks()`
- 调用者：`common/src/main/java/mtr/render/RenderTrains.java:409`。

### `private static <T> Set<T> deserializeData(FriendlyByteBuf, Function)`
- 读 `int 数量` 后逐个构造。

### `private static clearAndAddAll(Collection/Map target, source)` / `private static TrainClient getTrainById(long id)`
- 替换内容；按 id 线性查找列车（异常打印并返回 null）。

## 内部类 `MapRail`
- 字段：`x1, z1, x2, z2`（两端方块坐标）、`transportMode`、`node1`、`node2`（`BlockPos.asLong`）、`speedLimit`（km/h，未知为 0）。
- 构造：`MapRail(pos1, pos2, transportMode[, speedLimit])`。调用者：`common/src/main/java/mtr/client/MapDataCache.java:165`、测试 `common/src/test/java/mtr/client/SignalAspectsTest.java:68`。
