# ItemRoundTunnelWallCreator.java（本分支新增）

圆形隧道衬砌（隧道壁）生成器物品（服务端逻辑）：在两个轨道节点之间，用玩家事先选定的方块沿轨道铺一圈圆环，包住同半径的圆形隧道。注册了半径 4、6、8 三种（`common/src/main/java/mtr/Items.java:127`–`:129`）。实际铺设由 `RailwayDataRailActionsModule.markRailForRoundTunnelWall` 执行。

## 类 `ItemRoundTunnelWallCreator`（extends `ItemNodeModifierSelectableBlockBase`）

### 字段 `tunnelRadius`
- 对应隧道的半径（格）。

### 构造函数 `ItemRoundTunnelWallCreator(int radius)`
- 父类参数：需要选方块（true），外径宽高 `2 × radius + 1`（环包在同半径隧道外）。

### `void appendHoverText(...)`
- 显示半径提示，再显示父类提示（含已选方块）。

### `protected boolean onConnect(Player player, ItemStack stack, RailwayData railwayData, BlockPos posStart, BlockPos posEnd, int radius, int height)`
- 作用：取物品保存的方块状态（`getSavedState`）；没有选方块时直接返回 true（不做任何事）；否则 `markRailForRoundTunnelWall(player, posStart, posEnd, tunnelRadius, state)`。
- 返回：是否成功登记。
