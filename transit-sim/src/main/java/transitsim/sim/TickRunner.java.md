# TickRunner.java

不需要世界，按游戏服务器的方式逐 tick 运行列车（S4a）：依次让每条停车线的列车按 `TrainMotion`（或运行层 `RunMotion`）、`TrainRules`、`TrainService` 运行，然后处理申请的运行，最后让车厂发车。轨道占用方式同 `Occupancy`，运用列车的运行保存在 `RunReservations` 中。

跳过（S4c2、S4f）：列车只在其可能有变化的 tick 中被移动；其余 tick 中它只沿运行段前进或静止，在下次被移动或被查看时追上。被放置不管的列车在以下情况被唤醒：它查看过的轨道被占用或释放、其车厂占用的服务变化、以及每个输入。没有任何列车移动的 tick 整体跳过。结果与每 tick 移动每列车相同。

它是事件驱动仿真器（`EventSimulator`）比对的基准，并运行固定的整链场景。跨多条轨道的信号闭塞区按游戏信号的方式占用（G1），车厂按时刻表、运用或频率发车（与游戏一致）。可手动驾驶的停车线的列车停着，直到宿主接管并开出。不包含（需要世界或玩家的）：手动驾驶本身、乘客、车门所对站台。

在整体中的位置：`SimDataset.buildRunner` 构造；`EventSimulator` 驱动（`runUntil`、`getEvents`、`catchUpAll`、`wakeAll`、输入处理各方法）；`LiveSimulation`（游戏中的实时仿真：`shareReservations`、`renewReservations`、`setTrainIds`、`record`、`placeDutyTrains`、`getTrains`、`tick`）；`DayRun`（`placeOvernightTrains`、`setTrainListener`）；`RunInvariants`、`SimulateDay` 读取列车。游戏侧 `TrainServer` 实现与 `HeadlessTrain` 相同的接口（`MotionState` 等），因此调用者列表中 `TrainServer.java` 的同名方法多为其自身实现，而非对本类的调用；`TrainServer` 直接调用本类的只有静态/共享逻辑（注明者）。

## 类 TickRunner<T extends RunTrack>（final）

### 主要字段
- `reservations`（RunReservations）：运行预订；可被宿主共享的替换（`shareReservations`）。
- `zoneOfRail`（Map<UUID, Long>）：轨道 → 所在联锁区（见 `setInterlockZones`）。
- `hostResolves`（boolean）：预订与宿主共享时由宿主在其 tick 结束后处理申请（G1）。
- `sidings`（TreeMap<Long, SidingRun>）：停车线 id → 运行状态。
- `nodeNeighbours`：停车线路径轨道连接的节点邻接表（站咽喉道岔用；路径不用的轨道不会挡路），添加停车线时重建。
- `depots`（List<DepotBoard>）、`depotSidings`：每个车厂（按首个停车线顺序）及其停车线。
- `shedIds`（Set<Long>）：存放在车库中的列车 id（P2-C），新车不使用这些 id。
- `trainsById`、`events`（运动事件列表）。
- `externalUntil`（TreeMap<UUID, Long>）：被运行器不移动的东西（如手动列车）占用到某时刻的轨道。
- `blockTracks`、`publishedOccupants`：切分闭塞分区所依据的轨道，以及上次告诉仲裁器谁站在哪（运行层 5.5）。
- `externalTrainRails`、`externalTrainOccupants`：运行器不移动的列车所在轨道及其占用者（G0）。
- `externalRuns`（TreeMap<Long, List<Move>>）：运行器不移动的列车持有的运行，每 tick 重新持有（G1）。
- `rails`（Map<UUID, RailEntry>）：每条轨道本 tick 与上 tick 的占用者（同 `Occupancy`）。
- `blockRails`：信号闭塞区中每条轨道 → 其所在所有闭塞区的轨道（G1）。
- `signalBlockList`：给定的信号闭塞区；`blockSections`：切分后的闭塞分区（路径已知后计算，见 `getBlockSections`）。
- `changedRails`：自上 tick 结束以来占用者变化的轨道。
- `removedTracks`：已从网络删除、列车不再驶入的轨道（契约文档 5.1 规则 5）。
- `movedTrains`（本 tick 移动的列车，按顺序）、`removedTrains`（本 tick 移走的列车，其轨道在 tick 结束前仍占用）、`standingDrops`（被放置期间预订的服务，在车厂开始 tick 时释放）、`readyDepots`（本 tick 移动过的列车可能从中出发的车厂）。
- `routes`、`millis`（当前时刻）、`earlyThresholdMillis`（默认 0 即准点，数据会设置）、`nextTrainId`、`trainIds`（新车 id 来源，null 则从 1 递增）。
- `skipping`：是否跳过无变化的 tick。
- `afterTick`（每 tick 后回调）、`trainListener`、`moreListeners`（如 `BufferRecorder`，D1）。
- `recoveryTicks`、`recovered`：从记录恢复的列车放到计划位置前剩余的 tick 数，以及放到了哪里。
- `aligning`：列车逐个放到计划位置期间，别人占用的轨道都算被占。
- `skippedTicks`、`trainTicks`：统计（跳过的 tick 数、列车被移动的 tick 数）。
- `lastTickMillis`：上次运行的 tick（让车厂忘掉跳过前预订的服务）。
- `inTick`、`tickPosition`（本 tick 已处理到的列车顺序位置，其后的列车在本 tick 仍会移动）、`movingTrain`、`quietTrain`（正在计算能被放置多久、观察其查看的轨道的列车）。

### 常量
- `EXTERNAL_OCCUPANT = -1`：外部占用轨道的占用者。
- `REMOVED_OCCUPANT = -2`（private）：对仲裁器而言站在被删除轨道上的东西（运行层 5.5）。
- `TICK = 50`（private）。
- `MAX_STANDING_TICKS = 12000`（private）：静止列车最多被放置不管这么久（10 分钟）后重新计算。
- `RUN_LAYER`（public static volatile boolean）：非缆车列车是否由运行层（解耦文档 5.5，`RunMotion` + `Authority`）而非沿用 MTR 的规则运行；系统属性 `mtr.runLayer=false` 关闭。

### `void setEarlyThresholdMillis(long earlyThresholdMillis)`
设网络早到阈值（≥ 0）。调用者：`SimDataset.java:361`。

### 构造函数 `TickRunner(long startMillis, LongFunction<? extends PlanRoute> routes)`
- 参数：`startMillis` 第一个 tick 的时刻（时刻表计时）；`routes` 按 id 查线路。（源码中其 javadoc 被 `RUN_LAYER` 的声明隔开。）
- 调用者：`SimDataset.java:360`、`ScenarioLine.java:65`。

### `long getMillis()`
当前时刻。调用者：`EventSimulator.java:112`、`RunInvariants`。

