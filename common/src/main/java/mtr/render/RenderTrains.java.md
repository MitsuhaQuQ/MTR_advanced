# RenderTrains.java

客户端：每帧绘制列车、升降梯和轨道（包括轨道颜色、信号闭塞色带）的主渲染器。它是座位实体 `EntitySeat` 的实体渲染器（`common/src/main/java/mtr/MTRClient.java:249`、`forge/src/main/java/mtr/MTRForge.java:58` 注册），所以每帧运行一次；Fabric 另有备用调用（实体不存在时，`fabric/src/main/java/mtr/MTRFabricClient.java:26`）。`render` 让每列客户端列车前进一帧，列车再通过各自的 `TrainRendererBase` 画车厢。ANTE 用 `RenderTrainsMixin`/`RenderTrainsAccessor` 改写了部分行为。本分支改动：加了类说明；渲染矩阵不再在开头平移到相机原点（改由 `TrainRendererBase.translateToWorld` 和 `IDrawing.draw…World` 系列按世界坐标绘制）；每帧调用 `RenderRailInspect`（刷子查看轨道）和 `RenderStationAreas`（车站区域工具）；线路名比较改用 `Route.isSameLine`/`getLineKey`；颜色取值经 `Compat.getColor`。

## 类 `RenderTrains`（extends `EntityRendererMapper<EntitySeat>`，implements `IGui`）

### 静态字段与常量
- `maxTrainRenderDistance`：当前列车渲染距离（方块；动态 FPS 时随帧时间调整）。
- `creatorProperties`：资源包生成器正在编辑的属性（`ResourcePackCreatorOptionsScreen` 使用）。
- `lastRenderedTick`、`prevPlatformCount`、`prevSidingCount`、`renderedUuid`（本帧已由哪个座位实体渲染过）。
- `PLAYER_RENDER_OFFSET = 1000`：乘车玩家渲染时的竖向偏移（见 `TrainRendererBase.renderRidingPlayer`，与 `PlayerRendererOffsetMixin` 抵消）。
- `AVAILABLE_TEXTURES`、`UNAVAILABLE_TEXTURES`：纹理是否存在的缓存。
- `DETAIL_RADIUS = 32`、`DETAIL_RADIUS_SQUARED`、`LIFT_LIGHT_COLOR = 0xFFFF0000`、`MAX_RADIUS_REPLAY_MOD = 1024`、`TICKS_PER_SECOND = 20`、`DISMOUNT_PROGRESS_BAR_LENGTH = 30`、`TOTAL_RENDER_STAGES = 2`。
- `RENDERS` / `CURRENT_RENDERS`：`[阶段(0 普通, 1 优先)][QueuedRenderLayer] → 纹理 → 绘制回调集合`；一个游戏 tick 内收集的绘制回调，下一 tick 换成"当前"并在每帧执行（轨道等按 tick 更新、按帧绘制）。
- `LIFT_TEXTURE`、`ARROW_TEXTURE`。

### 构造函数 `RenderTrains(Object parameter)`

### `void render(EntitySeat entity, float entityYaw, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int entityLight)`（@Override）
- 转调静态 `render`。

### `ResourceLocation getTextureLocation(EntitySeat)`
- null（ANTE mixin 会改为座位纹理）。

