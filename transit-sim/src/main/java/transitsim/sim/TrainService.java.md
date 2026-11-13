# TrainService.java

列车运行哪个服务，以及服务结束时做什么：接下一个服务、换线路、绕环线一圈、直通运行到另一线路、重启后与时刻表对齐。按运用运行时还负责运用的分段、留线过夜、回送（回库）选择、跳站（D3）、提前折返（D4）、交接给备用车（D5）和调度员命令（D6）。移动列车交给 `Vehicle`，所以不需要世界就能测试整天的运行。

在整体中的位置：游戏侧 `TrainServer`（`:206`/`:225`/`:267` 构造并 `setSiding`，以及大量调用）、`Siding`、`Depot`（实现 `Board` 接口）、`DelayAlarms`、`DispatchConsole`；组件 `TickRunner.HeadlessTrain`（`TickRunner.java:1792` 构造，实现 `Vehicle`）、`DepotBoard`（实现 `Board`）、`TrainRules`（`getHoldReason` 调用各 `choose*`）、`DayRun`、`EventSimulator.apply`（`setSkipPlatforms`、`setTurnBack`、`setTakeover`、`dispatch`）。大量使用 plan 包：`DepotDuties`、`DutyShapes`、`OperatingDay`、`PathVariants`、`PlanRoute`、`PlanTrack`、`PlanVersions`、`RouteTimetable`、`StopTimes`、`TimeSegment`、`TripPath`、`TripTail`、`TurnBackPoint`、`TurnBacks`、`DutyLedger`、`PathKeyIndex`、`Contingency`。

## 类 TrainService<T extends PlanTrack>（public，非 final）

### 字段（状态）
- `trainId`（String）：车辆线路键（如 `"siding:id"`），用于向车厂查询线路/时刻表。
- `vehicle`（`Vehicle<T>`）：宿主列车（移动、路径、停站时间等）。
- `clock`（LongSupplier）：时刻表计时的当前时刻。
- `depot`（Board）、`routes`（线路查询）、`routeDepots`（线路 → 运行它的车厂）：由 `setSiding`/`setNetwork` 设置。
- `tripMillis`：当前服务在首站的计划到达（完整时间戳），无时刻表为 0。
- `tripRouteId`：当前服务的线路。
- `routeStartMillis`：当前服务的线路开始时刻（与停站计划同一时间系）；折返后的线路有自己的服务。
- `nextTripMillis`：本圈之后要运行的服务，提前预订以免车厂再派车。
- `isLastLap`：已无服务，本圈后离开环线回车厂。
- `isJoiningLoop`：从环线中途加入，正绕向首站。
- `needsAlignment`：刚从存档读入，停机时间尚待处理。
- `stopTimes`、`depotStopTimes`：当前路径与车厂常用路径的停站时间。
- `tripPath`、`tripPathKey`：不在车厂常用路径上时所在的路径（直通到另一线路，或折返点的另一种走法）；常用路径保留以便返回。
- `linkedService`、`linkedRouteId`：直通的下一线路及其服务（−1 为无）。
- `tripPaths`（Map<String, TripPath<T>>）：停车线的各服务路径。
- `turnBacks`（TurnBacks）：停车线的折返设置。
- `turnBackStop`：已进入其后折返点折返轨的停站，或 −1。
- `tripRoutes`（List<PlanRoute>）：本次运行的线路序列。
- 计划快照（S3d，修复 R04）：`planRoutes`、`planTimetable`、`planTripMillis`、`planRouteId`、`planTripPath`、`planStopTimes`、`planDutyIndex`——列车接服务时的线路与时刻表；时刻表、例外或停站的编辑不影响它，列车在换服务、换线路、换运用分段、换路径或回车厂时取最新计划。
- `sidingId`：所属停车线。
- `dutySlot`：按运用运行的车厂中，列车正在执行的槽位，或 −1（设计文档 6.12 E2）。
- `overnightHold`（HoldReason）：在线上等待且没有可继续运用的原因，或 null。
- `dutySidingId`：其槽位所属停车线（同池他线代发时），自己的为 0（解耦文档 D 步"车厂发车 4"）。
- `dutyId`：所运行运用的编号，随存档保存，运用被移动后仍能找回（P2-D）；未知为 0。
- `returnChosenStop`：选择运用后回哪条停车线的停站（只选一次），或 −1。
- `returnedElsewhere`、`returnedToSidingId`：回到了同池其他停车线；自己的停车线放掉它，那条停车线就地生成一列替代。
- `dutyDayStart`：该运用所属运营日开始（完整时间戳）。
- `dutyIndex`：当前所在运用分段。
- `dutyDay`（DayDuty）、`dutyLedger`（DutyLedger.Entry）：接运用时那天运用的拷贝（修复 F2、R04），编辑运用不改变执行中的运用；以账本键保存，重启后再读。
- `isLeavingForDuty`：已派出执行运用但仍在停车线内、等待出库运行获批；期间保留运用（不同于回到车厂的列车会结束运用）。
- 跳站（D3）：`skipPlatforms`（宿主告知的应通过不停的站台）、`skipChosenStop`、`pathVariants`（按需生成变体路径，null 则从不跳站）、`skipPath`（最后生成的跳站路径）。
- 提前折返（D4）：`turnMode`（0 不折返、`Contingency.TURN_NEAREST` 最近可折返站、`TURN_STATION` 在 `turnPlatforms` 之一）、`turnPlatforms`、`turnAfter`（之后继续运用 `AFTER_RESUME` 或空车回库 `AFTER_DEADHEAD`）、`turnChosenStop`、`turnUnreachable`（被要求在无法折返的车站折返，直到本分段结束）、`turnPath`。
- 调度命令（D6）：`dispatchFlags`（`DISPATCH_HOLD` 扣在下一站直到放行、`DISPATCH_CLEAR_OUT` 在下一站清客空车回库、`DISPATCH_RETURN` 本分段后回库）、`dispatchTurnPlatforms`（折返车站站台）、`extraDwellMillis`（多停多久）、`holdUntilMillis`（多停到何时）、`dispatchChosenStop`。
- 交接（D5）：`takeover`（被要求把剩余运用交给备用车）、`handOverChosenStop`、`handOverFailure`（上次失败原因 `TAKEOVER_NO_SPARE` / `TAKEOVER_CROSSES` 或 0）。