### `SidingRun addSiding(long sidingId, DepotBoard depot, Siding<T> siding)`
- 作用：添加车厂的一条停车线（下一 tick 生成第一列车）；重置节点邻接与闭塞分区；重建车厂列表；给车厂设置回库选择（指定车厂时 `chooseOrderedReturnSiding`，否则 `chooseReturnSiding`）与交接回调（转给 `dispatcher.requestTakeover`）。
- 调用者：`SimDataset.java:461`、`ScenarioLine.java:67`。

### `private Map<String, TripPath<T>> getSidingPaths(long sidingId)` / `private PathVariants<T> getSidingVariants(long sidingId)` / `private Object getSidingPool(long sidingId)` / `private Object getPoolKey(SidingRun)`
停车线的服务路径 / 变体生成器 / 池键（无池键时用停车线 id 自成一池）；停车线不存在时为 null（池为其 id）。供 `DepotDispatcher` 回调。

### `private List<SidingRun> getLinePool(SidingRun sidingRun)`
停车线在本车厂的池加上同运营线路其他车厂中同池的停车线（`FleetRules.linePool`，S7）。

### `private int getCapacity(SidingRun sidingRun)`
停车线容量（最大车数 + 1），无限制（≥ `UNLIMITED_TRAINS`）为 −1。

### `private int countComing(long sidingId, Collection<SidingRun> from)`
在线且路径为回送到该停车线的列车数。

### `private int getPoolCount(SidingRun sidingRun)` / `private int getPoolFleet(SidingRun sidingRun)`
线路池现有车数（在线、停放、车库、本 tick 回来的）/ 池总车数（−1 按需，P2-C）。

### `private long chooseReturnSiding(DepotBoard depot, long sidingId, LongPredicate canGoBack)`
池中运用后回哪条停车线（见 `DepotDispatcher.chooseReturnSiding`）：只考虑有回送路径、且停放 + 正在回来的少于容量的停车线；每条停车线的槽位和运行记录取自其所属车厂。池少于 2 条时回自己的。

### `private long chooseOrderedReturnSiding(DepotBoard depot, long sidingId, long depotId, LongPredicate canGoBack)`
运用被指定回某车厂时：该车厂中同车型（同池键或自身）、有空位、有回送路径的停车线里最早需要车的；没有则记入 `returnOrderMisses`（告知而非默默改道，P1-B）并按常规选择。

### `private boolean hasRoom(SidingRun sidingRun, SidingRun own)`
自身或（停放 + 正在回来的 < 容量）。

### `RunReservations getReservations()`
调用者：`EventSimulator.java:354`。

### `private transitsim.network.BlockSections getBlockSections()`
懒计算闭塞分区：收集所有停车线常用路径与服务路径的轨道（长度与切分速度 `cutSpeed`），`new BlockSections(signalBlockList, …)`。（源码在其上方有一段孤立 javadoc，描述 `shareReservations`。）

### `private static float cutSpeed(RunTrack track)`
可加速轨道按其限速切分，否则 −1（不切）。

### `private Map<Long, Set<Long>> getNodeNeighbours()`
懒计算节点邻接（只用停车线常用路径）。

### `void shareReservations(RunReservations shared)`
- 作用：在第一个 tick 之前，让列车在宿主的预订中持有和申请运行，与宿主自己的列车共享（G1）；此后宿主在其 tick 结束后处理申请，并在其每个 tick 调用 `renewReservations`（运行器不一定每个宿主 tick 都运行）。
- 调用者：`LiveSimulation.java:362`。

### `void renewReservations()`
每列车重新持有所持并重新申请所等待的运行（如同在一个 tick 中），使运行器未运行的宿主 tick 不会把它们收走。调用者：`LiveSimulation.java:278`。

### `LongFunction<? extends PlanRoute> getRoutes()`
调用者：`DayRun.java:127`、`EventSimulator.java:294`。

### 宿主输入（由 `EventSimulator.apply` 调用）

#### `void removeTracks(Set<UUID> tracks)`
把轨道从网络删除：先追上所有列车，记入 `removedTracks`，唤醒全部。列车停在其前；站在上面的由调用方处理。调用者：`EventSimulator.java:306`。

#### `boolean isRemoved(UUID track)`
该轨道是否已删除。

#### `void occupyUntil(UUID rail, long untilMillis)`
从现在到该时刻占用一条轨道（如手动驾驶或宿主移动的其他列车），以外部占用者登记。调用者：`EventSimulator.java:281`。

#### `private void publishOccupied()`
告诉仲裁器哪些东西不由运行层移动却占着位置（5.5）：外部占用到期前的轨道（占用者 −1）、宿主移动的列车的轨道、被删除的轨道（−2，不让任何列车驶入）、被接管列车所持轨道；每个都按其轨道的闭塞单元（`getUnits`）。已不存在的占用者清空。调用者：`tick`（第 1053 行附近）。

#### `private List<UUID> getUnits(UUID rail)`
轨道的闭塞分区片（运行层按此声明）；不在已知轨道中时就是轨道本身。

#### `void setExternalTrain(long trainId, Set<UUID> newRails)`
运行器不移动的列车现在所在的轨道，替换之前的（G0）；与外部占用一样，在一个 tick 中排在所有列车之前（占用者顺序为 `Long.MIN_VALUE` 组）。空集合即移除。调用者：`EventSimulator.java:284`；`takeOver`、`release`。

#### `void setExternalRun(long trainId, List<RunReservations.Move> moves)`
运行器不移动的列车现在持有的运行，替换之前的（G1）；运行器的列车不会获得与之相对的运行。空则释放。调用者：`EventSimulator.java:287`。

#### `boolean takeOver(long trainId)`
停止移动该列车（宿主已接管，3.1 规则 4）：其轨道保持占用（作为外部列车）、取消发车请求、不再到期、释放站立服务与预订。返回：无此车或已被接管时 false。调用者：`EventSimulator.java:289`。

#### `boolean release(long trainId, double railProgress, float speed)`
从宿主放手处继续移动被接管的列车（3.1 规则 4）：从那里重新计算运行、重新申请运行；为它报告的外部轨道撤销，下一 tick 起它占用自己的。返回：无此被接管列车时 false。调用者：`EventSimulator.java:292`。（列出的 `TrainServer.java:1078` 为 `Authority.release` 等同名方法。）

### 记录与恢复（G0）

#### `RunRecord record()`
- 作用：先追上所有列车，生成游戏同格式的列车记录，以及各车厂（以其首个停车线为键）的已派服务、运用运行日、上次发车时刻、轮流下标、接管项、各停车线车库（含本 tick 回来的）。
- 调用者：`LiveSimulation.java:538`、`DispatchTest.java:71`。

