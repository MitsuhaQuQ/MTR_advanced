# Train.java

`Train` 是沿一条轨道路径运行的列车的抽象基类，服务端子类为 `TrainServer`，客户端子类为 `TrainClient`。`simulateTrain(Level, float, Depot)` 每 tick（服务端）或每帧（客户端）推进列车：怎样移动完全由组件 `transitsim.motion.TrainMotion` 决定（R4 之后唯一的规则，由 `MotionConfig` 选择），本类通过内部类 `Motion` 实现组件的 `MotionState`/`MotionTrack`/`MotionVehicle`/`MotionHooks`/`MotionCurves` 接口把自身状态交给组件；之后算出每节车两端的位置，交给子类的 `simulateCar`（服务端检查前方轨道、开门；客户端移动乘客并绘制）。本类还负责：msgpack 存档、完整数据包与状态数据包（G2）的读写、长路径分段发送窗口（2026-11-09）、速度曲线（`SpeedCurves`，设计文档第 8 节）的缓存、客户端跟随服务端运行段（`RunSegment`，G2）。组件侧对应物为 `TickRunner` 中的列车状态与 `RunMotion`。`railProgress` 为车头沿路径走过的距离（方块）。

## class Train（abstract，extends NameColorDataBase implements IPacket）

### 状态字段（protected，组件通过 `Motion` 读写）
- `speed`：当前速度，方块/tick。
- `railProgress`：车头沿路径的进度，方块。
- `doorTarget` / `doorValue`：门目标（开/关）与当前开度 0–1。
- `elapsedDwellTicks`：已停站 tick 数。
- `nextStoppingIndex` / `nextPlatformIndex`：下一个停车的路径段下标 / 下一个站台段下标。
- `reversed`：列车是否反向（车尾在前）。
- `isOnRoute`：是否已出库在线运行。
- `isCurrentlyManual` / `manualNotch`：手动驾驶中 / 手动档位。
- `useSpeedCurve`：是否按速度曲线运行（设计文档第 8 节），由车厂设置。
- `decelerationConstant`：减速度，默认 `ACCELERATION_DEFAULT`。
- `cruiseCap`：到下一站的巡航速度上限（为准点而降低），无为正无穷。
- `doorsCloseTicks` / `doorsCloseReceivedMillis`：客户端：服务端所说的距关门开始的 tick 数（−1 未知，0.65.30）及收到时刻。
- `movedBySimulator`：是否由组件模拟器移动（G1）；此时每 tick 复制状态进来，本类只做需要世界的事（车厢、乘客、车门）。
- `segmentSpec`：服务端说列车所在的运行段，客户端按服务端时钟跟随（G2），或 `null`。
- `tripRouteIds`：路径不是车厂通常路径时（如直通到另一线路）的线路列表。
- `repeatIndex1` / `repeatIndex2`：循环运行的重复区间下标。
- `identity`（`TrainIdentity`，R1）：使其在各处为同一列车的东西：乘客 `ridingEntities`、货物 `inventory`。
- `vehicleMaxSpeed`（public）：车辆最高速度（方块/tick），0 为无，由停车线/车厂设置。

### 私有字段
- `followedSpec` / `followedSegment` / `smoothOffset` / `smoothStartMillis`：客户端跟随运行段的状态与平滑偏移。
- `speedCurves`、`speedCurvesPath/Size/Length/Deceleration/MaxSpeed`：速度曲线缓存及其失效条件。
- `cappedProfile` / `cappedRun` / `cappedCap`：带巡航上限的速度剖面缓存。
- `motion`（内部类实例）、`runSegment`、`motionEvents`（最多 `MAX_MOTION_EVENTS`）、`motionEventSequence`、`motionWorld`、`motionDepot`。
- `stopOffsets` 及其缓存键：每条带停站的轨道上列车停在离末端多远（优化第 4 轮）。
- `roundsPath` / `roundsSize` / `rounds`：发送窗口用的"轮"切分缓存。
- `railLength`：停车线长度（保留 3 位小数）。

### 公共不可变字段
- `sidingId`（可变，public）：列车所属停车线；客户端列车在同一列车作为同池另一停车线的列车继续时换成另一个（R1）。
- `trainId`、`baseTrainType`、`transportMode`、`spacing`、`width`、`trainCars`。
- `accelerationConstant`、`isManualAllowed`、`maxManualSpeed`、`manualToAutomaticTime`（public 可变）。
- `path`（`List<PathData>`）、`distances`（protected，每段末端的累计距离）。

