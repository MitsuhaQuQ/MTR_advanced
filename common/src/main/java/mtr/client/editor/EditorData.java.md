# EditorData.java

高级编辑器显示的数据，取自本客户端已知的内容（`ClientData`）。班次是一天中的 UTC 毫秒（同 `RouteTimetable#services`），每张时刻表各站的时刻在这里按表计算（与时刻表界面相同的方式，组件 `StopRules`），页面不必自己算。客户端代码，在游戏线程上由 `EditorServer.java:281` 调用 `build()`；`EditorActions` 用 `getRevision` 检查草稿是否过期；`EditorAudio.java:28` 用 `getLanguage`；`EditorTimes.java:142` 调 `clearTimingRequests`。

## final class EditorData

### 静态状态
- `TIMES_REQUESTED`：线路 → 上次请求最快时分的时间；`TIMES_KEYS`：线路 → 上次请求时的物理输入键（`TimingCache.key`）。`TIMES_REQUEST_INTERVAL_MILLIS = 30000`：页面轮询时不会每次都重复请求。
- `throats`：每条线路各停站经过的咽喉道岔（L2，`ClientThroats.compute()`），每次 `build` 重算。
- `railSpeeds`：轨道键 → 地图轨道的限速（km/h），每次 `build` 重建。

### `private static String bufferPlaceName(boolean siding, long id)`
- 返回：停车线名，或"车站名 站台名"，用于缓冲时间显示（D1）；找不到用 id。

### `public static String getLanguage()`
- 返回：游戏语言代码（如 `en_us`），网页用同一语言。

### `public static JsonObject build()`
- 作用：生成编辑器主数据。不在世界中时抛 `IllegalStateException("Not in a world")`。
- 返回字段：
  - `dayStartMillis`、`offsetMillis`（本地时刻 = UTC 时刻 + 此值）、`canEdit`；
  - `networkSettings`：`minReverseMillis`、`earlyThresholdMillis`、`waitThresholdMillis`、`stationWaitThresholds{}`、`ownerOverrides{"msb,lsb": [线路]}`（网页分层第 2 步）、`logicalLines[]`、`runningSides{}`、`railLines{}`、`lineTracks{}`；
  - `simulation`（服务器是否开启调试用的日模拟）、`language`、`regionCount`、`topology`（`buildTopology`）；
  - `routes[]`：未隐藏的线路按名称与 id 排序，`buildRoute`；
  - `depots[]`：每个车厂（按名称与 id）：
    - `id, name, cycleRoutes, releaseInterval`；
    - `oldLogic`（不按运用运行的旧逻辑——列车不锁咽喉；仅非连续运动且不支持时刻表的交通方式如飞机，并且确有发车设置）、`notConfigured`（支持时刻表但没有运用，R2/R3；或旧逻辑但什么都不会发车）；
    - `throatLocksOffBy[]`：与不按运用运行的车厂共享轨道、因此不锁咽喉的车厂（按服务器同样的方式用组件 `LockNetworks` 从客户端已有的停车线路径计算）；
    - `routes[]`；`pools[]`：同车辆的停车线池（P2-C）`{key, vehicle, fleet (设定或 null), sum (各停车线车数之和，有无限则 −1), sidings[], vehicleSettings{set, acceleration, deceleration, manual, maxManualSpeed, manualToAutomaticTime, maxSpeed} (R1：车厂为池设定，否则取 id 最小停车线自己的), serviceTypes (5c)}`；
    - `manualSpeeds[]`（手动驾驶可选最高速，MTR 轨道类型到 DIAMOND 为止）、`serviceTypesUsed`；
    - `buffers[]`：每处测得的额外时间建议与采用值（D1；第 4 轮第 6 步）`{key, kind (out/turn/back), from, to, seconds, added?}`；
    - `sidings[]`：`{id, name, maxTrains, fleet, routes[], vehicle, legs? {entrance (车站 id), out{}, back{}}, noWayOut[], noWayBack[], noWayOutWhy{}, noWayBackWhy{} (0.65.26), measuredOut[]}`（出入库，P2-A）；
    - `revision`（`getRevision(depot)`）、`byTimetable`；
    - `path`：上次找路状态 `{state (generating/failed/stale/found), text, failedDuties, failedDutyKeys[], detours[] ({from, to, length, apart}，远长于站台间距的衔接), turnTimesMeasured, turnTimes{from: {to: ms}}, holdIns{} (折返线进入时长，第 4 轮), holdCounts{} (站台以外可同时等候的列车数，"运用补完"第 4 步)}`；
    - `held[]`：在线上等待且没有运用的列车 `{siding, slot, platform, reason}`（P1-C）；
    - 晚点/受阻告警（`EditorContingencies.addToDepot`，D2）；
    - `returnMisses[]`：设置为回某车厂但列车回不去的班次 `{siding, depot, reason}`（P1-B）；
    - `duties`、`autoDuties`（`EditorDuties.toJson`）、`rules`（`EditorRules.toJson`）；
    - `shortfall`（线路运用编排的列车缺口，`LineDuties`）：`{lines, needed, available, uncovered, suggested, replan (第 4 轮第 4 步：自保留的运用编排以来改变的设置), pools[] {label, needed, available, uncovered}, missing, groups[] {key, label, days, trains, needed, uncovered, kept} (运用组第 4 步), unrunnable, entrancesUnset[]}`；
  - `contingencySystems`（`EditorContingencies.systemsToJson`）。
