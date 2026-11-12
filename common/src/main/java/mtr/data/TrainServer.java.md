# TrainServer.java

`TrainServer` 是服务端的列车，在 `Train` 之上：检查前方轨道是否被占（`isRailBlocked`，含车站咽喉锁 L2 与闭塞分区）、记录延误、开关站台与屏蔽门、上下乘客、触发传感器/货物装卸/报站方块、向显示屏报告到站时刻（`schedulesForPlatform`、`StationCall`），并在 `RunReservations` 中持有与申请"运行"（单线上列车互相等待，设计文档 6.12、E2、F1）。按时刻表与运用的运行逻辑在组件的 `TrainService`（`getService()`）中，停站规则在组件 `TrainRules` 中（内部类 `RulesTrain` 给它看本列车，`ServiceVehicle` 给 `TrainService` 看本列车）。当组件模拟器运行其停车线时（G1），`setSimulated` 交给它模拟器中的列车，本列车只显示那一列的位置（`showSimulated`）。由 `Siding.simulateTrain` 每 tick 调用 `simulateTrain(...)`；组件侧对应物为 `TickRunner.HeadlessTrain`。

## class TrainServer（extends Train）

### 字段
- 运行环境（每 tick 由 `simulateTrain` 设）：`trainPositions`、`trainsInPlayerRange`、`trainDelays`、`depot`、`dataCache`、`world`。
- `canDeploy`：车厂已允许其出库。
- `routeId`：当前时刻表段所属线路（用于传感器过滤与延误）。
- `updateRailProgressCounter`（每 `TICKS_TO_SEND_RAIL_PROGRESS = 40` 归零）、`manualCoolDown`。
- `service`（`TrainService<PathData>`）：按车厂运用与时刻表的运行。
- `rules`（`RulesTrain`）。
- 调试（0.65.31–0.66.8）：`holdReason`、`railBlockedLast`、`doorFlips`（最近一分钟车门变化时刻）、`doorFlipsToldMillis`、`lastDepartureMillis`/`lastPlannedDepartureMillis`/`lastDepartureStopId`、`lastArrivalMillis`/`lastPlannedArrivalMillis`、`lastStopHolds`/`stopHolds`、`reportedSchedules`（最多 4 条）、`reportedStartingIndex`、`reportedHidden`、`reportedNone`。
- `syncRepeatIndices`、`pathRepeatIndex1/2`：当前路径的重复下标及是否需要同步。
- `turnBackRails` / `turnBackUntil`：为本列车保留的折返轨道，直到车尾过了路径上该下标。
- `grantedRunEnd` / `requestedRunEnd`：已获得 / 已申请的运行终点下标（−1 无）。
- `throatLocks` / `throatZones`：路径上锁定通过的车站咽喉（L2）及其所用的联锁区。
- `waitingSinceMillis`：开始等待申请的运行的时刻（D2）。
- `lastSchedulesForPlatform`（public）：上 tick 的站台时刻，用于判断越行。
- `timeSegments`、`platformDwell`、`stationCall`、`passingCalls`（通过而不停的站台，每个保留到列车经过，使时刻传感器只触发一次，2026-11-12）。
- `depotPath`/`depotDistances`/`depotTimeSegments`/`depotRepeatIndex1/2`：停车线通常的路径。
- `cruiseCapMissing`：从没有保存巡航上限的存档读出，首个 tick 按位置重新算出。
- `simulated`（`TickRunner.HeadlessTrain`）、`sentSegmentVersion`、`simulatedService`（上次复制进来的服务字节）、`lastTripPaths`、`missingPathTold`。
- `authorityEnd`、`runSegmentSpec`：运行层的行车许可终点与运行段（5.5），使重启后照旧（0.41.0）。
- `manualAuthority`、`manualTracks`、`manualTracksFor`：手动驾驶的行车许可及路径轨道。
- `lastCheckedRailProgress`、`lastCheckedSpeed`、`lastCheckedMillis`：方块扫描的扫掠状态（G3）。

