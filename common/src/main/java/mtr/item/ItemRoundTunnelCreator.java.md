# ItemRoundTunnelCreator.java（本分支新增）

圆形隧道开挖器物品（服务端逻辑）：在两个轨道节点之间，沿轨道挖出圆形截面的隧道。注册了半径 4、6、8 三种（`common/src/main/java/mtr/Items.java:112`–`:114`）。继承 `ItemNodeModifierSelectableBlockBase`，实际挖掘由 `RailwayDataRailActionsModule.markRailForRoundTunnel` 排队执行。

## 类 `ItemRoundTunnelCreator`（extends `ItemNodeModifierSelectableBlockBase`）

### 字段 `tunnelRadius`
- 圆半径（格）。

### 构造函数 `ItemRoundTunnelCreator(int radius)`
- 作用：父类参数为"不需要选方块"（false）、宽与高都为 `2 × radius − 1`。清空的圆宽高为 `2r − 1` 格，圆心在轨道上方 `r − 1` 格（据注释）。

### `void appendHoverText(...)`
- 先显示 `tooltip.mtr.rail_action_radius`（半径），再显示父类提示。

### `protected boolean onConnect(Player player, ItemStack stack, RailwayData railwayData, BlockPos posStart, BlockPos posEnd, int radius, int height)`
- 作用：`railwayData.railwayDataRailActionsModule.markRailForRoundTunnel(player, posStart, posEnd, tunnelRadius)`；父类给的 `radius`/`height` 不使用。
- 返回：是否成功登记（两节点间有轨道等，具体见该模块）。
