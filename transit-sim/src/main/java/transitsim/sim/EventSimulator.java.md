# EventSimulator.java

契约文档第 9 节所述的仿真器，建立在 `TickRunner` 之上（S4c）。时间按运行器的 tick 推进（从其开始时刻起算），所以宿主在哪里停止一步不影响结果。不可能有变化的 tick 被跳过（S4c2），结果与逐 tick 运行相同。输入在其时刻或之后的第一个 tick 之前生效；事件按列车产生的顺序输出、在每个 tick 内排序，永不撤回（不会提前告知任何事）。

在整体中的位置：实现 `Simulator` 接口。游戏侧 `LiveSimulation`（`LiveSimulation.java:358` `resume`、`:541` 构造、`:273` `advanceTo`、`:280` `events`、`:290` `forgetEvents`、`:366` `setLive`、`:159` 等 `submit`）；组件 `DayRun.java:105`；大量测试。输入类型见 `HostInput`，输出见 `SimEvent`、`TrainState`、`SimulationSnapshot`。

## 类 EventSimulator<T extends RunTrack>（final，实现 `Simulator`）

### 字段
- `runner`（`TickRunner<T>`）：底层运行器。
- `start`（`Supplier<TickRunner<T>>`）：重建仿真起点（各自的线路与列车），用于分叉和从快照续跑。
- `skipping`（boolean）：是否跳过无变化的 tick。
- `predicted`（boolean）：是否为分叉出来的预测仿真（事件标记为预测）。
- `inputs`（PriorityQueue<HostInput>）：待生效输入（按时间排序）。
- `applied`（List<HostInput>）：已生效输入（按顺序），供分叉与快照；live 模式不保存。
- `events`（List<SimEvent>）：已产生的事件（按游标序）。
- `eventSequences`（Map<Long, Long>）：列车 → 事件序号计数。
- `doorsOpen`（TreeMap<Long, Boolean>）：列车 → 上次车门状态，用于产生开关门事件。
- `motionEventsRead`（int）：已读取的运行器运动事件数。
- `cursor`（long）：最后一个事件的游标。
- `live`（boolean）：是否随游戏一直运行（G1），不保留只为分叉/快照所需的东西。

### 常量
- `RECOVERY_MILLIS = 60000`：停机不超过此时长时列车照原样继续（契约文档 6.3）。

### 构造函数 `EventSimulator(Supplier<TickRunner<T>> start)`
跳过无变化 tick 的默认形式。调用者：`DayRun.java:105`、`LiveSimulation.java:541`。

### 构造函数 `EventSimulator(Supplier<TickRunner<T>> start, boolean skipping)`
`skipping = false` 时逐 tick 运行（用于与跳过对比）。调用者：测试。

### 私有构造函数 `EventSimulator(Supplier<TickRunner<T>> start, boolean skipping, boolean predicted)`
建运行器，设跳过开关，并把 `collectEvents` 设为每 tick 之后的回调。

### `static <T extends RunTrack> EventSimulator<T> resume(LongFunction<TickRunner<T>> startAt, RunRecord record, long now)`
- 作用：从游戏保存的列车状态续跑（G0）：停机 ≤ 1 分钟时从记录时刻开始、`restore(record, false)` 并运行到现在（事件照常产生）；更长时从现在开始、按计划此刻应在之处恢复（`recover = true`），缺失时间段的事件不发生。
- 参数：`startAt` 在某时刻建立无列车的世界；`record` 运行记录；`now` 当前时刻。
- 返回：已推进到 `now` 的仿真器。
- 调用者：`LiveSimulation.java:358`、`DayRun.java:105`。

### `static <T extends RunTrack> EventSimulator<T> restore(Supplier<TickRunner<T>> start, SimulationSnapshot snapshot)`
从快照续跑：从头重新提交快照中的全部输入并推进到快照时刻（6.4）。调用者：`ForkSnapshotTest.java:77`。

### `TickRunner<T> getRunner()`
底层运行器。调用者：`LiveSimulation.java:156`、`DayRun.java:107`。

### `long getTime()`（覆盖）
当前仿真时刻 = `runner.getMillis()`。调用者：`DayRun.java:106`。

### `void setLive()`
进入 live 模式（G1）：清空已生效输入，此后不能分叉或快照，事件只保留到 `forgetEvents`。调用者：`LiveSimulation.java:366`。

