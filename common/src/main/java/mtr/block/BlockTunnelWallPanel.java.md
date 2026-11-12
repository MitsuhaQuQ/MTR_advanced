# BlockTunnelWallPanel.java（本分支新增）

隧道墙面板：贴在墙面上的 1 像素厚装饰板（无碰撞、不遮挡），必须贴在一个坚固面上，支撑被拆则自身掉落。注册了普通面板和带电缆面板两种（`common/src/main/java/mtr/Blocks.java:127`、`:128`）。

## 类 `BlockTunnelWallPanel`（extends `Block`）

### 构造函数 `BlockTunnelWallPanel(Properties properties)`
- 在传入属性上加 `noOcclusion()`、`noCollission()`；默认朝北。

### `void createBlockStateDefinition(...)`
- 只有 `FACING`（`IBlock.FACING` 水平朝向，指向支撑面所在方向）。

### `BlockState getStateForPlacement(BlockPlaceContext context)`
- 只能点在水平方向的面上，朝向为被点面的反方向（即面向所贴的墙）；点上下面返回 null（不放置）。

### `boolean canSurvive(BlockState state, LevelReader level, BlockPos pos)`
- 朝向那一侧的相邻方块在相对的面上必须坚固（`isFaceSturdy`）。

### `BlockState updateShape(...)`
- 支撑一侧发生变化且不再能存活时变成空气。

### `VoxelShape getShape(...)`
- 贴墙一侧 16×16×1 像素的板，按朝向旋转。