#### `void restore(RunRecord record, boolean recover)`
- 作用：在第一个 tick 前放回记录的列车（G0）：恢复车厂的已派服务、运行日、接管项、上次发车、轮流下标；为每个记录（停车线存在且 id 未用）建 `HeadlessTrain` 并 `restore`；各停车线列车按 id 排序；恢复车库（id 不与列车冲突）。续跑（契约文档 6.4）时列车照原样继续；长停机恢复（6.3）时 `recoveryTicks = 2`，在所有列车第一个 tick 占好轨道后被放到计划位置（最远的先放，各自退到其前的空站），晚点与所持运行清除。
- 调用者：`EventSimulator.resume`（`EventSimulator.java:89`）。

#### `List<String> takeRecovered()`
恢复时各列车被放到了哪里（`"列车 轨道"`），在完成的 tick 之后取一次。调用者：`EventSimulator.java:348`。

#### `int placeDutyTrains()`
- 作用：为按运用运行（`diagramMode`）的车厂中此刻已在外、但没有列车的每个运用放一列车到运用此刻规定的位置：所停之站或两站之间（解耦文档 D 步）。不从一天开头推算，立即完成。离开时刻 = 首服务 − 出库提前量（按计划，运用补完第 1 步；旧计划用出库用时）；已到时刻的才放；最早开始的先放（走得最远），计划位置被占则去其前的空站；标记今天已派出。在新建运行器的第一个 tick 前调用。
- 返回：放到线上的列车数。调用者：`LiveSimulation.java:537`、`DutyPlacementTest.java:54`。
- 注：源码在其上方有一段孤立 javadoc "线上每列车去计划此刻所在之处（6.3），最远的先"，描述的是 `recover`。

#### `int placeOvernightTrains()`
- 作用：为今天由过夜留线列车运行的每个运用（`DutyShapes.getOvernightSlot`）放一列车在运用开始处（如同前一晚留在那里，2026-11-09）：从一天开头仿真的一天没有前一天的列车，调度器也不从停车线为这种运用派车，否则这些运用根本不会运行。在运营日开始时新建的运行器第一个 tick 前调用，使从同一起点重建的仿真器放法相同。
- 返回：放到线上的列车数。调用者：`DayRun.java:88`。

#### `private int place(List<Object[]> due, long day)`
- 作用：按时刻先后为每项 `{离开或开始时刻, 停车线, 槽位, 运用路径, 车厂}` 放一列车：优先取池中车库存放的列车（保持列车属于该池，P2-C），否则新 id；设置停站时间、路径变体，`deployDuty`，标记在线，`alignToTimetable(true)` 放到位置；成功则加入停车线、写占用、标记今天已派；失败则把取出的车放回车库最前。期间 `aligning = true`。最后唤醒所有列车。

#### `private void recover()`
在 tick 开始时（只追到上个 tick 末）把所有列车按进度从远到近、再按 id 排序，逐个 `alignToTimetable`（在线且未被接管时），写占用，记录到 `recovered`；最后唤醒。调用者：`tick`。

### 设置

#### `void setSkipping(boolean skipping)`
在无变化的 tick 中放置列车不管（结果相同，S4c2、S4f）；关闭时每 tick 移动每列车（同游戏）。调用者：`EventSimulator.java:68`。

#### `void setTrainIds(LongSupplier trainIds)`
由宿主给新车分配 id（G1，如游戏的随机 id），避免与仿真不运行的列车冲突；已有的 id 会重新取。调用者：`LiveSimulation.java:360`。

#### `private long newTrainId()`
新车 id：无宿主来源时从 `nextTrainId` 递增并跳过车库 id；否则反复从宿主取直到不与现有列车或车库冲突。

#### `private long takeFromPoolShed(SidingRun sidingRun)`
取停车线池中车库存放最久的列车（先自己的），返回其 id，没有为 0。

#### `List<Long> getShed(long sidingId)`
停车线车库中的列车（P2-C），最早的在前，本 tick 回来的在后；不是本运行器的停车线为空。调用者：`LiveSimulation.java:624`、`GameChainTest.java:154`。

#### `void setSignalBlocks(Collection<? extends Collection<UUID>> blocks)`
设置信号闭塞区（G1）：列车在有信号的闭塞区某轨道上时连同其所有闭塞区的全部轨道一起占用（同游戏 `SignalBlocks`），除非已有别的东西占着其中某条。重置闭塞分区并建立 `blockRails`。调用者：`SimDataset.java:362`。

#### `void setInterlockZones(List<? extends Collection<UUID>> zones)`
设置联锁区（L2）：联锁颜色的信号闭塞区，运用列车经站咽喉通过时整体占用。每个区的 id 为其在列表中的位置（从 1 起）；参数为每个信号闭塞区（按序）若为联锁区则为其轨道，否则空。调用者：`SimDataset.java:367`。

#### `void setTrainListener(TrainListener<T> trainListener)`
每个列车事件发生时（列车保持当时状态）通知（S5），监听者只能读取列车。调用者：`DayRun.java:107`。

#### `void addTrainListener(TrainListener<T> listener)`
再追加一个监听者（在上面那个之后通知）。

#### `void recordBuffers(BufferRecorder recorder)`
测量运用列车出库、折返、回库比计划多花的时间（D1）：添加一个把事件转给 `recorder.onEvent` 的监听者。

#### `void setAfterTick(Runnable afterTick)`
每个实际运行（未跳过）的 tick 结束时调用。调用者：`EventSimulator.java:69`、`RunInvariantsTest.java:38`。

#### `long getSkippedTicks()` / `long getTrainTicks()`
跳过的 tick 数（期间无列车移动）/ 列车被移动过一个 tick 的总次数。调用者：`SimulateDay.java:236`。

### 运行

#### `void runUntil(long endMillis)`
- 作用：运行 tick 直到该时刻。跳过模式下先把时间直接推进到下一个需要运行的 tick 之前（`getNextTickMillis`），累计跳过数；若下一需要的 tick 超出范围则停止。被放置的列车在被 `getTrain`/`getTrains` 查看时追上。
- 调用者：`EventSimulator.java:152`、测试。

#### `private long getNextTickMillis()`
下一个有列车必须移动或外部占用到期的 tick：有轨道占用变化或待释放的站立服务时为下一个 tick；外部占用在其时刻之后的第一个 tick 释放；各停车线的 `dueMillis` 取最小……（续见下一部分）。
（`getNextTickMillis` 续）结果至少为下一个 tick。

#### `void tick()`
- 作用：运行一个 tick：
  1. 时间 +1 tick；若在恢复倒计时中且降到 0，`recover`。
  2. 若上个 tick 不是紧邻的（中间有跳过），各车厂调两次 `dispatcher.startTick`，忘掉跳过前预订的服务（跳过期间只有被放置的列车，其站立服务另行保存）。
  3. 移除到期的外部占用；重新持有外部列车的运行（每个持有者每 tick 续期）。
  4. 依次运行每条停车线（跳过模式下只运行到期的）：`SidingRun.tick`。
  5. 运行层开启时 `publishOccupied`；不由宿主处理时 `reservations.resolve(millis)`。
  6. 释放被移走列车的站立服务；各车厂 `deploy`；`endTick`；记录本 tick；调用 `afterTick`。