### 常量与存档键
- `DOOR_FLIPS_TO_TELL = 6`、`TRAIN_UPDATE_DISTANCE = 128`、`TICKS_TO_SEND_RAIL_PROGRESS = 40`、`SWEEP_STEP = 0.5`。
- `STOP_STATE_RUNNING = 0`、`STOP_STATE_AT_NEXT = 1`、`STOP_STATE_AT_LAST = 2`。
- `DEBUG_SCHEDULES`：`-Dmtr.debugSchedules=<站台 ID>`。
- 存档键（在 `Train` 之外）：`TrainService` 的 `SAVED_KEYS` 个键；`cruise_cap`（出发时的巡航上限，G0、契约文档 6.4）；`authority_end`；`run_segment`（"version,startMillis,startPosition,startSpeed,cap,target" 字符串，空为无）。

### 构造函数
- `TrainServer(long id, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, List<TimeSegment> timeSegments, boolean isManual, int maxManualSpeed, int manualToAutomaticTime)`：新列车。调用者：`Siding.java:942`、`:1801`、`:1808`。
- `TrainServer(TrainServer same, long sidingId, …)`：与另一列车为同一列车（R1），持有其身份与 id，运行状态重新开始。调用者：`Siding.java:928`。
- `private TrainServer(TrainIdentity identity, …)`：设置路径重复下标、停车线通常路径、新建 `TrainService`（`ServiceVehicle`、系统时钟）并设停车线。
- `TrainServer(long sidingId, float railLength, List<TimeSegment> timeSegments, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime, Map<String, Value> map)`：从 msgpack 存档读出，并读服务（`MessagePackKeyed.Reader`）、巡航上限（无则 `cruiseCapMissing`）、许可终点、运行段；最后 `repairLoaded()`。调用者：`Siding.java:414`。
- `@Deprecated TrainServer(…, CompoundTag)`：旧 NBT；`cruiseCapMissing = true`；`repairLoaded()`。调用者：`Siding.java:443`。

### `private static RunSegment.Spec parseSegment(String text)`
- 解析 6 段逗号字符串；格式不对或数字错误为 null。

### `public void toMessagePack(MessagePacker)` / `public int messagePackLength()`
- 父类字段 + 服务 + `cruise_cap` + `authority_end` + `run_segment`；长度为父类 + `TrainService.SAVED_KEYS` + 3。调用者：`Siding.java:474`（经 `writeMessagePackDataset`）。

### 覆盖 `Train` 的钩子
- `getTotalDwellTicks()`：`TrainRules.getTotalDwellTicks(rules, 父类值)`。
- `isHeldAtStop()`：`TrainRules.getHoldReason(rules)` 非空，并记入 `holdReason`。
- `getTicksUntilDoorsClose()`：`TrainRules.getTicksUntilDoorsClose(rules)`。
- `onLapComplete()` / `onStopReached()`：交给服务。
- `onDwell(int rail, float departureTicks)`：在站台轨道且非回空时更新 `platformDwell`（`PlatformDwell.update`），再经 `withKnownDeparture`。
- `startUp(...)`：出发：`canDeploy = false`、`isOnRoute = true`、停站清零、速度设为 `ACCELERATION_DEFAULT`；反向时按 `MotionConstants.getReversalAdvance` 前移进度并翻转方向；重找下一停站、取巡航上限，再调父类。
- `openDoors()`：手动时 `doorTarget`，否则 `TrainRules.openDoors(rules)`。
- `keepsOnLineAtEnd()`：`service.endsOnLine()`。
- `asin(double)`：`TrigCache.asin`。
- `skipScanBlocks(...)`：`MAX_CHECK_DISTANCE` 内没有玩家时跳过。
- `getSegmentSpecToSend()`：模拟器列车的运行段，否则 null。

