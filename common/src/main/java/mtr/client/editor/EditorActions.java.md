# EditorActions.java

应用高级编辑器的修改。每个修改先在这里检查，然后改到本客户端的数据副本，并用游戏自身界面使用的同一批数据包发送，服务器按相同方式检查权限和数据。一批修改作为一个整体发给服务器（`ClientEditing.startBatch/sendBatch`），服务器对每个"组"（一条线路、一个车厂、一条新线路）全有或全无地应用，并按页面制作草稿时的版本检查线路（多人编辑冲突，见 `EditRevisions`/`EditGuard`）。客户端代码，`EditorServer.java:315` 在游戏线程上调用 `applyAndSend`，再在 HTTP 线程 `await()`；`EditorSimulation.java:137` 复用 `readTimetable`。各类型操作分派到 `EditorDuties`、`EditorContingencies`、`EditorConsole`、`EditorRules` 等。

## final class EditorActions implements IPacket

### 常量
- `MAX_OPERATIONS = 512`、`MAX_NAME_LENGTH = 256`、`MAX_SERVICE_TYPE_LENGTH = 32`、`MAX_STOPS = 256`、`MAX_WAYPOINTS = 16`（新线路每站途经节点上限）。
- `EDITOR_PARTS = ["timetable_services", "platform_ids", "is_light_rail_route"]`：编辑器修改一条线路时发送的部分（时刻表、停站、与之一起发送的设置），按部分检查版本。

### `public static JsonObject apply(JsonObject request)`
- 作用：`applyAndSend(request).result`（不等服务器答复）。
- 调用者：仓库中未找到外部调用（未确定）。

### `public static Pending applyAndSend(JsonObject request)`
- 参数：`request = {operations: [...]}`，每个操作有 `type`、`route` id，以及页面所基于的版本（`base` 或按部分的 `parts`），防止从已在游戏中改变的数据做出修改。
- 作用：有权限且操作数不超上限时，先找出所有"过期"的线路（`isOutOfDate`），这些线路的操作完全不发送并留在草稿中，其它照常发送；然后 `applyChecked`。
- 返回：`Pending`（`result = {ok, applied: [下标], created: [{index, id}], errors: [{index, route, message}]}`，以及待等待的服务器答复）。

### `private static String getGroup(JsonObject operation, int index)`
- 返回：修改的原子组：`duties`/`depot` → `EditGuard.DEPOT + ":" + 车厂`；`newRoute` → `"new:" + 下标`；其它 → `EditGuard.ROUTE + ":" + 线路 id`。

### `private static boolean isOutOfDate(JsonObject operation)`
- 返回：页面所基于的线路版本是否已被游戏超越：运用/车厂/新线路/`force` 不检查；线路不存在不算过期；有 `parts` 时逐部分比较（`ClientEditing.getRevisions`）；否则比较 `base` 与 `EditorData.getRevision(route)`。

### `private static Map<String, Integer> getParts(JsonObject operation)`
- 读 `parts` 对象。

### `private static String applyDepot(JsonObject operation)`
- 作用：车厂设置（按页面基于的 `base` 版本，`force` 跳过检查；不符返回 `gui.mtr.editor_depot_changed`）：
  - `routes`：线路列表（须存在且交通方式相同，去重；按时刻表运行的车厂无顺序）；
  - `cycleRoutes`；然后 `depot.setData` 发包；
  - `rules`：出入库规则与命名回送路径（"运用补完"第 2 步，`EditorRules.fromJson` → `setRules`）；
  - `poolFleets`：每个车池的车数（P2-C；缺省或 null 为各停车线车数之和）→ `setPoolFleets`；
  - `vehicleSettings`：每个车池的车辆设置（R1）→ `setVehicleSettings`；
  - `serviceTypes`：每种车辆运行的服务类型（5c），只发送变化的 → `setVehicleServiceTypes`；
  - `clearBuffers`：清除测得的额外时间建议（D1，已采用的保留）；
  - `addedBuffers`：出库/折返/回库各处采用的附加时间（秒 → 毫秒，≤0 忽略，第 4 轮第 6 步）→ `setAddedBuffers`；
  - `sidings`：每条停车线的线路与车数 → `siding.setOperation`（发 `PACKET_UPDATE_SIDING`）。
  最后 `DATA_CACHE.sync()` 并请求重新找路（`generatePathC2S`，结果显示在页面同步面板）。