- 调用者：`runUntil`；`LiveSimulation.java:112`（列出者可能为同名方法，未确定）、`SimulatorEquivalenceTest.java:19`。

#### `private void endTick()`
tick 结束时：各变化轨道的"现在"成为下一 tick 的"之前"；被移走列车的轨道释放；本 tick 回到车库的列车从下一 tick 起存放（P2-C）并使停车线下一 tick 到期；某车厂（或同伴）占用/释放了服务时，唤醒这些车厂停车线内所有未上线的列车；本 tick 运行过的停车线 `SidingRun.endTick`（列车计算能被放置多久）。

#### `List<MotionEvent> getEvents()`
自开始以来所有列车的运动事件（按发生顺序，不可修改）。调用者：`EventSimulator.java:331`。

#### `boolean hasTrain(long trainId)`
调用者：`LiveSimulation.java:156`、`EventSimulator.forgetEvents`。

#### `long getOccupant(UUID rail)`
此刻占有该轨道（按游戏列车位置的方式取第一个占用者）的列车 id，无为 0；先追上所有列车。调用者：`SimulateDay.java:182`、`SignalBlockTest.java:100`。

#### `HeadlessTrain getTrain(long trainId)`
取列车并追上到现在，无为 null。调用者：`EventSimulator.java:159`、`EventSimulator.java:228`；`takeOver`。

#### `boolean removeTrain(long trainId)`
移走列车并放弃其所持：从停车线移除、标记已移除、释放预订；其轨道作为"上一 tick"再保持一个 tick，其服务保留到车厂开始那个 tick。停车线需要时会再生成。返回：无此车时 false。调用者：`EventSimulator.java:277`、`EventSimulator.java:317`。

#### `List<HeadlessTrain> getTrains()`
追上后返回所有列车。调用者：`LiveSimulation.java:296`、`EventSimulator.publishNetwork`、`RunInvariants.check`。

#### `List<HeadlessTrain> getMovedTrains()`
上个 tick 中移动过且仍在的列车（只有它们可能有变化）。调用者：`EventSimulator.java:341`。

#### `void catchUpAll()`
把所有被放置的列车追到现在（如输入改变它们的依据之前）。调用者：`EventSimulator.java:225`；`removeTracks`、`record`、`getOccupant`、`getTrains`。

#### `void wakeAll()`
所有列车在下一 tick 移动（如输入之后）。调用者：`EventSimulator.java:298`。

#### `private void wake(HeadlessTrain train)`
让被放置的列车在本 tick 移动（若本 tick 尚未轮到它）或下一 tick 移动；正在移动的、已移除的、被接管的不处理。更新列车与停车线的 `dueMillis`。

#### `private RailEntry getEntry(UUID rail)` / `private void addOccupant(UUID rail, Occupant)` / `private void removeOccupant(UUID rail, Occupant)` / `private void railChanged(RailEntry)`
维护轨道占用：占用者按 `Occupant` 顺序插入（第一个即 `Occupancy` 认定的那个）；变化时登记到 `changedRails`，并唤醒观察该轨道的列车。

#### `private boolean isTakenByOther(UUID rail, HeadlessTrain train)`
- 作用：是否有本车以外的东西占着该轨道（同 `Occupancy.isTakenByOther`）：上个 tick 被别人占，或本 tick 被先移动的东西占。放置列车（`aligning`）期间任何他人占用都算。正在计算可放置时长的列车（`quietTrain`）看的是本 tick 结束时大家的占用，并登记为该轨道的观察者。
- 调用者：`HeadlessTrain` 的阻挡判断（第 2100、2407、2435、3155 行附近）。（列出的 `TrainServer.java:591` 等为对 `Occupancy.isTakenByOther` 的调用。）

### 车厂发车

#### `private void deploy(DepotBoard depot)`
- 作用：像游戏车厂一样，为时刻表中第一个无人占用的服务派出一列准备好的列车；按运用运行的车厂派出最先到期的运用。跳过模式下，若没有本 tick 移动过的可出发列车、且（含同伴）没有服务变化、且不按运用运行，只 `startTick` 后返回。收集可出发列车（本 tick 运行过的停车线按 `readyCount`，其余按请求过发车的被放置列车）。
- 调用者：`tick`。

#### `private void deploy(DepotBoard depot, List<SidingRun> ready)`
- 作用：`startTick` 后：按运用运行（`diagramMode`）时调 `dispatcher.findDutyDeploy`，有结果则该停车线的等待列车追上并 `deployDuty`（晚发则 `joinDutyLate`），标记车厂变化并唤醒；只按运用（R2）的车厂不按其他方式发车；没有停车线顺序时按就绪顺序逐个尝试 `deployTrain`；有顺序时像游戏车厂那样从上次位置起轮流尝试。

#### `private boolean deployTrain(DepotBoard depot, SidingRun sidingRun, boolean inTurn)`
- 作用：派出停车线等待的列车：按时刻表时用 `service.findDeployService(本车厂及同伴最后派出的服务)`，按频率时要求 `DepotDispatcher.getMillisUntilDeploy(...) == 0`；路径经过已删除轨道的停车线不再发车。成功则记录发车时刻、推进轮流下标、登记并预订服务、`service.deploy`、允许出发、标记变化并唤醒。
- 参数：`inTurn` 车厂是否轮流使用停车线（同游戏）。
- 返回：是否派出。

## 内部接口 TrainListener<T extends RunTrack>
`void onEvent(MotionEvent event, TickRunner<T>.HeadlessTrain train)`：列车事件回调（列车保持事件时的状态，只能读取）。实现：`DayRun.Recorder::onEvent`、`recordBuffers` 中的 lambda。

## 内部类 Occupant（private static final，Comparable）
在每个 tick 中占用轨道的顺序：外部占用最先，然后按停车线、再按列车 id（停车线按生成顺序保存列车）。字段 `sidingId`、`trainId`；常量 `FIRST`（最前）、`EXTERNAL`（外部占用）、`LAST`（最后）。`compareTo` 先比停车线再比列车。

## 内部类 RailEntry（private final，非静态）
一条轨道：`now`（现在占用者，有序，第一个即 `Occupancy` 认定者）、`before`（上个 tick 末的占用者）、`watchers`（查看过它的被放置列车，占用变化时被唤醒）、`changed`。