### 常量
- `ACCELERATION_DEFAULT = 0.01F`、`MAX_ACCELERATION = 0.05F`、`MIN_ACCELERATION = 0.001F`（m/tick²）。
- `DOOR_MOVE_TIME = 64`、`MAX_MOTION_EVENTS = 256`、`MAX_CHECK_DISTANCE = 32`、`DOOR_DELAY = 20`。
- `SMOOTH_DISTANCE = 2`（方块）、`SMOOTH_MILLIS = 500`。
- `SEND_WINDOW_MIN = 2048`：长于此段数的路径分部分发送；`SEND_BLOCK = 512`；`SEND_BACK_MARGIN = 64`。

### 存档键（msgpack）
`speed`、`rail_progress`、`stop_counter`（`elapsedDwellTicks`）、`next_stopping_index`、`next_platform_index`、`reversed`、`is_currently_manual`、`is_on_route`、`train_type`、`train_custom_id`、`riding_entities`（UUID 字符串数组）、`cargo`（NBT 二进制或 nil）。

### 构造函数
- `Train(long id, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime)`：新列车，新的 `TrainIdentity`。
- `protected Train(TrainIdentity identity, …同上)`：持有给定身份（自己的，或同一列车作为另一停车线列车的，R1）。把 `base_` 前缀的旧车型名换成 `train_`（向后兼容）；由车型得出运输方式、间距、宽度；加速度保留 3 位小数，≤0 用默认；身份没有货物容器时新建 `trainCars` 格。调用者：`TrainServer.java:197`。
- `Train(long sidingId, float railLength, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManualAllowed, maxManualSpeed, manualToAutomaticTime, Map<String, Value> map)`：从 msgpack 存档读出；`train_custom_id` 为空时用车型名；`trainCars = min(transportMode.maxLength, floor(railLength / spacing))`；`cargo` 用 NBT 解析，`IOException` 打印堆栈。调用者：`TrainServer.java:216`。
- `@Deprecated Train(…, CompoundTag compoundTag)`：从旧 NBT 存档读出。调用者：`TrainServer.java:258`。
- `Train(FriendlyByteBuf packet)`：客户端从完整数据包读出（字段顺序见 `writePacket`）；`doorsCloseReceivedMillis` 设为当前时间；`inventory = null`。调用者：`TrainClient.java:74`。

### `public void toMessagePack(MessagePacker messagePacker) throws IOException`
- 作用：写上述存档键；货物全空或无容器时写 nil。
- 调用者：`TrainServer.java:275`（`super`）。

### `public int messagePackLength()`
- 返回：父类长度 + 12。

### `public void writePacket(FriendlyByteBuf packet)`
- 作用：写完整列车数据包，只写发送窗口内的路径，距离与进度减去窗口基准。
- 字段顺序：父类字段 → 路径（`writePath`）→ `repeatIndex1`、`repeatIndex2` → `tripRouteIds` 数量与各 long → `sidingId`、`railLength`、`speed`、`accelerationConstant`、`railProgress - base`、`elapsedDwellTicks`、`nextStoppingIndex - window[0]`（≥0）、`nextPlatformIndex - window[0]`（≥0）、`reversed`、`trainId`、`baseTrainType`、`isManualAllowed`、`isCurrentlyManual`、`maxManualSpeed`、`manualToAutomaticTime`、`isOnRoute`、`manualNotch`、`doorTarget`、乘客数与 UUID、`useSpeedCurve`、`decelerationConstant`、`cruiseCap`、运行段（`writeSegmentSpec`，已变基）、`getTicksUntilDoorsClose()`。
- 调用者：`UpdateNearbyMovingObjects.java:115`。

### `protected int[] getSendWindow()`
- 作用（2026-11-09）：玩家只收到长路径的一部分，作为从 0 编号的独立路径：车头所在的"轮"和下一轮，以及前面一个车长的部分。一轮在路径折返处结束；不折返地循环的路径约每 `SEND_BLOCK` 段在某个停站处切开。车头进入下一轮时窗口前移；列车停在折返站时玩家重新收到完整的部分（`getSyncKey` 改变）。
- 返回：`{首段下标, 末段下标+1}`；段数 ≤ `SEND_WINDOW_MIN` 或有重复下标时为整条路径。
- 调用者：`Train.java:412`、`:587`、`TrainServer.java:502`。

### `private int[] getRounds(int size)`
- 返回：每轮首段下标，最后一个为 `size`。某段正好反向重走前一段为折返；连续 `SEND_BLOCK` 段未切时在最近的停站（前一段 `dwellTime > 0`）处切，否则就地切。按 `path` 与 `size` 缓存。

