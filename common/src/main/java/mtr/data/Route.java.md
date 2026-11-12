# Route.java

线路：一个班次按顺序停靠的站台，加上其类型、颜色、名称、途经点，以及线路的时刻表设置（各站停站时间、通过站、末端折返线等）。它实现组件 `transit-sim` 的 `PlanRoute` 接口，所以时刻表直接由它生成（`StopTimes`、`StopRules`、`PlanVersions`、`TimetableBoundaries`、`SimDataset` 读取它）。本分支在原 MTR 基础上加了：多张时刻表（`RouteTimetable`，按星期）、计划到发时刻与越行、通过站、停站时间覆盖、允许超长列车、贯通链接、回库/出库指令、停运、班次例外、最低车辆性能、线路名/服务类型/自有颜色、联络线及其释放轨道、站前折返与种别优先站台、线路限速、编辑器管理标记、手绘路径（ANTE `ante_path`）、编辑版本文本。数据三写：msgpack 存档、整包、`update(key, packet)` 增量（各 `set*` 方法发送）。

## final class Route extends NameColorDataBase implements IGui, PlanRoute

### 常量
- `MAX_PLATFORMS = 1024`、`MAX_WAYPOINTS = 64`（public）、`MIN_STOP_DWELL = 17`（public）、`MAX_DWELL_OVERRIDE = 7200`、`DOOR_CYCLE_SECONDS = 8.4`。
- `MAX_TURN_PLATFORMS = 16`、`MAX_CONNECTING_RELEASED = 512`（public）。
- `TIMETABLE_LINKS_MARKER = -5`：贯通链接数组的格式标记（新格式含加入站）。
- `DUPLICATE_SUFFIX = "_duplicate"`、`DUPLICATE_PATTERN`。

### 字段
- 原有：`routeType`、`isLightRailRoute`、`isHidden`、`disableNextStationAnnouncements`、`circularState`、`lightRailRouteNumber`、`platformIds`（`List<RoutePlatform>`）。
- `connectingLine`：联络线——普通线路，可载客贯通到另一条线，但其首末站之间的轨道其它线路的路径不经过（解耦文档 5.4，L1）。
- `connectingReleased`：联络线中其它线路仍可经过的轨道（两节点，任意顺序），即与其它线共享的轨道（如站场咽喉的渡线），在编辑器中手动释放。
- `terminalPlatforms`：末站所在车站中列车可折返的其它站台（站前折返），按顺序；见 `PlanRoute.getTerminalPlatforms`。
- `startPlatforms`：首站车站中线路更愿意出发的站台，优先在前（种别优先）。
- `pathData`（private）：手绘路径，非空时代替路径搜索。
- `timetable`（public final `RouteTimetable`）：时刻表（含多张表）。
- `timingBaseline`（volatile）：时分基线（`PlanRoute#getTimingBaseline`，由 `RoutePaths.java:1494` 设置）。
- `lineName`：所属线（line）名，空则由名称推算。
- `ownColor`：线路自己的颜色（用于未来的运行图）；`color` 是整条线的颜色，标志和其它一切（包括读这个字段的附加模组）显示它。
- `serviceType`：服务类型（如普通/快速），空则由名称推算。
- `speedLimit`：线路限速 km/h，0 为无；列车在站间不超过此值也不超过轨道允许值（编辑器保证它不高于沿线最快轨道）。
- `editorManaged`：在高级编辑器中设置，游戏自带的时刻表控件只显示（见 `AdvancedEditing`）。

### 存档键
`platform_ids`、`custom_destinations`、`pass_stations`、`dwell_overrides`、`waypoints`（数组的数组）、`route_type`、`is_light_rail_route`、`is_route_hidden`、`disable_next_station_announcements`、`light_rail_route_number`、`circular_state`、`line_name`、`diagram_color`、`service_type`、`speed_limit`、`editor_managed`、`connecting_line`、`connecting_released`（扁平 long 对）、`terminal_platforms`、`start_platforms`、`timetable_enabled`、`timetable_margin`、`timetable_min_performance`（[加速度, 减速度, 最高速]）、`timetable_services`、`plan_arrivals`、`plan_departures`、`overtakes`、`allow_longer`、`timetable_name`、`timetable_weekdays`、`entry_platform`、`timetable_links`、`timetable_returns`、`timetable_suspended`、`timetable_orders`（MTM_Logic 0.8.0：每项 4 个 long：班次、种类 0 出库/1 回库、站、车厂）、`timetable_exceptions`、`timetable_tables`（第 2 张起的表）、`ante_path`（手绘路径，非空时才写）。第一张表的字段直接在线路的 map 上，其它表各为一个子 map（13 个键）。