## 内部类 Siding<T extends RunTrack>（public static final）
停车线给其列车的东西：常用路径、离开它的服务路径和它生成的列车参数。
- `path`、`distances`（各轨道终点累计距离）、`repeatIndex1`、`repeatIndex2`、`stopTimes`、`tripPaths`、`turnBacks`、`pathVariants`（按需生成跳站等路径，D3；null 则从不跳站）。
- `trainId`（车辆线路键）、`trainCars`、`spacing`、`railLength`、`acceleration`、`deceleration`、`maxTrains`。
- `usesSpeedCurve`（默认 true）、`manual`（可手动驾驶：列车停在停车线直到宿主开出）。
- `maxSpeed`：路径最高物理限速（决定列车最远需要看多远）。
- `minSpeed`（默认 0.5，方块/tick）：该类车辆最低巡航速度；`vehicleMaxSpeed`：车辆最高速度，0 为无。
- `poolKey`、`linePool`、`poolFleet`：见 `SimDataset.SidingData`（池总车数 −1 时 `maxTrains` 即停车线容量）。
- 构造函数：累计距离、求最高物理限速。调用者：`SimDataset.java:408`、`SimDataset.java:587`、`GameChainTest.java:93`。

## 内部类 SidingRun（public final，非静态）
一条停车线及其运行中的列车。
- `sidingId`、`depot`、`siding`（public final）。
- `trains`（List<HeadlessTrain>）：本停车线的列车（按生成顺序）。
- `shed`（ArrayDeque<Long>）：池中存放在本停车线、超出停着那一列的列车 id，最早的在前（P2-C）；`arriving`：本 tick 回来、下一 tick 起存放的列车。
- `waitingTrain`：等待发车的列车；`readyCount`：上个 tick 请求出发的列车数。
- `dueMillis`：下一个有列车移动或可能生成/移走列车的 tick；`tickMillis`：上次运行的 tick。

### 私有构造函数 `SidingRun(long sidingId, DepotBoard depot, Siding<T> siding)`
下一 tick 到期。调用者：`addSiding`。

### `private void tick()`
- 作用：对每列车：（未被接管且到期或不跳过时）`move`，请求出发则登记车厂就绪；统计等待车数；若有列车不在线或还没开出停车线一个车长 + 停车线长，则不生成新车。回到池中另一停车线的列车（`takeReturnedElsewhereSiding`）从本停车线移走、加入目标停车线的 `arriving`（同一列车存入那里的车库，P2-C）；停车线中第二列停着的车被移走，按运用运行时存入车库（`FleetRules.keepsSecondStanding`）。移走的列车登记为已移除，轨道在 tick 结束释放。然后补车：按运用运行或只按运用（R3，即使还没有运用）的车厂，列车属于跨本线路各车厂的池（P2-C）：无车停着时按 `FleetRules.whatToMake` 从车库取最久的或新建；其他车厂在没有列车或（可生成且未超最大车数）时新建一列停着的车。

### `private void addStanding(long id)`
新建一列停在停车线的 `HeadlessTrain`。

### `private int getHeld()`
停着的列车 + 车库 + 本 tick 回来的。调用者：`chooseReturnSiding`、`hasRoom`、`tick`；列出的 `TrainServer.java:1094` 等为 `RunReservations.getHeld`。

### `private void endTick()`
计算停车线与本 tick 移动过的列车下次何时到期：需要每列车都动时（`needsEveryTrain`）全部下一 tick 到期；否则移动过的列车 `updateDue`，停车线到期取各列车最小值。

### `private boolean needsEveryTrain()`
不跳过、按运用运行、没有列车、有两列以上停着、两列车在同一位置（进度相同到 0.1）、或即将生成新车时，下一 tick 所有列车都要动。

## 内部类 HeadlessTrain（public final，非静态）
一列车，代替游戏的服务器列车：保存其状态，并在运动规则、停站规则与服务之间传递；所有决定都由共享规则做出。实现 `MotionState`、`MotionTrack`、`MotionVehicle`、`MotionHooks`、`MotionCurves`、`TrainRules.Train`、`TrainService.Vehicle<T>`。

### 字段
- `id`、`service`（TrainService<T>）、`platformDwell`、`sidingRun`、`siding`。
- 路径：`path`、`distances`、`pathRepeatIndex1/2`（路径环线）、`repeatIndex1/2`（当前循环，末圈为 0）。
- `throatLocks`（咽喉锁，旧规则用）、`requestedMoves`（运行申请内容，宿主无运行器 tick 时重新申请）。
- 运动状态：`speed`、`railProgress`、`doorTarget`、`doorValue`、`elapsedDwellTicks`、`nextStoppingIndex`、`nextPlatformIndex`、`reversed`、`onRoute`、`manualNotch`、`runSegment`。
- `canDeploy`（车厂已允许出发）、`requestedDeploy`（本 tick 请求出发）。
- `cruiseCap`：出发时的巡航上限。
- `turnBackRails`、`turnBackUntil`：保留的折返轨及保留到哪条轨道。
- `grantedRunEnd`、`requestedRunEnd`：已获批/已申请的运行终点轨道（旧规则单线预订）。
- `waitingSinceMillis`：开始等待所申请运行的时刻，或 0（D2）。
- `eventSequence`：事件序号。
- `hostHold`：宿主扣车原因或 null；`holdReason`：上次 `getHoldReason` 的结果；`railBlockedLast`：上次是否被前方轨道挡住。
- 曲线缓存：`curves`、`curvesPath`、`cappedRun`、`cappedCap`、`cappedProfile`。
- `occupant`（占用顺序）、`syncedMillis`（状态对应的时刻，被放置时早于现在）、`dueMillis`、`movedMillis`、`removed`、`takenOver`（宿主接管，不移动，G0）。
- `heldRails`（所占轨道）、`watched`（计算可放置时长时查看过的轨道）、`blockWatch`（所在信号闭塞区的轨道，变化时唤醒）、`blockedRail`（被挡的轨道或 −1）。
- `recordedTrips`、`standingTrips`：上个 tick 预订的服务，以及被放置期间为它保留的服务。
- `standing`（Standing）：在站静止被放置时依据的状态。
- `authority`（run.Authority）、`authorityHead`、`authorityEnd`：运行层（5.5）行车许可及上次更新时的车头与可运行终点。
- `restoredSegment`：续跑记录中的运行段，第一个 tick（曲线就绪后）重建。

### 私有构造函数 `HeadlessTrain(long id, SidingRun sidingRun)`
设占用顺序、同步与到期时刻、停车线常用路径与循环，新建 `TrainService`（时钟为运行器时刻）、设停车线与网络，新建 `Authority`（共享运行器当前的预订）。调用者：`restore`、`place`、`addStanding`。

### `boolean isTakenOver()`
调用者：`EventSimulator.java:163`。

### `private RunRecord.TrainRecord toRecord()`
按游戏格式生成记录：服务写入 `BinaryKeyed`，含巡航上限、许可记录终点、运行段描述。调用者：`record`。（列出的 `LiveSimulation.java:350`、`TrainServer.java:939` 为同名方法。）

