# Siding.java

`Siding` 是停车线：车厂中停放列车的一段轨道（`SavedRailBase`），以及它拥有的列车（`TrainServer`）。它保存其列车运行的路径：车厂找路时由 `generateRoute`（按车厂线路连成的主路径、出库路、折返路与回库路，`TripPath`）或 `generateDutyRoute`（按运用运行的车厂：停车线自身即路径，列车走运用路径）生成；还保存提前折返（D4）的绕行与回库路、晚出/备车接替用的出库路（D5）、多折返线信息（`TurnBacks`）、记录的出入库腿（`DepotLegs`，第 4 轮）。每 tick `RailwayData.simulateTrains` 调用 `setSidingData` 与 `simulateTrain` 移动其列车，除非组件模拟器运行它（`isRunBySimulator()`，G1，由 `LiveSimulation` 调用 `showSimulatedTrains`/`stopSimulated`）。按运用运行时，同车辆（`getPoolKey`）的停车线组成车池共享列车，多余列车留在"库"（`shed`/`arriving`，P2-C）。`toSimulation` 把停车线导出为组件 `SimDataset.SidingData`（S4e）。数据三种写法：msgpack 存档（完整含列车/精简）、数据包、`update(key, packet)`。

## class Siding（extends SavedRailBase implements IPacket, IReducedSaveData）

### 字段
- `world`、`depot`：每 tick 由 `setSidingData` 设。
- `trainId`、`baseTrainType`、`trainCars`：车辆型号、基础车型与节数（由停车线长度 / 间距，最多运输方式最大长度）。
- `unlimitedTrains`、`maxTrains`：旧方式的列车上限（新停车线默认不限）。
- `routeIds`（TreeSet）：本停车线列车运行的线路（车厂的子集），空为全部；最多 `MAX_ROUTE_IDS`。
- `lastFailedDuties`（volatile）：上次找路找不到路径的运用键。
- `lastDepotLegs`（volatile）：上次找路得到的出入库方式，供高级编辑器运行图；服务端供车厂发送，客户端为收到的。
- `recordedLegs`（volatile）：按区段找到的计划用出入库腿（第 4 轮第 3 步），存档、发送并跨找路保留。
- `foundNoWayOut` / `foundNoWayBack`：本次找路找不到出库/回库路的首/末站站台（P2-A）。
- `fleetSize`：按运用时从本停车线运行的列车数（车队），可超过轨道容量；−1 为运用需要多少就多少。
- `isManual`、`maxManualSpeed`、`repeatIndex1/2`、`loopJoinIndex`（中途加入环线时到首站的轨道下标，0 为从首站开始）。
- `trainsRelocated`：路径重找或列车被清除，下一 tick 同步所有列车，模拟器从当前列车重新开始。
- `runBySimulator`（G1）、`ticksSinceLoad`。
- `accelerationConstant`、`decelerationConstant`（0 为与加速度相同）、`useSpeedCurve`、`maxSpeed`（km/h，0 无）。
- `tripTimesPending`：从存档读出的行程路径尚待用车厂设置计时（第 5 轮第 4 步）。
- `railLength`（public final）、`path`、`distances`、`timeSegments`、`stopTimes`、`platformTimes`。
- `ticksElapsed`（public）：经过的 tick；为字段而非参数，因为 ANTE 按 `simulateTrain` 签名注入。
- `lastSchedulesForPlatform`（public）：上 tick 完整的时刻。
- `motionEvents`（public）：运动事件去向，tick 末由世界收集；null 为丢弃。
- `trains`、`shed`（按运用时停在库中的同池列车，最早的在前）、`arriving`（本 tick 回库的，下一 tick 起入库）。
- `tripPaths`：离开通常路径去别的线路的班次路径，按 `TripTail#getKey()`。
- `turnBacks`（`TurnBacks`）、`turnLinks`（"from>to" → 绕行路，D4）、`turnReturns`（站台 → 回本停车线路）、`wayOuts`（站台 → 出库路，D5）；`pendingTurnLinks`、`pendingTurnTargets`、`pendingWayOutTargets`：车厂为进行中的找路给的待用数据。
- `pathVariants`（匿名 `PathVariants`）：生成跳站（D3）路径：`pass` 停站时间置 0、`withStop` 复制并设停站、`getTimeGenerator`、`getTurnTargets(platformId)`（以 "id>" 开头的绕行目标，排序）、`getTurnLink`、`getTurnReturn`、`getWayOut`。调用者：`TripPath.java:281`、`PathVariants.java:35`、`SimDataset.java:439`。
- `timeGenerator`（匿名 `TimeGenerator`）：`calculateStopTimes(path)` 与 `generateTimeSegments(...)` 委托本类。
- `vehicle`（`VehicleSettings`）：服务端每 tick 开始时设为车池车辆（R1）；客户端与首次前为 null，用本停车线自己的设置。

