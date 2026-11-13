# BlockPlatformEndDoor.java（本分支新增）

站台端门：用对应站台门玻璃面板做成的单扇手动门（两格高）。门扇位置与同朝向的屏蔽门/安全门玻璃一致，所以放在一排屏蔽门旁边时会沿用其朝向并与之对齐；开门方向是它自己的设置（0.65.29）：向背离朝向的一侧开，或向朝向一侧开。四种样式：0 安全门（APG，上半较矮）、1 屏蔽门（PSD）、2 无外侧把手的屏蔽门、3 无外侧把手的安全门（也算 APG）。注册于 `common/src/main/java/mtr/Blocks.java:13`–`:15`，物品由 `ItemPSDAPGBase` 的 `PLATFORM_END_*` 类型放置；`BlockPSDTop` 会把它当作屏蔽门下方方块（`common/src/main/java/mtr/block/BlockPSDTop.java:73`）。测试：`common/src/test/java/mtr/block/PlatformEndDoorTest.java`。模型由 `models/export_platform_edge_doors.py` 从 Blockbench 源导出；0.76.3 起导出时把门整扇沿门扇平面做前后镜像（推杠在站台一侧、把手在另一侧，原先两种样式都装反了），门扇位置和铰链不变。

## 类 `BlockPlatformEndDoor`（final，extends `BlockPSDAPGBase`）

### 方块状态属性
- 继承 `FACING`、`HALF`（上下半）、`SIDE_EXTENDED`（固定 `SINGLE`）；新增：
- `OPEN`：是否打开。
- `INWARD`：门扇是否向方块后方（朝北时为 +z）打开，而不是向前方。
- `MIRRORED`（0.77.0）：整扇门是否左右镜像（铰链在 x 1 而不是 x 15）；刷子右键切换，开门方向不变，旧门读出为 false。

### 字段 `style`
- 0 APG、1 PSD_1、2 PSD_2（无外侧把手的全高）、3 APG_2（无外侧把手的半高）。

### 构造函数 `BlockPlatformEndDoor(int style)`
- 默认朝北、下半、关闭、`INWARD = true`（朝站台开）。
- 这个默认值就是实际放下的值：`ItemPSDAPGBase.useOn` 用 `defaultBlockState()` 直接 `setBlockAndUpdate`，不走下面的 `getStateForPlacement`（0.76.8）。

### `void createBlockStateDefinition(...)`
- 登记 `FACING, HALF, SIDE_EXTENDED, OPEN, INWARD, MIRRORED`。

### `BlockState getStateForPlacement(BlockPlaceContext ctx)`
- 作用：朝向取 `rowFacing`；`INWARD` 一律为 true（朝站台开，不再看玩家站哪一侧）。
- 端门由物品直接按默认状态放下，这里实际不会被调到；两边保持同一个值，以免以后改了放置路径又不一致。

### `static Direction rowFacing(BlockGetter world, BlockPos pos, Direction looking)`（包级）
- 作用：依次查看玩家视线的右、左、前、后四个相邻格，第一个"沿其自身一排"相邻（相邻方向与其朝向不同轴）的屏蔽门/安全门方块的朝向即为结果；没有则用玩家视线方向。

### `void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity, ItemStack)`
- 服务端把上半方块的 `INWARD`、`MIRRORED` 同步为下半的值。

### `InteractionResult use(...)`
- 手持刷子（`Items.BRUSH`）：上下两半一起切换 `MIRRORED`，开关状态不变（0.77.0）。
- 否则潜行右键切换开门方向并关门，普通右键开关门。

### `static VoxelShape outline(style, half, facing, open, inward, mirrored)`
- 关门时 0..16 × 0..4；开门时门扇在铰链那一格：未镜像 x 14..16，镜像 x 0..2；向外 z -12..4，向内 z 2..18。

### `protected boolean isAPG()`
- `style == 0 || style == 3`（两种半高安全门，上半块 9 格高）。

### `Item asItem()`
- 按样式返回对应端门物品。

### `VoxelShape getShape(...)`
- `outline(style, half, facing, open, inward)`。

### `static VoxelShape outline(int style, DoubleBlockHalf half, Direction facing, boolean open)` / `outline(..., boolean inward)`（包级）
- 作用：高度：APG 上半 9 像素，其余 16。关闭时为方块前部 4 像素厚的板（与屏蔽门玻璃 z 2..4 对齐）；打开时门扇绕 (15, 3) 处门轴转开：向外为 z −12..4、x 14..16，向内为 z 2..18、x 14..16（伸进后方方块）。按朝向旋转。

### `InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)`
- 作用：服务端：潜行时只切换开门方向并关闭门；否则切换开/关。上下两半同时更新，播放铁门开/关声音。客户端直接返回 SUCCESS。
