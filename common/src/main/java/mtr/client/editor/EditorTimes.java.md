# EditorTimes.java

时刻表页面上线路各班次各站的时刻，按游戏的计算方式（组件 `StopRules`）得出。页面把草稿发到这里而不是自己算时刻，所以显示的就是列车将要运行的时刻（S3e，A09）。线路"最快时分"由服务器计算（`PACKET_TIMETABLE_TIMES`），客户端缓存在 `ClientData.TIMETABLE_TIMES`，有效性由 `TimingCache` 判断。客户端代码：`EditorServer.java:389` 调 `fromDraftAsync`，`PacketTrainDataGuiClient.java:354` 调 `receiveDraft`，`ClientData.java:385` 调 `beginConnection`，`EditorData`/`EditorDuties` 调 `getFastest`、`toJson`。

## final class EditorTimes

### 常量与静态状态
- `MAX_ROUTES = 256`：一次请求最多线路数，也是未完成请求和计算缓存的上限。
- `SAVED_TIMES`（public `TimingCache`）：已保存线路的最快时分是否仍有效。
- `calculations`（`LinkedHashMap`）：物理输入键（`TimingCache.key`）→ 计算结果的 `CompletableFuture<long[][]>`，超过上限时淘汰最早的。
- `nextRequestId`：请求序号（跳过 0）。
- `timingConnection`：当前连接对象，变化时清缓存。
- `pending`：请求 id → 等待服务器回应的草稿（`PendingDraft`）。

### private static final class PendingDraft
- `routeId`、`created`（`System.nanoTime()`）、`future`。构造函数 `PendingDraft(long routeId)`。

### `public static void receiveDraft(int requestId, long routeId, long[][] times)`
- 作用（客户端线程）：完成对应的等待；线路不符则忽略。草稿回应不会替换已保存时刻表的时分。
- 调用者：`PacketTrainDataGuiClient.java:354`。

### `public static CompletableFuture<JsonObject> fromDraftAsync(JsonObject request)`
- 作用：在客户端线程上读取草稿，HTTP 线程等待服务器计算结果。
  - 清理超过 30 秒（超时异常）或已完成的等待；
  - `routes` 缺失、超过 256 条、或等待数加本次超过 256 时抛 `IllegalArgumentException("Too many timing requests")`；
  - 每条线路：复制线路，按草稿的时刻表数（1…`RouteTimetable.MAX_TABLES`）调整副本的表数，写入每表 `minAcceleration`/`minDeceleration`/`minMaxSpeed`，以及每站 `pass`（数量须与停站数相同，否则抛 `IllegalArgumentException("Invalid timing draft")`）；
  - 物理输入键与已保存线路相同时直接用 `getFastest(saved)`；否则复用同键的计算；否则新建请求并发 `PACKET_TIMETABLE_TIMES`：`long routeId, int requestId, varint 表数, (float minAcc, float minDec, float minMaxSpeed)×表数, varint 站数, boolean pass×站数`。回应到达后，若当前线路键仍相同且长度匹配，写入 `ClientData.TIMETABLE_TIMES` 并 `SAVED_TIMES.remember`。
- 返回：全部完成后 `{routes: [draftAnswer...]}`。
- 调用者：`EditorServer.java:389`；测试 `EditorTimesTest.java:22`。

### `private static JsonObject draftAnswer(Route route, JsonObject timetable, long[][] times)`
- 返回：`{id, tables: forDraft(route, timetable, 长度匹配的 times 或 null)}`。

### `public static void beginConnection(Object connection)`
- 作用：连接对象变化（换服务器/重连）时 `clearTimingCache()`。调用者：`ClientData.java:385`。

### `public static void clearTimingCache()`
- 作用：清空 `EditorData` 的时分请求、`SAVED_TIMES`、`calculations`、取消并清空 `pending`、清空 `ClientData.TIMETABLE_TIMES`。

### `private EditorTimes()`
工具类，禁止实例化。

### `public static long[][] getFastest(Route route)`
- 作用：线路各站最快到达/离开时刻。`SAVED_TIMES` 不匹配时尝试从已完成的同键计算取得并缓存。
- 返回：`long[停站数][...]`（每行至少含到达、离开，单位毫秒，相对首站；具体列含义由服务器 `StopRules`/`RoutePaths` 定义，未确定）；游戏尚无时 `null`。
- 调用者：`EditorData.java:549`、`EditorDuties.java:308`、`EditorDuties.java:316`。

### `public static JsonArray toJson(List<StopRules.StopTime> stopTimes)`
- 返回：每站 `[arrival, departure, overtake, passes, tooFast]`，时间为距首站的毫秒，其余 1/0。
- 调用者：`EditorData.java:594`、`EditorData.java:657`。

### `public static JsonObject fromDraft(JsonObject request)`
- 作用：同步版本：对每条（最多 256 条）存在的线路用当前 `getFastest` 计算。
- 返回：`{routes: [{id, tables: [{times, exceptions: [{service, times}]}]}]}`。
- 调用者：仓库中未找到（可能已被 `fromDraftAsync` 取代，未确定）。

### `private static JsonArray forDraft(Route route, JsonObject timetableObject)` / `forDraft(Route, JsonObject, long[][] fastest)`
- 作用：对草稿每张表（最多 `MAX_TABLES`）：裕量百分比限制在 `[0, MAX_MARGIN_PERCENT]`；读每站到达/离开秒数（限制在 `[-1, MAX_STOP_SECONDS]`）与越行标志填入 `DraftStop`；`StopRules.times(stops, fastest, margin, table, null)` 得到常规时刻；对每个例外班次（最多 `MAX_SERVICES`）用其停站例外再算一次。
- 返回：`[{times, exceptions: [{service, times}]}]`。

### `private static Map<Integer, ServiceExceptions.StopException> getStopExceptions(JsonArray stopsArray, int stopCount)`
- 作用：读例外：`stop` 下标、`run`/`dwell` 秒数（限制）、`pass`/`overtake`（−1/0/1，−1 推测为"不改"，未确定）。

### `private static int clampSeconds(int seconds)`
- 返回：限制在 `[-1, RouteTimetable.MAX_STOP_SECONDS]`。

### `private static boolean getBoolean(JsonArray array, int index)`
- 返回：越界或 null 为 `false`。

## private static class DraftStop implements PlanStop
页面草稿中的一个停站（组件 `PlanStop` 接口，供 `StopRules` 使用）。
- 字段：`platformId`、`pass`、`arrivals[]`、`departures[]`（每表秒数，−1 表示未设）、`overtakes[]`。
- 构造函数 `DraftStop(long platformId, boolean pass, int tableCount)`：数组初始化为 −1/false。
- `getPlatformId()`、`isPass()`、`getArrival(int table)`、`getDeparture(int table)`、`getOvertake(int table)`：越界时返回 −1 / false。