### `private PlatformDwell withKnownDeparture(PlatformDwell dwell)`
- 作用（0.66.5）：运动只在车门开始关闭时（约发车前 4 秒）才给出发车时刻，使发车前数秒触发的站停最迟那时才触发。停着且已知关门时间（站台停站时间、时刻表或运用发车）时，把发车时间设为关门 tick + `DOOR_MOVE_TIME` + `DOOR_DELAY`。
- 返回：可能更新过的 dwell；已有发车时间、在动或未知时原样。

### `void forEachRider(Consumer<Player> consumer)`
- 对在世界中找得到的乘客执行。调用者：`StationCall.java:79`。

### `private float getCruiseCap()`
- `TrainRules.getCruiseCap(rules)`。

### `protected void simulateCar(...)`
- `VehicleRidingServer.mountRider`：车门开着（或允许手动）时可上车；允许手动时只有车门开或手持司机钥匙才可；持钥匙者使手动冷却归零。

### `protected boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed)`
- 作用：列车外扩 128 格内或正在乘坐的玩家，把本列车加入其"范围内列车"；在车头附近与扫掠经过的方块（`getCheckPositions`）中：红石传感器（匹配过滤时）通电；货物装/卸车方块与六个方向的容器转移物品；有乘客时报站方块向乘客报站。只处理已加载的区块。
- 返回：是否有玩家在附近（决定是否算各车厢）。

### `protected boolean canDeploy(Depot depot)`
- 作用：不能出发的列车跳过本次发车而不排队，使时刻表不漂移。路径不止一段且第 1 段未被占、或车厂按运用运行（此时停车线本身就是路径，运用到点后沿运用路径出发）时，向车厂 `requestDeploy`。
- 返回：已获准出库，且不在运用上或已获得出停车线的运行（`isRunGranted(1)`）。

### `protected boolean isRailBlocked(int checkIndex)` / `private boolean isRailBlockedNow(int checkIndex)`
- 返回：前方轨道是否不可进入：在获得通过权之前，进入下一站咽喉的轨道被锁（L2）；或该段的闭塞单元被其他列车占用。两种情况都 `markDelay`。结果记入 `railBlockedLast`。

### `private void markDelay(int checkIndex)`
- 在该线路、该轨道起点上记一次延误（`TrainDelay.delaying()`），供线路延误显示。

### `public String describeShown()`
- 返回：游戏所显示的列车（`-Dmtr.debugPlace` 用）：数据包大小、发送窗口、在线、路径大小（是否停车线路径）、进度、速度、路径键，及模拟器列车的对应值。调用者：`LiveSimulation.java:432`。

### `public void setSimulated(TickRunner<SimDataset.DataTrack>.HeadlessTrain newSimulated)`
- 作用：显示组件的列车而不移动本列车（G1）；null 时回到由游戏移动，从组件所在处继续。设 `movedBySimulator`。调用者：`Siding.java:1957`、`:1963`、`:1974`。

### `private void showSimulated()`
- 作用：复制组件列车的状态：服务字节变化时读入服务，并按其路径键找路径（跳站路径由键生成，D3）；找不到时（停车线路径已重找，如时刻表变更后）只报告一次并保持原样，直到模拟器在新路径上重启；读入的服务只有路径键，所以交给它路径本身使其知道运用（0.66.1）；路径变化时 `followPath`。再复制速度、进度、停站、下标、方向、在线、车门、巡航上限、扣车原因、前方被占、许可终点、站台停留、运行段、重复下标、折返保留；从在线变为不在线时清空乘客；记录被阻挡轨道的延误。模拟器中的列车状态只在这里显示（A06）。

### `private boolean isRailOccupied(int checkIndex)`
- 返回：该段覆盖的任一闭塞单元被其他列车占用（`Occupancy.isTakenByOther`）；连续移动、无占用表或越界为 false。

### `private List<UUID> getCoveredUnits(int index, double fromPosition, double toPosition)`
- 返回：路径某段的一部分覆盖的闭塞分区：轨道自身键，或长轨道被切成的各片（`BlockSections.getPieces`，与组件运行器相同）。