### `void forgetEvents(long throughCursor)`
宿主处理完后丢弃游标 ≤ `throughCursor` 的事件，并清理已不存在列车的序号与车门记录。调用者：`LiveSimulation.java:290`、`:368`。

### `void submit(HostInput input)`（覆盖）
加入待生效输入。调用者：`LiveSimulation.java:159`、`DayRun.java:108`。

### `long advanceTo(long time)`（覆盖）
- 作用：推进到 `time`：每轮先应用时刻 ≤ 下一 tick 的输入，再让运行器运行到"下一个输入所在 tick 之前"或 `time`。
- 返回：推进后的时刻。
- 调用者：`LiveSimulation.java:273`、`DayRun.java:116`。

### `TrainState query(long trainId)`（覆盖）
列车当前状态快照（车头轨道、进度、速度、是否在线、是否静止、车门、宿主扣车原因、当前服务、是否被接管），无此车为 null。调用者：`ForkSnapshotTest.java:41`。

### `RunSegment getSegment(long trainId)`（覆盖）
列车当前运行段或 null。调用者：`SimulatorPropertiesTest.java:68`。

### `EventSimulator<T> fork()`（覆盖）
- 作用：分叉一个预测仿真：用同一起点新建（`predicted = true`），提交已生效与待生效的所有输入并推进到当前时刻。live 模式下抛 `IllegalStateException`。
- 调用者：`ForkSnapshotTest.java:39`。

### `SimulationSnapshot snapshot()`（覆盖）
已生效输入 + 排序后的待生效输入，连同时刻与游标。live 模式抛 `IllegalStateException`。调用者：`ForkSnapshotTest.java:76`。

### `boolean isPrediction()`
是否为预测仿真。仓库内未找到外部调用者。

### `List<SimEvent> events(long afterCursor)`（覆盖）
二分找到游标之后的事件，返回不可修改列表。调用者：`LiveSimulation.java:280`、`DayRun.java:120`。

### `private void apply(HostInput input, long tickMillis)`
- 作用：应用一个输入（非 live 时记入 `applied`）：先 `runner.catchUpAll()`（未被单独推进的列车按旧输入追上），然后按类型：
  - `Hold`：设宿主扣车原因并发 `HELD` 事件；`Resume`：解除扣车并发 `RELEASED`。
  - `Skip`：设跳站站台；`TurnBack`：设提前折返；`HandOver`：设交接；`Dispatch`：执行调度命令（`service.dispatch`）。
  - `Remove`：移除列车；`ExternalOccupancy`：外部占用轨道到某时刻；`ExternalTrain`/`ExternalRun`：设外部列车及其运行；`TakeOver`/`Release`：接管/释放列车（宿主手动控制）。
  - `PublishPlan`：对运行器线路应用计划变更；`PublishNetwork`：`publishNetwork`。
  - 最后 `runner.wakeAll()`。
- 调用者：`advanceTo`。

### `private void publishNetwork(Set<UUID> removedTracks, long tickMillis)`
移除轨道；前方有被移除轨道的在线列车发 `CONFLICT`（"track removed ahead"），站在被移除轨道上的列车发 `CONFLICT`（"track removed under the train"）并被移除，交由宿主另行放置（5.1 规则 5）。

### `private void collectEvents()`
- 作用：每 tick 后把发生的事转为事件：新的运动事件（经 `toKind` 映射）；移动过的列车车门状态变化（`DOORS_OPEN`/`DOORS_CLOSE`）；运行器恢复的列车（`RECOVERED`）；新死锁（`DEADLOCK`，列车取最小 id，详情为集合字符串）。按种类、列车、列车内顺序排序后 `addEvent`。注释说明：按下标遍历死锁列表以规避 JDK 17 C2 编译器在高负载下迭代器的 NPE（优化第 4 轮第 5 步）。
- 调用者：运行器 tick 后回调。

### `private SimEvent.Kind toKind(MotionEvent motionEvent)`
`DEPARTED`→`DEPART`，`PASSED`→`PASS`，`RETURNED`→`RETURNED`，`SEGMENT_REPLACED`→`SEGMENT_REPLACED`；`STOPPED` 在其下一停站处为 `ARRIVE`，否则为 `HELD`（被前方轨道挡住）；其他为 null（不输出）。

### `private void addEvent(long millis, SimEvent.Kind kind, long trainId, int rail, String reason)`
递增该车序号与全局游标，新建 `SimEvent`；`HELD` 且无原因且有轨道时原因为 `"blocked"`；预测仿真的事件标记为预测。
