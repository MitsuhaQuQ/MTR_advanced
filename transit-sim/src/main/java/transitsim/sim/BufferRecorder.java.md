# BufferRecorder.java

根据按运用运行的列车的实际表现，测量它们比计划多花多少时间（D1）：从停车线到首站（出库）、从一个服务的末站绕到下一个服务的首站（折返）、从末站回停车线（回库）；下一次编制运用时把这些余量加入（`Buffers`）。两个宿主都把列车的 `MotionEvent` 喂给它：游戏在每 tick 之后，组件在事件发生时；因此它在区间开始时就取走该区间需要的一切。

区间用时 = 从离开到到达，减去途中停站的时间（如列车在折返轨上等待下一个服务）；停在被占轨道前的时间计入（这正是导致晚点的原因）。计划用时是同一轨道上的最快运行。每类区间保留最近 `SAMPLES` 次测量，其余量取超出时间的 `PERCENTILE` 分位数，且只在 ≥ `TOLERANCE_MILLIS` 时保留（与在轨道上测量出入库一样，0.17.0）。

在整体中的位置：游戏侧 `Depot` 持有一个 `bufferRecorder`（`Depot.java:419` 读档、`:573` 存档、`:766` 取变化、`:777`/`:898` 清空），`Siding.java:1732` 喂事件；组件 `TickRunner.recordBuffers`（`TickRunner.java:953`）让无头运行也测量。使用 plan 包的 `Buffers`、`TripPath`、`PlanTrack`。

## 类 BufferRecorder（final）

### 常量
- `SAMPLES = 20`：每个键保留的测量数。
- `PERCENTILE = 85`：余量取的分位数。
- `TOLERANCE_MILLIS = 15000`：小于此值的余量视为 0；余量变化超过此值才更新。
- `MAX_EXCESS_MILLIS = 20 分钟`：超出计划这么多的区间是被别的事（列车卡住、死锁）耽误的，不计入。
- `MAX_KEYS = 1024`（private）：最多记录的键数。

### 字段
- `legs`（Map<Long, Leg>）：列车 id → 正在测量的区间。
- `samples`（TreeMap<String, ArrayDeque<Long>>）：`Buffers.key` → 最近测量（最新在后）。
- `buffers`（Buffers）：当前余量。
- `changed`（boolean）：自上次 `takeChanged` 以来余量是否变化。

### `public volatile ObjLongConsumer<String> outListener`（2026-11-13）
每测得一次按首站晚点算的出库，告诉它（出库的键、晚了多少毫秒）；宿主设置，给 `OutLeads` 用（游戏 `Depot`、日模拟工具）。

### `public static IntToLongFunction plannedArrivals(TrainService<?> service)`（2026-11-13）
返回：路径上某轨道的计划到达时刻（`service.getPlanAt(rail)` 的 `arrivalMillis` 经 `getPlannedMillis`），没有计划为 0。调用者：`TickRunner.recordBuffers`、`Siding`（游戏每 tick 喂事件处）。

### `<T extends PlanTrack> void onEvent(MotionEvent event, long sidingId, TripPath<T> tripPath)`
等于 `onEvent(event, sidingId, tripPath, null)`。

### `<T extends PlanTrack> void onEvent(MotionEvent event, long sidingId, TripPath<T> tripPath, IntToLongFunction plannedArrival)`
- 作用：处理一个运动事件：
  - `DEPARTED`：若该车正在测量且出发轨道不是区间起点（途中停站后再出发），累计站立时间；否则丢弃旧区间并 `startLeg`。
  - `STOPPED`：非回库区间到达终点轨道 → `finish`；停在途中有停站时间的轨道：若是站台（说明换了路径）→ 丢弃，否则开始计站立时间。
  - `RETURNED`：回库区间 → `finish`；然后丢弃。
  - `LAP`：丢弃。