### `public static float cutSpeed(RailType railType)`
- 返回：轨道按此限速切成闭塞分区；从不切的为 −1（可加速的轨道类型才切）。调用者：`RailwayData.java:214`、`SignalAspects.java:107`。

### `protected double getSectionBlockedPosition(int rail, double fromPosition)`
- 返回：该段上从 `fromPosition` 起第一个被其他列车占用的闭塞分区起点（路径进度），无为 NaN；第 0 段、无信号或连续移动为 NaN。

### `protected boolean openDoors(Level, Block, BlockPos checkPos, int dwellTicks)`
- 作用：对已解锁的屏蔽门/安全门（上下各一格）按列车门开度设开度；开着且未安排时安排方块 tick，使列车离开后关门。
- 返回：总是 false（不算"车门开着"的依据）。

### `public boolean simulateTrain(Level world, float ticksElapsed, Depot depot, DataCache dataCache, List<Map<UUID, Long>> trainPositions, Map<Player, Set<TrainServer>> trainsInPlayerRange, Map<Long, List<ScheduleEntry>> schedulesForPlatform, Map<Long, Map<BlockPos, TrainDelay>> trainDelays)`
- 作用：服务端一 tick：
  1. 保存环境、设网络（`setNetwork`），记下旧状态；提前折返回空时清空乘客（D4）。
  2. 有模拟器列车时 `showSimulated` + 父类 `simulateTrain`；否则更新过夜状态、补算巡航上限、更新手动许可、父类 `simulateTrain`、`limitToAuthority`、`holdRun`、`service.update`。
  3. 准备站停（`prepareStationCall`）；从时间段（`TimeSegment.indexAt`，折返间隙用 `gapSegmentIndex`，0.66.7）算出当前时间，遍历之后的段（循环且不在行程上时再一轮）：为每个有站台的段补上线路与站序，按估计与计划（取较晚；正停在此站时用 `ScheduleEntry.standingArrivalMillis`，0.66.4）给站台加入 `ScheduleEntry`；为下一停站加入接近时间的站停；为途经不停的站台加入通过站停；`-Dmtr.debugSchedules` 时每 10 秒打印。
  4. 释放已过的通过站停；手动/自动切换（冷却达到 `manualToAutomaticTime × 10` 后转自动，并据车门设停站时间）。
  5. 记车门变化（`noteDoorFlip`）、离站时刻与计划、扣车原因序列（0.66.8）。
  6. 检测新运行段（G2）。
- 返回：是否需要同步给客户端：新运行段、乘客减少、下一停站变化、手动状态变化、刚起步、车门变化、或重复下标变化。
- 调用者：`Siding.java:1723`。

### `private RailwayData prepareStationCall(int rail)`
- 作用：在 ETA 变化与列车停下期间保持同一个站停身份，列车离开时退休。只有在去下一个乘客停站途中的列车可报接近。非在线、回空、越界、非站台轨道或无停站时间、或停留站台不同时清空。新挂上的模拟器在首 tick 前可能已在站内，此时返回 null。停着时补线路并 `addStationCall`。
- 返回：`RailwayData` 实例，或 null。

### `public void writeTrainPositions(List<Map<UUID, Long>> trainPositions, SignalBlocks signalBlocks)`
- 作用：从车尾到车头的每段（不含第 0 段、本停车线、无信号的段），占用其覆盖的闭塞分区（`signalBlocks.occupy`）；折返保留：不在线或车尾已过 `turnBackUntil` 时释放，否则在下一 tick 表中占上。调用者：`Siding.java:1751`、`:1779`。

### `getDutyLedger()` / `getDutyPathReference()`
- 服务的运用账目；路径键的 `PathKeyIndex` 条目。调用者：`Siding.java:469`、`:470`。

### `public RunRecord.TrainRecord toRecord()`
- 返回：组件模拟器从中继续的列车记录（G0）：停车线、id、速度、进度、停站、下标、方向、在线、服务字节、巡航上限（缺失为 NaN）、许可终点、运行段。调用者：`LiveSimulation.java:350`、`Siding.java:1997`。