### `private double getWindowBase(int[] window)` / `private static RunSegment.Spec rebase(RunSegment.Spec spec, double base)`
- 窗口首段之前的累计距离（首段为 0 时为 0）；把运行段的起点与目标位置减去基准。

### `public static void writePath(FriendlyByteBuf packet, List<PathData> path, List<Double> distances)`
- 作用（2026-11-09）：每条不同的轨道（按其数据包字节去重）只写一次，再按轨道编号写每段：一天的运用在环线上会重复同样的轨道几千次，完整写出超过数据包上限，玩家收不到列车。
- 格式：varint 轨道数、各轨道数据包；int 段数；每段 varint 轨道编号、`writePacketWithoutRail`、double 距离。
- 调用者：`Train.java:418`、`TrainIdentityTest.java:154`。

### `public static void readPath(FriendlyByteBuf packet, List<PathData> path, List<Double> distances)`
- `writePath` 的逆操作，结果追加到两个列表。调用者：`Train.java:315`、`TrainIdentityTest.java:159`。

### `public final void writeStatePacket(FriendlyByteBuf packet)`
- 作用（G2）：给已有同一路径的同一列车的客户端写运行中会变的部分：`writePacket` 的全部，除去列车是什么和路径。
- 字段顺序：`id`、`getSyncKey()`（公共版本：ANTE 的 mixin 会在其中加入设置；曾写成 `getSyncKey(window)` 时客户端丢弃所有状态、车门从不打开）、重复下标、`tripRouteIds`、`speed`、`railProgress - base`、`elapsedDwellTicks`、两个下标（减窗口首）、`reversed`、`isCurrentlyManual`、`isOnRoute`、`manualNotch`、`doorTarget`、乘客、`useSpeedCurve`、`decelerationConstant`、`cruiseCap`、运行段、关门 tick。
- 调用者：`UpdateNearbyMovingObjects.java:112`、`TrainSendWindowTest.java:119`。

### `public int getSyncKey()` / `private int getSyncKey(int[] window)`
- 返回：路径或只有 `writePacket` 发送的东西改变时就改变的数；客户端有同一数时只需状态包。按窗口内每段的起止位置、`savedRailBaseId`、`stopIndex`、`dwellTime`，再加 `trainId`、车型、节数、间距、`railLength` 计算。车辆也是其中一部分：停车线换了车辆仍可保留列车 ID，客户端曾用旧车辆的车厢画新列车，后部车厢落在停车线起点外、叠在一点并横在轨道上。
- 调用者：`UpdateNearbyMovingObjects.java:111`、`:119`、`TrainClient.java:525`。

### `public final boolean isSameVehicle(Train other)`
- 返回：是否同一车辆：车型 ID、基础车型、节数、间距、宽度、运输方式、停车线长度都相同。调用者：`ClientData.java:193`、`TrainIdentityTest.java:39`。

### `protected final boolean repairLoaded()`
- 作用：把不可能的值（如损坏的存档）改回安全值：速度非有限或负 → 0；进度非有限 → 0 且 `isOnRoute = false`；停站 tick 非有限或负 → 0；`cruiseCap` 为 NaN 或 ≤0 → 无穷；下标越界 → 末段/下一停车段。有修正时打印一行日志。
- 返回：是否修正过。调用者：`TrainServer.java:236`、`:270`。

### `protected RunSegment.Spec getSegmentSpecToSend()`
- 返回：客户端要跟随的运行段，只有组件模拟器运行的列车才有；基类为 `null`。由 `TrainServer.java:491`、`:864` 覆盖/使用。

### `protected static void writeSegmentSpec(FriendlyByteBuf, RunSegment.Spec)` / `readSegmentSpec(FriendlyByteBuf)`
- 格式：boolean 是否有；有则 int `version`、long `startMillis`、double `startPosition`、`startSpeed`、`cap`、`target`。调用者：`Train.java:447`、`:612`、`TrainClient.java:514`、`SegmentPacketTest.java:21`。

### `private boolean standsForRunLayer(Level world, float ticksElapsed)`
- 作用（解耦文档 5.5）：客户端上由服务端运行层移动的列车只沿服务端发来的运行段移动；没有时停在服务端所给位置（速度置 0），车门按服务端所说开合（按 `MotionConstants.DOOR_MOVE_TIME` 推进开度），而不是客户端自己判断是否在站。缆车与手动驾驶照旧。
- 返回：本帧是否原地停着。