### 常量
- `DISPATCH_HOLD = 1`、`DISPATCH_CLEAR_OUT = 2`、`DISPATCH_RETURN = 4`：调度标志位。
- `COMMAND_HOLD = 1`、`COMMAND_RELEASE = 2`、`COMMAND_EXTEND_DWELL = 3`、`COMMAND_TURN_AT = 4`、`COMMAND_CLEAR_OUT = 5`、`COMMAND_RETURN = 6`、`COMMAND_CANCEL = 7`：`dispatch` 的命令。
- `TAKEOVER_OK = 0`（备用车在路上，可以且已接管）、`TAKEOVER_NO_SPARE = 3`（没有能及时出发的备用车或车厂无车）、`TAKEOVER_CROSSES = 4`（备用车出库会经过晚点列车被拦或尚需运行的地方，D5）。
- `KEY_*`（private）：存档键名：`trip_millis`、`next_trip_millis`、`is_last_lap`、`trip_route_id`、`route_start_millis`、`is_joining_loop`、`trip_path`、`linked_service`、`linked_route`、`duty_slot`、`duty_day_start`、`duty_index`、`duty_day`（运用拷贝，现为账本键）、`duty_siding`、`duty_id`、`skip_platforms`（D3）、`turn_back`（D4：模式、之后、站台）、`takeover`（D5）、`dispatch`（D6：标志、多停、扣到、站台）。
- `SAVED_KEYS = 19`：`write` 写出的值个数，宿主据此计算存档大小（有测试检查，曾少写一个）。
- `MILLIS_PER_TICK = 50`（private）。
- `DEPLOY_MARGIN_MILLIS = 15000`（private）：列车力求提前这么多到达首站，使途中小延误不致晚点。

### 构造函数 `TrainService(String trainId, Vehicle<T> vehicle, LongSupplier clock)`
调用者：`TrainServer.java:206`、`TickRunner.java:1792`。

### `void read(KeyedReader reader)`
- 作用：从存档读回状态：路径键经 `PathKeyIndex.resolve` 解析；跳站、折返、调度、交接各项；有槽位时用 `DutyLedger.read` 读回运用拷贝（旧存档没有则以后从车厂重读）；存档时有运用的列车视为"正在出库执行运用"（回车厂的列车会立即结束运用）；`tripMillis != 0` 时需要对齐；最后 `setLastLap`。
- 调用者：`TrainServer.java:235`、`TickRunner`（恢复记录时）。

### `void write(KeyedWriter writer) throws IOException`
- 作用：写出 19 个值；路径键写为 `PathKeyIndex` 条目的键（并把内存中的键规范为条目文本）；运用写为账本键；折返与调度没有时写空数组。
- 调用者：`TrainServer.java:528`、`TrainServer.java:946`。

### 调度员命令（D6）

#### `void dispatch(int command, long value, Set<Long> platforms)`
- 作用：执行调度员命令。扣车和多停在列车所在或将到的站生效；折返、清客、回库在列车于某站选择时生效，并优先于预案。`COMMAND_HOLD` 置扣车位；`COMMAND_RELEASE` 清扣车位、多停与扣到时刻；`COMMAND_EXTEND_DWELL` 多停 `value` 毫秒（夹到 [0, `MAX_EXTRA_DWELL_MILLIS`]）；`COMMAND_TURN_AT` 设折返车站站台（空则取消）并重新选择；`COMMAND_CLEAR_OUT` 置清客位并重新选择；`COMMAND_RETURN` 置回库位；`COMMAND_CANCEL` 清除全部。
- 调用者：`DispatchConsole.java:153`、`EventSimulator.apply`、`DutyRunTest.java:1433`。

#### 常量 `MAX_EXTRA_DWELL_MILLIS = 30 分钟`
调度员可让列车多停的上限。

#### `boolean hasDispatch()`
有任何调度命令（标志、折返站台、多停、扣到时刻）。调用者：`write`、测试。

#### `int getDispatchState()`
调度状态：`DISPATCH_` 标志位，加 8 表示多停中（或待多停），加 16 表示有折返车站。调用者：`DelayAlarms.java:134`、`DispatchConsole.java:99`。

#### `Set<Long> getDispatchTurnPlatforms()`
调度折返站台（不可修改视图）。

#### `boolean isDispatchHeld()`
- 作用：调度员是否把列车留在当前站（D6）：被扣直到放行，或多停（从本站开始计时）。有待多停时把它转为"扣到 现在 + 多停"；扣到时刻已过则清零。
- 副作用：修改 `extraDwellMillis`、`holdUntilMillis`。
- 调用者：`TrainRules.java:61`、`TrainRules.java:87`、`TrainRules.java:147`。

#### `boolean chooseDispatch()`
- 作用：运用列车在站准备出发时执行清客或回库命令（每站一次）：
  - 清客：若另有折返车站或折返预案，交给 `chooseEarlyTurn`；否则尝试在下一停站折返并空车回库（`turnAt(TURN_NEAREST, …, false, 下一停站, …)`）；不行则从下一停站起空车跑完本分段再回库。
  - 回库：跑完本分段到末站，全员下车，空车回停车线。
  - 已在回库或已折返的路径上则清除命令；本分段末站已过、或最后一个分段（且清客无意义）时清除命令不做事。
  - 否则取 `TripPath.getEndKey(base, 分段, 末站轨道, 清客 ? 下一站 : 末站)` 路径，换上（`vehicle.changePath`）。
