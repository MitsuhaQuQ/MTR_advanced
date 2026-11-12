# NetworkSettings.java

一个维度整个网络的设置（优化第 4 轮），保存在其存档中（id 固定为 0 的单条记录），发给每个玩家，由有编辑权限的玩家在网页编辑器中修改。涵盖：最短换端时间、早点阈值、等候阈值（全网/按车站）、手动设定的区段所有者（网页分层第 2 步）、逻辑线路（2026-11-09）、双线行车方向（2026-11-12）、票务规则与票价（6.13，0.68.0 / 0.73.0）。数据三写：msgpack 存档（`RailwayDataFileSaveModule.java:311-312/813-814/901-903`）、整包（`NetworkSettingsPackets.java:30-48`、`FarePackets.java:67-73`）以及在 `EditorActions` 中修改后的整包发送。服务端与客户端各一份，位于 `DataCache.networkSettings`。

## final class NetworkSettings extends SerializedDataBase

### 常量
- `DEFAULT_MIN_REVERSE_MILLIS = DutyGenerator.MIN_TURNAROUND_MILLIS`：默认最短换端时间（列车一向需要的 30 秒折返）。
- `DEFAULT_EARLY_THRESHOLD_MILLIS = 30000`：早点列车在减速巡航前可保持的提前量（第 4 轮第 5 步）。
- `MAX_MILLIS = 600000`：上两者的上限。
- `ID = 0`：存档中唯一条目的 id。
- `DEFAULT_WAIT_THRESHOLD_MILLIS = TrainService.LONG_TURN_BACK_WAIT_MILLIS`、`MAX_WAIT_THRESHOLD_MILLIS = 3600000`。
- 数量上限：`MAX_OWNER_OVERRIDES = 4096`、`MAX_OVERRIDE_LINES = 32`、`MAX_LOGICAL_LINES = 1024`、`MAX_RAIL_LINES = 65536`、`MAX_LINE_TRACKS = 4096`、`MAX_RUNNING_SIDES = 4096`、`MAX_FARE_GROUPS = 1024`、`MAX_FARE_ASSIGNMENTS = 65536`、`MAX_FARE_TIERS = FareLevels.MAX_TIERS`、`MAX_STATION_THRESHOLDS = 4096`、`MAX_NAME_LENGTH = 256`、`MAX_LINE_KEY_LENGTH = 256`。
- `SIDE_NETWORK = ""`：`runningSides` 中全网的键。
- `DEFAULT_FARE_RULE = "default"`。

### 字段
- `minReverseMillis`：列车折返换端的最短时间（换端最短时间），编排处处采用。
- `earlyThresholdMillis`：列车早到多少时改为降速巡航而不是在站台等。
- `waitThresholdMillis`：等候下一班次多久以上时列车在折返线而不是站台等候（"运用补完"第 2 步），全网值；车站可有自己的，车厂规则可再覆盖。
- `stationWaitThresholds`：车站 → 自己的等候阈值。
- `ownerOverrides`：手动设定的区段所有者，键为区段中某条轨道的 `RailKeys`：允许运行的线路键列表，空列表表示对所有线路开放。叠加在由边界与站台推算的所有者（`TrackOwnership`）之上。
- `logicalLines`：逻辑线路（轨道本身的线，区别于在其上运行的运营线路，`LogicalLines`）id → `LogicalLine`。只要有逻辑线路，就由它们决定每个区段允许哪些运营线路运行，而不再用推算的所有者。
- `railLines`：区段（以其某轨道键）→ 所属逻辑线路 id。
- `lineTracks`：运营线路键（`LineDuties.getLineKey`）→ 可运行的逻辑线路 id 列表；不在其中的线路运行在其站台所在的逻辑线路上。
- `runningSides`：双线行车方向：`1` 右行、`-1` 左行；键 `""` 为全网（未设时右行），`"L" + id` 为逻辑线路，`"R" + 线路键` 为运营线路。
- `fareRules`（`LinkedHashMap`，显示顺序）：票务规则 id → `FareRule`；第一个为默认规则，总至少有一个（初始 `default` → "默认"）。0.73.0 之前的存档（每条线一个票价组）读成单一默认规则，原组的票价与分配被丢弃（用户 2026-11-12）。
- `fareLevels`：规则 id → 票价（`transitsim.fare.FareLevels`）；无条目用默认票价。
- `fareRouteGroups`：线路 id → 规则 id（0.73.0 起对一条线的所有线路同时设置）。
- `fareStationGroups`：车站 id → 手动设置的规则 id 列表（多个时闸机共享）。

### 存档键
`min_reverse_millis`、`early_threshold_millis`、`wait_threshold_millis`、`station_wait_thresholds`（map 车站 id 字符串 → long）、`owner_overrides`（map `"msb,lsb"` → 字符串数组）、`logical_lines`（map id → `{name, color, connecting}`）、`rail_lines`（map `"msb,lsb"` → long）、`line_tracks`（map 线路键 → long 数组）、`fare_levels`（map 规则 → `{start_fare, initial_blocks, unit_blocks, unit_fare, max_blocks, cap, overtime, overtime_minutes, overtime_fine, manual_tiers, bounds[], prices[]}`）、`fare_route_groups`、`fare_station_groups`、`running_sides`、`fare_rules`（map id → `{name, reference_line}`）。共 13 个键（`messagePackLength() = 13`）。