### `public boolean needsAlignment()` / `public void alignToTimetable(Depot, DataCache, List<Map<UUID, Long>>)`
- 见 `TrainService#alignToTimetable`：在线、非手动、非连续移动时对齐时刻表。调用者：`Siding.java:1696`。

### `public void relocate(double newRailProgress, int railIndex, int newRepeatIndex1, int newRepeatIndex2)`
- 作用：把列车移到新路径上而不替换，乘客、货物与车门保持；设重复下标、按服务是否最后一圈设、从 `railIndex` 起找第一个停站作为下一停站。调用者：`Siding.java:1665`、`:1670`。

### 出库
- `deployTrain()`：允许出库。调用者：`Depot.java:1901`。
- `findDeployService(long lastDeployedMillis)`：见 `TrainService#findDeployService`。调用者：`Depot.java:1893`。
- `deployTrain(long newTripMillis, long routeId)`：为时刻表的一个班次出库。调用者：`Depot.java:1905`。
- `deployDuty(int slot, [long dutySidingId,] long dayStart, TripPath<PathData> dutyPath[, int startSegment])`：为某槽位的运用出库；`dutySidingId` 为代替另一停车线时其停车线；`startSegment` 为从第几段开始（D5）。调用者：`Depot.java:1925`。
- `takeReturnedElsewhere()` / `takeReturnedElsewhereSiding()`：见 `TrainService`。调用者：`Siding.java:1739`。
- `isReturningTo(long otherSidingId)`：在线且路径键（去掉跳站部分）以回到同池另一停车线的回库键结尾。调用者：`Siding.java:959`。
- `joinDutyLate()`：出库远晚于运用所需时，放到仍能赶上的第一站（见 `TrainService#joinDutyLate`）。调用者：`Depot.java:1927`。

### `private void holdRun()`
- 作用：每 tick 设置运用列车按方向持有的双向轨道：从车尾到运行终点，无论在动、停站或被信号拦住（设计文档 6.12、E2、F1）；车尾离开即释放，回库后全部释放。手动且有许可时由许可持有；不在线/手动/不在运用上时释放。有咽喉锁且未在申请时 `locks.approach(...)`（按制动距离与计划发车），加入咽喉持有项，最后 `reservations.hold`。

### `private ThroatLocks getThroatLocks()`
- 返回：路径上车站咽喉的锁（L2），路径或联锁区变化时用节点、停站轨道、节点轨道数与联锁区重建；关闭（`ThroatLocks.enabled`）、不在线、手动、不在运用、或车厂与不按运用运行的车厂共轨（`isThroatLocksOff`）时为 null。

### `public long getWaitingMillis()` / `public long getWaitingSinceMillis()`
- 已等待申请的运行多久 / 从何时开始（D2），0 为没在等。调用者：`RailwayData.java:811`、`DelayAlarms.java:94`、`:96`。

### 调度台（D6）用
- `isOnRouteNow()`、`getHeadRail()`（车头所在轨道键）、`getNextStopId()`、`getLastStopId()`（下一站之前的最后一个停站站台）、`getStopState()`（运行中/停在下一站/停在上一站）。调用者：`DispatchConsole.java:78`、`:86`、`:93`、`:101`、`:102`。
- `private int getLastStopIndex()`。

### `private long standingDepartureMillis()`
- 返回：停在此处的列车何时离开：被结束时间未知的事扣着（折返、前方路、前方运行；非时刻表/运用发车）时为负的计划发车（显示但不倒数，0.67.1；否则发车计时器会反复显示几秒，Q 世界 2026-11-12）；已知关门时为关门时刻 + 开关门时间；否则计划发车在未来时为其负值，或 0。

### `private long platformStopMillis(long platformId)`
- 站台停站时间（`getDwellTime() × 500` 毫秒）。

### `private int gapSegmentIndex(double position)`
- 在折返处两个时间段之间的间隙中，返回之前的段，否则 −1。