- 返回：刚换了路径（列车在站稍等）为 true。
- 调用者：`TrainRules.java:184`（`getHoldReason` 的 "dispatch_way"）。

### 交接给备用车（D5）

#### `void setTakeover(boolean newTakeover)` / `boolean getTakeover()`
设置/读取是否把剩余运用交给车厂的备用车；改变时重置选择站与失败原因。调用者：`EventSimulator.apply`、`DelayAlarms.java:88`、`DelayRules.getTakeover` 的调用方。

#### `int getTakeoverState()`
0 未要求；1 等待选择在哪交接；2 已交接正回库（路径为 end 键）；`TAKEOVER_NO_SPARE`/`TAKEOVER_CROSSES` 上次尝试失败。调用者：`DelayAlarms.java:126`、`DispatchConsole.java:98`。

#### `boolean chooseHandOver()`
- 作用：被要求交接的运用列车在站准备出发时（每站一次），从当前分段起找一个分段，使备用车能及时出库到下一分段首站，且出库路径不经过本车被拦或仍需运行的地方（由 `vehicle.getAffectedRails` 从当前到该分段末站的轨道求得）。向车厂 `depot.requestTakeover` 申请；成功则本车改走 `TripPath.getEndKey(base, 分段, 末站)`：跑到该分段末站、清客、空车回库；失败记下原因（任一分段为 CROSSES 则为 CROSSES，否则 NO_SPARE）。
- 返回：刚换路径时 true。
- 调用者：`TrainRules.java:185`（"hand_over"）。

### 提前折返（D4）

#### `void setTurnBack(int mode, Set<Long> platformIds, int after)`
设置提前折返（`mode` 0 取消）；每个运用只折返一次，在站选择，不在两站之间。变化时重置选择站与"无法到达"标志。调用者：`EventSimulator.apply`、`DutyRunTest.java:1218`。

#### `int getTurnMode()` / `int getTurnAfter()` / `Set<Long> getTurnPlatforms()`
访问器。调用者：`DelayAlarms.java:85`、`DelayAlarms.java:117`。

#### `int getTurnState()`
0 未要求；1 正前往折返处；2 已折返（路径为 turn 键且有折返轨道）；3 被要求在无法折返的车站折返。调用者：`DelayAlarms.java:120`、`DispatchConsole.java:97`。

#### `boolean isClearingOut()`
列车下一停站即提前折返处（全员在此下车，D4）。调用者：测试；宿主用于清客（推测）。

#### `boolean isOutOfService()`
- 作用：列车是否空车运行：从提前折返处到继续运用处（或回停车线）之间。在继续运用处开始下一分段后结束。
- 调用者：`TrainServer.java:316`、`TrainServer.java:683`、`DispatchConsole.java:96`。

#### `boolean chooseEarlyTurn()`
- 作用：被要求提前折返的运用列车在站准备出发时（每站一次），若路径生成找到了在指定站到另一站台的绕行路径，就从此处起改走本分段在该站折返的运用路径（D4）。继续运用时在到达的下一分段某站加入并等该服务；否则空车回停车线。分段末站不算（运用本就在那里折返）。调度员指定的折返站（D6）优先于预案；被要求清客时不继续运用。成功后清除调度折返指令。在指定车站无法折返时置 `turnUnreachable`。
- 返回：刚换路径时 true。
- 调用者：`TrainRules.java:186`（"early_turn"）。

#### `private boolean turnAt(int mode, Set<Long> platforms, boolean resume, int onlyRail, int stopIndex)`
- 作用：换到运用在本分段（从当前站起）第一个有绕行路径的停站折返的路径：站台须在指定车站站台中（`TURN_STATION`），或只在 `onlyRail`。继续运用时，目标为下一分段（末站之前）第一个能从此处绕到的站台，键 `TripPath.getTurnKey(base, 分段, 轨道, 站序, 下一分段轨道, 站序, true)`；否则目标为有回库路径的折返目标站台，键 `getTurnKey(…, 目标站台, 0, false)`。用 `findPath` 生成并换上。
- 调用者：`chooseDispatch`、`chooseEarlyTurn`。

### 跳站（D3）

#### `void setSkipPlatforms(Set<Long> platformIds)` / `Set<Long> getSkipPlatforms()`
设置/读取从下一停站起应通过不停的站台（空集合恢复按计划停站）；在站切换，不在两站之间。调用者：`EventSimulator.apply`、`DelayAlarms.java:79`、`DelayRules.getSkipPlatforms` 的调用方。

#### `boolean isSkipping()`
当前路径是否为跳站路径（skip 键）。调用者：测试。

#### `void setPathVariants(PathVariants<T> newPathVariants)`
设置变体路径生成器。调用者：`Siding.java:403`、`TrainServer.java:1555`、`TickRunner`。

#### `TripPath<T> findPath(Map<String, TripPath<T>> paths, String key)`
- 作用：取该键的路径；没有且有变体生成器时：跳站键由其基础路径 `TripPath.withSkips` 生成（缓存最近一个于 `skipPath`）；变更键（提前折返 turn、从中途开始 from、在中途结束 end，D4/D5）由基础路径 `withTurn`/`withFrom`/`withEnd` 生成（缓存于 `turnPath`）。
- 返回：路径或 null。
- 调用者：`Depot.java:3052`、`Depot.java:3072`；本类多处。

#### `boolean chooseSkip()`
- 作用：运用列车在站准备出发时（每站一次），换到从此处起跳过所告知站台的路径版本，未告知时回到常用路径（D3）。每个运用分段的首站和末站（以及前一分段末站）始终停车。已经通过的站保持"已通过"，使路径键不必每站都变；被告知恢复停站时立即换回。
- 返回：刚换路径时 true。
- 调用者：`TrainRules.java:187`（"skip"）。