- 返回：错误消息（车厂/线路/停车线不存在等）或 `null`。

### `private static Pending applyChecked(JsonObject request, JsonArray operations, Set<String> outOfDate)`
- 作用：无权限或操作过多时只返回一般错误。否则开始一批：对每个操作记录组；过期线路的操作记 `CONFLICT` 错误跳过；设置批的组；有 `parts` 且非 `force` 时设置该线路的按部分基准版本（服务器按部分检查）；`applyOperation`（异常记 `gui.mtr.editor_bad_change`）；成功记入 `applied`，且只有 `base` 的线路操作记录 `routeBases`。最后 `ClientEditing.sendBatch(routeBases)` 得到服务器答复的 future。

## public static final class Pending
- 字段：`result`、`future`（`ClientEditing.BatchResult`）、`operations`、`groups`；`ANSWER_SECONDS = 10`。
- `public JsonObject await()`：等服务器答复（最多 10 秒）。没有被拒绝的组时返回原结果。被拒（另一玩家同时改了该线路）的组中的操作不算已应用，改为 `CONFLICT:<改它的玩家名>`（不知道是谁时 `CONFLICT`，0.80.2）错误留在草稿；新建线路只保留未被拒的。一般错误：对象已被删除（`editor_server_deleted`，含玩家名）、被玩家本人在游戏中改过（`editor_server_rejected_self`，2026-11-09）、被他人改过（`editor_server_rejected`）；无答复时所有操作都不算应用（`editor_no_answer`）。返回 `ok: false` 的新结果。
- 调用者：`EditorServer.java:315`。

### `private static String applyOperation(JsonObject operation, int index, JsonArray created)`
- 作用：按 `type` 分派：
  - `duties` → `EditorDuties.apply`；`contingencies` → `EditorContingencies.applyPlans`；`contingency` → `EditorContingencies.applyCommand`（D2）；`dispatch` → `EditorConsole.applyCommand`（D6）；
  - `recall`：把在线上等待且无运用的列车召回停车线（P1-C，`HeldTrains.sendRecallC2S(depot, siding, slot)`）；
  - `depotRules`：单独设置车厂规则；`depot` → `applyDepot`；
  - `platformSpeed`：站台通过速度 1–1000 km/h（等于默认值存 0，第 3b 轮）；
  - `networkSettings`：在网络设置副本上修改 `minReverseMillis`、`waitThresholdMillis`、`stationWaitThresholds`（null 恢复全网值，"运用补完"第 2 步）、`earlyThresholdMillis`、`ownerOverrides`（`"node1,node2"` → 线路列表 / 空列表对所有线路开放 / null 重新推算，网页分层第 2 步）、`logicalLines`（id → `{name, color, connecting}`，null 删除并移除其区段与选择，2026-11-09）、`railLines`（区段 → 逻辑线路 id 或 null）、`lineTracks`（运营线路 → 逻辑线路列表或 null）、`runningSides`（`""`/`"L"+id`/`"R"+键` → ±1 或 null，2026-11-12），校验上限后写回本地并 `NetworkSettingsPackets.sendC2S`；
  - `platformStop`：站台停车位置 −1…1000（0 出站端、−1 居中、其它为距出站端方块数，第 4 轮）；
  - `platformStation`：站台所属车站（"0" 为按区域，R6）；
  - `pathBoundary`：在轨道 `a`–`b` 上设置/取消寻路边界（与游戏节点界面相同）；
  - `newRoute` 且无 `route`：新线路（L4）→ `createRoute(null, ...)`；
  - 以下需要线路存在：`newRoute`（基于已有线路的新种别，源线路不变，不检查版本）→ `createRoute(route, ...)`；其它类型先检查 `base`（非 `force`，不符返回 `CONFLICT`）：
    - `timetable`：`readTimetable`，设为编辑器管理、`sanitize`，发时刻表与附加数据；
    - `platforms`：`changePlatforms`（换股道），发停站、自定义路径、时刻表（时刻按站保存，服务器会丢弃换了站台的站的时刻，所以重发）、附加数据；
    - `stops`：`changeStops`（重排/增删停站，网页分层第 1 步，2026-11-09；计站序号的时刻表与运用在同一批中由页面重新编号），发停站、路径、附加数据并 `sync`；
    - `managed`、`connectingLine`：设置并发附加数据；
    - `speedLimit`：线路限速 0…`VehicleSettings.MAX_SPEED` km/h（不能高于沿线最快轨道，由页面限制）；
    - `connectingRelease`：联络线释放的轨道列表（每条两节点，≤ `Route.MAX_CONNECTING_RELEASED`）；
    - `turnPlatforms`：站前折返的终点站台与优先起点站台（各 ≤ `Route.MAX_TURN_PLATFORMS`）；
    - `deleteRoute`：与仪表盘删除相同（发 `PACKET_DELETE_ROUTE`，从本地列表移除；车厂、运用与贯通链接中对它的引用保持不变）；
    - 其它 → `gui.mtr.editor_unknown_change`。
