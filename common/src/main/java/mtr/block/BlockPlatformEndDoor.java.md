# BlockPlatformEndDoor.java（本分支新增）

站台端门：用对应站台门玻璃面板做成的单扇手动门（两格高）。门扇位置与同朝向的屏蔽门/安全门玻璃一致，所以放在一排屏蔽门旁边时会沿用其朝向并与之对齐；开门方向是它自己的设置（0.65.29）：向背离朝向的一侧开，或向朝向一侧开。三种样式：0 安全门（APG，上半较矮）、1 / 2 两种屏蔽门（PSD）。注册于 `common/src/main/java/mtr/Blocks.java:13`–`:15`，物品由 `ItemPSDAPGBase` 的 `PLATFORM_END_*` 类型放置；`BlockPSDTop` 会把它当作屏蔽门下方方块（`common/src/main/java/mtr/block/BlockPSDTop.java:73`）。测试：`common/src/test/java/mtr/block/PlatformEndDoorTest.java`。

## 类 `BlockPlatformEndDoor`（final，extends `BlockPSDAPGBase`）

### 方块状态属性
- 继承 `FACING`、`HALF`（上下半）、`SIDE_EXTENDED`（固定 `SINGLE`）；新增：
- `OPEN`：是否打开。
- `INWARD`：门扇是否向方块后方（朝北时为 +z）打开，而不是向前方。

### 字段 `style`
- 0 APG、1 PSD_1、2 PSD_2。

### 构造函数 `BlockPlatformEndDoor(int style)`
- 默认朝北、下半、关闭、向外开。

### `void createBlockStateDefinition(...)`
- 登记 `FACING, HALF, SIDE_EXTENDED, OPEN, INWARD`。

### `BlockState getStateForPlacement(BlockPlaceContext ctx)`
- 作用：朝向取 `rowFacing`；若该朝向与玩家视线相反（玩家站在那一排的另一侧），则设 `INWARD = true`，即改变开门方向而不是把门转过来。

### `static Direction rowFacing(BlockGetter world, BlockPos pos, Direction looking)`（包级）
- 作用：依次查看玩家视线的右、左、前、后四个相邻格，第一个"沿其自身一排"相邻（相邻方向与其朝向不同轴）的屏蔽门/安全门方块的朝向即为结果；没有则用玩家视线方向。

### `void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity, ItemStack)`
- 服务端把上半方块的 `INWARD` 同步为下半的值。

### `protected boolean isAPG()`
- `style == 0`。

### `Item asItem()`
- 按样式返回对应端门物品。

### `VoxelShape getShape(...)`
- `outline(style, half, facing, open, inward)`。

### `static VoxelShape outline(int style, DoubleBlockHalf half, Direction facing, boolean open)` / `outline(..., boolean inward)`（包级）
- 作用：高度：APG 上半 9 像素，其余 16。关闭时为方块前部 4 像素厚的板（与屏蔽门玻璃 z 2..4 对齐）；打开时门扇绕 (15, 3) 处门轴转开：向外为 z −12..4、x 14..16，向内为 z 2..18、x 14..16（伸进后方方块）。按朝向旋转。

### `InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)`
- 作用：服务端：潜行时只切换开门方向并关闭门；否则切换开/关。上下两半同时更新，播放铁门开/关声音。客户端直接返回 SUCCESS。