### `private boolean restore(RunRecord.TrainRecord trainRecord)`
- 作用：按游戏读回的方式取回列车状态；恢复许可（有记录时）与运行段；读服务、设置停站时间与变体；路径已不存在时，在线列车返回 false（无法继续），停在停车线的列车放掉路径照常停着（0.66.2）；下一停站越界时，在线返回 false，否则重置（完成运用后遗留的下标不描述停车线路径）；没有保存巡航上限的行驶中列车从当前位置重算；`restoreDoors`。
- 调用者：`TickRunner.restore`。

### `private void restoreDoors()`
按已停站时间恢复站台停站列车的车门（0.66.1）：记录没有车门，若当作关闭会立刻再开，游戏中显示为关了又开；仿真器每半分钟重启时，开着门停着的列车每次都响关门音。开门期间按已停时间求开度；已过开门期则按关门开始以来的时间求关闭程度。

### `private void placeAt(double newRailProgress, float newSpeed)`
宿主交还列车时放到路径某处并设速度：在线、清运行段与停站计时、关门、重新求下一停站与巡航上限、清运行申请；静止但不在停站点的列车给爬行速度（像从站出发那样起步）；同步到现在、下一 tick 到期。调用者：`release`。

### `void setHostHold(String reason)` / `String getHostHold()`
宿主扣车（在下一停站开门停住），null 放行。调用者：`EventSimulator.java:230`、`RunInvariants.java:141`。

### `double getAuthorityEnd()` / `double getRecordAuthorityEnd()` / `RunSegment.Spec getRecordSegment()`
运行层上次允许运行到的位置 / 记录用的许可终点（含尚未取走的批准，或 NaN）/ 记录用的运行段（静止也有）或 null。（`TrainServer.java:568`/`:570` 调用的是 `TrainServer` 自己的同名方法，未确定。）

### `int getHeadRail()`
车头所在路径轨道（停在轨道末端时为该轨道）。调用者：`EventSimulator.java:163`、`SimulateDay`、`RunInvariants`。

### `private void move()`
本 tick 移动列车：先追上被放置的 tick（到上个 tick 末），标记正在移动、记录移动 tick、`trainTicks++`，设置停站时间与变体，`simulate`，同步到现在，`writePositions`，加入已移动列表；有站立服务时登记释放。调用者：`SidingRun.tick`。

### `private void catchUp(long toMillis)`
把被放置的列车带到该时刻，如同每 tick 都移动过（期间只沿运行段前进或静止）：临时把运行器时钟设为目标时刻后 `skip` 相应 tick 数。被接管的列车只更新同步时刻。

### `private void simulate()`
清请求出发与被挡轨道；`service.updateOvernight`；运行层开启且非缆车时 `runLayerTick`，否则 `TrainMotion.tick(…, 1)` + `holdRun`。然后在记录模式下 `service.update(onRoute)`，把本 tick 预订的服务记下（被放置的每个 tick 都要再预订）；变化时标记车厂变化。

### `private void runLayerTick()`
- 作用：运行层（5.5）的一个 tick：先求从车头往目标方向的行车许可，再沿运行段移动但不超过许可。
  1. 有续跑记录的运行段时，用同一条曲线重建并推进到上个 tick 末；只有列车确实在曲线所说位置（±1E-3）才采用（长时间停机后从所在处重新出发，而不是沿曲线跳跃）。
  2. 车头 = 在线时为进度，否则为停车线中央。在线或在执行运用时：求下一停站（`RunMotion.findNextStop`）；在站静止时目标为再下一站；`PathClaims.Path` 用当前路径与累计距离，折返由 `isOppositeOf` 判断；站咽喉的道岔与联锁区（L2）作为 `Attached` 随带列车经过它的分区一起声明；在站且按时刻/调度等原因需要等待（`TrainRules.waitsBeforeRequestingRun`）时目标为车头本身；保持一节车厢的前后间隔（用户 2026-11-12，`setMargin(spacing)`）；`authority.update(…)` 得到 `authorityEnd`。在站想走但许可未超过车头时开始记等待时间（D2），否则清零。不在线且不在运用中时释放许可。
  3. `RunMotion.tick(…, authorityEnd)`；运行后若不在线且不在运用则再释放。

### `private void writePositions()`
- 作用：写出列车所占轨道：车尾到车头间每条（不是本停车线、且有信号的）轨道上覆盖的闭塞单元（整条或长轨道的若干片，`getCoveredUnits`）；若某单元属于信号闭塞区，则在该闭塞区所有单元都没被别人占时把整个分区都占上（同游戏信号），并观察该分区；保留的折返轨在车尾过 `turnBackUntil` 或下线前也占着。与上次不同则增删占用。
- 调用者：`move`、`place`、`recover`、`HeadlessTrain` 其他处。

### `private void dropRails()` / `private void unwatch()`
释放所有占用并停止观察（列车被移走时）/ 从观察的轨道中注销。

### `private void dropStandingTrips()`
释放被放置期间为它保留的服务。调用者：`tick`、`takeOver`。

### `private void updateDue()`
- 作用：在移动过的 tick 末算出下一个必须移动的 tick：跳过模式下以 `quietTrain` 身份计算 `getQuietTicks`（同时登记查看过的轨道和所在闭塞区为观察对象）；到期 = 现在 + (静默 tick + 1)；若之后不是下一 tick，则把本 tick 预订的服务登记为站立服务，在此期间保持预订。
- 调用者：`SidingRun.endTick`。

### `private long getQuietTicks()`
- 作用：接下来多少个 tick 中除了沿运行段的位置或已停时间外没有任何变化，或 0。运行层下每列车每 tick 都动，返回 0（这些预测针对沿用 MTR 的规则，5.5）。不用速度曲线、无路径、运用列车、按运用运行的车厂、尚无停站时间的新车都返回 0。不在线：已允许出发 0；手动停车线永远（只等宿主开出）；按频率发车的车厂到下一发车前；按时刻表的车厂到 `service.getNextDeployMillis` 之前；其他永远。在线：下一停站越界或已到回库点返回 0；静止 `getStandingQuietTicks`；车门在动返回 0；否则 `getMovingQuietTicks`。

### `private long getStandingQuietTicks()`
静止列车：没有停站时间（停在被占轨道前）时，被挡且未开门则永远（直到前方有东西离开），否则 0；循环列车 0；否则用 `Standing.step` 逐 tick 推演（最多 `MAX_STANDING_TICKS`），直到需要实际移动为止。