### `private long getPlannedDepartureHere()`
- 所停站的计划发车：运用与时刻表中较晚者，或 0。

### `private void noteDoorFlip(boolean standing)` / `private int getDoorFlipsLastMinute()`
- 记录车门目标变化（0.65.33）；停着时一分钟内 ≥6 次，每分钟最多打印一次详细日志（谁在移动、扣车原因、前方被占、停站进度、运用发车）。

### `public List<String[]> getDebugLines(DataCache dataCache)`
- 返回（0.65.31）：手持刷子的玩家看到的列车信息，每行 {键, 值}，以 "#" 开头的值为客户端文本键：train、siding、route、duty、trip、this_stop/next_stop、planned_arrival、planned_departure、expected_departure、late、dwell、doors_close、hold（只在停站时间到后才问扣车原因，之前显示已知的运用/时刻表等待）、speed（km/h = ×72）、cruise_cap、state、driver、reported（报告给显示屏的前几站及时间段位置）、last_arrival/last_holds/last_departure（早/晚/准点）、door_flips。时间按 `TimetableConfig.getTimeZone()` 格式化。调用者：`TrainDebugPackets.java:66`。

### `private boolean isRunGranted(int fromIndex)`
- 作用：申请从路径某段到其后下一站的运行，tick 结束后给或不给。已获得为 true；已申请且 `takeGrant` 成功则记下、清除等待、通知咽喉锁并为 true；否则记申请与等待开始，申请时把离开站的咽喉出路（全部或全不）与持有项一起加入，`reservations.request(...)`，返回 false。

### `private List<RunReservations.Move> getRunMoves(int runEnd)`
- 返回：从车尾（向下取整，保留刚离开的轨道）到 `runEnd` 的双向轨道及行驶方向；运行终点处折返的列车也占用折返出去的那段（掉头车优先，同组件）。

### `private int findRunEnd(int fromIndex)` / `private long getPlannedDepartureMillis()`
- 委托 `TrainRules.findRunEnd` / `TrainRules.getPlannedDepartureMillis`。

### `private double updateManualAuthority()`
- 作用：手动驾驶列车的行车许可（运行层 5.5）：按停车所需距离 + 一点余量（`ThroatLocks.APPROACH_MARGIN`）经与自动列车相同的仲裁申请；路径轨道由 `SimExport.toTracks` 生成。
- 返回：手动列车最远可走的进度；非手动在线时释放许可并返回 NaN。

### `private void limitToAuthority(double end)`
- 作用：手动列车越过许可终点则停在终点；否则限制速度不超过在终点前停下所允许的速度。

### `private RunReservations getRunReservations()`
- 本世界 `RailwayData.runReservations`，或 null。

### `public void setStopTimes(StopTimes depotStopTimes, Map<String, TripPath<PathData>> newTripPaths[, TurnBacks turnBacks])`
- 作用：设置停车线通常路径的停站时间与从它出发的各班次路径（及多条折返线的折返方式），记下路径供 `showSimulated` 使用。调用者：`Siding.java:1715`。

### 其他服务委托
- `isOnTripPath()`（调用者 `Siding.java:1660`）、`setDepotRepeatIndices(int, int)`（`Siding.java:1661`）、`setPathVariants(PathVariants)`（停车线如何生成跳站路径，D3；`Siding.java:403`、`:1716`）、`getActiveTripPath()`（在线时的行程路径，在列车完成已开始的运用前须保留；`Siding.java:1234`）、`restoreTripPath(Map)`（把存档列车放回其路径，路径不存在为 false；`Siding.java:404`）、`getLeadMillis()`、`getService()`（`RailwayData.java:842`）。
- `private void setNetwork()`：给服务车厂、线路查找、线路 → 唯一车厂查找。
- `private void setLastLap(boolean isLastLap)`：最后一圈时重复下标置 0，否则用路径的；变化时需同步。

