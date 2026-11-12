# RenderSignalBase.java

客户端：信号机（色灯与臂板）方块实体渲染器的基类：找到信号机旁的轨道节点，按前方闭塞占用情况决定显示的显示（aspect），再交给子类画灯/臂板。子类：`RenderSignalLight2Aspect`、`RenderSignalLight3Aspect`、`RenderSignalLight4Aspect`、`RenderSignalSemaphore`。本分支改动：显示计算从本类内联的逐节点扫描改为 `mtr.client.SignalAspects.getAspect`（按前方闭塞分段计数）。

## 类 `RenderSignalBase<T extends BlockEntityMapper>`（abstract，extends `BlockEntityRendererMapper<T>`，implements `IBlock`, `IGui`）

### 字段
- `isSingleSided`（只画正面）、`aspects`（显示数，2–4）。

### 构造函数
- `RenderSignalBase(BlockEntityRenderDispatcher dispatcher, boolean isSingleSided, int aspects)`。
- `@Deprecated RenderSignalBase(BlockEntityRenderDispatcher, boolean isSingleSided)`：两显示，向后兼容。

### `final void render(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)`
- 作用：方块须是信号灯或臂板；超出列车渲染距离不画；`getNodePos` 找附近节点，没有则不画；对正面（和双面时的背面）分别求显示（`getOccupiedAspect(节点, 朝向角 + 90)`），≥ 0 时用发光渲染层调用两个子类钩子：新的 `render(…, int occupiedAspect, …)` 和临时的 `render(…, boolean isOccupied = (aspect == 1), …)`。

### `protected void render(PoseStack, MultiBufferSource, VertexConsumer, T entity, float tickDelta, Direction facing, int occupiedAspect, boolean isBackSide)`
- 子类按显示画（默认空，TODO 以后改为抽象）。

### `protected void render(PoseStack, MultiBufferSource, VertexConsumer, T entity, float tickDelta, Direction facing, boolean isOccupied, boolean isBackSide)`
- 临时的旧接口（默认空）。

### `private int getOccupiedAspect(BlockPos startPos, float facing)`
- `SignalAspects.getAspect(startPos, facing, aspects)`：按前方闭塞分段计数的显示；负数表示不显示。

### `private static BlockPos getNodePos(BlockGetter world, BlockPos pos, Direction facing)`
- 在信号机周围（侧向与前后各 ±4，由近到远；向下 5 格到本层）找第一个轨道节点。
