# PathData.java

列车路径上的一段轨道（服务端与客户端共用），带所属站台/停车轨（若有）、在此停站的时间、是第几个停靠点。停车轨的路径就是这些对象的列表；本分支让它实现仿真组件的 `transitsim` 接口 `PlanTrack`，使组件的时刻/运用规划可以直接使用游戏的路径。本分支改动：`savedRailBaseId`、`dwellTime`、`stopIndex` 改为可变；新增 `speedCap`（线路限速，存档键 `speed_cap`，包末尾 float）；新增按线路限速、"运行速度/规划速度"的速度函数（`SpeedRules`）；包读写可把轨道分开（同一轨道在路径中多次出现只写一次，2026-11-09）；站台停车位置偏移（优化第 4 轮）。

## 类 `PathData`（extends `SerializedDataBase`，implements `PlanTrack`）

### 字段
- `rail`（final）：这段轨道。
- `savedRailBaseId`：所属站台/停车轨 id，0 为普通轨道。
- `dwellTime`：在此停站时间（单位与 MTR 站台停站时间相同，未在本文件确定；折返线上为折返停留时间）。
- `stopIndex`：第几个停靠点（从 0 起）。
- `speedCap`：所在线路的限速（格/tick），0 表示无；列车不会超过它和轨道限速中的较小者。
- `startingPos`（final，public）、`endingPos`（final，private）：轨道两端节点。

### 存档键（msgpack）
`rail`（嵌套 map）、`saved_rail_base_id`、`dwell_time`、`stop_index`、`starting_pos`、`ending_pos`、`speed_cap`（只在 > 0 时写，`messagePackLength` 相应为 7 或 6）。

### 包字段顺序
`Rail`（`rail.writePacket`）后接 `writePacketWithoutRail`：`long savedRailBaseId`、`int dwellTime`、`int stopIndex`、`long startingPos`、`long endingPos`、`float speedCap`。

### 构造函数
- `PathData(Rail rail, long savedRailBaseId, int dwellTime, BlockPos startingPos, BlockPos endingPos, int stopIndex)`。
- `PathData(Map<String, Value> map)`：从存档读（`speed_cap` 缺省 0）。
- `PathData(CompoundTag)`（已弃用）：旧 NBT 格式，无限速。
- `PathData(FriendlyByteBuf packet)`：先读轨道再读其余。
- `PathData(Rail rail, FriendlyByteBuf packet)`：轨道已单独读出，只读其余字段。

### `void toMessagePack(MessagePacker)` / `int messagePackLength()`
- 写存档（见上）。

### `void writePacket(FriendlyByteBuf)` / `void writePacketWithoutRail(FriendlyByteBuf)`
- 完整写 / 不写轨道（路径对每条轨道只写一次时用）。

### `float getMaxSpeed()`
- 轨道的速度标签（`SpeedRules.label(railType, transportMode, 起点, 终点)`），有线路限速时取较小值（格/tick）。信号闭塞按这个值划分。

### `float getRunSpeed()`
- 实际运行速度：速度标签对应的物理上限（`SpeedRules.run`，优化文档 5.6）。

### `float getPlanSpeed()`
- 编时刻表时使用的速度（`SpeedRules.plan`）。

### `PathData copy(long newSavedRailBaseId, int newDwellTime)`
- 复制并换停靠站与停站时间，保留线路限速。调用者：`PathFinder.applyStopSettings`。

### `static void capSpeed(List<PathData> path, int speedLimit)`
- 把线路限速（km/h，0 为无）换算为格/tick（`/3.6/20`）设到路径每段。调用者：`common/src/main/java/mtr/data/Depot.java:3255`、`:4142`。

### `BlockPos getLastPos()`
- 终点节点。

### `boolean isSameRail(PathData)` / `boolean isOppositeRail(PathData)`
- 起终点相同 / 互换。

### `UUID getRailProduct()` / `static UUID getRailProduct(BlockPos, BlockPos)`
- 与方向无关的轨道键：两端 `asLong` 的较小值与较大值组成 UUID。调用者：`cn/zbx1425/mtrsteamloco/mixin/RenderTrainsMixin.java:107` 等。

### `PlanTrack` 接口实现
- `getTrackLength()`：轨道长度（格）。
- `getDwellTime()`、`getStopId()`（= `savedRailBaseId`）、`getStopIndex()`、`setStopIndex(int)`。
- `isPlatformTrack()` / `isSidingTrack()`：轨道类型为 PLATFORM / SIDING。
- `getStopOffset(double trainLength)`：站台轨道且有站台 id 时，按该站台的停车位置设置（`PlatformStops.get`，在出口端停车为 0）用 `transitsim.plan.StopPositions.getOffset(设置, 轨道长, 车长)` 求离出口端的距离（格）；否则 0。
- `isOppositeOf(PlanTrack)`：另一段是反向的同一轨道。
- `getTrackKey()`：`getRailProduct()`。