### `private boolean isOvertakingTrainComing(long platformId, long arrivalMillis, long departureMillis, long currentMillis)`
- 返回：本列车停站期间，计划通过本站另一站台的列车是否尚未到达（按上 tick 时刻）。

### `private int getNextStoppingIndex()`
- `TrainRules.findNextStop(track, railProgress)`。

### `private Set<BlockPos> getCheckPositions(BlockPos frontPos)`
- 作用（G3）：本 tick 可触发传感器、装卸、报站方块的位置：车头周围（`checkBlock`，半径为速度向下取整，向下 0–3 格），以及自上次以来车头扫过的每个方块下方 0–3 格，使快车或服务器缓慢时一次走多 tick 的列车不漏掉。按时钟算经过的 tick（游戏计数在服务器慢时会停在几个）。

### `public static List<Double> getSweptRailProgresses(double fromRailProgress, double toRailProgress, double speed, float ticksPassed)`
- 参数：`from` 上次检查的进度或 NaN；`speed` 当时与现在中较快者（方块/tick）；`ticksPassed` 经过 tick。
- 返回：每半格一个的扫掠进度；后退、几乎没动（≤1）或跳得比可行驶的远（> 速度×max(1,tick)×1.5+2，如被放到别处）时为空。调用者：`SensorSweepTest.java:18`。

### `private void checkBlock(BlockPos, Consumer<BlockPos>)` / `private static void transferItems(Container from, Container to)`
- 车头周围方块；每次只转移一件物品（带 NBT），成功即返回。

## private class RulesTrain implements TrainRules.Train
组件停站规则所见的本列车。
- `getMillis()`：系统时间；`isBlockingOthers()`：是否在仲裁中挡着别人；`getEarlyThresholdMillis()`：网络设置的提前阈值（默认 `TrainRules.DEFAULT_EARLY_THRESHOLD_MILLIS`）；`getService()`、`getState()`、`getTrack()`、`getStopId(rail)`、`isContinuousMovement()`、`usesSpeedCurve()`、`getSpeedCurves()`、`getTotalDwellTicks()`、`isOvertakingTrainComing(...)`、`isRunGranted(fromIndex)`：委托外部类。调用者：`TrainRules.java:34`、`:105` 等。

## private class ServiceVehicle implements TrainService.Vehicle<PathData>
`TrainService` 所见的本列车。
- `getPath()`、`getTrainLength()`（节数×间距）、`getNextStoppingIndex()`、`isRepeat()`、`getRepeatIndex2()`、`getPathRepeatIndex1/2()`、`setLastLap(boolean)`。
- `followPath(TripPath<PathData> tripPath)`：换到行程路径（null 为停车线通常路径）：路径、距离、时间段、线路列表、重复下标；需同步重复下标；旧路径上获得的运行重新申请；停车线路径且不在线时 `parkAtSiding()` 并清运行段，否则不在线且下标越界时置 0。调用者：`TrainService.java:2213`、`TickRunner.java:3146`。
- `changePath(TripPath<PathData> tripPath, int newStopIndex)`：换路径并保持到下一停站的距离不变，下一停站改为 `newStopIndex`。调用者：`TrainService.java:551`。
- `isTurnBackFree(List<UUID> rails)`：这些轨道没被其他列车占用。
- `reserveTurnBack(List<UUID> rails, int untilIndex)`：保留折返轨道。
- `isWayClear(List<UUID> rails)`：未被占用且未被其他列车在仲裁中持有。
- `isStopFree(int stopIndex)`：该站往回一个车长的轨道都未被占。
- `moveToStop(int stopIndex)`：直接放到该停站（按途中折返设方向），速度 0、档位 −2、停站清零、清运行申请。调用者：`TrainService.java:1154`、`TickRunner.java:3207`。
- `moveBetweenStops(int fromStop, int toStop, double newRailProgress, float newSpeed)`：放到两站之间（途中折返翻转方向），返回 true。调用者：`TrainService.java:1294`。
- `returnToDepot()`：不在线、档位 −2、速度 0、清空乘客。调用者：`TrainService.java:1143`。