- 参数：`event` 事件；`sidingId` 列车所属停车线；`tripPath` 列车当前路径（可 null）；`plannedArrival` 轨道 → 计划到达（可 null，见下）。
- 副作用：修改 `legs`，可能新增测量。
- 调用者：`Siding.java:1732`、`TickRunner.java:954`。

### `private <T extends PlanTrack> void startLeg(MotionEvent event, long sidingId, TripPath<T> tripPath)`
- 作用：只对运用路径（`isDuty()` 且有 `dutyStarts`）开始测量：
  - 出发轨道为 0（出库）：到第一个站台停站，计划用时 = `stopTimes.getLeadMillis()`，种类 `OUT`；有 `plannedArrival` 时记下首站的计划到达（2026-11-13）。
  - 从站台出发且之后无站台：若不留在线上且回库时间 > 0，种类 `BACK`，终点为停车线（回送路径用 `TripPath.getReturnSidingId`）。
  - 下一个站台恰好是运用中下一段的起点：种类 `TURN`，计划用时 = `stopTimes.getMovingMillis(rail, next)`（≥ 0 时）。
- 调用者：`onEvent`。

### `private void finish(Leg leg, long millis)`
超出 = 到达 − 出发 − 站立 − 计划；出库且知道首站计划到达时（2026-11-13）改为到达 − 计划到达，即到首站晚了多少：提前出库却在路上等按钟点走的东西（如折返线上的正线列车）时，跑得更久但到得一样晚，按用时算会越加越多（六线图日模拟三轮里一条出库从 80 秒涨到 932 秒）。超过 `MAX_EXCESS_MILLIS` 忽略，否则 `add`，按晚点算的出库再告诉 `outListener`。

### `void add(String key, long excessMillis)`
- 作用：加入一个测量（比计划多的毫秒，提前为负）；键数达上限时忽略新键；保留最近 20 个；`update`。
- 调用者：`finish`、测试。

### `private void update(String key)`
重新计算该键余量；与当前值相差 > 容差、或由 0 变非 0、或反之时，生成新的 `Buffers` 并置 `changed`。

### `static long getBuffer(Collection<Long> keySamples)`（包内）
排序后取第 ⌈n×85%⌉ 个（下标 −1），≥ 容差才返回，否则 0；空返回 0。调用者：`update`、`readList`、`BufferRecorderTest.java:121`。

### `Buffers getBuffers()`
当前余量。调用者：`Depot.java:420`、`Depot.java:767`。

### `boolean hasSamples()`
是否有任何测量（只有服务器会测量）。调用者：`Depot.java:900`。

### `boolean takeChanged()`
返回并清除 `changed`，供宿主决定是否下发。调用者：`Depot.java:766`、`Depot.java:778`。

### `void clear()`
清空测量与区间；余量非空时置为 `Buffers.NONE` 并标记变化。调用者：`Depot.java:777`、`Depot.java:898`。

### `List<Long> toList()`
存档格式：键数，然后每个键的（种类、起点、终点——`Buffers.parse`）、测量数、各测量。调用者：`Depot.java:573`。

### `void readList(List<Long> list)`
- 作用：读回 `toList` 的结果并重算余量；数据截断时尽量读取；测量超过 20 个只保留最后 20 个；读后 `changed = false`。
- 调用者：`Depot.java:419`、`BufferRecorderTest.java:126`。

### `private static int nextPlatformStop(List<? extends PlanTrack> path, int after)`
after 之后第一个站台停站下标，没有为 −1。

### `private static boolean isPlatformStop(PlanTrack track)`
有停站时间、有站台 id、且不是停车线轨道。

## 内部类 Leg（private static final）
正在测量的区间。
- `kind`（`Buffers.OUT` / `TURN` / `BACK`）、`from`、`to`（站台或停车线 id）、`startRail`、`endRail`、`plannedMillis`、`startMillis`、`path`、`standingMillis`（途中停站累计）、`standingSince`（开始站立时刻，−1 = 未站立）。
- `private boolean isStopRail(int rail)`：该轨道有停站时间。
- `private boolean isPlatformStop(int rail)`：该轨道是站台停站。