- 返回：错误消息、`"CONFLICT"` 或 `null`（已发送）。

### `private static String createRoute(Route source, JsonObject operation, int index, JsonArray created)`
- 作用：新建一条线路的新种别（`source` 非空：复制其线路名、颜色、时刻表与车厂，不复制车站）或新线路（`source` 为空：只有车站，无班次与车厂，需 `mode` 与 `line` 名）。校验名称（1–256）、服务类型（1–32）、停站数（2–256）、每站站台存在且交通方式相同、每站途经节点（≤16）、首末站不能通过、整体连通（`checkConnected`）；车厂交通方式须相同。设置名称、服务类型、线路名、颜色（新线路同时作线路颜色；否则线路保持首条线路颜色，由 `applyLineColors` 赋回）、编辑器管理；停站全部替换，清除手绘路径；入口站台不在新停站中则清零；清空所有表的班次/链接/回库/停运/出入库指令/例外，并把页面给的班次（对一天取模，≤ `MAX_SERVICES`）放进指定表；`sanitize`。加入本地线路，发名称颜色、附加数据、停站、时刻表（有路径时发路径）；有车厂时加入其线路、发车厂数据并请求找路；`sync`。
- 返回：错误或 `null`；成功时 `created` 加 `{index, id}`。

### `private static String checkConnected(long[] platformIds)`
- 作用：按 `ClientData.PATH_REGIONS.platformGraph` 检查：所有站台在同一网络（`editor_other_network`），且相邻两站可达（可经过其它站台；在站折返允许，页面另行警告；`editor_no_path`）。

### `private static String changeStops(Route route, JsonArray stops)`
- 作用：给线路新的停站列表：每站为站台、是否通过、以及它原来的停站下标（−1 为新增）；原下标未被占用且站台未变时保留该停站的全部设置。校验站数、站台、交通方式、连通；清除手绘路径；入口站台不在其中则清零。

### `private static String changePlatforms(Route route, JsonArray platforms)`
- 作用：把停站移到同一车站的其它股道（来自服务器为每站找出的候选），停站数须不变（`editor_stations_changed`），换到别的车站为 `editor_wrong_track`。只有股道变了才检查连通（游戏中已坏的线路可逐站修正）。换了股道的停站保留目的地、途经点、停站时间覆盖、通过、允许超长、各表的到发与越行；入口站台随之更新；有变化时清除手绘路径。

### `static String readTimetable(Route route, JsonObject timetable)`
- 作用：先完整检查页面的时刻表（表数 1…`MAX_TABLES`、`pass` 与每表 `stops` 数等于停站数、每表班次 ≤ `MAX_SERVICES`、每个贯通链接经 `TimetableBoundaries.checkLink`），全部通过才写入（坏时刻表不会改动线路）：调整表数；`enabled = true`（2026-11-09：有班次的时刻表无论此值都运行，存为开启使旧版读取者也运行）；入口站台；每表名称、星期、裕量、最小加减速与最高速（≥0）、班次、链接、回库班次、停运、出库/回库指令、班次例外（停站下标在范围内）、各站设定到发与越行；各站通过标志，以及长度一致时的 `allowLonger`（第 3b 轮）。不发送。
- 返回：错误或 `null`。
- 调用者：本类、`EditorSimulation.java:137`。

### 私有辅助
- `addError(JsonArray errors, int index, String routeId, String message)`：追加 `{index, route, message}`。
- `getString(JsonObject, String)`：缺失或 null 为 `""`。
- `parseId(String)`：id 以文本传输（超出 JS 数字精度），非数字为 0。
