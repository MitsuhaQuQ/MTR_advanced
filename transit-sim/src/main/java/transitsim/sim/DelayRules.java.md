# DelayRules.java

晚点或卡住的列车属于车厂哪一个应急预案（`Contingency`），还是只报告（D2）。晚点列车不会被自动处理：处理方式由预案规定；等待调度员启用的预案或没有预案时只报告。两个宿主共用这一份逻辑（与 `FleetRules` 相同）。

在整体中的位置：游戏侧 `DelayAlarms.java:105-122` 每次检查时依次调用 `evaluate`、`getSkipPlatforms`、`getTurnBack`、`getTakeover`；测试 `DelayRulesTest`。使用 plan 包的 `Contingencies`（`plans`、`adhoc`、`active`）、`Contingency`（`appliesTo`、`getReason`、`manual`、`skipStationIds`、`turn`、`turnStationId`、`afterTurn`、`takeover`）。

## 类 DelayRules（final，不可实例化）

### 常量
- `DEFAULT`（Contingency）：车厂没有适用于该线路的预案时用于报告的默认规则（与新建预案相同）。
- `KEEP_SKIPPING_MILLIS = 60000`：已在跳站（或折返、交接）的列车，晚点 ≥ 1 分钟时继续执行，避免刚低于触发阈值就停站、下一站又跳站（D3）。

### `static Outcome evaluate(Contingencies contingencies, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked)`
单个系统的便捷形式（null 视为空列表）。调用者：`affecting`、测试。

### `static Outcome evaluate(List<Contingencies> systems, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked)`
- 作用：多个已连通的轨道系统的预案按给定顺序综合判断：
  1. 任一系统中调度员为该列车临时设定的（`adhoc`）优先，不论其状态，原因按 `DEFAULT` 计算。
  2. 否则依次检查各系统适用于该线路的预案：有触发原因（`getReason` 非 null）且非手动或已被激活的，立即返回；手动且未激活的记为"等待调度员"的第一个。
  3. 有等待中的预案：返回无预案、报警原因为其原因、`waitingPlanId` 为其 id。
  4. 都没有：若有适用于该线路的预案（只是未触发），原因为 null；否则按 `DEFAULT` 判断是否报警。
- 参数：`trainId` 列车；`routeId` 当前线路；`lateMillis` 晚点（`TrainService.getLateMillis`）；`waitingMillis` 等待前方路径的时间或 0；`deadlocked` 是否处于互相等待的环中。
- 返回：`Outcome`。副作用：无。
- 调用者：`DelayAlarms.java:105`、`DelayRulesTest.java:43`。

### `static Set<Long> getSkipPlatforms(List<Contingencies> systems, Outcome outcome, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked, boolean skipping, LongFunction<? extends Collection<Long>> stationPlatforms)`
- 作用：列车现在应不停站通过的站台（D3）：其预案或调度员设定所跳过车站的全部站台。已在跳站、晚点 ≥ 1 分钟且"若仍像之前一样晚点同一预案仍会生效"（以极大晚点重新 `evaluate`）时继续跳；预案关闭或设定取消则立即停止。
- 参数：`outcome` 本次 `evaluate` 结果；`skipping` 列车当前是否在跳站；`stationPlatforms` 车站 → 其站台。
- 返回：站台 id 集合（有序），或空集合。
- 调用者：`DelayAlarms.java:107`、`DelayRulesTest.java:44`。

### `static long[] getTurnBack(List<Contingencies> systems, Outcome outcome, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked, boolean turning, LongFunction<? extends Collection<Long>> stationPlatforms)`
- 作用：列车现在应在哪里提前折返（D4），延续规则同 `getSkipPlatforms`。
- 参数：`turning` 当前是否被要求折返。
- 返回：`{mode, after, 站台…}`：`mode` = 预案的 `turn`，`after` = `afterTurn`，折返方式为指定车站（`TURN_STATION`）时其后附该站全部站台（有序）；不折返为 `{0, 0}`。
- 调用者：`DelayAlarms.java:112`。

### `static boolean getTakeover(List<Contingencies> systems, Outcome outcome, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked, boolean takingOver)`
- 作用：列车现在是否应把运用交给备用车（D5），延续规则同上。
- 参数：`takingOver` 当前是否已被要求交接。
- 调用者：`DelayAlarms.java:122`。

### `static Contingency affecting(Contingencies contingencies, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked)`
列车所属的预案或 null（`evaluate(...).contingency`）。调用者：`DelayRulesTest.java:93`。

## 内部类 Outcome（public static final）
- `contingency`：列车所属的预案（或调度员设定），null 为无。
- `reason`：晚点或卡住的原因，null 为无。
- `alarm`：没有生效预案时报告它的原因，null 为不报告。
- `waitingPlanId`：适用于它、正等待调度员启用的预案 id，或 0。
- `systemIndex`：预案/设定/等待预案所在系统的下标，或 −1。
- 私有构造函数。
