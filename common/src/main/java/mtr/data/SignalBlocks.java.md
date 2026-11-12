# SignalBlocks.java

信号闭塞：玩家用信号刷给轨道染色，同色相连的轨道组成一个闭塞（`SignalBlock`），列车占用闭塞时其它列车不能进入。本分支在原 MTR 基础上加了：闭塞分区（`transitsim.network.BlockSections`，按长度/速度把长轨道切成小段，列车只占用所在区段及闭塞的其余部分）、按颜色的"区域"（联锁区 L2、停留闭塞"运用补完"第 4 步）、给组件模拟器的闭塞轨道列表（G1）、调度台用的轨道访问（D6）、版本号。服务端 `RailwayData` 持有一份（`RailwayData.java:405/448` 读存档，`:575` 每 tick 重置，`:577` 更新分区，`:977` 添加），客户端 `ClientData.SIGNAL_BLOCKS` 一份；`TrainServer.java:918` 占用，`SimExport.java:93-94` 导出，组件 `TickRunner` 读取 `getBlockSections()`。

## class SignalBlocks

### 字段
- `version`：每次 `writeCache` 递增。
- `railToSignalBlocks`：轨道键（UUID，`PathData.getRailProduct`，低 64 位与高 64 位为两端 `BlockPos.asLong`）→ 所在闭塞集合。
- `zonesByColor`：颜色 → 轨道 → 闭塞 id（`getZones` 缓存，版本变时清空）；`zonesVersion`。
- `blockSections`、`sectionsVersion`、`sectionsColor`、`sectionsRailsVersion`：闭塞分区及其计算时的版本/颜色/轨道版本。
- `UNKNOWN_RAILS`：轨道信息未知时的 `RailInfo`（长度 0、速度 0，于是每个闭塞整体为一段）。
- `unitRails`：本 tick 被占用的单元（轨道或长轨道的一段）→ 所属轨道。
- `signalBlocks`（public）：全部闭塞。

### `public long add(long id, DyeColor color, UUID rail)`
- 作用：给轨道染色：若与同色闭塞相连（共享端点），把轨道及所有相连闭塞合并进 id 最小的那个；否则用 `id` 新建闭塞。随后 `writeCache()`。
- 返回：新建时为新闭塞 id；合并时为 0。
- 调用者：`RailwayData.java:977`、`PacketTrainDataGuiClient.java:485`、本类 `remove`。

### `public long remove(long id, DyeColor color, UUID rail)`
- 作用：从同色相连闭塞中移除该轨道；剩余轨道按排序重新逐条 `add`（第一条沿用原 id，其余用 `id`，可能因此拆成多个闭塞）。
- 返回：拆分出的新闭塞 id（无则 0）。
- 调用者：`RailwayData.java:674`、`:775`、`ClientData.java:198`。

### `public void occupy(UUID currentRail, List<Map<UUID, Long>> trainPositions, long trainId)`
- 等价 `occupy(currentRail, currentRail, ...)`（整条轨道为一个单元）。

### `public void occupy(UUID currentRail, UUID unit, List<Map<UUID, Long>> trainPositions, long trainId)`
- 参数：`currentRail` 列车所在轨道；`unit` 该轨道的键或长轨道某段的键（`BlockSections`）；`trainPositions` 至少两张表（[0] 上一 tick、[1] 本 tick 的占用：单元 → 列车 id）；`trainId`。
- 作用：本单元总是记为该列车占用（否则进入已占用闭塞的列车对他车不可见）。若轨道属于闭塞：闭塞 `occupied = 2`；未计算分区时先以未知轨道信息计算；把该单元所在区段的全部单元加入。若任何要加入的单元在任一表中已被别的列车占用，则不再加入其余部分；否则全部记为本列车。
- 调用者：`TrainServer.java:918`；测试 `BlockingTest.java:73`。

### `public Map.Entry<UUID, Integer> getPiece(UUID unit, BlockSections.RailInfo railInfo)`
- 返回：单元所属的（轨道, 段号），供客户端显示哪条轨道的哪一段被占用：分区中已知的直接返回；否则按本 tick 记录的轨道与 `railInfo` 计算段数并匹配；未知单元视为整条轨道（段 0）。
- 调用者：`RailwayData.java:736`。

### `public void resetOccupied()`
- 作用：清空 `unitRails`，每个被占用闭塞的 `occupied` 减 1（2 → 1 → 0，跨 tick 保持一拍）。
- 调用者：`RailwayData.java:575`。

