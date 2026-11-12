# ScheduleEntry.java

站台的一条到站信息（PIDS、报站、其它模组读取的到站列表）。服务端 `TrainServer` 为每列车在每个站台生成（`TrainServer.java:789`），`PidsSchedules` 生成按运用计划的到站，经 `RailwayData` 发包；客户端 `ClientData` 接收并按时间修正（`atTime`）。本分支加了计划时刻、是否计划条目、发车时刻等字段，并区分给其它模组的列表。

## class ScheduleEntry implements Comparable<ScheduleEntry>

### 字段
- `arrivalMillis`：预计到站时刻（毫秒，UTC）。
- `trainCars`：车厢数。
- `routeId`：线路 id。
- `currentStationIndex`：该站在线路停站中的下标。
- `trainId`：列车 id（计划条目为 0）。
- `pass`：列车是否通过不停。
- `scheduledMillis`：时刻表计划到站，无时刻表为 0。
- `planned`：是否只是计划（来自运用计划，列车尚未出车）。
- `departureMillis`（0.67.0）：停在站台的列车预计离开时刻，或 0。原先停留超过站台停站时间的列车每次发送都被当作"现在到站"，导致倒数停站时间的显示每几秒跳回；现在客户端每 tick 由此推算到站时刻（`standingArrivalMillis`）。为负表示因结束时间未知的原因被扣（0.67.1），其绝对值为计划发车，供显示但不倒数。
- `waitingToCountDown`（0.67.3，客户端，不发送）：停站列车距离开还多于站台停站时间、或被扣且结束未知时为真；倒数停站时间的发车计时器在此期间留空。
- `standingForDuty`（包内）：车厂估计、在乘客显示上被运用计划替代的条目（只在服务端）。

### 构造函数
- `ScheduleEntry(arrival, cars, routeId, index, trainId, pass)`：`scheduledMillis = 0`。
- `ScheduleEntry(..., pass, scheduledMillis)`：`planned = standingForDuty = false`。
- `ScheduleEntry(..., scheduledMillis, planned, standingForDuty)`（包内）：`departureMillis = 0`。
- `ScheduleEntry(..., standingForDuty, departureMillis)`（包内）：`waitingToCountDown = departureMillis < 0`。
- 私有全参构造：赋值全部字段。
- 主要调用者：`TrainServer.java:789`、`PidsSchedules.java:108`、`ClientData.java:346`。

### `public ScheduleEntry atTime(long now, long platformStopMillis)`
- 作用：返回在 `now` 时刻应显示的条目：到站时刻为"发车前一个站台停站时间"与 `now` 中较早者，使从它倒数的停站时间正好在列车离开时归零；`waitingToCountDown = now < departure - stop`。`departureMillis <= 0` 时原样返回；值未变时返回自身。
- 主要调用者：`ClientData.java:120`；测试 `ScheduleForOtherModsTest.java:38`。

### `public static long standingArrivalMillis(long now, long departureMillis, long platformStopMillis)`
- 返回：`min(now, departureMillis - platformStopMillis)`。
- 主要调用者：`TrainServer.java:773`。

### `public static boolean shownToOtherMods(ScheduleEntry entry, long now)`
- 作用（0.65.31）：其它模组读取站台到站时只给停站且尚未到来的列车。它们不知道"通过"和"仅计划"，否则通过的快车会显示为停站、过去的计划会一直留着。
- 返回：`!pass && (!planned || arrivalMillis > now)`。

### `public static List<ScheduleEntry> forOtherMods(Collection<ScheduleEntry> entries, long now)`
- 返回：过滤后的新列表；`entries == null` 返回 `null`。
- 主要调用者：`RailwayData.java:1375`、`ClientData.java:122`。

### `public ScheduleEntry(FriendlyByteBuf packet)` / `public void writePacket(FriendlyByteBuf packet)`
- 包字段顺序：`long arrivalMillis, int trainCars, long routeId, int currentStationIndex, long trainId, boolean pass, long scheduledMillis, boolean planned, long departureMillis`。读取时 `standingForDuty = false`，`waitingToCountDown = departureMillis < 0`。
- 主要调用者（write）：`RailwayData.java:558`、`RailwayData.java:717`、`Siding.java:616`。

### `public int getDelayMinutes()`
- 返回：晚点整分钟数（`(arrival - scheduled) / 60000`，不小于 0）；无计划时 0。
- 主要调用者：`RenderPIDS.java:279`。

### `public int compareTo(ScheduleEntry o)`
- 按到站时刻升序，相同则按线路 id。
