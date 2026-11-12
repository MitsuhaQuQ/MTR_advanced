# TrainClient.java

客户端上的一列车，由服务器发来的数据创建（`ClientData.java:186`、`ClientData.updateTrains`）。在两次服务器更新之间按与服务器相同的运动规则运行，移动正在乘车的玩家，并用其 `trainRenderer`（由 `mtr.client.TrainProperties` 选择）绘制每节车厢。注意：构建中包含 ANTE 时，`TrainClientMixin` 整体替换 `simulateTrain`（当前站、报站、声音），这里对它的修改在那种情况下不生效。

本分支的改动：报站的"下一站"取自列车自己路径上的下一个停站（`transitsim.plan.AnnouncementStops`，0.65.27，跳停的站不报）；屏蔽门同步只在需要时扫描（`skipScanBlocks`）；视锥剔除；运行状态增量包 `State`/`applyState`（G2）；`copyFromTrain` 保持同一列车身份（R1）、跟随运行段（`segmentSpec`）；`ticksElapsed` 为浮点（掉帧补偿）。

## class TrainClient extends Train implements IGui

### 字段
- `isRemoved`（public）：已被移除。
- `justMounted`：本地玩家刚上车（用于轻轨报站）。
- `oldSpeed`、`oldRailProgress`、`oldDoorValue`：上一帧的速度、进度、门值；`doorOpening`：门正在打开。
- `isSitting`、`previousShifting`：按 Shift 切换坐/站（`Config.shiftToToggleSitting`）。
- 显示用：`currentStationIndex`、`thisRoute`、`nextRoute`、`thisStation`、`nextStation`、`lastStation`。
- 回调：`speedCallback`、`announcementCallback`、`lightRailAnnouncementCallback`。
- `depot`、`routeIds`：所属车厂与要报站的线路（惰性查找）。
- `announcementHead`、`announcementRail`、`announcementPlatform`：缓存车头所在段及其之前最近的站台。
- `trainRenderer`、`trainSound`（public final）：渲染器和声音实例。
- `vehicleRidingClient`（public final）：乘客位置管理（发 `PACKET_UPDATE_TRAIN_PASSENGER_POSITION`）。
- `scrollingTexts`（public）：滚动文字。
- `trainTranslucentRenders`：半透明通道再画的任务；`activePlatformDoors`：正在被本列车驱动的站台门方块实体。
- 常量 `CONNECTION_HEIGHT = 2.25`、`CONNECTION_Z_OFFSET = 0.5`、`CONNECTION_X_OFFSET = 0.25`：车厢连接处几何。

### `public TrainClient(FriendlyByteBuf packet)`
- 作用：读 `Train` 的包字段，按 `trainId` 取 `TrainProperties` 创建渲染器与声音实例。调用者：`ClientData.java:186`。

### `protected void simulateCar(Level world, int ridingCar, float ticksElapsed, double carX..., boolean doorLeftOpen, boolean doorRightOpen, double realSpacing)`
- 作用（每节车厢一帧）：播放车厢声音和开门声；按乘车视角偏移修正位置后 `renderCarModels`；第 2 节起若需要，计算并绘制与前一节之间的连接（`renderConnection`）和护栏（`renderBarrier`）。
- 调用者：`Train.java:1094`。

### `protected void renderCarModels(int car, double x, double y, double z, float yaw, float pitch, boolean leftOpen, boolean rightOpen)`
- 作用：更新 `doorOpening`；对 `JonModelTrainRenderer` 且非乘车视角时做视锥剔除；绘制车厢并登记半透明重画。
- 调用者：本类；`TrainClientMixin.java:102`。

### `protected boolean shouldRenderCarConnection(double x, double y, double z, double endX, double endY, double endZ)`
- 返回：不做剔除时为真；否则两点连线（加半径）在视锥内。调用者：本类；`TrainClientMixin.java:103`。

### `protected boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed)`
- 作用：`ticksElapsed > 0` 时：本地玩家在车上则回调速度（传 `speed * 20`，即方块/秒）、在越过"上一停站 + (车厢数+1)×车长"处时回调报站、轻轨在刚开门或刚上车时回调报站；然后移动所有乘客（计算所在车厢、跨车厢移动、Shift 坐站切换、设置偏移）。之后找离相机最近的车厢播放声音。
- 返回：无本地玩家时 `false`，否则 `true`。
- 调用者：`Train.java:1084`。

### `protected boolean canDeploy(Depot depot)` → `false`；`protected boolean isRailBlocked(int checkIndex)` → `false`
- 客户端不发车、不判断闭塞。

### `protected boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ)`
- 作用：开启了 `Config.optimizeTrainRendering()` 且不需要扫描站台门时，清除已驱动的门并跳过扫描。
- 返回：是否跳过。调用者：`Train.java:1236`、`TrainMixin.java:63`。

### `private boolean shouldScanPlatformDoors()`
- 返回：在线路上，且（连续运动；或停着且处于停站并且下一停站点在站台上；或手动驾驶时门开着且车身覆盖站台轨道——司机可能在非计划位置停车开门）。