### 网络与每 tick 维护

#### `void setSiding(long newSidingId)`
设所属停车线。调用者：`TrainServer.java:207`、`TickRunner.java:1793`。

#### `void setNetwork(Board newDepot, LongFunction<? extends PlanRoute> newRoutes, LongFunction<? extends Board> newRouteDepots)`
设所属车厂、线路查询、线路 → 车厂查询（线路未知时可为 null）。调用者：`TrainServer.java:672`、`TrainServer.java:961`。

#### `void update(boolean isOnRoute)`
- 作用：每 tick 调用，保持本车正在和即将运行的服务被占用（车厂若某 tick 无人重新预订就忘掉）。在线：清除"正在出库"标志；运用列车不预订（运用已规定谁跑什么）；否则预订当前服务和环线下一圈的服务。不在线：正出库执行运用时保持不变；否则列车已回车厂，结束服务：若路径是回送到别的停车线（return 键）则记 `returnedElsewhere` 与目标停车线；清空运用、各种选择站、扣车与多停、清客/回库标志、调度折返站台（跳站站台保留，到宿主改变为止）、服务时刻、环线状态，`leaveTripPath`，`setLastLap(false)`。
- 调用者：`TrainServer`、`TickRunner`（每 tick）；列出的 `Depot.java:855` 等为同名其他方法。

#### `void onLapComplete()`
- 作用：循环列车开始新的一圈（`MotionHooks.onLapComplete`）：在直通路径上且有环线线路时，取预订的下一服务（或找本圈服务），设线路与线路开始时刻，预订下一个。否则按车厂时刻表：没有时刻表则清空服务与末圈标志；有则（在加时刻表前就在绕圈的列车在此加入）取预订的下一服务或车厂的下一服务、车厂首条线路，`updateTripPath`，预订下一个。
- 调用者：`TrainServer.java:305`、`TickRunner.java:2968`；本类 `onStopReached`。

#### `void onStopReached()`
- 作用：列车刚到站时（`MotionHooks.onStopReached`）：运用列车若到达后续分段首站，依次 `startDutySegment`（服务已由运用规定）。非运用：中途加入环线的列车到达首站即结束所加入的服务（同一圈结束）；到达直通路径的入口轨道时 `joinLapRoute`；到达路径上下一线路的起点（或紧邻其前的折返轨）时换到该线路的下一服务（直通的用链接服务，否则向车厂 `findNextService`；不等服务的用计划时刻），在此等待（使有折返轨的列车不占站台），更新路径、线路开始时刻并预订下一个。没有服务则按已有时间继续。
- 调用者：`TrainServer.java:310`、`TickRunner.java:2973`。

#### `boolean needsAlignment()`
刚读档、尚待对齐。调用者：`TrainServer.java:950`。

#### `void alignToTimetable(boolean canAlign)`
- 作用：停止的世界不追补丢失的时间。列车直接去它此刻应在的站：若所运行服务未结束就按该服务，否则按此刻正在运行的服务；该站被占则去其前最近的空站，从那里晚点运行；无事可做则回车厂。运用列车交给 `alignDuty`。
- 参数：`canAlign` 列车当前不按时刻表运行（在车厂或手动驾驶）时为 false。
- 副作用：可能 `vehicle.moveToStop`/`returnToDepot`，修改服务状态。
- 调用者：`Siding.java:1696`、`TrainServer.java:955`。

#### `void joinDutyLate()`
发出时间远晚于运用所需的列车在仍能赶上的第一站加入运用（仿真重新开始时被放到那里），而不是全程晚点运行（解耦文档 D 步"车厂发车 3"）；只放在站上（来自车厂），不放在区间。调用者：`Depot.java:1927`、`TrainServer.java:1051`、`TickRunner.java:1309`。
（源码中 `alignDuty` 的说明 javadoc 位于此方法之前，见下。）

#### `private void alignDuty()` / `private void alignDuty(boolean onRun)`
- 作用：运用列车去运用此刻规定的位置：已开始的最新分段（由运用账本 `segmentAt` 求，中途开始的运用（D5）从其起始分段算），其中第一个尚未离开（计划发车 + `ALIGN_TOLERANCE_MILLIS` > 现在）的站，或其前最近的空站。运用开始前在首站等待；运用结束后回车厂，留线过夜的去末站。`onRun` 时先尝试 `placeOnRun` 把列车放在两站之间的区间上。分段数与路径不符时回车厂。
- 调用者：`alignToTimetable`、`joinDutyLate`。

#### `private boolean placeOnRun(int stopIndex, int[] starts, long currentMillis)`
- 作用：若列车此刻应在前一站与该站之间（已过前站计划发车、未到本站计划到达，且本站空闲），按最快运行曲线乘以"计划/最快"拉伸比，二分（40 次）求出此刻位置与速度，`vehicle.moveBetweenStops`。不同于从站重新开始，不留富余，刚出发的列车被放在途中。
- 返回：列车不在此区间或时间未知时 false。

#### `private double getFastTime(double railProgress, boolean leaving)`
沿运用路径最快运行到该进度的时间（tick），或 NaN。

#### `private TimeSegment getTimeSegment(double railProgress, boolean leaving)`
找包含该进度的时间段；`leaving` 时取区间 [start, end)，否则 (start, end]（折返处进度跳一个车长，取跳后的段）；离开时找不到则取之后第一个段。

#### `private int findRunningStop(int fromIndex, long currentMillis)`
从该站起当前服务尚未离开的第一站；或当前之后可开始的另一线路的起点站（等服务的线路需有可用服务）；服务已结束返回 −1。遇停车线轨道或已过环线加入终点返回 −1；出库途中的折返（无计划）跳过。