### 常量与存档键
- `DEPOT_WAYS`（public static ThreadLocal<DepotWays>）：本线程找运用路径时用的出入库路查找。
- `MAX_ROUTE_IDS = 1024`、`MAX_TRAINS = 999`、`MAX_UNLIMITED_TRAINS = 4096`。
- 键：`rail_length`、`trip_paths`、`turn_links`（每项 `from`、`to`、`path`）、`turn_returns`（`platform`、`path`）、`way_outs`（`platform`、`path`）、`train_type`、`train_custom_id`、`unlimited_trains`、`max_trains`、`route_ids`、`fleet_size`、`recorded_depot_legs`、`siding_operation`（只作 update 键：线路与车队一起设置）、`is_manual`、`max_manual_speed`、`path`、`repeat_index_1`、`repeat_index_2`、`loop_join_index`、`trains`、`shed`、`duty_ledger`、`acceleration_constant`、`deceleration_constant`、`max_speed`，以及 `TurnBacks` 自己的键。

## interface DepotWays
按区段、按线路运行站台的方向找到的停车线到站台的出库与回库路（第 4 轮第 3 步）；未设时在轨道上照旧搜索（草稿、测试）。
- `List<PathData> find(SavedRailBase from, SavedRailBase to, boolean out, long platformEnd)`：`platformEnd` 为线路运行该站台所朝的节点或 0。返回轨道（无路为空），null 表示改为在轨道上搜索。
- `default find(..., long pathId)`：`pathId` 为运用所走的车厂命名回送路径（运用补完第 3 步），0 为按规则；默认忽略。
- 实现/调用者：`Depot.java:1237`、`DepotWayPreview.java:100`。

### 构造函数
- `Siding(long id, TransportMode, BlockPos pos1, BlockPos pos2, float railLength)` / `Siding(TransportMode, pos1, pos2, railLength)`：新停车线；选择第一个能放下的车型；默认不限列车数（列车数为车队，在高级编辑器中设）；加速度：连续移动为最大，否则默认。调用者：`RailwayData.java:1492`。
- `Siding(Map<String, Value> map)`：从存档读：基本设置；旧存档无 `fleet_size` 时为原上限；`recorded_depot_legs`（旧存档无，按估计规划直到测量发现偏差）；加/减速度与最高速钳制；路径与时间段；`TurnBacks`；`turn_links`/`turn_returns`（0.32.0 起，D4）；`way_outs`（0.33.0 起，D5）；行程路径（完整或引用共享片段，第 5 轮第 2 步；片段缺失的重新生成；`PathPieces.remember` 使首次保存按原片段写出），待用车厂设置计时；运用账目（`DutyLedger`/`PathKeyIndex`；损坏的条目只跳过并记日志，停车线位置必须保留）；列车与库（`loadTrain`）；算距离；路径已不存在的列车删去（从停车线重新开始），跳站列车先设 `pathVariants`。调用者：`RailwayData.java:379`、`EditGuard.java:263`、`RailwayDataFileSaveModule.java:174`（`Siding::new`）。
- `@Deprecated Siding(CompoundTag)`：旧 NBT。调用者：`RailwayData.java:427`。
- `Siding(FriendlyByteBuf packet)`：客户端；字段顺序见 `writePacket`。调用者：`ClientEditing.java:321`。