### `public void syncPlatformDoor(BlockEntityEyeCandy door)`
- 作用：把门目标与门值同步到站台门方块实体；非连续运动且门开着时记入活动集合，否则移出。调用者：`TrainMixin.java:247`。

### `public void clearPlatformDoors()`
- 作用：通知所有活动门本列车已离开并清空。调用者：`ClientData.java:197`、`:233`。

### `protected boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks)` → `true`
- 客户端总是认为能开门。

### `protected float getModelZOffset()`
- 返回：几种特定车型（伦敦地铁 199x、mpl_85、br_423）的模型前后偏移 ±0.5（按是否反向），否则 0。

### `protected double asin(double value)`
- `Math.asin`（服务端可能用别的实现，见 `Train`）。

### `public void simulateTrain(Level world, float ticksElapsed, SpeedCallback speedCallback, AnnouncementCallback announcementCallback, AnnouncementCallback lightRailAnnouncementCallback)`
- 作用：记录上一帧值；`ticksElapsed != 0` 时：车头所在段变化则重新找之前最近的站台；用 `AnnouncementStops.nextStop(path, head)` 求列车自己路径上的下一停站；`RailwayData.useRoutesAndStationsFromIndex` 求当前/下一线路与车站（下一站优先用路径上的），失败则清空。然后 `simulateTrain(world, ticksElapsed, null)`（基类运动）。若尚无车厂或线路：按停车线查车厂，线路取贯通运行的 `tripRouteIds` 或车厂对该车型的线路，并记下车厂最近发车时间。手动驾驶且本地玩家持钥匙在车上时设置驾驶界面数据。
- 调用者：`RenderTrains.java:156`（每帧，`ticksElapsed` 为上一帧时长）。

### `public void renderTranslucent()`
- 执行并清空半透明重画任务。

### 取值
- `getViewOffset()`：乘车视角偏移（`vehicleRidingClient`），非乘车为 `null`。调用者：`RenderTrainD51.java:102` 等。
- `getCurrentStationIndex()`、`getThisRoute()`、`getNextRoute()`、`getThisStation()`、`getNextStation()`、`getLastStation()`：报站与显示。调用者：`ModelTrainBase.java:79-84`、`RenderDrivingOverlay.java:96-100`、`RenderTrains.java:157-160`。

### `public void startRidingClient(UUID uuid, float percentageX, float percentageZ)`
- 作用：乘客上车；本地玩家则标记刚上车、站立。调用者：`ClientData.java:284`、`:304`。

### `public void updateRiderPercentages(UUID uuid, float percentageX, float percentageZ)`
- 作用：更新乘客在车内的相对位置。调用者：`ClientData.java:294`、`:314`。

### `public void copyFromTrain(Train train)`
- 作用：用服务器发来的完整列车更新本对象：换了停车线（同一列车去同车池另一停车线，R1）时重新查车厂与线路；复制路径、距离、乘客；无运行段时复制速度与进度（有运行段时由段按时钟定位，客户端渐入，G2）；贯通运行线路变化时重查报站线路；复制其余运行状态；路径原地重填，所以使速度曲线缓存失效。
- 调用者：`ClientData.java:201`。

## public static final class State
运行中变化的部分（G2），按 `Train#writeStatePacket` 的格式在网络线程读取，再应用到客户端线程的列车。
- 包字段顺序：`long id, int syncKey, int repeatIndex1, int repeatIndex2, int n + long×n tripRouteIds, float speed, double railProgress, float elapsedDwellTicks, int nextStoppingIndex, int nextPlatformIndex, boolean reversed, boolean isCurrentlyManual, boolean isOnRoute, int manualNotch, boolean doorTarget, int m + UUID×m ridingEntities, boolean useSpeedCurve, float decelerationConstant, float cruiseCap, segmentSpec（readSegmentSpec）, int doorsCloseTicks`。
- `id`、`syncKey` 为 public。
- 调用者：`ClientData.java:212`。

### `public boolean applyState(State state)`
- 作用：同步键（路径）不一致时返回 `false` 不修改；否则像 `copyFromTrain` 一样（不含路径）更新；减速度或速度曲线开关变化时使曲线缓存失效；记录收到关门倒计时的时间。
- 调用者：`ClientData.java:217`。

### 状态查询
- `speedChange()`：本帧速度变化。调用者：`JonTrainSound.java:65`、`BveTrainSound.java:92`。
- `justOpening()`：门值从 0 变为 >0。
- `justClosing(float doorCloseTime)`：门值跨过阈值下降。
- `isDoorOpening()`、`getRouteIds()`。

### `private int getPreviousStoppingIndex(int headIndex)`
- 返回：车头处或之前最近的站台停站段下标，无则 0。

### `interface SpeedCallback { void speedCallback(float speed, int stopIndex, List<Long> routeIds); }`
### `interface AnnouncementCallback { void announcementCallback(int stopIndex, List<Long> routeIds); }`
- 由渲染层（`RenderTrains`）或 ANTE 的 mixin 提供。