### `private long getMovingQuietTicks(int trainLength)`
行驶列车：没有运行段、段已结束、下一停站不等于下一站台、或循环时 0。否则界限 = min(车头所在轨道下一分区起点, 停车位置, 车尾所在轨道下一分区起点 + 车长, 离开停车线的门槛, 回库点) − 1E-3；二分求段上在界限前能走的 tick 数；然后检查在此期间制动可达范围（按路径最高速度）内是否有被占轨道或车头所在轨道前方被占的分区（下一条轨道总要检查），有则 0。

### `private void skip(long ticks)`
- 作用：把被放置的列车直接推进若干 tick（只在线时）：静止且有停站时间时逐 tick 用 `Standing.step` 累加已停时间（若某 tick 需要实际移动则抛 `IllegalStateException("Train … was left alone through a tick that changed it")`，表示预测有误）；车门每 tick 移动 1/64，一次加总；行驶时运行段推进 ticks，取位置与速度，手柄按最后一 tick 是否加速设 2/0。
- 调用者：`catchUp`。

## 内部类 Standing（private final，属于 HeadlessTrain）
在站静止的列车所依据的、在静止期间不变的东西：停站时间、计划发车、扣车原因、前方是否被占（S4f）。据此按 `TrainMotion` 与 `TrainRules` 的规则逐 tick 推演，比实际移动快得多。
- 字段：`dwellTicks`（路径停站秒 × 10）、`planned`、`departureMillis`、`timed`（定时停站）、`atPlatform`、`blocked`（同运动规则：前方要驶入的轨道开头被占，或本轨道前方紧邻分区被占）、`hostHeld`、`heldByClock`（询问是否被扣不改变任何东西且只取决于时钟：被宿主扣，或非留线、无运用发车、非运用、不可能在此选择去向）。
- 构造函数：从当前状态求上述值。
- `private int getTotalDwellTicks(float elapsed, long atMillis)`：有计划时按 `TrainRules.getTotalDwellTicks` 缩短，否则停站时间。
- `private float step(float elapsed, long atMillis)`：静止列车一个 tick 的 `TrainMotion`：返回该时刻 tick 之后的已停时间；若该 tick 必须实际移动（第一个停站 tick、进入关门阶段而扣车不只依赖时钟、车门开关变化、或停站结束且前方不堵）返回 −1。

### `private boolean isRailOccupied(int index)`
路径第 index 条轨道是否被占：已删除，或其任一闭塞单元被他人占用。调用者：`getQuietTicks`、`Standing`、`isRailBlocked` 等。（列出的 `TrainServer.java:443` 等为同名方法。）

### `private List<UUID> getCoveredUnits(int index, double fromPosition, double toPosition)`
路径某轨道上被路径某段覆盖的闭塞单元（轨道键或长轨道的若干片）。

### `double getSectionBlockedPosition(int rail, double fromPosition)`（覆盖 `MotionHooks`）
- 作用：对被切成闭塞分区的有信号轨道，返回从 `fromPosition` 起第一个被他人占用的片的起点（路径位置），使列车可以跟进到那里；单片轨道或前方片都空闲时 NaN（整条是否被占由 `isRailBlocked` 判断）。……（实现续见下一部分。）
- 调用者：`TrainMotion.tick`、`TrainMotion.isSectionTakenJustAhead`、`SimulateDay.java:210`。
（`getSectionBlockedPosition` 续）实现：首条轨道、越界或无信号轨道为 NaN；否则按 `BlockSections.getPieceStarts` 逐片找起点 ≥ `fromPosition` 且被他人占用的片（所在的片属于自己）。

### `private double getNextSectionStart(int rail, double position)`
该轨道上位置之后下一个闭塞片的起点（路径位置），没有则轨道终点。调用者：`getMovingQuietTicks`。

### `boolean isOnRemovedTrack()`
车尾到车头所在轨道中是否有已删除的。调用者：`EventSimulator.java:315`。

### `int findRemovedRail()`
从车尾（不在线时从路径开头）起第一条已删除的轨道，或 −1。调用者：`EventSimulator.java:311`、`deployTrain`。

### `private void holdRun()`
- 作用：旧规则（非运行层）下运用列车持有其运行：不在线、不在运用或无路径时释放；获批终点落后于下一站台则作废；持有从车尾到 max(下一站台起的运行终点, 已获批终点) 的双向轨道（`getRunMoves`）；有咽喉锁时，不在等待离站申请期间 `locks.approach` 申请进站进路，并加上未越过的已获批道岔；`reservations.hold`。
- 调用者：`simulate`。

### `private int getTailIndex()`
车尾所在路径轨道。

### `private ThroatLocks getThroatLocks()`
- 作用：路径的咽喉锁（路径变化时重建）；咽喉锁关闭、不在运用、无路径或车厂不允许（与非运用车厂共轨）时为 null。停站轨道为停车线轨道和有停站时间的站台轨道；节点相交轨道数取自各停车线路径的邻接表，并加入本车自己的路径（可能走到没有停车线路径经过的地方，如回送到另一停车线）；联锁区取 `zoneOfRail`。
- 调用者：`runLayerTick`、`holdRun`、`isRunGranted`、`isRailBlocked`。

### `private void renewReservations()`
见 `TickRunner.renewReservations`：未被接管且未移除时重新持有所持，并重新申请等待中的运行。

### `private List<RunReservations.Move> getRunMoves(int runEnd)`
从车尾到运行终点的双向轨道（带方向）；若列车在运行终点所在站折返，也带上折返出去的那条轨道（掉头车优先：后车申请该站时会在站外等，而不是跟进来堵住它的出路）。

### `TrainRules.Train` 实现
- `long getMillis()`：运行器时刻。
- `boolean isBlockingOthers()`：`reservations.isBlockingOthers(id)`。
- `long getEarlyThresholdMillis()`：运行器的早到阈值。
- `TrainService<?> getService()`、`MotionState getState()`（自身）、`MotionTrack getTrack()`（自身）。
- `long getStopId(int rail)`、`boolean isContinuousMovement()`（恒 false）、`boolean usesSpeedCurve()`。
- `SpeedCurves getSpeedCurves()`：路径变化时用 `RunTrack.toTrackView` + `SpeedCurves.of` 重建（起点停车线中央、车长、加/减速度），并清除限速曲线缓存。
- `boolean isOvertakingTrainComing(...)`：恒 false（不跟踪越行列车）。
- `long getWaitingMillis()`：已等待所申请运行多久，或 0（D2）。调用者：`RunInvariants.java:244`。
- `boolean isRunGranted(int fromIndex)`：运行层下，只要许可超过车头一部分即可走；旧规则下：已获批同一运行终点返回 true；申请过且 `takeGrant` 成功则记为获批（并让咽喉锁 `onGranted`）返回 true；否则（重新）申请从车尾到运行终点的运行，连同离开本站咽喉的出站进路（全有或全无）与未越过的已获批道岔，开始记等待，返回 false。