#### `private boolean takeRunningService(long currentMillis)`
换到此刻应在运行的服务（`getLatestService`），没有则下一个（`getNextService`，最多等 `MAX_LAYOVER_MILLIS`）；在直通路径上的列车（路径只适合那个服务）返回 false 回车厂。成功时预订并 `updateTripPath`。

### 折返去向选择

#### `boolean chooseTurnBack()`
- 作用：在一个有多种折返走法的地点（线路末站之后的折返轨，或可在其折返的站台）之前的站，列车即将离开时选择一种，并保留到通过为止。每种走法有自己的路径（到本站为止与当前路径相同），在此换上。
  - 提前折返路径（D4）上不选（保持常用走法）；同一站只选一次；本站不是折返点前站时不选。
  - 运用按计划规定的等待方式（运用补完第 1 步：`DayDuty.getWait`）只考虑该类走法，被占时在此等待；没有规定时由运行时自行选择。
  - 混合折返：存在站台走法时，下一服务等待很久（≥ `LONG_TURN_BACK_WAIT_MILLIS`）或计划要求在折返轨等待的列车，优先选空闲且畅通、能赶上下一分段服务的折返轨走法，把站台留给他车（规划器与网页编辑器同样处理）。
  - 尝试的顺序（站后双折返，0.73.2）：可用走法都是同一类（都是折返轨或都是站台）时轮流，从 `getServiceOrdinal()`（列车当前班次在本线路当天的序号）对可用走法数取余的那一种开始（`getWayOrder`），所以相邻两班用不同的折返轨；不需要保存状态，跳 tick 和读档结果相同。两类混合时仍从第一种开始。
  - 否则按上述顺序：折返轨空闲、无计划时刻或走法畅通且能按时离开下一线路首站的第一种；都不行时取畅通优先、再最早可离开的一种。
  - 选中后 `vehicle.reserveTurnBack` 预订其折返轨，若路径不同则换上并重设末圈状态。
- 返回：所有走法都被占（需在站等待）或刚换了路径时 true。
- 调用者：`TrainRules.java:183`（"turn_back_way"）。

#### `static int[] getWayOrder(int wayCount, List<Integer> usableWays, int turn)`
- 作用：折返点各走法的尝试顺序：可用走法从第 `turn % 可用数` 个起轮转，其后是不可用的。

#### `private int getServiceOrdinal()`
- 返回：列车当前班次（`tripRouteId`、`tripMillis`）在线路时刻表当天的序号（`RouteTimetable.getServiceOrdinal`）；没有班次时 0。

#### `private long getNextSegmentServiceMillis()`
运用列车下一分段服务的时刻，或 0。

#### `private boolean isRailWay(TripPath<T> wayPath, int stopIndex, int length)`
该走法是否在折返轨（无站台、有停站时间）上折返，而非在站台。

#### `private long[] getWayTimes(TripPath<T> wayPath, int stopIndex, int nextStart, long nowMillis)`
- 作用：现在出发走这条路，最快何时能离开下一线路首站，以及下一服务计划何时从那里出发（运用列车按计划；其他列车只有环线预订的下一服务）。
- 返回：`{可离开时刻, 计划时刻}`；下一服务无计划时间时 null。副作用：`updatePlan`。

#### `boolean mayChooseWayAtStop()`
下一站 `chooseTurnBack` 或 `chooseDepotReturn`（或运用末站的 `chooseDutyReturn`）可能做事时为 true，此时是否能离站须每 tick 询问（S4f）。调用者：`TickRunner.java:2370`。

#### `boolean chooseDepotReturn()`
- 作用：列车留线运行的车厂（`keepsTrainsOnLine`），在标为回库的服务（`isReturnService`）所属线路的末站，换到回库路径（在列车惯常折返处折返后驶向车厂），清空线路序列，本圈之后的服务留给别的列车。
- 返回：刚换路径时 true。调用者：`TrainRules.java:181`（"depot_return"）。

### 发车

#### `long findDeployService(long lastDeployedMillis)`
- 作用：列车从停车线出发，使其按计划或稍晚到达首站，但绝不运行车厂已经在其后派过车的服务（避免服务乱序）：在 [max(上次派出服务, 现在 + min(出库用时, 0) − `MAX_DEPLOY_DELAY_MILLIS`), 现在 + 出库用时 + 15 秒] 内取最晚的未被占用服务。
- 返回：服务完整时间戳，现在不该出发则 0。调用者：`Depot.java:1893`、`TrainServer.java:990`。

#### `long getNextDeployMillis(long lastDeployedMillis)`
在没有服务被占用或释放的前提下，`findDeployService` 最早能找到服务的时刻；永远不能则 `Long.MAX_VALUE`。调用者：`TickRunner.java:2217`（跳 tick 时计算下一个需要检查的时刻）。

#### `void deploy(long newTripMillis, long routeId)`
为时刻表某服务派出本车：设服务与线路，有环线加入站时标记中途加入环线，`updateTripPath`，预订下一个。调用者：`TrainServer.java:1001`、`TickRunner.java:1272`。

#### `void deployDuty(int slot, long dayStart, TripPath<T> dutyPath)` / `deployDuty(int slot, long newDutySidingId, long dayStart, TripPath<T> dutyPath)` / `deployDuty(int slot, long newDutySidingId, long dayStart, TripPath<T> dutyPath, int startSegment)`
- 作用：为某槽位的运用派出本车（设计文档 6.12 E2）：设槽位、所属停车线（自己的记 0）、运用编号、运营日开始、运用拷贝；标记正出库；清直通链接；使用运用路径并开始 `startSegment`（备用车接管或晚发列车从后面的分段开始，D5，路径为 `TripPath.withFrom`）。
- 调用者：`Depot.java:1925`、`TrainServer.java:1007`、`TickRunner`。

#### `boolean isOnDuty()`
有槽位且路径是运用路径。调用者：`TrainServer.java:447`、`TrainRules` 多处。

