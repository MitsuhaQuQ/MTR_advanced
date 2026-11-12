# ItemNodeModifierBase.java

所有"在两个轨道节点之间操作"的物品基类（轨道连接器、信号修改器、隧道/桥梁/圆形隧道创建器、ANTE 复合创建器等），服务端与客户端共用。继承 `ItemBlockClickingBase` 的"先点一个方块、再点另一个"流程：第一次点击记下节点，第二次点击时根据两节点角度求轨道两端朝向，交给子类 `onConnect`（连接器）或 `onRemove`（拆除器）。本分支改动：节点角度改用 `BlockNode.getAngle(world, pos, state)`（支持节点自定义任意角度，存于节点数据而非方块状态）；新增 `canAlignNodes()`：子类返回 true 时，玩家潜行连接会先把两端尚未连接的节点角度对齐到两点连线方向（`BlockNode.setCustomAngle`）。测试驱动 `Fixture.connectRail` 复刻了 `onEndClick` 的步骤（`testkit/src/main/java/mtm/testkit/serverdriver/Fixture.java:133`）。

## 类 `ItemNodeModifierBase`（abstract，extends `ItemBlockClickingBase`）

### 字段与常量
- `forNonContinuousMovementNode`、`forContinuousMovementNode`、`forAirplaneNode`：可用于哪类节点（非连续运动如火车，连续运动如缆车，飞机）。
- `isConnector`：true 为连接类（调 `onConnect`），false 为拆除类（调 `onRemove`）。
- `TAG_POS = "pos"`：物品 NBT 中第一个节点坐标（`BlockPos.asLong`），由父类写入。
- `TAG_TRANSPORT_MODE = "transport_mode"`：第一个节点的交通方式。

### 构造函数 `ItemNodeModifierBase(boolean forNonContinuousMovementNode, boolean forContinuousMovementNode, boolean forAirplaneNode, boolean isConnector)`
- 放入 `CreativeModeTabs.CORE`，堆叠上限 1。

### `void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag)`
- 已选第一个节点时显示其坐标（金色）。

### `protected void onStartClick(UseOnContext context, CompoundTag compoundTag)`
- 记下第一个节点的交通方式。

### `protected void onEndClick(UseOnContext context, BlockPos posEnd, CompoundTag compoundTag)`
- 作用：两端都是节点且交通方式与记录一致时：
  - 连接类且两点不同：计算连线方向角 `angleDifference`（度，`atan2(dz, dx)`）；若 `canAlignNodes()` 且玩家潜行，先对齐两端未连接节点的角度；读两端节点角度，起点朝向取与连线方向相近的那一侧（否则 +180°），终点朝向反之，转为 `RailAngle`，调 `onConnect`；
  - 拆除类：调 `onRemove`。
  最后清除交通方式记录。
- 注意：按父类 `ItemBlockClickingBase.useOn`，`posEnd` 是第一次点击时存入 NBT 的位置，`context.getClickedPos()`（代码中称作 `posStart`）是第二次点击的位置；只在服务端执行。

### `protected boolean clickCondition(UseOnContext context)`
- 点到的是节点，且节点交通方式属于本物品适用的类别。

### `protected boolean canAlignNodes()`
- 默认 false；子类覆盖为 true 后启用潜行对齐（轨道连接器覆盖，`common/src/main/java/mtr/item/ItemRailModifier.java:46`）。

### `protected abstract void onConnect(Level world, ItemStack stack, TransportMode transportMode, BlockState stateStart, BlockState stateEnd, BlockPos posStart, BlockPos posEnd, RailAngle facingStart, RailAngle facingEnd, Player player, RailwayData railwayData)`
- 子类实现：建轨道、放信号、标记隧道等。

### `protected abstract void onRemove(Level world, BlockPos posStart, BlockPos posEnd, Player player, RailwayData railwayData)`
- 子类实现：拆除两节点间的轨道或信号等。

### `private static void alignNode(Level world, BlockPos pos, BlockState state, float angle)`
- 节点尚未连接（`IS_CONNECTED` 为 false）时设为自定义角度 `angle`（度）。
