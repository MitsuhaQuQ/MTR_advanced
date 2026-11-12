# TrainClientMixin.java（ANTE mixin）

客户端 mixin，作用于 `mtr.data.TrainClient`（客户端列车）。整体替换 `handlePositions` 与 `simulateCar` 两个方法，在 MTR 原逻辑上加入 ANTE 的车厢滚转（乘客跟着倾斜、车厢连接处按滚转计算），并按 ANTE 客户端配置关闭列车渲染/声音。两处替换都列在 `MixinLintTest` 的允许项中（`common/src/test/java/cn/zbx1425/mtrsteamloco/MixinLintTest.java:42`、`:43`）。本分支改动：去掉了 ANTE 对 `TrainClient.simulateTrain` 的整体替换（它是旧版本拷贝，缺少 MTR 现有逻辑，如直通到另一条线路的班次），现在 `simulateTrain` 用 MTR 自己的。注意：`common/src/main/java/mtr/data/TrainClient.java:31` 的注释仍写着本 mixin 替换 `simulateTrain`，与此处代码不一致（以代码为准）。

## 类 `TrainClientMixin`（abstract，extends `Train`，implements `IGui`）

### 构造函数 `TrainClientMixin(long sidingId, float railLength, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime, CompoundTag compoundTag)`
- 仅为编译继承 `Train` 所需，合并后不使用。

### 影子成员
- 字段：`isRemoved`、`justMounted`、`oldSpeed`、`oldRailProgress`、`isSitting`、`previousShifting`、`CONNECTION_HEIGHT`/`CONNECTION_Z_OFFSET`/`CONNECTION_X_OFFSET`（static）、`trainSound`、`vehicleRidingClient`、`trainRenderer`、`speedCallback`、`announcementCallback`、`lightRailAnnouncementCallback`、`depot`、`routeIds`、`currentStationIndex`、`thisRoute`、`nextRoute`、`thisStation`、`nextStation`、`lastStation`。
- 方法：`getPreviousStoppingIndex(int)`、`justOpening()`、`renderCarModels(...)`、`shouldRenderCarConnection(...)`。

### `private void initTail(FriendlyByteBuf packet, CallbackInfo ci)`
- 注入点：`TrainClient(FriendlyByteBuf)` 构造 `TAIL`。
- 作用：ANTE `ClientConfig.enableTrainRender` 关闭时把渲染器换成 `NoopTrainRenderer.INSTANCE`；`enableTrainSound` 关闭时把声音换成 `NoopTrainSound.INSTANCE`（通过 `TrainClientAccessor`）。

### `private void copyFromTrainTail(Train other, CallbackInfo ci)`
- 注入点：`copyFromTrain` 的 `TAIL`。
- 作用：把另一列车对象的自定义配置复制过来（服务器更新包到达时保留配置）。

### `protected boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed)`
- 作用：整体替换 MTR 同名方法。本地玩家为空时返回 false。否则：
  1. `vehicleRidingClient.begin()`；计算每节车厢滚转角（`TrainExtraSupplier.getRollAngleAt`），把 `reversed`、滚转、车厢位置交给 ANTE 扩展的乘车客户端；
  2. `ticksElapsed > 0` 时：若本地玩家在车上，调速度回调（`speed * 20` 即格/秒，停靠序号，线路 id）；车头越过"上一停站点 + (车厢数+1)×车长"位置时调报站回调；刚开门或刚上车时调轻轨报站回调。然后对每个乘客：确定所在车厢，`moveSelf` 移动，车厢变了就按新车厢重算，再 `setOffsets`（含按住 Shift 切换坐/站，`Config.shiftToToggleSitting()` 且非 Vivecraft）；
  3. `vehicleRidingClient.end()`，`justMounted = false`；
  4. 找离相机最近的车厢，在该位置 `trainSound.playNearestCar`。
- 参数：`positions` 每节车厢连接点的世界坐标（长度 = 车厢数 + 1）；`ticksElapsed` 本帧经过的 tick 数。
- 返回：true（已处理）。

### `protected void simulateCar(Level world, int ridingCar, float ticksElapsed, double carX, double carY, double carZ, float carYaw, float carPitch, double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch, boolean doorLeftOpen, boolean doorRightOpen, double realSpacing)`
- 作用：整体替换 MTR 同名方法。本地玩家为空直接返回。播放每节车厢声音（开门时加开门声）；用 `renderPlayerAndGetOffset()` 的偏移修正坐标后 `renderCarModels`；第 2 节起，若需要画车厢连接，按前后车厢各自的滚转（`reversed` 时取反）用 ANTE `Matrices` 求两车端点，计算连接中心、偏航、俯仰，分两次（i = 0 连接通道，i = 1 栏杆/挡板）求 8 个角点，调 `trainRenderer.renderConnection` / `renderBarrier`。
- 参数：`ridingCar` 车厢序号；`car*` 本车中心坐标与角度；`prevCar*` 前一节；`realSpacing` 实际车长（格）。

### `private static Vec3 now(Matrices matrices)`
- 返回当前矩阵的平移部分（即该点的世界坐标）。