### 构造函数
- `Route(TransportMode)` / `Route(long id, TransportMode)`：默认值（普通线路、非轻铁、非环线等）。
- `Route(Map<String, Value> map)`：读存档：停站与按站数组（目的地、通过、停站覆盖、途经点、第一张表的计划到/发、越行、允许超长），各属性，联络线释放（最多 512 对），折返/起点站台（去重，≤16，`addTurnPlatform`），手绘路径，第一张表（启用、裕量、最低性能、班次、名称、星期、入口站台、链接、回库、停运〔旧存档无〕、出入库指令、例外），其余表（`readTable`）；最后每张表的例外限制到停站数（`ServiceExceptions.limitStops`）并 `sanitize()`。
- `Route(CompoundTag)`（已弃用）：旧 NBT 的基本字段。
- `Route(FriendlyByteBuf packet)`：见包格式。
- 调用者：`PacketRoutePathCreator.java:45`、`RoutePathCreatorScreen.java:309`、`EditorSimulation.copyRoute`。

### `toMessagePack(MessagePacker)` / `messagePackLength()`
- 按上述存档键写出；长度 = 基类 + 37（有手绘路径再 +1）。

### 时刻表存档辅助（私有静态）
- `readLinks` / `writeLinks`：贯通链接：新格式以 `-5` 开头，每条 5 个 long（班次、目标线路、目标班次、离开站、加入站）；旧格式每条 4 个（无加入站，读为 0）。班次不会为负，所以标记不会被误认。
- `readExceptions` / `writeExceptions`：`ServiceExceptions.fromInts/toInts`。
- `readOrders` / `writeOrders`：出库/回库指令（按班次排序写出）。（组件 `TimetableBinary` 有自己的同名方法。）
- `writeReturns`：写回库班次与停运班次两个键（停运排序）。
- `writeMinPerformance` / `readMinPerformance`（恰好 3 个值才读）/ `copyMinPerformance`。

### `public int getLoopEntryStop()`
- 返回：从车厂出来的列车可在环线中途某站加入、此后按时刻表运行；只对首末站相同的环线、且入口站台是停车站（非通过）有效。返回加入的停站下标，否则 0。
- 调用者：`Depot.java:2965`、`TimetableScreen.java:132`。

### `public boolean isLoop()`
- 停站数 > 2 且首末站台相同。

### `private void readTable(MessagePackHelper messagePackHelper)` / `private void writeTable(MessagePacker messagePacker, int tableIndex)`
- 读/写第 2 张起的一张表（名称、星期、裕量、最低性能、班次、链接、回库、停运、指令、例外，以及该表各站计划到发与越行）。

### `public int addTable(int copiedTable)`
- 作用：新增一张没有班次的表，复制另一张表的裕量、最低性能和各站时刻。
- 返回：新表下标；已达 `RouteTimetable.MAX_TABLES` 时 −1。
- 调用者：`EditorActions.java:990`、`EditorTimes.java:74`、`PacketTrainDataGuiServer.java:711`。

### `public void removeTable(int tableIndex)`
- 作用：删除第 2 张起的一张表及各站在该表的时刻（第 1 张不能删）。
- 调用者：`EditorActions.java:987`、`EditorTimes.java:73`、`PacketTrainDataGuiServer.java:709`。

### `public void writePacket(FriendlyByteBuf packet)`
- 包字段顺序：基类字段；`int 停站数`，每站 `long platformId, String customDestination, RoutePlatform.writeWaypoints`（见下）；`String routeType, boolean isLightRailRoute, boolean isHidden, boolean disableNextStationAnnouncements, String lightRailRouteNumber, String circularState, String lineName, String serviceType, int ownColor, boolean editorManaged, boolean connectingLine`；联络线释放（`int n, (long, long)×n`）；折返/起点站台（`int n, long×n`，两组）；`int speedLimit`；`int 路径段数, PathData×n`；时刻表（`writeTimetable`）。

