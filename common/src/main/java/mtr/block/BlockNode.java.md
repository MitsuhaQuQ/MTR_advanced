# BlockNode.java

MTR 轨道节点方块（火车、船、缆车、飞机等各交通方式），服务端与客户端共用。方块状态中的 `FACING`/`IS_45`/`IS_22_5` 表示原版的 16 个 22.5° 朝向之一；本分支新增"自由角度节点"：火车节点和船节点带方块实体 `TileEntityNode`，可保存任意角度（0.1° 精度，`custom_angle`），优先于方块状态的角度。刷子右键这类节点打开节点角度界面（`NodeAngleScreen`）；潜行放置时取玩家朝向作为自定义角度。还提供角度文本、指南针式方向描述、玩家朝向换算等工具。被 `ItemNodeModifierBase`（`common/src/main/java/mtr/item/ItemNodeModifierBase.java:78`、`:120`）、`RailNodeRepair`（`common/src/main/java/mtr/data/RailNodeRepair.java:105`、`:114`）、`RenderNode`、`NodeAngleScreen`、`PacketTrainDataGuiServer`（`:1108`、`:1115`）、`RailwayData`（`:1513`）等使用。

## 类 `BlockNode`（extends `BlockDirectionalMapper`）

### 字段与方块状态属性
- `transportMode`（final）：节点所属交通方式。
- `FACING`（布尔：true 为 0°，false 为 90° 基准）、`IS_22_5`、`IS_45`：组合成 16 个朝向。
- `IS_CONNECTED`：是否已有轨道连接（已连接时轮廓变成 1 像素厚的板）。

### 构造函数 `BlockNode(TransportMode transportMode)`
- 金属灰、强度 2、不遮挡、活塞推不动（`Compat.noPistonPush`）。

### `BlockState getStateForPlacement(BlockPlaceContext ctx)`
- 按玩家朝向取 16 象限之一设置三个角度属性，未连接。

### `void playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player)`
- 服务端：`railwayData.removeNode(player, pos, transportMode)` 删掉连在该节点上的轨道，并 `PacketTrainDataGuiServer.removeNodeS2C` 通知客户端。

### `VoxelShape getShape(...)` / `VoxelShape getCollisionShape(...)`
- 已连接为底部 1 像素板，否则整块；无碰撞。

### `PushReaction getPistonPushReaction(BlockState)`
- `BLOCK`（旧版本方式，1.20 由 `Compat.noPistonPush` 属性生效）。

### `void createBlockStateDefinition(...)`
- `FACING, IS_22_5, IS_45, IS_CONNECTED`。

### `static void resetRailNode(Level world, BlockPos pos)`
- 把节点设为未连接。调用者：`common/src/main/java/mtr/data/RailwayData.java:1513`、`:1540`。

### `static float getAngle(BlockState state)`
- 方块状态角度：`(FACING ? 0 : 90) + (IS_22_5 ? 22.5 : 0) + (IS_45 ? 45 : 0)`（度）。

### `static float getAngle(BlockGetter world, BlockPos pos, BlockState state)`
- 有自定义角度的节点方块实体则返回自定义角度，否则方块状态角度。

### `static String getAngleText(float angle)` / `static String getAngleValue(float angle)`
- 角度规范到 (−180, 180]（−180 显示为 180），保留 1 位小数；前者加 `°`。

### `static Component getAngleDirection(float angle)`
- 作用：把角度描述为指南针方向：以东为 0°、俯视顺时针（南 90°、北 −90°、西 180°）；偏差 < 0.05° 显示"正东"等（`gui.mtr.angle_due`）；正好 45° 显示组合方向（`gui.mtr.direction_es` 等，按从东顺时针先出现者在前）；否则"东偏南 30.0°"（`gui.mtr.angle_toward`）。调用者：`common/src/main/java/mtr/render/RenderNode.java:84`、`screen/NodeAngleScreen.java:232`。

### `static float getPlayerAngle(Player player)`
- 玩家朝向按节点角度约定换算：`wrapDegrees(yaw + 90)`。

### `private static Component getCardinal(int cardinal)` / 常量 `DIRECTION_KEYS = {e, s, w, n}`

### `static void setCustomAngle(BlockGetter world, BlockPos pos, float angle)`
- 若该位置是节点方块实体，设置自定义角度（规范到 `RailAngle` 网格）。

## 内部类 `BlockFreeAngleNode`（abstract，extends `BlockNode`，implements `EntityBlockMapper`）
- 可设自由角度的节点基类。
- `setPlacedBy(...)`：服务端、放置者潜行时，以 `round(yaw) + 90` 为自定义角度并在动作栏显示角度。
- `use(...)`：只在手持刷子时处理（其他物品必须放行，否则轨道连接器无法在节点上使用），服务端发 `openNodeAngleScreenS2C` 打开角度界面。

## 内部类 `BlockRailNode`（extends `BlockFreeAngleNode`）
- 火车节点；方块实体 `TileEntityRailNode`。注册于 `common/src/main/java/mtr/Blocks.java:79`。

## 内部类 `BlockBoatNode`（extends `BlockFreeAngleNode`）
- 船节点；只能放在液体或冰面上方且自身位置无液体（`canSurvive`，否则 `updateShape` 变空气）；方块实体 `TileEntityBoatNode`。

## 内部类 `TileEntityNode`（abstract，extends `BlockEntityClientSerializableMapper`）
- 字段：`hasCustomAngle`、`customAngle`（度）。
- NBT 键 `custom_angle`：有自定义角度时才写；读时以是否存在该键判断。
- `private void setCustomAngle(float angle)`：设置（经 `RailAngle.fromAngle` 规范）、标记保存并同步客户端。

## 内部类 `TileEntityRailNode` / `TileEntityBoatNode`
- 分别对应 `RAIL_NODE_TILE_ENTITY`、`BOAT_NODE_TILE_ENTITY` 类型（`common/src/main/java/mtr/BlockEntityTypes.java:12`、`:13`）。

## 内部类 `BlockContinuousMovementNode`（extends `BlockNode`）
- 缆车节点：`upper`（上半格还是下半格）、`isStation`（是否站台节点）。
- `getStateForPlacement`：8 象限（无 22.5°）。
- `getShape`：上半或下半 8 像素。
- `appendHoverText`：多行提示 `tooltip.mtr.cable_car_node(_station)`。