### `private void loadTrain(Collection<TrainServer> list, Value value)`
- 读一列存档列车；读不出（如运用账目引用缺失）时记日志并跳过，使停车线仍能加载。

### `public void toMessagePack(MessagePacker)`
- 精简部分 + `duty_ledger`（所有列车、库、回库中的运用账目与路径键条目，按键排序）+ `trains` + `shed`（库与回库中）。

### `public void toReducedMessagePack(MessagePacker)`
- 父类字段 + 上述设置键 + `path` + `trip_paths`（每项 `TripPath.WRITTEN_VALUES + 1` 个键，路径经 `PathPieces.writePath`）+ `TurnBacks` + `turn_links`/`turn_returns`/`way_outs`（按键排序）。调用者：`RailwayDataLoggingModule.java:114`。

### `public int messagePackLength()` / `public int reducedMessagePackLength()`
- 父类 + 26；精简为其 − 3。

### `public void writePacket(FriendlyByteBuf packet)`
- 顺序：父类字段、`railLength`、`trainId`、`baseTrainType`、`unlimitedTrains`、`maxTrains`、`isManual`、`maxManualSpeed`、`accelerationConstant`、`decelerationConstant`、`maxSpeed`、运行设置（`writeOperation`：线路数、各线路、车队）、`recordedLegs`。调用者：`SandboxRuns.java:184`、`PacketTrainDataGuiServer`（发送全部）。

### `public void update(String key, FriendlyByteBuf packet)`
- `deceleration_constant`：钳制后设；`max_speed`；`siding_operation`（`readOperation`）；`recorded_depot_legs`；`train_type`：读两个字符串设车型，清空列车、标记重定位；`unlimited_trains`：名称、颜色、停站时间（连续移动为 1）、不限、上限（−1..999）、车队 = 上限、手动、手动最高速、加速度（钳制在最小/最大之间，NaN 为默认）、是否清空列车；其他交给父类。调用者：`DataCache.java:126`、`LiveSimulation.java:270`。

### `public void setTrainIdAndBaseType(String customId, String trainType, Consumer<FriendlyByteBuf> sendPacket)`
- 发送 `train_type` 更新并在本地设置。调用者：`SidingScreen.java:229`。

### `public void setUnlimitedTrains(boolean unlimitedTrains, int maxTrains, boolean isManual, int maxManualSpeed, float accelerationConstant, int newDwellTime, boolean clearTrains, Consumer<FriendlyByteBuf> sendPacket)`
- 发送 `unlimited_trains` 更新并在本地设置（车队 = 上限）。调用者：仅测试 `SidingOperationTest.java:112`、`TrainIdentityTest.java:96`。

### 读取
- `getTrainId()`、`getRouteIds()`（只读）、`getFleetSize()`、`getLastFailedDuties()`（`Depot.java:1251`）、`getDepotLegs()` / `setDepotLegs(DepotLegs)`（`PacketTrainDataGuiClient.java:657`）、`getRecordedLegs()`（`Depot.java:1149`）、`getTrainCount()`、`getTrainCars()`、`getTripPaths()`（只读；`Depot.java:1741`、`PidsSchedules.java:48`）、`getStopTimes()`、`getMaxTrains()`、`getTrainLimit()`（不限为 −1，否则 `maxTrains + 1`：停车线在不多于 `maxTrains` 列时再造一列）、`getUnlimitedTrains()`、`getWayOuts()`、`getTurnLinks()`、`getTurnReturns()`、`getPathVariants()`（`Depot.java:1751`）。

