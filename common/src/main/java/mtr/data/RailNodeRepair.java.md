# RailNodeRepair.java

补回世界丢失、但铁路数据仍保留其轨道的节点方块。玩家铺的轨道在 tick 结束时即存入铁路数据，而节点所在区块要等下次世界保存才写盘；服务器若未保存世界就停止（崩溃、被杀），重启后会有轨道但节点不在。本类在数据加载后记下所有节点，待其区块加载后逐个检查：缺失且位置为空气或可替换方块时，按其轨道离开的方向重新放置节点；位置被别的方块占用则保持原样并记日志，等那个方块被清掉后再补。由 `RailwayData` 持有（`RailwayData.java:223`），加载时 `start`、每 tick `tick`，删除节点/轨道连接时用 `isKept` 豁免（`RailwayData.java:986`、`:1322`）。

## final class RailNodeRepair

### 字段与常量
- `nodesToCheck`：尚未检查的节点。
- `nodesTaken`：位置被其它方块占用的节点，保留到玩家在那里重新放节点或移除其轨道。
- `CHECK_INTERVAL_TICKS = 20`：每秒检查一次。

### `public void start(Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：铁路数据加载后调用：清空两个集合，把所有有轨道连出的节点加入 `nodesToCheck`。
- 主要调用者：`RailwayData.java:457`、`:1077`、`:1163`。

### `public void tick(Level world, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：两集合都空或不到间隔则返回；否则对 `nodesToCheck` 中每个节点首次检查、对 `nodesTaken` 再检查，处理完的移出集合。有补回时打印 "put back N node(s) ..."。
- 主要调用者：`RailwayData.java` 的 tick 流程（约 `:594-639`）。

### `private boolean check(Level world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos, int[] repaired, boolean firstLook)`
- 返回：该节点是否不再需要检查。
- 逻辑：区块未加载 → `false`（以后再看）；已无轨道 → `true`；已是 `BlockNode` → `true`；被非空气且不可替换的方块占用：首次检查时打印日志、加入 `nodesTaken` 并返回 `true`，再次检查返回 `false`；否则 `placeNode` 并计数，返回 `true`。

### `public boolean isKept(BlockPos pos)`
- 作用：节点方块缺失时，若它还在待检查或被占用集合中，`RailwayData` 中"移除缺失节点轨道"的检查会跳过它。玩家在该处重新放节点后就不再属于此类。
- 主要调用者：`RailwayData.java:986`、`RailwayData.java:1322`（方法引用）。

### `public int getRemaining()`
- 返回：剩余待检查节点数，供测试。

### `private static void placeNode(Level world, BlockPos pos, Rail rail)`
- 作用：按轨道交通方式选节点方块，按 `rail.facingStart.angleDegrees` 计算方块状态：角度对 180 取模，`<90` 为 `FACING=true`；余量按 22.5° 分档，恰在档位上（且连续运动节点只用偶数档）时设 `IS_45`、`IS_22_5`，否则放置后 `BlockNode.setCustomAngle` 设自定义角度。始终 `IS_CONNECTED=true`。`world.setBlockAndUpdate` 放置。

### `private static Block getBlock(TransportMode transportMode)`
- 返回：`BOAT → BOAT_NODE`，`CABLE_CAR → CABLE_CAR_NODE_LOWER`，`AIRPLANE → AIRPLANE_NODE`，其它 `RAIL_NODE`。
