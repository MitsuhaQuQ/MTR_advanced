# RailwayDataRailActionsModule.java

`RailwayData` 的一个子模块（`RailwayDataModuleBase`），管理沿轨道自动施工的"轨道动作"队列：桥梁、隧道、圆形隧道、隧道墙、圆形隧道墙。玩家用对应物品（`ItemBridgeCreator` 等）标记一段轨道后加入队列；每个服务器 tick 推进队首动作，完成后出队。队列变化时通过 `PacketTrainDataGuiServer.updateRailActionsS2C` 发给客户端显示。由 `RailwayData.java:339` 构造，每 tick 由 `RailwayData` 调用 `tick()`。与组件无关。

## class RailwayDataRailActionsModule extends RailwayDataModuleBase

### 字段
- `private final List<Rail.RailActions> railActions`：待执行的施工动作队列（FIFO）。

### `public RailwayDataRailActionsModule(RailwayData railwayData, Level world, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：保存所属铁路数据、世界与轨道表（由基类保存）。
- 主要调用者：`RailwayData.java:339`。

### `public void tick()`
- 作用：若队列非空，调用队首 `build()` 推进一步；返回 `true`（完成）时出队并向该世界的客户端发送更新后的队列。
- 主要调用者：`RailwayData.java:594` 一带的 tick 流程。

### `public boolean markRailForBridge(Player player, BlockPos pos1, BlockPos pos2, int radius, BlockState state)`
- 作用：若 `pos1→pos2` 轨道存在，加入一个 `BRIDGE` 动作（半径 `radius`，高度 0，方块 `state`）并发包。
- 返回：轨道存在返回 `true`，否则 `false`。
- 主要调用者：`ItemBridgeCreator.java:18`。

### `public boolean markRailForTunnel(Player player, BlockPos pos1, BlockPos pos2, int radius, int height)`
- 作用：同上，动作类型 `TUNNEL`，参数 `radius`、`height`，无方块。
- 主要调用者：`ItemTunnelCreator.java:16`。

### `public boolean markRailForRoundTunnel(Player player, BlockPos pos1, BlockPos pos2, int radius)`
- 作用：同上，类型 `TUNNEL_ROUND`，高度 0。
- 主要调用者：`ItemRoundTunnelCreator.java:34`。

### `public boolean markRailForTunnelWall(Player player, BlockPos pos1, BlockPos pos2, int radius, int height, BlockState state)`
- 作用：同上，类型 `TUNNEL_WALL`，半径与高度各 +1（墙在隧道外一圈），方块 `state`。
- 主要调用者：`ItemTunnelWallCreator.java:18`。

### `public boolean markRailForRoundTunnelWall(Player player, BlockPos pos1, BlockPos pos2, int radius, BlockState state)`
- 作用：同上，类型 `TUNNEL_WALL_ROUND`，半径不变，高度 0。
- 主要调用者：`ItemRoundTunnelWallCreator.java:36`。

### `public void removeRailAction(long id)`
- 作用：移除 id 匹配的动作并发包更新。
- 主要调用者：`PacketTrainDataGuiServer.java:1331`（玩家在界面中取消）。