### `public void forEachHeldTrain(HeldTrainConsumer consumer)`
- 对每列在线上没有后续运用而等待的列车（`getOvernightHold() != null`）给出其槽位、等待的站与原因名。调用者：`HeldTrains.java:42`。

### `public boolean recallHeldTrain(int slot)`
- 让该槽位等待中的列车直接回停车线。返回是否有。调用者：`HeldTrains.java:95`。

## interface HeldTrainConsumer
- `accept(int slot, long stopId, String reason)`。

### `public void setRecordedLegs(DepotLegs legs, Consumer<FriendlyByteBuf> sendPacket)`
- 保存计划用出入库腿并以 `recorded_depot_legs` 发给客户端。调用者：`Depot.java:1151`、`:1194`。

### `public void setOperation(Collection<Long> newRouteIds, int newFleetSize, Consumer<FriendlyByteBuf> sendPacket)`
- 设置线路（空为全部，最多 1024）与车队（−1..1000），`limitToFleet`，发送 `siding_operation`。调用者：`EditorActions.java:223`。

### `private void writeOperation(...)` / `private void readOperation(...)`
- 线路数超出 0..1024 时抛 `IllegalArgumentException`；读后 `limitToFleet`。

### `private void limitToFleet()`
- 网页编辑器设的车队也是不按运用运行的种类所用的上限（5c，游戏的停车线界面不再设）：车队 <0 或连续移动为不限，否则 `maxTrains = 车队 − 1`。

### `public String getPoolKey()`
- 返回：同车厂中列车可互相跑运用的停车线的共同点："车辆型号/节数"（解耦文档步骤 D，发车 4、5）。调用者：`EditorData.java:177`、`EditorDuties.java:192`。

### `public void forEachPathNodes(Consumer<long[]> consumer)`
- 依次给出通常路径与各运用路径的节点，用于判断哪些车厂共轨（`LockNetworks`）。调用者：`RailwayData.java:132`。

### `public long getPathSignature()` / `private static long pathSignature(...)` / `private static long[] nodesOf(...)`
- 路径端点与长度的哈希（行程路径相加，与顺序无关），找路换路径时改变；节点序列。调用者：`RailwayData.java:118`。

### 库（P2-C）
- `getShedCount()`：库中与本 tick 回库的列车数。
- `getHeldCount()`：停在停车线上（不在线）的列车 + 库中列车，用于判断是否还能容纳。调用者：`Depot.java:1791`。
- `keepArrived()`：本 tick 回库的从下一 tick 起入库（同组件运行器）。调用者：`RailwayData.java:602`。
- `private TrainServer adopt(TrainServer train)`：同一列车作为本停车线列车继续（P2-C、R1）：本停车线的新列车，运行状态重新开始，持有其身份（ID、货物、乘客、ANTE 设置相同）；已是本停车线的原样返回。
- `showSimulatedShed(List<Long> shedIds, Map<Long, TrainServer> known)`：让停车线保留模拟器库中的列车，每列为游戏中同 ID 的列车（不论原在何处）。调用者：`LiveSimulation.java:624`。
- `indexTrains(Map<Long, TrainServer> index)`：把站着的与库中列车按 ID 加入索引。调用者：`LiveSimulation.java:617`。
- `countReturningTo(long otherSidingId)`：正回到另一停车线的列车数。调用者：`Depot.java:1842`。

### 车辆设置（R1）
- `getOwnVehicleSettings()`：停车线自身设置的车辆（池的首条停车线且车厂未设时池采用它）。调用者：`Depot.java:2469`、`SidingScreen.java:89`。
- `getMaxSpeed()`（池车辆的，或自身；km/h，0 无）、`getOwnMaxSpeed()`、`setMaxSpeed(int, Consumer)`（发送 `max_speed`；调用者：本仓库主代码中未找到）。
- `getAccelerationConstant()`、`getDecelerationConstant()`（未设为加速度）、`getManualToAutomaticTime()`（自身时用停站时间字段）、`getIsManual()`、`getMaxManualSpeed()`：有池车辆且非连续移动时取车辆的值。
- `setDecelerationConstant(float, Consumer)`：发送 `deceleration_constant`（调用者：主代码中未找到）。
- `private static int clampMaxSpeed(int)`：0..`VehicleSettings.MAX_SPEED`；`private static float clampDeceleration(float)`：NaN/≤0 为 0，否则保留 3 位并钳在最小/最大加速度间。