### `MotionState` 实现
`getSpeed`/`setSpeed`、`getRailProgress`/`setRailProgress`、`getDoorTarget`/`setDoorTarget`、`getDoorValue`/`setDoorValue`、`getElapsedDwellTicks`/`setElapsedDwellTicks`、`getNextStoppingIndex`/`setNextStoppingIndex`、`getNextPlatformIndex`/`setNextPlatformIndex`、`isReversed`/`setReversed`、`isOnRoute`/`setOnRoute`、`getManualNotch`/`setManualNotch`、`getRunSegment`/`setRunSegment`：直接读写字段。`isCurrentlyManual()`：同游戏，可手动驾驶的列车在停车线中视为手动驾驶。调用者：`TrainMotion`、`RunMotion`、`EventSimulator.query`、`LiveSimulation`、`RunInvariants`。

### `RunSegment.Spec getSegmentSpec()`
按运行器时钟描述列车当前运行段（G2）；没有段、不在线、被接管、静止、段已结束或列车不在段所说位置时为 null（如在停车线或刚出发、下一 tick 才开始新段）。调用者：`DepotReturnTest.java:44`、`SegmentSpecTest.java:42`。

### `MotionTrack` 实现
- `size()`、`getDistanceCount()`、`getDistance(int)`：路径轨道数与累计距离。
- `getStopOffset(int rail)`：有停站时间的轨道按车长求 `track.getStopOffset`，否则 0；`getStopDistance(int)`：终点减偏移。
- `getTrainLength()`：车厢数 × 车厢长。
- `getDwellTime(int)`、`canAccelerate(int)`（恒 true：第 3b 轮起每条轨道都按自身限速，`canAccelerate` 只决定是否切分闭塞片）、`getMaxSpeed(int)`（`RunTrack.runSpeed`，含车辆最高速度）、`isOppositeRail(int, int)`、`getRepeatIndex1/2()`。

### `MotionVehicle` 实现
`getTrainCars`、`getSpacing`、`getRailLength`、`getAcceleration`、`getDeceleration`：取自停车线；`getDefaultMaxSpeed()`：min(最低巡航速度, 车辆物理最高速度)；`getManualMaxSpeed()`：最低巡航速度。

### `MotionHooks` 实现
- `isClientSide()`：false。
- `getTotalDwellTicks()`：`TrainRules.getTotalDwellTicks(this, 停站秒 × 10)`。
- `canDeploy()`：路径多于一条轨道时第二条未被占（单轨道路径则在按运用运行的车厂中）即请求出发（不能出发的列车跳过本次发车而不是排队，使时刻表不漂移；按运用时停车线本身就是路径，运用到期后沿运用路径出发——端到端检查发现这种列车从不请求出发）；运用列车还需出库运行获批（`isRunGranted(1)`）。
- `startUp(boolean isOppositeRail)`：上线、清停站计时、给爬行速度；折返时车头前移 `getReversalAdvance` 并反向；求下一停站与巡航上限，关门。
- `keepsOnLineAtEnd()`：`service.endsOnLine()`；`clearRiders()`：空实现（无乘客）；`isRepeat()`：当前循环下标都 > 0。
- `onLapComplete()` / `onStopReached()`：转给服务。
- `onDwell(int rail, float departureTicks)`：在站台（且不在空车运行中）时更新 `PlatformDwell`。
- `PlatformDwell getPlatformDwell()`：调用者：`TrainServer.java:569`（同名方法，未确定）。
- `isHeldAtStop()`：宿主扣车为 "host"，否则 `TrainRules.getHoldReason`；保存原因。`String getHoldReason()`：上次的原因（0.65.31）。`boolean wasRailBlocked()`：上次前方是否被占（0.65.31）。
- `isRailBlocked(int rail)`：进入下一站咽喉的那条轨道在进路未获批前视为被占（咽喉锁）；否则 `isRailOccupied`；记录被挡轨道。
- `openDoors()`：`TrainRules.openDoors(this)`。
- `emit(MotionEvent.Type type, int rail)`：生成 `MotionEvent`（当前时刻、序号、运行段版本），加入事件列表并通知监听者。

### 其他访问器
`long getSidingId()`、`List<UUID> getTurnBackRails()` / `int getTurnBackUntil()`（为折返保留的轨道及保留到车尾到达哪条）、`DepotDispatcher getDispatcher()`（车厂的已派服务与运用运行日，同游戏车厂保存的）、`DepotBoard getDepot()`、`int getBlockedRail()`（上次移动时等待的轨道，或 −1，同游戏标记延误）。调用者：`LiveSimulation.java:608`/`:629`、`RunInvariants`、`SimulateDay`。

### `MotionCurves` 实现
- `SpeedProfile getProfile(double position)`：所在 run 的曲线；巡航上限有限时用 `run.withCap(cruiseCap)`（缓存）。
- `SpeedCurves.Run getRun(double position)`、`double getCruiseCap()`。

### `TrainService.Vehicle` 实现
- `getPath()`、`getPathRepeatIndex1/2()`。
- `setLastLap(boolean)`：末圈时当前循环下标置 0，否则恢复路径环线。
- `followPath(TripPath<T> tripPath)`：换路径（null 为停车线常用路径）及其距离、环线；运行是路径上的轨道，所以旧路径上获批的运行作废、重新申请；宿主可能在运动再次运行前就投射本 tick，所以停在停车线的列车随路径一起重新放到停车线中央（`RunMotion.parkAtSiding`）；不在线且下一停站越界时重置。
- `changePath(TripPath<T> tripPath, int newStopIndex)`：保持到停车点的距离换到新路径同一站。
- `isTurnBackFree(List<UUID>)`：这些轨道都未被他人占用；`reserveTurnBack(List<UUID>, int)`：保留。
- `isWayClear(List<UUID>)`：空闲且无他人持有（含其间道岔）。
- `isStopFree(int)`：从该站向后整列车长度内的轨道都未被占。
- `moveToStop(int)`：按途中折返次数确定方向，放到停车位置，静止，清停站计时与运行申请。
- `moveBetweenStops(int fromStop, int toStop, double, float)`：先放到前站再按途中折返调整方向，放到给定位置与速度，在线，运行段从所在处重算（同宿主交还），下一停站为 `toStop`；返回 true。
- `returnToDepot()`：下线、手柄 −2、速度 0。
- `getAffectedRails(List<UUID> remaining)`：仍需运行的轨道，加上持有其中任一轨道的其他列车所持的全部轨道（不含道岔与联锁区），即列车被拦之处（D5）。

### `private void deployDuty(int slot, long dutySidingId, long dayStart, TripPath<T> dutyPath, int startSegment)`
允许出发并 `service.deployDuty`。调用者：`deploy(DepotBoard, List)`、`place`（经 `service`）。

### `private void joinDutyLate()`
晚发列车放到仍能赶上的运用首站；没有空站则留在停车线；上线后写占用。调用者：`deploy`。