### `public void update(String key, FriendlyByteBuf packet)`
- 作用：增量更新（先清除时分基线）：
  - `platform_ids`：替换停站（每站 `platformId, customDestination, 途经点块`），按站台从旧停站继承时刻（`takeTimes`，同一站台出现两次时各自继承），最多 1024；`ServiceExceptions.remapStops` 重映射例外；`timetable.revision++`。
  - `is_light_rail_route`：附加数据（名称、颜色、类型、轻铁及编号、隐藏、禁报下一站、环线、线名、服务类型、自有颜色、编辑器管理、联络线、释放轨道、折返/起点站台、限速〔变化时 `revision++`，重新计时线路与运用〕）。
  - `timetable_services`：时刻表（`readTimetable`）。
  - `ante_path`：手绘路径。
  - 其它交给基类。
- 调用者：`DataCache`/增量包处理（`PacketTrainDataBase` 一类，未逐一确认）。

### `protected boolean hasTransportMode()` → `true`。

### `PlanRoute` 接口实现
- `getRouteId()` → `id`；`getTimetable()` → `timetable`；`getStops()` → `platformIds`；`getTransportModeId()` → `transportMode.ordinal()`；`getTerminalPlatforms()`、`getStartPlatforms()`；`getTimingBaseline()`。
- 调用者：组件 `StopTimes`、`PlanVersions`、`TimetableBoundaries`、`SimDataset` 等。

### `public void setTimingBaseline(long[][] times)`
- 内容不同时替换。调用者：`RoutePaths.java:1494`。

### 发送增量包的方法（包头：`long id, String transportMode, String key`）
- `setPlatformIds(Consumer)`：键 `platform_ids`，停站列表。调用者：`EditorActions.java:612`、`:626`、`:808`。
- `setExtraData(Consumer)`：键 `is_light_rail_route`，附加数据（同 `update` 中的顺序）。调用者：`EditorActions.java:604` 等。
- `setCustomPath(Consumer)`：键 `ante_path`，手绘路径。调用者：`EditorActions.java:613`。
- `setTimetable(Consumer)`：键 `timetable_services`，时刻表。调用者：`EditorActions.java:603`。

### 私有包辅助
- `writeTurnPlatforms` / `readTurnPlatforms`、`addTurnPlatform`（非 0、不重复、≤16）。
- `writeConnectingReleased` / `readConnectingReleased`（读取时最多 512）。

### `public Route duplicate()`
- 返回：一条复制了全部内容的新线路（名称加 `_duplicate`），以便赋予不同时刻表：属性、联络线、折返/起点站台、路径、各站（含各表时刻）、全部表（含班次、链接、回库、停运、指令、例外），`sanitize()`。新线路 id 为新分配的。
- 调用者：`EditorActions.java:758`、`EditRouteScreen.java:64`。

### 线与名称
- `getLineName()`：`lineName` 非空则用之，否则 `getAutoLineName(name)`。
- `getLineKey()`：线名小写；比较它判断是否同一条线（如折返的两个方向）。调用者：`RunningSides.java:92`、`ClientCache.java:89` 等大量处。
- `getServiceType()`：设置的服务类型小写；空则从名称（`||` 之前）找服务词（`LineWords.findServiceWord`）。
- `getOwnColor()`：未设置自有颜色的旧线路用 `color`。`setOwnColor(int)`：同时设置 `color`。
- `static applyLineColors(Collection<Route>)`：给每条线路其线的颜色——按名称（再按 id）排序后，同一交通方式同一线键的第一条线路的自有颜色。调用者：`DataCache.java:133`。
- `isSameLine(Route)`：线键相同。
- `static getAutoLineName(String name)`：去掉只用来区分同线线路的部分：`||` 之后、复制后缀、两端的 `LineWords`（每种语言分别处理，去光了则保留去后缀的原文）。

### 停站时间换算（静态）
- `getDwellFromDoorOpenSeconds(int doorOpenSeconds)`：门全开时间 → 停站时间（半秒）：`ceil((开门秒 + 8.4) × 2)`（开关门及前后延迟共 8.4 秒）。
- `getDoorOpenSeconds(int dwell)`：反向，`max(floor(dwell/2 − 8.4), 0)`。
- 调用者：`DashboardScreen.java:829`、`PlatformScreen.java:258`。

### `public List<Object> getEntries()` / `public void setEntries(List<Object> entries)`
- 停站（`RoutePlatform`）与其间关键节点（`BlockPos`）的混合列表；设置时首站之前的关键节点移到首站之后（关键节点属于停站之后的区段），每站最多 64 个。
- 调用者：`DashboardScreen.java:294`、`:610`。

### `public List<PathData> getPathData()` / `public void setPathData(List<PathData> pathData)`
- 手绘路径；设置时为路径进入的每个站台（不含在同一站台折返）追加停站。调用者：`RoutePathCreatorScreen.java:312`（set）。