### `public void removeRider(UUID uuid)`
- 从各列车乘客中移除。调用者：`RailwayData.java:1353`。

### `public void setSidingData(Level world, Depot depot, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：每 tick 开始：无车厂时清空列车与路径；否则路径为空时生成默认路径与距离；取车厂给的车池车辆；速度曲线开关或车辆（与自身/上次比较）变化时重算时间段；变化或有待计时的行程路径时在共享线程上逐条 `generateTimes`（第 5 轮第 4 步）；把站台时分写入车厂 `platformTimes`。调用者：`RailwayData.java:582`。

### `generateRoute(...)`（5 个重载）
- 签名演进：`(server, mainPath, successfulSegmentsMain, rails, firstPlatform, lastPlatform, repeatInfinitely, cruisingAltitude, useFastSpeed)` → 加 `graph` → 加 `loopJoinPath`、`tripTails` → 加 `turnBackSections`、`depotReturns` → 加 `dutyPlans`、`poolSidings`。
- 参数：`firstPlatform` 列车首次加入路径处；`loopJoinPath` 中途加入环线时到首站的余下路程（只走一次）；`tripTails` 班次离开主路径去其他线路处；`turnBackSections` 主路径可在多条轨道上折返处（带站序）；`depotReturns` 在线过夜列车某线路后可回库处；`dutyPlans` 按运用时本停车线列车的运用；`poolSidings` 同池其他停车线（运用后可回去）。
- 作用：在找路线程上：停车线 → 首站；接上环线加入段与主路径，设重复下标；为每个班次尾找回库路并 `addTripPath`；末站 → 停车线；有多折返线时 `addTurnBackPaths` 并生成 `TurnBacks`；`addDepotReturnPaths`；计时；为每个运用 `addDutyPath`（失败的打印"The path of a duty can't be found"）；记 `lastDepotLegs`（含不可达站台）；找提前折返回库路与出库路；`PathPieces.precut`。然后在服务器线程应用：找路失败且已有路径时保留旧路径与全部列车；否则替换路径、时间段、停站时间、站台时分、距离、重复下标，保留正在使用且未过期的行程路径（`ActiveTripPaths.retain`），替换行程路径与折返数据，`recordPaths`，重新定位列车。
- 返回：成功的段数（0 无首/末站；1 出库失败；主段数 + 1 回库失败；+2 成功）。
- 调用者：`Depot.java:4207`。

### `generateDutyRoute(MinecraftServer, rails, graph, cruisingAltitude, useFastSpeed, dutyPlans, poolSidings)` / `generateDutyRoute(Executor applier, ...)`
- 作用：按运用运行的车厂（线路不连成一条路径）：停车线本身是路径，列车走运用路径；总是采用新的运用路径（一条线路不可达不保留旧的）。`applier` 在服务器线程或（无服务器时，如找路端到端检查）立即应用。
- 调用者：`Depot.java:1629`、`:3570`。