#### `boolean isStayingOnLine()`
留线过夜的列车正停在末站等下一天。调用者：`TrainRules.java:61`、`TickRunner.java:2370`。

#### `boolean endsOnLine()`
所在运用路径以线上停站而非回库结束。调用者：`TrainServer.java:1059`、`TickRunner.java:2955`。

#### `long getTurnBackLeaveMillis()`
- 作用：运用列车停在下一分段首站之前的折返轨上时，为准时到达该站应离开折返轨的时刻（下一分段服务 − 折返轨到首站的运行时间），使列车在折返轨而非站台等待，站台可供他车使用（混合折返）。其他情况 0。
- 调用者：`TrainRules.java:64`、`TrainRules.java:161`。

#### `long getDutyDepartureMillis()`
运用列车在某分段首站时，该站的计划发车时刻；否则 0。调用者：`TrainServer.java:1223`、`TrainRules`。

#### 常量 `LONG_TURN_BACK_WAIT_MILLIS = 5 分钟`
在末站等待下一服务这么久以上的列车，若有空闲折返轨就改在折返轨等待，空出站台（混合折返）。（其上方另有两段孤立 javadoc，分别描述 `updateOvernight` 与 `HoldReason`。）

### 留线过夜

#### 枚举 HoldReason
留线过夜的列车在所停之处没有可继续运用的原因：`NO_DUTY`（接续槽位当天无运用）、`OTHER_PLATFORM`（运用从另一站台开始）、`ALREADY_RUN`（当天已有别的列车运行了该运用）。

#### `HoldReason getOvernightHold()`
等待原因或 null。调用者：`Siding.java:752`、`DutyRunTest.java:643`。

#### `long getHeldStopId()`
无运用而等待时所停站台 id（路径末站），否则 0。调用者：`Siding.java:753`。

#### `boolean recallHeld()`
按玩家命令把无运用等待的列车直接送回停车线；返回它原本是否在等。调用者：`Siding.java:766`。

#### `void updateOvernight()`
- 作用：新运营日开始后（距运用日开始超过半天），留线过夜停在末站的列车接手为它选定的槽位的运用（从所在处开始）。新一天是编辑生效之处，所以按车厂当前的运用读取接续：先按运用编号找下一个运用（可能属于车厂其他停车线，P2-D），否则 `DutyShapes.getNextSlot`；求路径键与路径。若无运用、运用不从此站台开始、或当天已被运行，则记录原因（变化时打印日志）并在原地等待（P1-C：不凭空消失进停车线），直到有从此处开始的运用或玩家召回。否则标记运行、设槽位与运用、换路径并开始第 0 分段。
- 调用者：`TrainServer.java:692`、`TickRunner.java:1986`。

### 运用分段

#### `private void startDutySegment(int index)`
把列车移到运用的某分段：设线路、服务时刻（该运用日中的服务）；第 0 分段时线路开始时刻为 0，若从线路中途加入，服务时刻加上入口偏移（`getEntryOffsetMillis`）；其他分段取其首站计划中的线路开始时刻。

#### `transitsim.plan.DutyLedger.Entry getDutyLedger()`
列车接手的运用（随存档保存）；旧存档则为从车厂重读的该槽位运用。无槽位时清空并返回 null；运用变化时重新 `DutyLedger.intern`。调用者：`Siding.java:469`、`TrainServer.java:936`；`write`、`alignDuty`。

#### `private DepotDuties.DayDuty getDutyDay()`
缓存的运用拷贝，缺失时 `readDutyDay`。

#### `private long currentDutyId()`
当前槽位运用的编号，或 0。（其上方孤立 javadoc "车厂当前该槽位当天运用的拷贝" 属于 `readDutyDay`。）

#### `private DepotDuties.DayDuty readDutyDay()`
从车厂读该槽位在运用日（按日中点求星期）的运用拷贝；有运用编号时先 `depot.findDuty` 找回其当前停车线与槽位（运用可能被移动，P2-D）。

#### `private List<DepotDuties.Segment> getDutySegments()`
运用分段，无运用为空。

#### `int getDutySlot()`
槽位，或 −1。调用者：`Siding.java:753`、`TrainServer.java:795`、`SimulateDay`。

#### `long getDutySidingId()`
运用槽位所属停车线（`dutySidingId` 为 0 时为自己的）。

### 回送（回库）

#### `boolean chooseDutyReturn()`
- 作用：共享列车的停车线池中的列车，在运用末站即将回库时（只选一次），选池中下一个需要车的停车线，并换到同一运用回该停车线的路径（解耦文档 D 步"车厂发车 5"）。提前折返空车回库（D4）的列车回自己的停车线。运用最后服务可在运行图上指定回某车厂（`getReturnOrderDepot`）；自己的停车线只在运用路径确实回到那里时算能回（`getEndSiding`：路径最后一段存车线轨道的编号，不知道为 0 时照旧算能回；0.75.4）；按池选到自己却回不去时，回运用路径实际进入的那条停车线。运用本身设定的回库停车线（A 出 B 入，P2-D）若有路径就去那里，否则记录未能（`noteReturnMissed`）并按池选择（`depot.chooseReturnSiding`，只选有回送路径的）。走了另一种折返走法、提前折返或从中途开始的路径，用回送路径的末站（轨道编号可能不同）。
- 返回：刚换路径时 true。调用者：`TrainRules.java:182`（"duty_return"）。

#### `private static long getEndSiding(TripPath)`
运用路径结束所在的存车线编号（最后一段是存车线轨道时取其编号），否则 0（0.75.4）。

#### `private long getReturnOrderDepot()`
运用最后服务在运行图上设定回的车厂（`getReturnOrder`），或 0。

#### `private int getLastDutyStop()` / `private static int getLastDutyStop(TripPath<T> dutyPath)`
运用路径在进入停车线之前的最后一个停站，或 −1。

