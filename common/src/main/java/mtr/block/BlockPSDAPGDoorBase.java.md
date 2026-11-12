# BlockPSDAPGDoorBase.java

屏蔽门、安全门和升降梯门的门方块基类（服务端与客户端共用），带方块实体记录开度。列车停站时由 `TrainServer` 打开/关闭（`common/src/main/java/mtr/data/TrainServer.java:646`、`:647`），升降梯由 `Lift` 控制（`common/src/main/java/mtr/data/Lift.java:386`）；客户端渲染见 `RenderPSDAPGDoor`。子类：`BlockAPGDoor`、`BlockLiftDoor`、`BlockLiftDoorOdd`、`BlockPSDDoor` 等。本分支改动：`TileEntityPSDAPGDoorBase.writeCompoundTag` 中清除 `TEMP` 标志不再在写数据时立刻改方块，而是排到下一 tick（写数据发生在区块向玩家发送方块更新时，那时改方块会让该区块段被标记为已改但没有变化可发，此后这个段的变化在服务器重启前都发不到玩家——表现为被破坏的方块在客户端又出现）。

## 类 `BlockPSDAPGDoorBase`（abstract，extends `BlockPSDAPGBase`，implements `EntityBlockMapper`）

### 常量与方块状态属性
- `MAX_OPEN_VALUE = 32`：完全打开的开度值。
- `END`：门是否紧挨端头玻璃（用于渲染）。
- `UNLOCKED`：是否被刷子解锁（解锁后玩家可以穿过/手动开）。
- `TEMP`：放置后尚未初始化的临时标志（用于渲染判断）。

### `BlockState updateShape(...)`
- 同组另一扇门（侧向）被拆则本方块变空气；否则按另一侧相邻是否为端头玻璃设置 `END`。

### `void playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player)`
- 定位到这组门的左下方块，创造模式下整组拆除（`IBlock.onBreakCreative`）。

### `void tick(BlockState state, ServerLevel world, BlockPos pos)`
- 方块刻：若已解锁，把门关上（开度 0）。

### `InteractionResult use(...)`
- 手持刷子时：切换上下三格内同类门方块的 `UNLOCKED`（连同左右相邻同类方块），并在动作栏提示已解锁/已上锁。

### `VoxelShape getCollisionShape(...)`
- 门开着时无碰撞。

### `void createBlockStateDefinition(...)`
- `END, FACING, HALF, SIDE, TEMP, UNLOCKED`。

### `private static void lockDoor(Level world, BlockPos pos, BlockState state, boolean unlocked)`
- 设置本方块及左右相邻同类方块的 `UNLOCKED`。

## 内部类 `TileEntityPSDAPGDoorBase`（abstract，extends `BlockEntityClientSerializableMapper`，implements `IGui`）

### 字段与 NBT 键
- `open`（服务端开度，0–32）、`openClient`（客户端动画中的开度）、`temp`。
- NBT 键 `open`、`temp`。

### `void readCompoundTag(CompoundTag)`
- 读 `open`、`temp`。

### `void writeCompoundTag(CompoundTag)`
- 写两个键；若 `temp` 且在服务端，置 `temp = false`，并用 `TickTask` 安排下一 tick 把方块的 `TEMP` 设为 false（不在写数据时改方块，原因见上）。

### `AABB getRenderBoundingBox()`
- 无限大（永不按包围盒剔除）。

### `void setOpen(int open)`
- 开度变化时保存并同步；开度为 1（刚开始打开）时立即把方块 `TEMP` 设为 false。调用者：`TrainServer`、`Lift`、本类 `tick`。

### `float getOpen(float lastFrameDuration)`
- 客户端：`openClient` 以每帧 `0.95 × 帧时长` 的速度追赶目标（目标为 `open − 2×SMALL_OFFSET_16`），返回 0–1 的开门比例（除以 32）。

### `boolean isOpen()`
- `open > 0`。