### `private boolean addDutyPath(Map<String, TripPath<PathData>> tempTripPaths, DutyPlan<PathData, SavedRailBase> dutyPlan, rails, graph, cruisingAltitude, useFastSpeed, List<Siding> poolSidings, Map<String, List<PathData>> returnTails, List<TurnBackPoint> dutyPoints, int firstDutyPoint)`
- 作用：本停车线一个运用的路径（设计文档 6.12、E2）：出库到首站（除非在线过夜起步；优先 `DEPOT_WAYS`，按线路运行首站的方向与命名路径，第 4 轮；否则搜索）、各段、回停车线（除非留在线上）。每个可多路折返的地方成为停车线自己的折返点。生成通常路径、回同池其他停车线的路径（`TripPath.getReturnKey`）、以及每个折返点的其他走法（`TripPath.getTurnBackKey`，回本停车线）。
- 参数：`dutyPoints` 为目前的运用折返点（追加），null 只给每处通常走法（草稿）；`firstDutyPoint` 为第一个运用折返点在停车线所有点中的编号。
- 本停车线回不去时（0.75.4）：通常路径改为回同池第一条能回去的停车线（列车到末站时 `TrainService.chooseDutyReturn` 换到回那条的路径），不再整份失败；六线图 1、4 号线的车从一条存车线出、从末站只能回另一条。
- `-Dmtr.debugDutyPaths=true` 时打印失败原因（没有出库路到哪个站台、从哪个站台回不到本停车线和同池哪些停车线）。
- 返回：任一部分找不到为 false（并在非草稿时记入 `foundNoWayOut`/`foundNoWayBack`）。

## static final class DutyBody
运用主体按列车所走：每个折返点走通常方式，或其中一处走另一方式。
- 字段：`body`（`JoinedPath`）、`segmentStarts`、`indexBoundaries`、`stopRails`（每个折返点之前停站的主体轨道，经过替换走法内部的点为 −1）。
- `DutyBody(DutyPlan, List<TurnBackSection>, int changedSection, int way)`：`changedSection` 为走另一方式的区段或 −1；替换时停站与下一线路首站仍属主体（保留停站设置），之后的段起点、索引边界与停站轨道按多出的轨道数平移。调用者：`Siding.java:1361`、`:1389`、`DutyBodyTest.java:41`。
- `join(List<PathData> wayOut)`：把主体接在出库路之后；出库路最后一段与主体首段是同一轨道时用主体那段（保留首站停站时间）。调用者：`LiveSimulation.java:397`、`DutyBodyTest`。
- `getIndexBoundaries(wayOut)` / `getStarts(wayOut)`：加上主体起点偏移。
- `getTurnBackStops(int[] points, int pointCount, wayOut)`：停车线每个点之前停站的轨道，其他路径的点为 −1。
- `private getBodyStart(wayOut)`。

### `private static List<PathData> getReturnTail(DutyPlan, Siding siding, rails, graph, cruisingAltitude, useFastSpeed, Map<String, List<PathData>> returnTails)`
- 返回：从运用末站进入某停车线的路（每个末站/停车线/回程命名路径只找一次，优先 `DEPOT_WAYS`），拷贝；无为 null。

### `private static long runningEnd(DutyPlan, boolean first)`
- 运用主体中首站（或末站）轨道的末端节点，即运行朝向；无为 0。

### `private static List<PathData> copyPath(List<PathData>)`
- 逐段 `copy`。

### `private void addDutyTripPath(...)`
- 用 `PathPieces.indexDuty` 建 `TripPath`、计时、加入。

### `private void recordPaths()`
- 把通常路径（"siding:id"）与各行程路径（"siding:id:键"）交给 `graphRecords` 写入数据库。

### `private void addTripPath(...)`
- 主路径前部 + 主路径到离开轨道 + 班次尾 + 回库路，记圈终、尾起点、重复终点，计时加入。

### `private void addTurnBackPaths(...)`
- 主路径不走的每个折返走法，为通常路径和该点之后离开的每个班次各生成一条路径；只有那段走法不同，列车可在折返点前一站换到这些路径上。

### `private void addDepotReturnPaths(...)`
- 在线过夜列车在标记的班次后回库：照常在该线路末站后折返，从那里去车厂而不是下一线路。键 `TripPath.getDepotReturnKey(routeIndex)`。

### `private static int[] getTurnBackStops(List<TurnBackSection<PathData>> sections, int mainOffset, int leaveRail, int changedPoint, int extraRails)`
- 路径上每个折返点之前停站的轨道（在离开前经过的点），不经过为 −1；在改走的点之后的加上多出的轨道。

