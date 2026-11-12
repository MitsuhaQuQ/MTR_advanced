# SignalAspects.java（本分支新增）

客户端：信号机显示什么，按前方闭塞分区计数（解耦文档 5.4、2b）：信号后第一个分区被占用为 1，第二个为 2……最多到信号自身的显示数；全部空闲为 0；信号所在节点该方向没有轨道为 −1。沿客户端已有的轨道、按信号朝向前进，并像服务器那样切分闭塞分区：长轨道按其限速下的制动距离切成小段，信号闭塞按服务器切分的分区；过道岔后每个方向都算，取最严格的显示。若某节点上有客户端只从地图上知道（不知道长度）的轨道，无论去向都视为被占用，所以信号不会对看不见的轨道显示空闲。调用者：`common/src/main/java/mtr/render/RenderSignalBase.java:106`；测试 `common/src/test/java/mtr/client/SignalAspectsTest.java:53`。

## 类 `SignalAspects`（final，私有构造）

### 静态字段与常量
- `MAX_RAILS = 256`：最多走过的轨道数（防环路）。
- `mapRailsSize`、`mapNeighbours`：由 `ClientData.MAP_RAILS` 建立的节点相邻表缓存（地图轨道数变化时重建）。
- `CLIENT_RAILS`（`BlockSections.RailInfo`）：用客户端轨道提供长度与切分速度（`TrainServer.cutSpeed`），供 `SIGNAL_BLOCKS.updateSections` 按服务器方式切分闭塞。

### `static int getAspect(BlockPos startPos, float facing, int aspects)`
- 作用：先更新闭塞分区（联锁色 `ClientData.interlockColor`）；从信号节点按朝向深度优先前进：对每条起点朝向与当前方向相近的轨道，按行车方向依次遍历其小段，属于新分区就计数 +1；计数达到显示数就停止该支；遇到被占用的小段记录该计数并停止；走完整条轨道则从终点继续。进入过分区且在该节点存在未知轨道时也记为占用（下一个分区）。
- 参数：`facing` 信号朝向（度）；`aspects` 显示数（含"空闲"）。
- 返回：上述显示值。

### `static boolean isTaken(UUID railKey, int piece)`
- 服务器发送分段占用信息（`ClientData.OCCUPIED_SECTIONS` 非 null）时按小段判断；否则按整轨或其信号闭塞是否被占用。

### `static int[] getPieceOrder(Rail rail, boolean forwards)`
- 一条轨道切分的小段序号，按列车行进方向排列（`BlockSections.getPieceCount(长度, 切分速度)`）。

### `private static boolean hasUnknownRail(BlockPos node, Map<BlockPos, Rail> railMap)`
- 地图上该节点有客户端轨道表中没有的相邻节点。

## 私有内部类 `Step`
- `node`、`facing`（度）、`entered`（到此已进入的分区数）、`lastSection`（上一分区 id）。