### `private boolean followSegment(Level world)`
- 作用（G2）：客户端按服务端时钟（`ClientClock.INSTANCE.serverMillis()`）把列车放到运行段所给位置。新段时在当前速度曲线上重建 `RunSegment`；与显示位置差小于 `SMOOTH_DISTANCE` 时记为平滑偏移，在 `SMOOTH_MILLIS` 内淡出。段已结束则停在终点、清空段并返回 false。
- 返回：是否在跟随（false 时列车照旧移动）。

### `protected final boolean hasTransportMode()`
- 返回 false（`NameColorDataBase` 用）。

### `public final void setSpeedCurve(boolean useSpeedCurve, float decelerationConstant)`
- 作用：停车线每 tick 以车厂设置与列车制动调用；减速度 ≤0 时用加速度。调用者：`Siding.java:1717`。

### `public final boolean usesSpeedCurve()`
- 返回：`useSpeedCurve` 且非手动且非连续移动（缆车）。调用者：`TrainServer.java:1739`。

### `protected final SpeedCurves getSpeedCurves()`
- 返回：当前路径的速度曲线，路径、段数、总长、减速度或车辆最高速变化时用 `SpeedCurves.of(TrackViews.of(path, transportMode, vehicleMaxSpeed, false, this::getStopOffset), (railLength + trainCars*spacing)/2, trainCars*spacing, 加速度, 减速度)` 重算，并清空带上限的剖面缓存。调用者：`TrainServer.java:1744`，以及 `followSegment`、`Motion.getProfile/getRun`。

### `public final void setVehicle(float newAccelerationConstant, boolean newManualAllowed, int newMaxManualSpeed, int newManualToAutomaticTime)`
- 作用（R1）：按其车池当前车辆运行，按池设定一次而非每条停车线。缆车不处理；加速度变化使速度曲线失效；不再允许手动时退出手动。调用者：`Siding.java:1719`、`TrainIdentityTest.java:84`。

### `protected final void invalidateSpeedCurves()`
- 清空速度曲线缓存。调用者：`TrainClient.java:459`、`:551`。

### `private SpeedProfile getRunProfile(SpeedCurves.Run run)`
- 返回：所在运行区间的曲线；`cruiseCap` 有限时用 `run.withCap(cruiseCap)`（有空余时间时巡航较慢），并缓存。

### `protected boolean keepsOnLineAtEnd()`
- 返回：路径末端是否为线上停站而非入库（如在线上过夜）；基类 false。

### 简单读取
- `getIsOnRoute()` / `isOnRoute()`、`getRailProgress()`、`isCurrentlyManual()`、`getSpeed()`、`getDoorValue()`、`getElapsedDwellTicks()`、`isReversed()`。调用者如 `Siding.java:912`、`:1665`、`TrainServer.java:555`。
- `closeToDepot(int trainDistance)`：未在线或 `railProgress < trainDistance + railLength`。调用者：`Siding.java:1756`。
- `isPlayerRiding(Player)`：玩家 UUID 是否在乘客中。调用者：`TrainClient.java:165`、`TrainWrapper.java:237`。

### `public boolean changeManualSpeed(boolean isAccelerate)` / `public boolean toggleDoors()`
- 作用：把加速/制动或开关门命令交给 `TrainMotion.applyCommand`。返回：命令是否被接受。调用者：`RailwayDataDriveTrainModule.java:43`、`:45`、`:48`。

### `public final void drainMotionEvents(Collection<MotionEvent> events)`
- 作用：交出上次以来的运动事件供宿主在 tick 末处理，并清空。调用者：`Siding.java:1728`。

### `public final int getIndex(int car, int trainSpacing, boolean roundDown)` / `getIndex(double tempRailProgress, boolean roundDown)`
- 返回：某节车（或某进度）所在的路径段下标，见 `findIndex`。调用者：`TrainMixin.java:65`、`TrainClientMixin.java:125` 等。

### `protected final int getSegmentIndex(transitsim.plan.TimeSegment timeSegment)`
- 返回：时间段结束处的路径下标（向下取整），路径不变时缓存在时间段对象上。调用者：`TrainServer.java:741`、`:766`。

### `public static int findIndex(List<Double> distances, int size, double tempRailProgress, boolean roundDown)`
- 返回：二分查找第一个末端在进度之后的段（`roundDown` 时恰好等于末端也算前一段），最多 `size - 1`。距离沿路径不减。调用者：`TickRunner.java:1943`、`BlockingTest.java:35`。