## public static final class FareRule
- `name`（截断到 256）、`referenceLine`（票价档位延伸到的参考线路键 `FareData#getAutoGroupKey`，空为"本规则线路上的最长行程"；超长视为空）。
- `equals` / `hashCode`：按两字段。调用者：`FareScreen.java:150`、`:415`。

### `public String getDefaultFareRule()`
- 返回：第一个规则的 id（为空时 `"default"`）。调用者：`FareData.java:57`、`FareScreen.java:398`。

## public static final class LogicalLine
- `name`（截断到 256）、`color`、`connecting`（联络线：所有运营线路都可运行）。
- `equals` / `hashCode`：按三字段。调用者：`EditorActions.java:483`。

### `public NetworkSettings()`
- 默认值。调用者：`DataCache.java:33`、`EditorActions.java:428`。

### `public NetworkSettings(Map<String, Value> map)`
- 作用：读存档，各值限幅（`clamp`/`clampWait`），各映射按上限截断，无效项跳过（非数字 id、非法轨道键、行车方向非 ±1）。有 `fare_rules` 时读取（为空补默认）；没有（0.73.0 之前）时清空票价、线路分配、车站分配。最后 `keepFareRulesKnown()`。
- 调用者：`RailwayDataFileSaveModule.java:311`。

### `public void keepFareRulesKnown()`
- 作用：去掉指向不存在规则的票价与分配（车站列表为空则删除条目）。调用者：`FarePackets.java:83`、`FareScreen.java:459`。

### `private static FareLevels readFareLevels(MessagePackHelper helper)`
- 作用：读票价参数（`unit_blocks` 至少 1）；`manual_tiers` 且价格数 = 边界数 + 1 时设置手动档位；最后 `clamp()`（不允许手改存档造成负票价）。

### `public boolean usesLogicalLines()`
- 是否有逻辑线路。调用者：`RoutePaths.java:108`。

### `public static UUID parseRailKey(String text)` / `public static String railKeyText(UUID railKey)`
- 轨道键与文本 `"node1,node2"` 互转（`parseRailKey` 经 `RailKeys.of(node1, node2)`；`railKeyText` 写 UUID 的高低 64 位）。解析失败返回 `null`。
- 调用者：`EditorActions.java:454`、`:490`（parse）；`EditorData.java:83`、`:101`（text）。

### `public long getWaitThreshold(long stationId)`
- 返回：车站自己的等候阈值，否则全网值。调用者：测试 `NetworkSettingsTest.java:31`（游戏代码中的调用未在脚本结果中出现，未确定）。

### `public void read(FriendlyByteBuf packet)` / `public void writePacket(FriendlyByteBuf packet)`
- 包字段顺序：
  1. `long minReverseMillis, long earlyThresholdMillis, long waitThresholdMillis`；
  2. `varint n, (long stationId, long millis)×n`；
  3. 所有者覆盖：`varint n, (long msb, long lsb, varint m, String×m)×n`；
  4. 逻辑线路：`varint n, (long id, String name, int color, boolean connecting)×n`；
  5. 区段线路：`varint n, (long msb, long lsb, long lineId)×n`；
  6. 线路轨道：`varint n, (String lineKey, varint m, long×m)×n`；
  7. 票价：`varint n`，每项 `String rule, varint startFare, varlong initialBlocks, varlong unitBlocks, varint unitFare, varlong maxBlocks, int cap, boolean overtime, varint overtimeMinutes, varint overtimeFine, boolean manual, varint k, varlong×k bounds, varint×(k+1) prices`（非手动时 k=0、价格为 `{startFare}`）；
  8. 线路规则：`varint n, (long routeId, String rule)×n`；
  9. 车站规则：`varint n, (long stationId, varint m, String×m)×n`；
  10. 规则：`varint n, (String id, String name, String referenceLine)×n`；
  11. 行车方向：`varint n, (String key, byte side)×n`。
- `read`：各计数越界抛 `IllegalArgumentException`（车站阈值数超过上限时只截断）；票价 `clamp()`（`FarePackets.receiveSetC2S` 也用它读玩家包，防止负票价）；规则为空补默认；行车方向只收 ±1。
- 调用者：`NetworkSettingsPackets.java:30-48`、`FarePackets.java:67-73`。

### `private static int readCount(FriendlyByteBuf packet, int max)`
- 读 varint 并校验 `[0, max]`。

### `public void copyFrom(NetworkSettings other)`
- 深拷贝全部字段（列表和票价对象复制）。调用者：`EditorActions.java:429`、`:543`、`RailwayDataFileSaveModule.java:312`。

### `toMessagePack(MessagePacker)` / `messagePackLength()`
- 按存档键写出（顺序：三项时间、车站阈值、所有者、逻辑线路、区段线路、线路轨道、票价、线路规则、车站规则、行车方向、规则）；长度 13。

### `equals(Object)` / `hashCode()`
- 按全部字段；规则按顺序比较。用于判断设置是否改变（`RunningSides.java:53-55`、`FareData.java:122`、`EditorData.java:794`）。

### `public int getRunningSide(String lineKey)`
- 返回：运营线路的行车方向：自身设置 `R<key>`；否则其各逻辑线路设置一致时取之；否则全网（默认右行 `RunningDirections.RIGHT`）。
- 调用者：`RunningSides.java:113`。

### `public static long clampWait(long millis)` / `public static long clamp(long millis)`
- 限制到 `[0, MAX_WAIT_THRESHOLD_MILLIS]` / `[0, MAX_MILLIS]`。调用者：`EditorActions.java:431-448`。