#### `boolean takeReturnedElsewhere()`
列车回到了池中其他停车线后返回一次 true，供自己的停车线放掉它。调用者：`TrainServer.java:1029`。

#### `long takeReturnedElsewhereSiding()`
同上但返回回到的停车线（一次），不是则 0。调用者：`Siding.java:1739`、`TickRunner.java:1560`。

### 路径与停站时间

#### `void setStopTimes(StopTimes newDepotStopTimes, Map<String, TripPath<T>> newTripPaths)` / `setStopTimes(..., TurnBacks newTurnBacks)`
设置停车线常用路径的停站时间、各服务路径与折返设置；当前停站时间随所在路径。调用者：`Siding.java:1715`、`TrainServer.java:1525`。

#### `TripPath<T> getTripPath()` / `StopTimes getStopTimes()` / `String getTripPathKey()` / `boolean isOnTripPath()`
当前非常用路径（如运用路径）或 null；当前停站时间；当前路径键（常用路径为空串）；是否在非常用路径上。调用者：`Siding.java:1731`、`TrainServer.java:710`、`TrainServer.java:503`、`Siding.java:1660`。（源码有一段孤立 javadoc "不在车厂常用路径上时为 true……" 属于 `isOnTripPath`。）

#### `boolean isOnTrip()`
是否在运行直通到另一线路的服务（路径有 `tripKey`）。调用者：`TrainServer.java:735`；本类多处。

#### `boolean isLastLap()`
是否末圈。调用者：`TrainServer.java:974`。

#### `boolean restoreTripPath(Map<String, TripPath<T>> newTripPaths)`
- 作用：把读档列车放回其路径（跳站等变体路径由键重新生成，D3）；保留末圈与下一服务；旧存档缺少链接线路时取路径最后一条线路。
- 返回：路径已不存在时 false；没有路径键时 true。
- 调用者：`Siding.java:404`、`TrainServer.java:1564`、`TickRunner.java:1834`。

#### `private void updateTripPath()`
- 作用：换到直通到另一线路的服务的路径：在常用路径上、非中途入环、有服务且当前线路是车辆线路的最后一条时，按其时刻表该服务的接续链（`RouteTimetable.getLinkChain`）求 `TripTail.getKey`，取对应路径；已走过某折返点另一走法的列车保持该走法（可能已过折返点）。路径到列车离开常用路径处为止都相同，所以在任何位置都可换。中途加入的环线在首站换。

#### `void forgetTripPath()`
放掉已不存在的路径（停在停车线的列车，0.66.2）：它在那里不需要路径，而保留停车线已没有的路径键曾导致仿真器放弃该车、并每半分钟重启整个仿真器尝试继续。调用者：`TickRunner.java:1838`。（其上方另有一段孤立 javadoc，描述按复制源路径键取路径的用法（0.66.1），对应 `takeTripPath`。）

#### `void takeTripPath(TripPath<T> newTripPath)`
用于另一服务的副本（游戏显示仿真器列车）：直接采用其键所指的路径（读档时作为副本自己停车线的对象被略去，0.66.1）；不改变宿主自己的路径。调用者：`TrainServer.java:549`。

#### `private void useTripPath(TripPath<T> newTripPath)` / `private void leaveTripPath()`
换上路径（清线路序列、`vehicle.followPath`、清下一服务、非末圈）/ 离开路径回常用路径（`followPath(null)`）。

#### `private void setNextLink(PlanRoute route)`
接手直通链上某线路的服务后，找其后的链接服务。

#### `private void joinLapRoute()`
到达直通路径在环线中途加入的站：与从车厂中途加入环线的列车一样，在该服务到达此站的时刻到达，该服务在环线首站结束；取最接近的链接服务（按此站相对线路开始的时间回推），标记中途入环并预订下一个。

#### `private PlanRoute getLapRoute()`
列车循环运行的线路：常用路径为车辆首条线路；直通路径以环线结束时为最后一条；否则 null。

#### `private int getJoinEndIndex()`
中途入环的列车到达环线首站的轨道下标。

#### `private long getLastTripRouteId()`
直通路径最后一条线路 id。

#### `private void reserveTrip(long newRouteId, long newTripMillis)`
在本车厂预订；直通到由另一车厂运行的线路时也在那个车厂预订，使它不再派车。

#### `private long findLapService(PlanRoute route, long readyMillis)`
直通末端环线的下一个无人占用的服务（同时检查该线路自己车厂的预订），找到即预订。

#### `private List<? extends PlanRoute> getTripRoutes()`
当前路径的线路：常用路径为车辆线路；直通路径按其 `routeIds` 查得（缓存）。

#### `long getLeadMillis()`
- 作用：从离开停车线到首站计划到达的时间；中途加入环线的列车在此之后才到首站，所以可能为负。
- 调用者：`TrainServer.java:710`、`TrainServer.java:1569`；`findDeployService`。

#### `private void reserveNextTrip()`
路径有环线时（且已在环线所在线路上）计算并预订本圈结束后的服务，没有则为末圈。

#### `private void setLastLap(boolean newIsLastLap)`
设末圈并通知宿主（`vehicle.setLastLap`）。

#### `RouteTimetable.StopPlan getPlanAt(int stopIndex)`
列车到达该路径停站时所运行服务的计划时间，无时刻表为 null。调用者：`TrainServer.java:1222`、`TickRunner.java:2358`、`TrainRules`、`DayRun`。

#### `private int getPlanIndex(int stopIndex)`
一圈末站也是下一圈首站（届时运行的是下一服务），映射到首站（直通为 `lapStop`）。

#### `private RouteTimetable.StopPlan getStopPlan(int stopIndex)`
按当前计划快照、服务所属时刻表、服务在一天中的时刻求停站计划。