### 停站查询
- `getPlatformIdIndex(long)`（无为 −1）、`containsPlatformId(long)`、`getFirstPlatformId()`、`getLastPlatformId()`（无停站为 0）。
- `getDestination(int index)`：从该站往前找第一个非空的自定义目的地；遇到重置标记 `\r`/`\reset` 返回 `null`。
- `static destinationIsReset(String)`。

### `private void readTimetable(FriendlyByteBuf packet)` / `private void writeTimetable(FriendlyByteBuf packet)`
- 时刻表包格式：`boolean enabled, long entryPlatformId, int 表数`；每张表：`readTableHeader` 部分（`String name, int weekdays, int marginPercent, float minAcceleration, float minDeceleration, float minMaxSpeed, int n + int×n 班次, int n + (int service, long routeId, int toService, int fromStop, int toStop)×n 链接, int n + int×n 回库, int n + int×n 停运(排序), 出库指令 int n + (int service, int stop, long depot)×n, 回库指令同上, ServiceExceptions.write(PacketBinary)`），然后 `int 站数, (int arrival, int departure, boolean overtake)×站数`；最后 `int 站数, boolean pass×站数`。
- 读取：清除其它表；各计数超出上限的部分读后丢弃；例外限制到站数并 `sanitize()`；`revision++`。

### `private static void readTableHeader(FriendlyByteBuf packet, RouteTimetable table)`
- 读一张表的头部（见上）。

## public static class RoutePlatform implements PlanStop
线路的一个停站。

### 字段
- `customDestination`、`platformId`（final）。
- `waypoints`：本站到下一站之间列车须经过（不停）的轨道节点。
- `pass`：列车通过不停，但车站仍属于线路。
- `dwellOverride`：本线路在此站的停站时间（半秒），0 用站台自己的。
- `overtake`：列车在此等待应先行的列车（在本站另一站台）越行后再出发。
- `allowLonger`：比站台长的列车也可在此停（优化第 3b 轮第 3 步，手动设置以免长度检查报出）。
- `arrival` / `departure`（私有）：第 1 张表的计划到/发（距到达首站的秒数，−1 为自动/停站时间可变）。
- `otherTimes`：第 2 张起各表的时刻（每表一个 `RoutePlatform` 对象）。

### `public RoutePlatform(long platformId)`
- 目的地为空。

### `public int getStopDwell(boolean canPass, int platformDwell, boolean continuousMovement)`
- 参数：`canPass` 首末站为 false。
- 返回：实际停站时间（半秒）：可通过且通过时 0；否则覆盖值或站台值，非连续运动时至少 `MIN_STOP_DWELL = 17`（更短来不及开门；缆车在站内不停）。
- 调用者：`PathFinder.java:86`、`PlatformScreen.java:253`。

### `getPlatformId()`、`isPass()`（`PlanStop` 实现）。

### 时刻读写
- `getArrival()` / `setArrival(int)`、`getDeparture()` / `setDeparture(int)`：第 1 张表，限制在 `[-1, RouteTimetable.MAX_STOP_SECONDS]`。
- `getArrival(int table)` / `setArrival(int table, int)`、`getDeparture(int table)` / `setDeparture(int table, int)`、`getOvertake(int table)` / `setOvertake(int table, boolean)`：按表（0 为第 1 张）。
- 调用者：`EditorActions`、`EditorData`、`TimetableScreen` 等。

### 私有辅助
- `takeTimes(List<RoutePlatform> stops)`：停站改变时按站台继承时刻（时刻随站台而不是位置），并从旧列表移除已取的那一个（同一站台用两次时各保留一套）。
- `removeTable(int table)`、`clearOtherTables()`。
- `getOtherTimes(int table)`：不足时补空白（表之后新增的站没有时刻）。
- `readWaypoints(FriendlyByteBuf)` / `writeWaypoints(FriendlyByteBuf)`：每站块的格式：`int n, long×n 途经点 (读取 ≤64), boolean pass, int dwellOverride (限制 0…7200), int arrival, int departure, boolean overtake, boolean allowLonger`。

## public enum CircularState
`NONE`、`CLOCKWISE`、`ANTICLOCKWISE`。

### `public String getEditorRevision()`
- 返回：时刻表、停站或编辑器管理状态变化时就改变的短文本（把 `setTimetable` 与 `setPlatformIds` 生成的包内容哈希合并，十六进制），网页编辑器以它为修改基准，服务器据此检查。
- 调用者：`EditorData.java:782`、`EditGuard.java:357`。