### `private void relocateTrains(List<String> oldRails, List<Double> oldDistances)`
- 在行程路径上的列车跑完原路径（只更新停车线重复下标）；停着的原地重定位；在线的用 `PathRelocator.locate` 找到新路径上的对应位置，找不到则删除。标记重定位。

### `private static List<String> getRailKeys(List<PathData>)`
- "起点>终点" 列表。

### `public void simulateTrain(DataCache dataCache, RailwayDataDriveTrainModule railwayDataDriveTrainModule, List<Map<UUID, Long>> trainPositions, SignalBlocks signalBlocks, Map<Player, Set<TrainServer>> trainsInPlayerRange, Set<TrainServer> trainsToSync, Map<Long, List<ScheduleEntry>> schedulesForPlatform, Map<Long, Map<BlockPos, TrainDelay>> trainDelays)`
- 作用：本停车线的一 tick（无车厂不做）：
  1. 重定位过则同步所有列车；加载后第 2 个 tick（非模拟器时）按进度从远到近把需要对齐的列车对齐时刻表。
  2. 每列车：手动驾驶处理；设停站时间与行程路径、跳站方式、速度曲线、车池车辆（R1）、车辆最高速、上 tick 时刻；`TrainServer.simulateTrain`（需同步则加入）；运动事件交给车厂缓冲记录器（D1，带 `BufferRecorder.plannedArrivals(train.getService())`，出库按首站晚点算，2026-11-13）并加入 `motionEvents`；回到同池另一停车线的列车从本处删除并 `adopt` 到目标的 `arriving`（P2-C）；模拟器运行时只写占用。
  3. 否则：靠近车厂的列车阻止出新车；停着的第二列及以后删除（按运用时入库，`FleetRules.keepsSecondStanding`）；同一进度的重复列车删除；写占用。
  4. 补车：按运用（或支持时刻表的非连续移动种类，R3）时按组件规则 `FleetRules.whatToMake`（S7）从库中取或新造（线路池车队、池列车、池槽位、容纳数、车队）；否则按旧上限新造。
- 调用者：`RailwayData.java:599`。

### `public double getTrainLength()` / `public boolean isValidVehicle(int spacing)`
- 列车长度（节数 × 间距，方块）；停车线（至少 2）是否放得下一节。调用者：`StopLengths.java:32`、`SidingScreen.java:228`。

### `public boolean areTripPathsStale(rails)` / `public boolean isPathStale(rails)`
- 运用路径中有轨道没了或变了（测量出的出入库腿不再成立）；通常路径（非飞机、≥2 段）有轨道没了或类型/朝向/长度变了。调用者：`Depot.java:1149`、`RailwayData.java:1053`。

### `toSimulation(long depotId, long[] routeIds, Function<List<PathData>, List<SimDataset.DataTrack>> toTracks[, boolean byDuties])`
- 返回：组件模拟器需要的停车线（S4e），无路径或车厢为 null：基本参数、上限（按运用为车队 − 1 或不限）、速度曲线、默认最高速、重复下标、轨道、`TurnBacks` 二进制、手动、池键、线路池（`depot.getLineUnitKey() + "|" + 池键`，SimDataset 7）、池车队（SimDataset 11）、车辆最高速（15）、提前折返（13）、出库路（14）、按键排序的行程路径（保留正在用且未过期的）。调用者：`SimExport.java:73`、`SandboxRuns.java:242`。