#### `private void updatePlan()`
列车换了服务、线路、运用分段、路径或停站时间后取最新计划（`PlanVersions.fix` 固定版本）；运用列车用该线路自己的时刻表。线路基准时间刷新会改变全日运用的累计偏移，因此把当前服务重新锚定到其分段首站的线路开始时刻。

#### `private PlanRoute getCurrentRoute(PlanRoute planRoute)`
线路的当前版本（用于选择接下来做什么），而非计划快照中的版本。

#### `private int getTripTable(List<? extends PlanRoute> newTripRoutes)`
当前线路中当前服务所属的时刻表下标。

#### `long getPlannedMillis(long planMillis)`
停站计划中的时间换算为当前服务上的完整时间戳 = `tripMillis + planMillis − routeStartMillis`。调用者：`TrainServer.java:1223`、`TrainRules`、`DayRun`。

#### `long getLateMillis()`
晚点：已过下一停站（或所停之站）计划发车多久，或 0（D2）。调用者：`TrainServer.java:1284`、`DelayAlarms.java:95`。

#### `long getLateSinceMillis()`
已过的那个计划发车时刻（自那时起晚点，在该站晚点期间不变），或 0。调用者：`DelayAlarms.java:93`、`DispatchConsole.java:94`。

#### `long getPlannedArrivalMillis(int stopIndex)`
该站计划到达；圈末站或中途入环的首站为下一服务时刻。调用者：`TrainServer.java:680`、`TrainRules.java:226`。

#### `long getTripMillis()` / `long getTripRouteId()` / `long getNextTripMillis()`
当前服务时刻、线路、下一服务时刻。调用者：`TrainServer.java:1275`、`DelayAlarms.java:99`、`DayRun`、`EventSimulator.query`。

## 内部接口 Vehicle<T extends PlanTrack>
服务告诉列车做的事（宿主实现：游戏 `TrainServer`、组件 `TickRunner.HeadlessTrain`）。
- `default Collection<UUID> getAffectedRails(List<UUID> remaining)`：交接前列车仍需运行的轨道，加上持有其中任一轨道的列车所持有的轨道（列车被拦处，D5）；默认原样返回。
- `List<T> getPath()`；`default double getTrainLength()`（站台停车位置依赖，第 4 轮，默认 0）；`int getNextStoppingIndex()`。
- `boolean isRepeat()`：当前是否在绕环线（末圈不再）；`int getRepeatIndex2()`：一圈结束的轨道。
- `int getPathRepeatIndex1()` / `int getPathRepeatIndex2()`：所在路径环线的起止（无为 0），不论是否末圈。
- `void setLastLap(boolean isLastLap)`。
- `void followPath(TripPath<T> tripPath)`：改走直通路径（null 为车厂常用路径）。
- `void changePath(TripPath<T> tripPath, int newStopIndex)`：停在折返点前的列车换到到折返点为止相同的路径；`newStopIndex` 为新路径上同一站的轨道。
- `boolean isTurnBackFree(List<UUID> rails)`：这些轨道上没有别的列车，也未被别人保留。
- `void reserveTurnBack(List<UUID> rails, int untilIndex)`：为本车保留这些轨道直到到达路径该轨道。
- `boolean isWayClear(List<UUID> rails)`：这些轨道上没有别的列车，也没有人持有它们或其间的道岔（单线与站咽喉），列车现在就能不等待地通过。
- `boolean isStopFree(int stopIndex)`：整列车能否停在该站轨道末端之后而不碰到别的列车。
- `void moveToStop(int stopIndex)`。
- `default boolean moveBetweenStops(int fromStop, int toStop, double railProgress, float speed)`：把列车放在两站之间的区间上（仿真重新开始时运用规定它此刻在那里，解耦文档 D 步）；不能则返回 false（改放到站上）。默认 false。
- `void returnToDepot()`。

## 内部接口 Board
车厂的服务及其列车已占用的服务（实现：游戏 `Depot`、组件 `DepotBoard`）。
- `RouteTimetable getTimetable(String trainId)`：派出该车辆的时刻表，不按时刻表为 null。
- `List<? extends PlanRoute> getTrainRoutes(String trainId)`：该车辆依次运行的线路。
- `long findNextTrip(String trainId, long readyMillis)`：车辆首条线路自此时起第一个无人占用的服务（随即占用），等太久为 0。
- `long findNextService(PlanRoute route, long readyMillis)`：同上，指定线路。
- `void reserveTrip(long routeId, long tripMillis)`、`boolean isTripReserved(long routeId, long tripMillis)`。
- `default boolean keepsTrainsOnLine()`：列车从最后一条线路折返到第一条而不回车厂，并在线上无论多久都等下一服务。默认 false。
- `default List<DepotDuties.Slot> getDutySlots(long sidingId)`：按运用运行车厂中某停车线的槽位，默认空。
- `default boolean hasDutyRun(long sidingId, int slot, long day)` / `default void markDutyRun(...)`。
- `default long chooseReturnSiding(long sidingId, long depotId, LongPredicate canGoBack)`：运用后回池中哪条停车线（默认自己的）；`depotId` 为运用最后服务指定回的车厂（0 为无：则在同车型有空位的停车线中选最早需要车的，没有则常规选择并告知）；只从有回送路径的停车线中选。见 `DepotDispatcher.chooseReturnSiding`。
- `default long[] findDuty(long dutyId)`：该运用编号所在的停车线与槽位下标，无为 null（P2-D）。
- `default void noteReturnMissed(long sidingId, long targetSidingId, String reason)`：设定回某停车线的运用其列车未能回去时告知，而不是默默回别处。
- `default int requestTakeover(long dutySidingId, int slot, long dayStart, int fromSegment, String fromKey, long serviceMillis, Set<UUID> affected)`：请车厂派备用车从某分段起接管运用（D5），并及时派出；返回 `TAKEOVER_OK` 或失败原因。默认 `TAKEOVER_NO_SPARE`。