### `public List<SignalBlock> getSignalBlocksAtTrack(UUID rail)`
- 返回：该轨道所在闭塞，按颜色序排序（新列表）。调用者：`RenderTrains.java:500`、`RenderRailInspect.java:109`。

### `public boolean isOccupied(UUID rail)`
- 返回：该轨道所在任一闭塞被占用。调用者：`RailwayData.java:694`、`RenderTrains.java:505`。

### `public void getSignalBlockStatus(Map<Long, Boolean> signalBlockStatus, UUID rail)`
- 作用：把该轨道所在闭塞的占用状态写入映射。脚本未找到调用者。

### `public void writeSignalBlockStatus(Map<Long, Boolean> signalBlockStatus)`
- 作用：客户端按服务器状态设置 `occupied`（2 或 0）。调用者：`ClientData.java:377`。

### `public FriendlyByteBuf getValidationPacket(Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：找出闭塞中已不存在的轨道并移除（`remove(0, color, rail)`）。
- 返回：无此类轨道时 `null`；否则一个包：`int n`，每条 `long remove 返回值, int color.ordinal, UUID rail`（供客户端同步删除）。
- 调用者：`RailwayData.java:992`、`RailwayData.java:1325`。

### `public List<List<UUID>> getBlockRails()`
- 返回：每个闭塞的轨道（排序），顺序同 `signalBlocks`，给组件模拟器（G1）。调用者：`SimExport.java:93`。

### `public Map<Long, List<UUID>> getBlockRailsById()`
- 返回：闭塞 id → 轨道，给轨道区段的信号层。调用者：`RailwayData.java:648`。

### `public void updateSections(DyeColor interlockColor, BlockSections.RailInfo railInfo)` / `updateSections(..., int railsVersion)`
- 作用：闭塞、联锁颜色或轨道版本变化（客户端得到附近轨道时轨道版本会变）时，重新构建 `BlockSections(getBlockRails(), railInfo)`：每个闭塞一个区段，含其全部轨道的各段。
- 调用者：`RailwayData.java:577`、`SignalAspects.java:38`。

### `public BlockSections getBlockSections()`
- 返回：分区；尚未 `updateSections` 时为 `null`。调用者：`SignalAspects.java:39`。

### `public List<Boolean> getInterlockFlags(DyeColor color)`
- 返回：按 `getBlockRails` 顺序，每个闭塞是否为该颜色（联锁区，L2）。调用者：`SimExport.java:94`。

### `public synchronized Map<UUID, Long> getZones(DyeColor color)`
- 作用：某颜色闭塞构成的区域（联锁区：咽喉进路整体占用；停留闭塞）：轨道 → 闭塞 id。只在闭塞或颜色变化时重算；返回的映射不可变，可在其它线程使用。
- 调用者：`TrainServer.java:1113`、`PacketTrainDataGuiServer.java:805`、`ClientThroats.java:32`。

### `public int getVersion()`
- 返回：版本号。调用者：`RailwayData.java:648`。

### `public void writeCache()`
- 作用：版本 +1，重建 `railToSignalBlocks`。调用者：`RailwayData.java:459`、`ClientData.java:405`、`ConfigScreen.java:279`。

## public static class SignalBlock extends NameColorDataBase

### 字段与存档键
- `color`（public final）：颜色；键 `color`（ordinal）。读取越界时打印堆栈并用 RED。
- `rails`：轨道集合；键 `rails`（UUID 字符串数组）。
- `occupied`：占用计数（0 空闲，>0 占用）。

### 构造函数
- 私有 `SignalBlock(long id, DyeColor color, UUID rail)`：新建。
- `SignalBlock(Map<String, Value> map)`：读 msgpack。调用者：`RailwayData.java:405`。
- `SignalBlock(CompoundTag)`（已弃用）：读旧 NBT。调用者：`RailwayData.java:448`。
- `SignalBlock(FriendlyByteBuf packet)`：读基类、`int color`、`int n`、`UUID×n`。

### `toMessagePack` / `messagePackLength`（基类 + 2） / `writePacket`
- 包字段顺序：基类字段，`int color.ordinal, int n, UUID×n`。

### `protected boolean hasTransportMode()` → `false`。

### `public boolean isOccupied()`
- `occupied > 0`。

### `public Set<UUID> getRails()`
- 只读视图，供调度台（D6）等使用。调用者：`EditorConsole.java:42`、`Siding.java:1896`。

### `private boolean isConnected(UUID checkRail)`
- 与闭塞中任一轨道共享端点。

### `public int compareTo(NameColorDataBase compare)`
- 按 id 比较。