### 模拟器（G1）
- `isRunBySimulator()`、`hasTrainsRelocated()`（`LiveSimulation.java:642`）、`hasSettled()`（加载后已跑 2 tick）、`canBeSimulated()`（有路径且车厢 >0）、`whyNotSimulated()`（日志用原因）。
- `showSimulatedTrains(List<HeadlessTrain> simulatedTrains, Set<Long> takenOver[, Map<Long, TrainServer> known])`：停车线列车显示模拟器的列车：每列一个、同 ID，删去其他（游戏自己移动的手动列车除外）；`known` 为模拟器运行的停车线的游戏列车，使移到本停车线的列车是同一列（P2-C）。调用者：`LiveSimulation.java:623`。
- `stopSimulated()`：游戏重新从模拟器所在处移动列车。调用者：`LiveSimulation.java:587`。
- `getCarryOnKey()`（0.66.2）：决定模拟器能否继续本停车线列车的因素（行程路径键集合、每列车 ID、是否在线、路径键），只在它变化时才重试。调用者：`LiveSimulation.java:715`。
- `forEachTrain(Consumer<TrainServer>)`：按 ID 排序。调用者：`RailwayData.java:810`、`LiveSimulation.java:256`。
- `recordTrains(RunRecord record)`：把非手动列车记录加入（G0），并记库中列车 ID。调用者：`SimExport.java:79`。
- `static toSimulation(TripPath<PathData>, toTracks)`：行程路径二进制 + 轨道。

### `public Map<String, TripPath<PathData>> findDutyPaths(List<DutyPlan<PathData, SavedRailBase>> dutyPlans, rails, graph, cruisingAltitude, useFastSpeed, Consumer<String> failed)`
- 作用：像找路那样找这些运用的路径但与停车线分开（运用草稿，S6），只有回本停车线的路径；找不到的键交给 `failed`。返回按运用形状键的路径。调用者：`SandboxRuns.java:143`、`Depot.java:1605`。

### `public List<UUID> getTurnBackCandidates(UUID rail)`
- 可代替通常路径上这条折返轨道的轨道，按列车尝试顺序。调用者：`PacketTrainDataGuiServer.java:906`。

### `public StopTimes calculateStopTimes(List<PathData> newPath)`
- 用本停车线列车对一条路径计时（没有车厂单独运行的线路用）。调用者：`Depot.java:2843`、`PacketTrainDataGuiServer.java:1047`。

### `public long[] getPathNodes()`
- 通常路径节点。调用者：`RailwayData.java:1044`。

### `public void clearTrains()`
- 清空列车并标记重定位。调用者：`PacketTrainDataGuiServer.java:512`。

### `private void setTrainDetails()` / `setTrainDetails(String trainId, String baseTrainType, boolean force)`
- 选第一个同运输方式且放得下的车型；设置时 `base_` 改 `train_`（兼容），放不下且非强制时改选默认。

### `private void generateDefaultPath(rails)`
- 清空列车；路径为停车线本身一段（若轨道存在）；加一列以停车线 id 为 id 的列车。

### `private void generateDistances()`
- 累计距离；路径不止一段时删去 id 与"不限"状态不符的列车（`(train.id == id) == unlimitedTrains`）。

### `private void generateTimeSegments(path, timeSegments, platformTimes, stopTimes, joinIndex, lapEndIndex)`
- `PathTiming.generateTimeSegments(getTimingVehicle(), ...)`。

### `public PathTiming.Vehicle getTimingVehicle([boolean speedCurves])`
- 路径计时所依赖的本停车线列车参数：长度、节数、间距、加/减速度、速度曲线、运输方式、车辆最高速。调用者：`Depot.java:1364`、`DepotWayPreview.java:128`。

### `public static int getStopRail(List<PathData> path, List<Route.RoutePlatform> stops, int stop)`
- 返回：沿路径依次匹配线路各站（站台 id 相同且停站或为通过站台的首段），第 `stop` 站的轨道；无为 −1。调用者：`Depot.java:2996`、`:3090`、`:3111`。

### `public static boolean isPassingPlatform(List<PathData> path, int index)`
- 停站或折返之后的轨道也带该站台 id，只有第一条带此 id 的才算（`PathTimes.isPassingPlatform`）。调用者：`StopSettingsTest.java:60`。