### `static void render(EntitySeat entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers)`
- 作用：
  1. 去重：`entity == null` 表示备用调用；装了 Pehkui 时只用备用调用；同一帧只渲染一次。
  2. 帧时长（暂停或同一 tick 为 0）；动态 FPS：帧时长 > 0.5 tick 时把渲染距离向 `DETAIL_RADIUS` 收缩一半，< 0.4 时每帧 +1，上限 `渲染区块数 × (trainRenderDistanceRatio + 1)`；否则固定为该上限。
  3. 设置 `TrainRendererBase` 静态信息，不透明批次中对每列列车 `simulateTrain`，三个回调：速度回调（未按住 Shift 下车进度条时在动作栏显示速度，速度 ≤ 5 时轮流显示本站/下一站/终点站）、报站回调（启用 TTS 或显示报站消息时，拼接"下一站"、换乘线路、连通车站换乘、换乘到下一条不同线路的说明后朗读/显示）、轻轨回调（轻轨线路号报站）；不隐藏半透明部件时再画半透明批次。
  4. 升降梯：`tickClient` 后在可见时画轿厢模型与楼层显示。
  5. 刷子查看轨道、车站区域。
  6. 轨道：只画渲染距离内的；同一对节点的正反两条记录只画一次（类型不同才都画）；按交通方式：火车画标准轨道（拿轨道相关物品时带颜色并画信号色带）、船只在拿相关物品时画、缆车画站台/站段金属轨与单向箭头和缆线、飞机拿物品时彩色否则铁块纹理。
  7. 新 tick 时把收集的绘制回调换成当前，然后按阶段与渲染层执行（`MoreRenderLayers.getLight/getInterior/getExterior`）。
  8. 站台或停车轨数量变化时 `DATA_CACHE.sync()`；`clearDataIfNeeded()`。

### `static boolean shouldNotRender(BlockPos pos, int maxDistance, Direction facing)`
- 用相机实体位置调用私有重载。被信号、升降梯按钮/面板、PIDS 等使用（`common/src/main/java/mtr/render/RenderLiftButtons.java:55`）。

### `static void clearTextureAvailability()`
- 清空纹理存在缓存。调用者：`common/src/main/java/mtr/client/CustomResources.java:48`。

### `static void renderLiftDisplay(PoseStack, MultiBufferSource, BlockPos pos, String floorNumber, Lift.LiftDirection, float maxWidth, float height)`
- 画升降梯楼层号与上/下箭头（超出 PIDS 可视距离不画）。调用者：`RenderLiftButtons.java:127`。

### `static boolean isHoldingRailRelated(Player player)`
- 手持节点修改类物品、信号灯/臂板、节点或站台方块。调用者：`common/src/main/java/mtr/render/RenderNode.java:60` 等。

### `static boolean showShiftProgressBar()`
- 按住 Shift 下车时在动作栏显示进度条并返回 false；否则返回 true。

### `@Deprecated static void scheduleRender(ResourceLocation, boolean priority, Function<ResourceLocation, RenderType>, BiConsumer<PoseStack, VertexConsumer>)`
- 旧接口，按 `EXTERIOR` 层登记。

### `static void scheduleRender(ResourceLocation resourceLocation, boolean priority, QueuedRenderLayer queuedRenderLayer, BiConsumer<PoseStack, VertexConsumer> callback)`
- 登记一个绘制回调（本 tick 收集，下一 tick 起每帧执行）。

### `static String getInterchangeRouteNames(Station station, Route thisRoute, Route nextRoute)`
- 该站中与本线、下一线都不同"线"（按线路键）的线路名，用逗号连接。调用者：`common/src/main/java/mtr/model/ModelTrainBase.java:242`。

### `private static double maxDistanceXZ(Vec3, BlockPos)` / `private static boolean shouldNotRender(Vec3 cameraPos, BlockPos pos, int maxDistance, Direction facing)`
- 水平切比雪夫距离；无相机、玩家在方块背面（给了 `facing` 时）或超出距离（Replay Mod 下用 1024）时不渲染。

### `private static void renderRailStandard(Level, Rail, float yOffset, boolean renderColors, float railWidth[, String texture, float u1, float v1, float u2, float v2])`
- 按 `rail.render` 的分段四边形，以方块光照登记轨道纹理绘制（`NONE` 类型只在需要时画单向箭头；石英类型带颜色时用预览纹理；纹理按位置和配置偏移）。

### `private static void renderSignalsStandard(Level, PoseStack, MultiBufferSource, Rail, BlockPos startPos, BlockPos endPos)`
- 在轨道上为每个信号闭塞画一条颜色色带（多个闭塞并排），闭塞被占用时每秒闪烁发光。

### `private static Component getStationText(Station station, String textKey)`
- `gui.mtr.<键>_station(_cjk)` 文本。

## 枚举 `QueuedRenderLayer`
- `LIGHT`、`LIGHT_TRANSLUCENT`、`INTERIOR`、`EXTERIOR`。
