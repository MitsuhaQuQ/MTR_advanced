# Platform.java

站台：两个节点之间、列车停靠的一段轨道，带停站时间；线路是站台的列表。继承 `SavedRailBase`（id、名称、颜色、交通方式、两端位置、停站时间）。本分支加了：所属车站覆盖 `stationId`、时刻表边界、待避线标记、通过速度、停车位置。数据同时存在于服务端与客户端，三种写法（msgpack 存档、整包、`update(key, packet)` 增量）同步修改。被 `RailwayData`、`DataCache`、`PlatformSpeeds`/`PlatformStops`（同步时提交）、`PlatformScreen`（游戏界面）、`EditorActions`/`EditorData`（网页编辑器）使用。

## final class Platform extends SavedRailBase

### 字段
- `stationId`：车站区域重叠时本站台所属的车站，0 表示自动选择。
- `timetableBoundary`：班次可在此站中途离开或加入线路，与另一线路贯通运行（时刻表边界，见组件 `TimetableBoundaries` 与 `PlanAdapters.boundaryPlatforms`）。
- `passingTrack`：是否为待避线（车站的侧线）而非正线。由玩家设置，从不猜测；编辑器的股道选择与股道图据此区分，只有有待避线的车站才能让列车被越行。
- `passSpeed`：列车通过本站台的限速标签（km/h，优化第 3b 轮第 2 步），0 表示默认 `PlatformSpeeds.DEFAULT_KMH`。属于轨道的性质，类似长度。
- `stopPosition`：列车在站台上停的位置（第 4 轮停车点）：`StopPositions.EXIT_END`（默认，出站端）、`StopPositions.CENTRED`（居中），或距出站端的方块数。经 `StopPositions.normalise` 规范化。

### 存档键 / 增量更新键
`dwell_time`（仅作增量键，见 `update`）、`pass_speed`、`stop_position`、`station_id`、`timetable_boundary`、`passing_track`。

### 构造函数
- `Platform(long id, TransportMode, BlockPos pos1, BlockPos pos2)` / `Platform(TransportMode, BlockPos, BlockPos)`：新建（后者自动分配 id）。
- `Platform(Map<String, Value> map)`：读 msgpack；`passSpeed` 取 `max(0, …)`，`stopPosition` 规范化。缺键时 long/boolean 默认 0/false。
- `Platform(CompoundTag)`（已弃用）：旧 NBT，只读基类字段。
- `Platform(FriendlyByteBuf packet)`：读基类后依次读 `long stationId, boolean timetableBoundary, boolean passingTrack, int passSpeed, int stopPosition`（读包时未规范化）。
- 主要调用者：`RailwayData.java:374`、`ClientEditing.java:318`。

### `toMessagePack` / `messagePackLength`
- 在基类之后写 5 个键；长度 = 基类 + 5。

### `writePacket(FriendlyByteBuf)`
- 包字段顺序：基类字段，`long stationId, boolean timetableBoundary, boolean passingTrack, int passSpeed, int stopPosition`。

### `public void update(String key, FriendlyByteBuf packet)`
- 作用：增量更新：`station_id`（long）、`timetable_boundary`（boolean）、`passing_track`（boolean）、`pass_speed`（int，≥0）、`stop_position`（int，规范化）；`dwell_time` 读 `name`(UTF)、`color`(int)、`dwellTime`(int)，连续运动交通方式强制为 1；其它交给基类。
- 主要调用者：`DataCache`/数据同步路径（`PacketTrainDataBase` 一类的增量包处理，未逐一确认）。

### 设置方法（都会先改本地字段，再构造增量包交给 `sendPacket`）
增量包格式：`long id, String transportMode, String key, 值`。
- `setStationId(long, Consumer)`：调用者 `EditorActions.java:564`、`PlatformScreen.java:267`。
- `setTimetableBoundary(boolean, Consumer)`：调用者 `PlatformScreen.java:270`。
- `setPassingTrack(boolean, Consumer)`：调用者 `PlatformScreen.java:279`。
- `setPassSpeed(int, Consumer)`（负数置 0）：调用者 `EditorActions.java:422`、`PlatformScreen.java:276`。
- `setStopPosition(int, Consumer)`（规范化）：调用者 `EditorActions.java:553`、`PlatformScreen.java:273`。
- `setDwellTime(int newDwellTime, Consumer)`：不改本地，发 `dwell_time` 键：`name, color`，再由 `writeDwellTimePacket` 写停站时间。调用者 `PlatformScreen.java:265`。

### `public int getPassSpeedKmh()`
- 返回：`passSpeed > 0 ? passSpeed : DEFAULT_KMH`。
- 主要调用者：`EditorData.java:690`、`PlatformScreen.java:70`。