### `public static int findBlockedIndex(List<Double> distances, int headIndex, int lastIndex, double railProgress, double lookAhead, IntPredicate isBlocked)`
- 作用：沿路径找列车必须开始制动的被占轨道；下一段总是检查，此后直到 `lastIndex`（下一停站）且距离不超过 `lookAhead`。
- 返回：被占段下标或 −1。调用者：`BlockingTest.java:43` 等。

### `public final float getRailSpeed(int railIndex)`
- 返回：该段的限速：不保持之前限速的轨道用其运行速度；站台/停车线/折返线等（`SpeedRules.keepsLimitBefore`）取前一段的运行速度（或默认最高速）与当前速度的较大值；再按车辆最高速限制。调用者：`TrainMotion.java:157`、`LegacyTrainMotion.java:116`。

### `protected final double getStopOffset(int rail)`
- 返回（优化第 4 轮）：在该段停车时列车车头离段末多远；按路径从站台求出（`PathData.getStopOffset(trainCars * spacing)`），使运动与速度曲线一致；无停站为 0。调用者：`Depot.java:3883`、`TickRunner.java:2831`。

### `protected final float limitToVehicle(float speedLimit)`
- 返回：不高于车辆最高速（按 `SpeedRules.run` 换成物理上限）的限速。

### `public int getTotalDwellTicks()`
- 返回：下一停车段的 `dwellTime × 10`。调用者：`TrainServer.java:290`。

### `protected int getTicksUntilDoorsClose()`
- 返回：发给客户端的距关门 tick 数（0.65.30），基类 −1。由 `TrainServer.java:301` 覆盖。

### `public float getDoorsCloseTicksLeft()`
- 返回：客户端上按服务端最后所说并倒数的剩余关门 tick；未知为 −1。调用者：`RenderTrainDK3.java:117`、`DwellTimeBveTrainSound.java:74`。

### `protected final MotionState getMotionState()` / `getMotionTrack()`
- 返回内部 `motion` 对象，供子类交给组件规则。调用者：`TrainServer.java:1719`、`TrainClient.java:261`。

### `protected final void parkAtSiding()`
- 作用：`RunMotion.parkAtSiding` 把列车放回停车线，并清空运行段。调用者：`TrainServer.java:1823`。

### `protected final void simulateTrain(Level world, float ticksElapsed, Depot depot)`
- 作用：推进一 tick/帧：若由模拟器移动、在跟随运行段或为运行层原地停着，则只取总停站 tick；否则 `TrainMotion.tick(motion, …, ticksElapsed)`。返回 <0 时结束。然后算出 `trainCars + 1` 个车端位置，`handlePositions` 通过后对每节车 `calculateCar` 并回调 `simulateCar`（带上一节车的位置/角度）。异常打印堆栈。
- 参数：`world` 为 null 时直接返回；`ticksElapsed` 为经过的 tick（客户端可为小数）；`depot` 供 `canDeploy`。
- 调用者：`TrainServer.java:690`、`:701`、`TrainClient.java:350`。

### `protected final void calculateCar(Level world, Vec3[] positions, int index, int dwellTicks, CalculateCarCallback callback)`
- 作用：以两端中点（y+1）为车厢位置，求实际间距、偏航、俯仰；两侧各扫描车门（`scanDoors`）且开度 >0 才算开门；回调。调用者：`TrainClient.java:199`、`TrainClientMixin.java:157`。

### 可覆盖的钩子
- `startUp(Level, int trainCars, int trainSpacing, boolean isOppositeRail)`：出发时关门、`nextPlatformIndex = nextStoppingIndex`。由 `TrainServer.java:354` 覆盖。
- `openDoors()`：返回 `doorTarget`。
- `getModelZOffset()`：0。
- `isRepeat()`：两个重复下标都 >0。
- `onLapComplete()`：循环列车开始下一圈、找下一站之前。
- `onStopReached()`：刚停下、停站时间开始计数之前，可能多次调用。
- `onDwell(int rail, float departureTicks)`：停站中。调用者：`PlatformDwell.java:31`。
- `isHeldAtStop()`：为真时已停列车保持开门不发车（同前方被占）。
- `getSectionBlockedPosition(int rail, double fromPosition)`：长轨道上被占闭塞分区起点，基类 NaN（见 `MotionHooks`）。

