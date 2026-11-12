# EditorSimulation.java

编辑器的"模拟一天"（S5）：把页面的草稿（时刻表、运用）发给服务器，服务器用它模拟一天（`SandboxRuns`，组件 `TickRunner`），再把结果交给页面。只有最新一次请求有效。客户端代码；`EditorServer.java:361` 调 `start`、`:337` 调 `getStatus`，`PacketTrainDataGuiClient.java:325` 在游戏线程调 `receive`；`EditorTimes` 复用 `copyRoute`/`toJson`。服务端是否允许由 `SecurityConfig.editorSimulation` 决定（见 `SandboxRuns`）。

## final class EditorSimulation implements IPacket

### 静态状态
- `requestId`：当前请求序号（自增）。
- `state`：`idle`、`running`、`done`、`busy`、`permission`、`disabled`、`error`。
- `notSimulated`：无法模拟而被跳过的操作数（客户端统计）。
- `ignoredDrafts`：服务器忽略的草稿数。
- `missingDutyPaths`：草稿运用中没有沿轨道路径的形状数（这些列车不运行）。
- `scopeDepots`、`allDepots`：服务器模拟的车厂数（线路自己的那部分世界）与总车厂数。
- `parts`：分片接收的结果缓冲；`result`：解析后的结果 JSON。

### `private EditorSimulation()`
工具类，禁止实例化。

### `public static JsonObject start(JsonObject request)`
- 参数：`request = {weekday, fromNow, routes: [id], operations}`；`operations` 为页面按应用顺序的操作；`fromNow` 且为今天时，从当前列车状态往后模拟（G0）。
- 作用：
  - 无权限（`ClientData.hasPermission()`）返回 `{error: "permission"}`；
  - `timetable` 操作 → `draftRoute`（失败计入跳过），最多 `SandboxRuns.MAX_DRAFTS` 个；
  - `duties` 操作 → `EditorDuties.getDepot` + `EditorDuties.read` 读入 `DepotDuties`，序列化（失败计入跳过），每车厂一个，最多 `MAX_DRAFTS`；
  - `managed` 忽略；其它类型（改车站、增删类型等需要服务器应用时才能算路径）计入跳过；
  - 报告线路最多 `SandboxRuns.MAX_REPORT_ROUTES` 个；
  - 重置状态为 `running` 并发 `PACKET_SIMULATE`。
- 包字段顺序：`int requestId, int weekday, int n + long×n 报告线路, int m + byteArray×m 线路草稿, int k + (long depotId, byteArray 运用)×k, boolean fromNow`。
- 返回：`{request: requestId}`。

### `static byte[] draftRoute(JsonObject operation)`
- 作用：在线路副本上应用时刻表草稿（`EditorActions.readTimetable`），`sanitize()` 后序列化为组件 `SimDataset.RouteData`，因此在草稿应用前实际数据不变。
- 返回：字节；线路不存在或时刻表读取出错时 `null`。

### `static Route copyRoute(Route route)`
- 作用：经一次包写入/读取复制线路（释放临时缓冲）。
- 调用者：`EditorTimes.java:65`、`EditorTimes.java:72`；测试 `EditorSimulationTest.java:30`。

### `public static JsonObject getStatus()`
- 返回：`{request, state, notSimulated, ignored, missingPaths, scopeDepots, allDepots, result?}`。

### `public static void receive(int receivedRequestId, int status, int ignored, int missing, int part, int partCount, byte[] bytes)`
- 等价下面的版本且 `depots = allDepots = 0`。

### `public static void receive(int receivedRequestId, int status, int ignored, int missing, int depots, int allDepots, int part, int partCount, byte[] bytes)`
- 作用：接收服务器回应的一个分片。请求序号不是最新的则忽略。更新忽略数/缺路径数；`depots > 0` 时更新模拟范围。状态不是 `SandboxRuns.STATUS_RESULT` 时设为 `busy`/`permission`/`disabled`/`error` 并返回。否则把分片放入缓冲（分片数限制在 1–256），全部到齐后 `SandboxRuns.join(parts)` → 组件 `DayRun.fromBytes` → `toJson`，状态 `done`（解析失败为 `error`）。
- 调用者：`PacketTrainDataGuiClient.java:325`。

### `static JsonObject toJson(DayRun dayRun)`
- 作用：结果转 JSON：时间为运营日内毫秒（或 −1），班次为时刻表中的班次时刻（对一天取模）。
- 返回：`{trips[] ({route, service, train, stops[] ([stopIndex, 到达, 离开, 计划到达, 计划离开, 通过 0/1])}), problems[] ({kind, time, duration, route, service, train, stop, platform, detail}), dayStart, from (模拟起点相对日起点)}`。
- 调用者：`EditorTimes.java:237`、`EditorTimes.java:244`。

### `private static long toDayTime(DayRun dayRun, long millis)`
- 返回：`millis < 0 ? -1 : millis - dayRun.dayStart`。