- 副作用：更新 `throats`、`railSpeeds`；可能请求线路最快时分（`requestTimes`）。

### `private static int getFastestRail(Route route)`
- 返回：线路在地图上路径经过轨道的最高限速（km/h），未知为 0（线路限速不能高于它）。

### `private static JsonObject buildRoute(Route route, Map<Long, List<Platform>> stationPlatforms)`
- 返回（id 一律为字符串，因为超出 JS 数字精度）：`speedLimit, fastestRail, id, name, line, lineKey ("模式|线路键"), serviceType, color (自身), lineColor, circular, timetable (是否启用), entryPlatform, editorManaged, connectingLine, connectingReleased[] ([节点, 节点]), terminalPlatforms[] (站前折返), startPlatforms[] (种别优先起点), revision, editors (正在游戏中编辑它的其他玩家), parts{} (各部分版本，草稿只检查它改动的部分)`；
  - `stops[]`：每站 `{boundary (时刻表边界), platformId, dwellOverride, station, name, platform, pass, allowLonger, tooShort (第 3b 轮), platformLength, passingTrack, waypoints (末站之后为站后折返节点数), network, candidates[] (同站且同网络、同交通方式的股道 {id, name, passingTrack}), throat? {entry, exit, zones, crossings[] {route, stop, entry, otherEntry, zone, x, z}}}`；
  - `hasTimes`、`pathFailure?`、`fastest[][]`（每站最快到发，设定时刻不能更早）；没有时分时 `requestTimes`；
  - `tables[]`：每张表 `{name, weekdays, marginPercent, minAcceleration, minDeceleration, minMaxSpeed, services[], plans[] ([到, 发] 相对首站毫秒), times (EditorTimes.toJson), stops[] ({arrival, departure, overtake} 设定秒数，−1 跟随前站), links[] {service, route, toService, fromStop, toStop}, returns[], suspended[] (停运保留), orders[] (orderJson：中途从车厂出/回车厂), exceptions[] {service, stops[] {stop, run, dwell, pass, overtake}, plans, times}}`。
- 调用者：本类 `build`。

### `private static JsonObject buildTopology()`
- 作用：列车不折返时站台之间如何运行（组件 `PlatformGraph`），供页面选股道和新建类型。
- 返回：`platforms[]`（图中顺序）每个 `{id, name, station, stationName, mode, passingTrack, passSpeed, stopPosition, stationSet ("0" 为未设), x, y, z, network, line [x1, z1, x2, z2], ways[2] {usable, edges [state, cost, ...]}, track? {direction, kind, order} (L2：上下行、正线/待避线、站内排列顺序，ClientStationTracks.compute)}`；`stations[]`（有主区域的车站及其中心）；`overlapping[]`（`DataCache.overlappingPlatforms` 及 `byIdAlone`）；`networkCount`。状态编号 = 站台下标 × 2 + 方向。

### `private static String platformLabel(long platformId)`
- 返回："车站名 站台名"，找不到时 id。

### `public static String getRevision(Route route)`
- 返回：`route.getEditorRevision()`——编辑器可改动的线路内容任一变化都会改变的文本（由发送这些修改的同一批包生成），用于发现草稿制作后游戏中又有修改。
- 调用者：`EditorActions.java:106`、`:133`、`:591`。

### `public static String getRevision(Depot depot)`
- 返回：车厂可被编辑器改动部分的哈希（十六进制）：线路 id、`cycleRoutes`、运用模式、每条停车线的线路与车数。
- 调用者：同上；本类 `build`。

### `public static void clearTimingRequests()`
- 清空两张请求记录表。调用者：`EditorTimes.java:142`。

### `private static void requestTimes(long routeId)`
- 作用：线路物理输入键变化、从未请求或已过 30 秒时，发 `requestTimetableTimesC2S(routeId)`。

### `private static JsonObject orderJson(int service, String kind, RouteTimetable.ServiceOrder order)`
- 返回：`{service, kind ("deploy"/"return"), stop, depot}`。