### 抽象方法
- `simulateCar(world, ridingCar, ticksElapsed, carX/Y/Z, carYaw, carPitch, prevCarX/Y/Z, prevCarYaw, prevCarPitch, doorLeftOpen, doorRightOpen, realSpacing)`：对每节车的动作。
- `handlePositions(Level, Vec3[] positions, float ticksElapsed)`：处理车端位置，返回是否继续算各车厢。
- `canDeploy(Depot)`：是否可出库。
- `isRailBlocked(int checkIndex)`：该段是否被占。
- `skipScanBlocks(Level, x, y, z)`：是否跳过方块扫描。
- `openDoors(Level, Block, BlockPos, int dwellTicks)`：对站台/屏蔽门方块开门，返回是否打开。
- `asin(double)`：客户端/服务端各自的反正弦实现。

### `private double getRailProgress(int car, int trainSpacing)` / `private Vec3 getRoutePosition(int car, int trainSpacing)`
- 第 car 个车端的进度（`railProgress - car * spacing`）及其轨道位置（减模型 Z 偏移，加运输方式的轨道高度偏移）。

### `protected final Vec3 getFrontPosition(double frontRailProgress)`
- 返回：该进度下车头在轨道上的点（检查经过方块的位置）。调用者：`TrainServer.java:1627`。

### `public final Vec3 getTrackPosition(int car, double offset)`
- 返回：某节车上某点在轨道上的位置（画转向架用）；正偏移从车的第一端指向第二端；不改变状态；无路径为 null。调用者：`JonModelTrainRenderer.java:220`。

### `private boolean scanDoors(Level, x, y, z, float checkYaw, float pitch, double halfSpacing, int dwellTicks)`
- 作用：在车侧 1–3 格、高 −2–3、沿车长范围内找 `BlockPlatform`/`BlockPSDAPGBase`，对其调用 `openDoors`；`skipScanBlocks` 为真则 false。
- 返回：任一打开为 true；否则是否有站台方块。

### 静态工具
- `isHoldingKey(Player)`：非仅电梯模式且手持司机钥匙。调用者：`TrainServer.java:376`、`TrainClient.java:362`。
- `getAverage(a, b)`。
- `convertMaxManualSpeed(int)`：0..`RailType.DIAMOND` 的序号对应 `RailType`，否则 null。调用者：`SidingScreen.java:240`。

## interface CalculateCarCallback（protected）
- `calculateCarCallback(x, y, z, yaw, pitch, realSpacing, doorLeftOpen, doorRightOpen)`。

## private final class Motion
组件运动规则所见的列车；状态仍在外部类字段中、钩子仍可覆盖，使子类与附加模组照旧工作。
- 状态读写：`get/setSpeed`、`get/setRailProgress`、`get/setDoorTarget`、`get/setDoorValue`、`get/setElapsedDwellTicks`、`get/setNextStoppingIndex`、`get/setNextPlatformIndex`、`isReversed/setReversed`、`isOnRoute/setOnRoute`、`isCurrentlyManual`、`get/setManualNotch`、`get/setRunSegment`。
- 轨道：`size()`、`getDistanceCount()`、`getDistance(rail)`、`getStopOffset(rail)`、`getDwellTime(rail)`、`canAccelerate(rail)`（站台/停车线/折返线按自身限速，第 3b 轮）、`getMaxSpeed(rail)`（按车辆限制的运行速度）、`isOppositeRail(rail, nextRail)`、`getRepeatIndex1/2()`。
- 车辆：`getTrainCars()`、`getSpacing()`、`getRailLength()`、`getAcceleration()`、`getDeceleration()`、`usesSpeedCurve()`、`isContinuousMovement()`、`getDefaultMaxSpeed()`、`getManualMaxSpeed()`（按 `maxManualSpeed`，无效时铁轨速度）。
- 钩子：`isClientSide()`、`getTotalDwellTicks()`、`canDeploy()`（用 `motionDepot`）、`startUp(isOppositeRail)`、`keepsOnLineAtEnd()`、`clearRiders()`、`isRepeat()`、`onLapComplete()`、`onStopReached()`、`onDwell(...)`、`isHeldAtStop()`、`isRailBlocked(rail)`、`getSectionBlockedPosition(...)`、`openDoors()` 均委托外部类。
- `emit(MotionEvent.Type type, int rail)`：事件少于 `MAX_MOTION_EVENTS` 时记录（时间、类型、列车 id、递增序号、轨道、运行段版本）；过多时丢弃（如宿主不收集）。
- 曲线：`getProfile(position)`（所在区间的带上限剖面或 null）、`getRun(position)`、`getCruiseCap()`。
