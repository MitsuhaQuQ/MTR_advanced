# TrainRendererBase.java

客户端：绘制一列列车各节车厢的渲染器基类。静态字段保存一帧内共享的东西（相机、矩阵、缓冲等），由 `setupStaticInfo` 在画列车前设置（`common/src/main/java/mtr/render/RenderTrains.java:154`）。子类：`JonModelTrainRenderer`、ANTE 的 `ScriptedTrainRenderer`、`RenderTrainDK3` 等。本分支改动：加了类说明；`translateToWorld`（渲染矩阵以相机为原点时把世界坐标转换过去）；`applyAverageTransform` 的距离判断与相机偏移。

## 类 `TrainRendererBase`（abstract）

### 静态字段（一帧共享）
- `camera`、`world`、`lastFrameDuration`（上一帧经过的 tick 数）、`matrices`、`vertexConsumers`、`isTranslucentBatch`（当前是否半透明批次）；私有：`cameraEntity`、`hasEntity` 与 `entityX/Y/Z`（乘坐座位实体的插值位置）、`entityRenderDispatcher`、`player`、`playerEyePosition`。

### 抽象方法
- `TrainRendererBase createTrainInstance(TrainClient train)`：为具体列车创建实例。
- `void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen)`。
- `void renderConnection(Vec3 prevPos1..4, Vec3 thisPos1..4, double x, double y, double z, float yaw, float pitch)`。
- `void renderBarrier(...)`（同参数）。

### `static void renderRidingPlayer(Vec3 viewOffset, UUID playerId, Vec3 playerPositionOffset)`
- 作用：在乘客位置画其他玩家（或第三人称时画自己）：`applyAverageTransform` 后向下偏移 `RenderTrains.PLAYER_RENDER_OFFSET`，用全亮光照渲染玩家实体，最后 `popPose`。调用者：`common/src/main/java/mtr/data/VehicleRidingClient.java:64`。

### `static void setupStaticInfo(PoseStack matrices, MultiBufferSource vertexConsumers, EntitySeat entity, float tickDelta)`
- 设置上述静态字段（座位实体位置按 `tickDelta` 插值）。

### `static void setBatch(boolean isTranslucentBatch)`
- 设置当前批次（不透明/半透明分两遍画）。调用者：`RenderTrains.java:155`、`:237`。

### `static void translateToWorld(Vec3 viewOffset, double x, double y, double z)`
- 无视角偏移（不在车上第一人称）时，渲染矩阵以相机为原点，用 `IDrawing.translateWorldOrigin` 把世界坐标转换过去；有视角偏移时直接平移。调用者：`RenderTrains.java:247`、ANTE 的 DK3/脚本渲染器。

### `static BlockPos applyAverageTransform(Vec3 viewOffset, double x, double y, double z)`
- 作用：求车厢（或乘客）所在方块位置（有视角偏移时加上相机实体位置）；超出 `渲染距离 × (trainRenderDistanceRatio + 1)` 返回 null（不压栈）；否则 `pushPose`，在乘车第一人称视角下按相机/玩家眼睛（Vivecraft 用座位实体）位置与相机偏航修正矩阵，再减去视角偏移。
- 返回：方块位置或 null。调用方在非 null 时负责之后 `popPose`。调用者：`RenderTrains.java:242`、各子类。
