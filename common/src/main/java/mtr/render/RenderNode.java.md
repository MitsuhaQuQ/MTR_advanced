# RenderNode.java（本分支新增）

客户端：轨道节点（带自由角度方块实体的火车节点，船节点通过子类 `RenderBoatNode`）的方块实体渲染器。玩家手持刷子或轨道相关物品时，在节点上画一条蓝线表示节点方向；手持刷子时再画角度刻度盘（从正东起的红线与红弧、箭头）和浮空文字（角度、指南针方向、相连轨道的坡度）。对准节点时在动作栏显示 `gui.mtr.node_angle_status`（节点角度、方向、玩家朝向）。注册：`common/src/main/java/mtr/MTRClient.java:112`（`RAIL_NODE_TILE_ENTITY`）。

## 类 `RenderNode<T extends BlockNode.TileEntityNode>`（extends `BlockEntityRendererMapper<T>`）

### 常量
- `LINE_LENGTH = 1.5`（方向线半长，格）、`LINE_Y_CONNECTED = 0.125`、`LINE_Y_NOT_CONNECTED = 1.05`（线的高度，已连接节点是薄板，所以更低）、`TEXT_Y_OFFSET = 0.6`、`TEXT_SCALE = 0.025`、`TEXT_LINE_HEIGHT = 10`、`MAX_TEXT_DISTANCE = 16`（显示文字的最大距离）、`DIAL_RADIUS = 1`、`DIAL_Y_OFFSET = 0.01`、`DIAL_DEGREES_PER_SEGMENT = 7.5`（弧线每段角度）、`DIAL_ARROW_LENGTH = 0.15`。

### 构造函数 `RenderNode(BlockEntityRenderDispatcher dispatcher)`

### `void render(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)`
- 作用：无世界/玩家或没拿刷子且没拿轨道相关物品（`RenderTrains.isHoldingRailRelated`）时不画；取节点角度（`BlockNode.getAngle(world, pos, state)`）画蓝色方向线；已连接节点显示其轨道实际朝向（`Rail.getNodeFacing`，与刷子界面同一数值），否则显示节点角度；拿刷子时画刻度盘；准星对准该节点时更新动作栏；拿刷子且在 16 格内时画三行文字。

### `private static void renderAngleDial(PoseStack, MultiBufferSource, float y, float angle)`
- 作用：说明角度含义：从节点中心向正东（0°）画红线，再从正东沿角度方向画红弧（俯视顺时针为正），弧长 ≥ 7.5° 时在末端画两道箭头。

### `private static void renderText(PoseStack, MultiBufferSource, String angleText, Component directionText, String slopeText, float y)`
- 面向相机绘制三行居中文字。

### `private static void drawCentered(PoseStack, MultiBufferSource, Font, Component, int y)`
- 白色、带阴影、全亮。

### `private static String getSlopeText(BlockPos pos)`
- 节点还没有自己的坡度，所以显示每条相连轨道离开节点方向的平均坡度（`(终点 y − 节点 y) × 100 / 长度`，带符号百分比，两位小数），用 ` / ` 分隔；没有轨道为 `-`。
