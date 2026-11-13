# Depot.java

`Depot` 是车厂：一块区域（`AreaBase`），包含若干停车线、其列车运行的线路，以及列车如何发出（按发车频率、按时刻表、或按运用 `DepotDuties`）。`generateMainRoute` 在独立线程上用轨道与数据的拷贝为所有停车线找路（由 `RailwayDataPathGenerationModule` 调用）：按时刻表运行且没有设定运用时，同时由 `LineDuties`（按编排单元/线路）算出自动运用 `autoDuties`（解耦文档步骤 D）；按运用运行时只生成运用路径（`generateDutiesOnly`），否则把线路连成主路径（`MainPath`、`generate`）交给各停车线的 `Siding.generateRoute`。`deployTrain(RailwayData, Level)` 每 tick 运行，到点时发车（按运用时由 `DepotDispatcher` 决定哪个槽位、哪列车）。运行同一线路的车厂在数据同步时互相关联（`shareServicesWith`），任一车厂的列车所取的班次在两边都算已取，只运行一次。它还实现组件 `TrainService.Board`（列车服务向车厂询问班次、运用、回库停车线、接替），持有出入库规则 `DepotRules`（运用补完第 2 步）、实测与采用的缓冲时间 `Buffers`（D1、第 4 轮第 6 步）、车池车辆设置 `VehicleSettings`（R1）与车池车队（P2-C）。组件侧对应：`DutyPlanner`/`DutyGenerator`（编排）、`DepotBoard`/`DepotDispatcher`（发车）、`TickRunner`（运行）。

## class Depot（extends AreaBase implements IReducedSaveData, TrainService.Board）

### 公共字段
- 客户端显示：`clientPathGenerationSuccessfulSegments`、`clientPathFailure`、`clientFailedDuties`（服务端上次找路找不到路径的运用键）、`clientFailedDutyCount`、`clientDetours`、`clientTurnTimes`（volatile：从站台 → 到站台 → 毫秒，站内换站台在轨道上所需时间；服务端用于规划，客户端供高级编辑器）、`turnTimesMeasured`、`clientAlarms`（volatile：迟到/卡住与应急预案生效的列车，`DelayAlarms`）、`clientAlarmClockOffset`、`clientHeldTrains`（volatile：在线无运用等待的列车，`HeldTrains`）、`lineShortfall`（编排单元的线路列车是否足够，非按线路为 null）。
- `lastDeployedMillis`、`useRealTime`、`repeatInfinitely`、`useDeterministicPath`（默认 true）。
- `cycleRoutes`：列车从末条线路折返到第一条线路并留在线上，只在标记的班次后回库；近期没有下一班次时在线上等待，无论多久。
- `speedCurves`：按速度曲线运行（设计文档第 8 节）；旧车厂在开启前保持旧方式；支持时刻表的运输方式总是开启。
- `releaseInterval`：按运用发出两列车的最短间隔（秒），0 为无（解耦文档步骤 D，发车 2）；最大 `MAX_RELEASE_INTERVAL = 3600`。
- `cruisingAltitude`（默认 256）。
- `routeIds`、`platformTimes`、`departures`、`tempDepartures`。
- `vehicleServiceTypes`：车辆 ID → 可运行的服务类型文本（如 `local, express`），无则全部。
- `poolFleets`：按池键给每个停车线车池的列车数（P2-C）；未列出的池为其停车线车队之和；−1 为运用需要多少。
- `bufferRecorder`（服务端）：实测出库、折返、回库比计划多用的时间（D1）。
- `loggedAlarms`：服务端已写入日志的列车 → 原因。
- `vehicleSettings`：池键 → 车池车辆设置（R1）；未列出的池按其第一条停车线的设置。
- `frequenciesMigrated`：发车频率或发车列表是否已（在路径首次找到后一次性地）转为线路班次（R2）；保留但除连续移动与飞机外不再发车。
- `duties`（`DepotDuties`）：高级编辑器中设定的每列车运行哪些班次。
- `autoDuties`：按时刻表运行且没有设定运用时从时刻表算出的运用，与路径一起重算；此时车厂只在运用开始时发车。
- `rules`（`DepotRules`）：车厂列车如何出入库（按线路与星期）及命名回送路径（出入库规则，运用补完第 2 步）。
- `entrancesUnset`（并发 Set）：在多于一个车站接入线路、却没有规则说明走哪个的线路键（上次找路发现），在规则设定之前不编排。
- `returnOrderMisses`：最近几次"设定回某车厂但列车回不去"的班次（见 `noteReturnOrderMissed`）。
- `runProblemsChanged`（volatile）。
- `runBySimulator`：组件模拟器是否为本车厂发车（G1）。
- `DUTIES_ONLY_FOUND = Integer.MAX_VALUE / 2`：按运用运行且所有线路都找到时找路给出的值，大于任何段数，读作已找到。

### 私有字段
- `deployIndex`、`departureOffset`、`isDirty`、`fillRequested`（玩家要求把频率/发车再加入时刻表，不保存）。
- `buffers`：实测多用时间（服务端为记录器的，客户端为收到的）；第 4 轮第 6 步起只作建议；`addedBuffers`：玩家采用或设定的、加到出库/折返/回库上的时间，运用按它编排（2026-11-08 起保存在车厂）。
- `autoDutiesKey`：自动运用上次的计算依据（不保存，加载后重算一次）；`NOT_BY_LINE = ""`。
- `autoPlanRecord`：自动运用所依据的计划（`LineDuties.Plan#toBytes`），仅服务端。
- `replanGroups`：玩家要求重新编排的运用组键，直到下次找路取走。
- `pathDetours`（同步 Set）：上次找路发现绕远的班次间连接，"从站台 到站台 长度 距离"。
- `lineDepotIds` / `lineUnitKey`（volatile）：本车厂所在编排单元的车厂（含自己）及单元键；不按线路为空；其列车也可回这些车厂中同车辆的停车线。
- `sidingsInDepotCache` / `sidingsInDepotKey`：车厂停车线的顺序缓存（按频率发车每 tick 都要）。
- `frequencies[24]`、`headways[24]`（R2：现实一天中每小时的发车间隔分钟，0 为无；旧车厂由频率近似）。
- `deployableSidings`、`dataCache`、`trainRoutes`。
- `dispatcher`（`DepotDispatcher`）：列车取了哪些班次、哪些运用已运行；保存在此，决定在组件中；`slotsOf = this::getDutySlots`（已运行按运用编号记，运用移到别的停车线或地方仍保留，P2-D）。
- `areaHeights`：区域包含的高度 {最低, 最高}，null 为所有高度（2026-11-09，用车站区域工具设，使上下叠放的车厂各含自己的停车线）。
- 池：见 `clearPools`/`addToPool`。

### 常量
- `HOURS_IN_DAY = 24`、`TRAIN_FREQUENCY_MULTIPLIER = 4`、`TICKS_PER_HOUR = 1000`、`MILLIS_PER_TICK = 50`、`MILLISECONDS_PER_DAY`、`DEFAULT_CRUISING_ALTITUDE = 256`、`TICKS_PER_DAY`、`CONTINUOUS_MOVEMENT_FREQUENCY = 8000`、`THRESHOLD_ABOVE_MAX_BUILD_HEIGHT = 64`。
- `MAX_DEPARTURES = 4096`、`MAX_ROUTES = 1024`、`MAX_POOL_FLEET = 999`、`MAX_RUN_PROBLEMS = 16`。
- `NEARBY_TURN_BACK_BLOCKS = 200`、`MAX_NEARBY_TURN_BACKS = 6`：离站台多远的折返线仍算该站的、尝试几条。
- `DETOUR_MIN_BLOCKS = 300`、`DETOUR_FACTOR = 5`：两班次间连接长于 300 格且是两站台直线距离 5 倍以上时算绕远。
- `MAX_TURN_LINK_BLOCKS = 1000`：提前折返（D4）的绕行超过此长度不采用。
- `MAX_PLATFORM_CHANGE_REACH = 300`：换站台离原站台最远多少格（2026-11-09）。

### 存档键
`route_ids`、`use_real_time`、`frequencies`、`headway_minutes`、`departures`、`last_deployed`、`last_deployed_trips`、`vehicle_service_types`、`pool_fleets`、`measured_buffers`、`added_buffers`、`depot_rules`、`area_heights`、`auto_duties_basis`（自动运用的计划依据与结果，依然成立时保留，第 4 轮第 4 步）、`vehicle_settings`（每项 6 个值：加速度、减速度、手动、手动最高速、手动转自动时间、最高速——最高速自 0.34.0）、`frequencies_migrated`、`deploy_index`、`repeat_infinitely`、`cruising_altitude`、`use_deterministic_path`、`cycle_routes`、`speed_curves`、`release_interval`、`duties`（二进制）、`auto_duties`（二进制）、`duty_mode`（"diagram"/"online"）、`duty_slot`（仅 update 键）、`duty_run_days`、`duty_takeovers`（备车接替的运用，D5）。

### `private static double pathLength(List<PathData> path)`
- 各段轨道长度之和。

### 构造函数
- `Depot(TransportMode)`：新车厂，非连续移动时用现实时间，开启速度曲线。
- `Depot(long id, TransportMode)`：开启速度曲线。
- `Depot(Map<String, Value> map)`：从存档读出所有键；频率读失败打印堆栈；无 `headway_minutes` 时由频率得出；车辆设置读失败的忽略；运用、自动运用、规则、高度、自动计划、运用运行日、接替、实测缓冲（交给记录器）、采用的缓冲（`limitAdded`）。调用者：`RailwayDataFileSaveModule.java:183`（`Depot::new`）、`RailwayData.java:389`。
- `@Deprecated Depot(CompoundTag)`：旧 NBT；`useDeterministicPath = false`。调用者：`RailwayData.java:437`。
- `Depot(FriendlyByteBuf packet)`：客户端与服务端接收编辑；字段顺序见 `writePacket`。调用者：`ClientEditing.java:327`、`ClientData.java:391`、`MTR.java:495`（`Depot::new`）。

### `public void toMessagePack(MessagePacker)`
- 精简部分 + `deploy_index`、`last_deployed`（距现在的毫秒）、`last_deployed_trips`、`duty_run_days`、`duty_takeovers`。

### `public void toReducedMessagePack(MessagePacker)`
- 父类 + 设置、频率、发车列表、服务类型、池车队、迁移标志、发车间隔、车辆设置、运用、运用模式、自动运用、自动计划、规则、区域高度（无为空数组）、实测缓冲、采用的缓冲。调用者：`RailwayDataLoggingModule.java:114`。

### `public int messagePackLength()` / `public int reducedMessagePackLength()`
- 父类 + 28；精简为其 − 5（只有完整存档写的 5 项：发车进度、时间、已发班次、已运行运用、接替；接替曾未计入数目，存档头少算、读不回，直到 2026-11-09）。

### `public void writePacket(FriendlyByteBuf packet)`
- 顺序：父类、线路数与各线路、`useRealTime`、24 个频率、发车数与各发车、`lastDeployedMillis`、`deployIndex`、`repeatInfinitely`、`cruisingAltitude`、`useDeterministicPath`、`cycleRoutes`、服务类型、运用（`PacketBinary`）、`speedCurves`、自动运用、`releaseInterval`、`Shortfall`、池车队、车辆设置、`frequenciesMigrated`、发车间隔、实测缓冲、采用的缓冲、规则字节、区域高度。调用者：`SandboxRuns.java:184`、`EditorSimulation.java:147`。

### 区域高度（2026-11-09）
- `inArea(int x, int y, int z)`：平面内且（无高度限制或 y 在范围内）。调用者：`DataCache.java:347`、`ClientCache.java:499`。
- `getHeight(int[] rectangle)`：高度范围拷贝或 null。
- `setAreaHeights(int y1, int y2)`（排序后保存）、`clearAreaHeights()`。调用者：`StationAreaToolScreen.java:125`、`DashboardScreen.java:684`。
- `sendAreaHeights(Consumer<FriendlyByteBuf>)`：发送 `area_heights` 更新。调用者：`StationAreaToolScreen.java:138`。
- `private writeAreaHeights` / `readAreaHeights`：boolean 是否有 + 两个 int；包已读完时视为无。

### 缓冲时间（D1、第 4 轮第 6 步）
- `private writeBuffers` / `readBuffers`、`private static writeBufferList`（varint 数量 + 各 long）/ `readBufferList`（数量超出 0..1+4×`MAX_ADDED_BUFFERS` 抛 `IllegalArgumentException`）。
- `MAX_ADDED_BUFFERS = 1024`；`private static limitAdded(Buffers)`：最多 1024 项，只保留 >0 的，每项不超过 `BufferRecorder.MAX_EXCESS_MILLIS`。
- `getBuffers()`：实测比计划多用的时间（建议，玩家可采用）。调用者：`EditorData.java:219`。
- `getAddedBuffers()`：运用编排所用的、已采用的附加时间。调用者：`EditorData.java:220`、`EditorDuties.java:388`。
- `setAddedBuffers(Buffers, Consumer)`：网页编辑器设置，限制后发送 `added_buffers`。调用者：`EditorActions.java:209`。
- `sendBuffersIfChanged(Consumer)`：先 `adoptOutLeads`，再在记录器有足够变化或没用的出库变了时取出并发送 `measured_buffers`（带 `withIneffective`）。
- 出库提前量自动校正（0.75.0，用户 2026-11-13 定按实际跑的校正、按首站晚点算、没用就停）：字段 `outLeads`（组件 `OutLeads`，不存档：重启后被判为没用的出库会再试一次）、`pendingOutLeads`、`sentIneffective`；初始化块把 `bufferRecorder.outListener` 接到 `outLeads.add`，要采用的值先放进 `pendingOutLeads`。`sendBuffersIfChanged` 每 tick 先 `adoptOutLeads`（取出待采用的，改 `addedBuffers` 经 `setAddedBuffers` 发出，日志 `corrected the ways out by how late their trains reached the first stop`，被判没用的写明仍晚多少、要改时刻表或出库路），再在实测变了或“没用的出库”变了时发 `measured_buffers`：`withIneffective` 把没用的出库按仍晚的毫秒取负放进实测余量里，网页据此显示（`DepotPanel`、`sync.ts`）。网页清空实测（`clearBuffers`、`update` 收到空实测）时 `outLeads.clear()`。凡改 `addedBuffers` 处（读档、整包、`setAddedBuffers`、`update`）都同步 `dispatcher.addedBuffers`，派车时马上按它提前（`DepotDispatcher.wayOutMillis`）。
- `clearBuffers(Consumer)`：网页编辑器清除实测并发送。调用者：`EditorActions.java:194`。
- `private sendBuffers(Consumer)`。

### `public void rehomeDuties(Consumer<FriendlyByteBuf> sendPacket)`
- 作用（运用补完第 5 步，`DepotDuties#rehome`）：每 tick 收集车池后，把停车线已不存在或换了车辆的停车线上设定的运用交给其车池；只有停车线、车池或运用所在停车线变化（签名）时才重看。移动了时打印（含无同车辆停车线、不运行的数量）并发送全部运用（`duties` 键）。自动运用无论如何会为新停车线重新编排。调用者：`RailwayData.java:592`。

### `public void sendAutoDuties(MinecraftServer)`
- 把自动运用与线路列车缺口（`Shortfall`）发给所有玩家（`PACKET_AUTO_DUTIES`），供高级编辑器。调用者：`Depot.java:2802`、`:2828`、`:2873`。

### `protected boolean hasTransportMode()`
- true。

### `public void update(String key, FriendlyByteBuf packet)`
- `frequencies`：名称、颜色、现实时间、24 个频率、发车列表（最多 4096）、线路（最多 1024）、重复、巡航高度、确定性寻路、循环线路、服务类型、速度曲线（支持时刻表时恒真）、放行间隔（0..3600）、发车间隔。
- `pool_fleets`、`area_heights`、`depot_rules`（字节，最大 1 MiB）、`measured_buffers`（网页编辑器发空表示忘掉实测；服务端以记录器为准，客户端用收到的）、`added_buffers`、`vehicle_settings`、`frequencies_migrated`（并据此设 `fillRequested`）、`duty_mode`、`duties`（服务端在交给车池后发来的全部运用，保留本地 `diagramMode`）、`duty_slot`（停车线、槽位数、下标+1，0 为无槽位；`DepotDuties.setSlot`）；其他交给父类。总是 `isDirty = true`。
- 调用者：`DataCache.java:126`、`LiveSimulation.java:270`。

### 频率与发车间隔
- `getFrequency(int index)` / `setFrequency(int, int)`。调用者：`EditDepotScreen.java:196`、`:259`。
- `setData(Consumer)`：发送 `frequencies` 更新（发车列表先取模一天、去掉非整秒、排序）。调用者：`EditorActions.java:154`、`TrainClient.java:363`。
- `getHeadway(int hour)` / `setHeadway(int minutes, int hour)`（R2：现实一天每小时的发车间隔分钟，钳在 0..`FrequencyTimetable.MAX_HEADWAY_MINUTES`）。调用者：`EditDepotScreen.java:196`、`:257`。
- `private headwaysFromFrequencies()`、`readHeadways`、`writeHeadways`。

### 运用设定
- `setDutyMode(Consumer)`：发送运用模式（diagram/online）。调用者：`EditorDuties.java:120`。
- `setDutySlot(long sidingId, int index, Consumer)`：发送停车线的槽位数与其中一个（index −1 为不带槽位）；每次一个槽位使包远小于客户端可发送的大小。调用者：`EditorDuties.java:125`、`:129`。
- `setFrequenciesToMigrate(Consumer)`：让下次找到路径时再次把频率或发车列表转为线路班次（R2，车厂界面按钮）。调用者：`EditDepotScreen.java:89`。

### `private void migrateFrequencies(Level world, DataCache liveDataCache, Map<BlockPos, Map<BlockPos, Rail>> liveRails)`
- 作用（R2）：把按频率或发车列表发车的车厂的发车转为其线路的班次，使其按运用运行：第一条线路按原来发车的时刻，之后每条在前一条到达后一个折返时间（如一列车依次跑）。只做一次；已按时刻表运行的线路保留，有一条线路按时刻表的车厂（或图表模式）也保留，除非玩家要求填充；连续移动与飞机照旧。现实时间用发车列表（`FrequencyTimetable.cleaned`），否则由发车间隔生成；用 `RoutePaths.Search` 取线路时分，`FrequencyTimetable.chain` 串起来，写入时刻表、发送线路更新并打印。

### `public void generateMainRoute(MinecraftServer minecraftServer, Level world, DataCache liveDataCache, RailwayDataPathGenerationModule.Snapshot snapshot, Set<Siding> sidings, Consumer<Future<?>> callback)`
- 作用：找本车厂所有停车线的路径（服务器线程上准备，在 `WorkPool` 共享线程上执行，第 5 轮第 5 步；`callback` 收到 Future）。
  1. `migrateFrequencies`（R2）。线程使用开始时的轨道与数据快照（同 tick 开始的车厂共享，无人写入），玩家同时铺轨不会中途破坏它。
  2. 在服务器线程读编排单元 `LineDuties.of(...)` 并 `setLineUnit`；收集运用可回的其他车厂（单元内、以及运行图上回库指令指向的车厂）的停车线。
  3. 按服务类型把区域内同运输方式的停车线分组（`getSidingGroupKey`），每组一个 `MainPath`（其路线由第一条停车线的线路决定）。
  4. 图（`usesPathGraph` 时）、线路路径搜索（解耦文档 5.4，L1）。
  5. 运用路径轨道已变的停车线清除记录的出入库腿（P2-A）。
  6. 读单元各车厂已编排的计划记录与槽位（保留仍成立的，第 4 轮第 4 步）以及玩家要求重排的运用组。
  7. 线程中：测站内换站台时间（`turnTimesFor`）；测量出入库腿（`measureDepotWays`，第 4 轮第 3 步）并在变化时 `setRecordedLegs`；按键（单元键|运营日起点|换站时间|出入库|强制组）从 `linePlans` 缓存取或计算计划：先 `lineUnit.keep(...)`（部分保留时打印"planned"，全部保留时打印"kept…"并给出建议重排提示），否则 `lineUnit.plan(...)`；设置 `Siding.DEPOT_WAYS`（按 `RoutePaths.DepotLine` 与规则/命名路径找出入库路，2026-11-09 从离停车线最近的车站开始沿线找）；对每个 `MainPath` 调 `generate`；收集失败运用、出入库腿、绕远；`-Dmtr.debugDepotLegs` 时对比；`generatePathS2C` 发结果；`ConnectionStats.ON` 时打印。被新请求取消时打印"Stopped"；其他异常打印并发送失败。
- 调用者：`RailwayDataPathGenerationModule.java:99`。

### `private Map<Long, List<List<Long>>> ownSidingStops(DataCache dataCache)`
- 不按线路编排的车厂：本车厂各停车线 → [各线路首站, 各线路末站]。

### `static String describeWayReason(long[] reason)`
- 日志用：停车线没有出/回库路的原因（`DepotLegs` 原因码）：轨道不连通；只能逆线路方向跑某些站台；接入多个车站但无规则；规则给的车站或路径不通；只能反向到达且无处折返；未知。

### `private static Map<Long, DepotLegs> measureDepotWays(Depot self, Map<Long, List<List<Long>>> sidingStops, RoutePaths routePaths, RoutePaths.Search routeSearch, Collection<Route> routes, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用（第 4 轮第 3 步）：在区段上、按线路运行站台的方向找停车线到首站与从末站回来的路，并按停车线车辆计时。每条停车线按其所属车厂的规则（运用补完第 2 步；不在任何车厂区域内的单元停车线按测量的车厂）。回库到同车厂同池最近的停车线（2026-11-08 用户决定）；回库时间加上末站停站时间。记录无路的站台及原因（并打印），出库路在转入站台前经过的另一站台（`outPasses`）。区段未就绪而未测量的站台保留原值。
- 参数：`self` 为进行测量的车厂。
- 返回：按停车线的 `DepotLegs`。

### `private static boolean sameWays(DepotLegs, DepotLegs)` / `sameReasons(...)` / `describeWays(...)`
- 比较出/回库时间、不可达、原因、经过站台；`describeWays` 为计划缓存键的一部分。

### `private static long runningEnd(long platformId, boolean first, RoutePaths, DataCache, rails)`
- 线路运行该站台（作为首站/末站）所朝的节点，无已找到的线路时为 0。

### `private static void debugDepotLegs(Siding, RoutePaths, rails, DataCache)`
- `-Dmtr.debugDepotLegs`：比较车厂路径测得的与按腿测得的出入库时间并打印差值。

### `public void copyDispatcher(DepotBoard simulated)`
- 作用（G1）：取模拟器车厂上次发出的班次、运用运行日、接替、发车时间与进度，使其被保存，并在模拟器重启时继续。调用者：`LiveSimulation.java:629`。

### `public void recordDispatcher(RunRecord record, long sidingId)`
- 作用（G0）：把上述发车状态加入列车记录，记在其中一条停车线下。调用者：`LiveSimulation.java:348`、`SimExport.java:77`。

### `public int[] getDeployTimes(Level world)`
- 按频率发车的发车时刻（一天中的时间，排序），按组件模拟器所取（G1）；车厂变化（如加载后）时先 `generateTempDepartures`。调用者：`SimExport.java:61`。

### `public boolean usesFastSpeed(Level world)`
- 巡航高度 ≥ 世界最高建筑高度 + 64：路径按在世界上空高飞找。调用者：`SandboxRuns.java:225`。

### `public Map<Long, Map<Long, Long>> measureTurnTimes(List<Route> routes, DataCache, rails, graph, boolean useFastSpeed)`
- 测试用：这些线路间站内换站台所需时间（同找路为计划测量的方式）。调用者：`DutyTurnBackTest.java:95`。

### `public Map<Long, Map<String, TripPath<PathData>>> findDutyPaths(DepotDuties draft, DataCache, rails, graph, Set<Siding> sidings, boolean useFastSpeed, Consumer<String> failed)`
- 作用（S6）：像找路那样为运用草稿从车厂每条停车线找路径，不改变车厂与停车线；在服务器线程外运行，图由服务器线程上的 `getPathGraph` 给。返回按停车线、再按运用形状键的路径。调用者：`SandboxRuns.java:143`。

### `public void generateDutyPathsNow(DepotDuties runDuties, DataCache, rails, graph, Set<Siding> sidings, boolean useFastSpeed, Consumer<String> failed)`
- 作用：立即（无服务器）像按运用运行的车厂找路那样给每条停车线运用路径："游戏找路径、组件跑列车"的端到端检查。调用者：`GameChainTest.java:121`。

### `public Map<String, List<PathData>> findTurnLinks(DepotDuties draft, DataCache, rails, graph, Siding siding, boolean useFastSpeed)`
- 测试用：停车线运用可提前折返（D4）的绕行，"from>to"。调用者：`EarlyTurnLinksTest.java:81`。

### `public RailGraph<Rail> getPathGraph(rails[, PathBoundaries boundaries])`
- 必须在服务器线程调用；`usesPathGraph()` 时 `DeterministicPathFinder.compile(rails, boundaries)`，否则 null（旧搜索，飞机与缆车总用）。调用者：`Depot.java:1073`、`SandboxRuns.java:225`。

### `public boolean usesPathGraph()`
- 列车车厂总是确定性搜索，与其线路路径相同（2026-11-07 审查，L1），无论旧存档如何：（确定性或支持时刻表）且非飞机且非连续移动。

### 车池（每 tick 开始重新收集）
- 字段 `pools`：池键 → 本车厂停车线。
- `clearPools()`、`addToPool(Siding)`。调用者：`RailwayData.java:584`、`:588`。
- `private getPool(Siding)`：本车厂同池停车线（无则只有自己）。
- `private getLinePool(Siding)`：车厂池，再加编排单元其他车厂中同车辆的停车线（列车也可回到那里，解耦文档 5.4，L1 之后）；哪些算池由组件规则 `FleetRules.linePool` 决定（S7）。
- `private getSlotsOf(long sidingId)`：线路池中某停车线按其自己车厂运行的运用槽位。
- `getPoolSlots(Siding)`：池中停车线运用槽位总数（跨编排单元；无运用为 0）。调用者：`Siding.java:1795`。
- `getPoolTrains(Siding)`：池中停车线的列车（在外、站着、从别处回来的）总数。调用者：`Siding.java:1794`。
- `private getSidingPaths(long)`、`getSidingPool(long)`（池键或 id）、`getSidingVariants(long)`：`Board` 接口用。

### `TrainService.Board` 实现与回库
- `requestTakeover(long dutySidingId, int slot, long dayStart, int fromSegment, String fromKey, long serviceMillis, Set<UUID> affected)`：游戏的列车请求备车接替其运用（D5），见 `DepotDispatcher#requestTakeover`。调用者：`TrainService.java:637`、`TickRunner.java:255`。
- `chooseReturnSiding(long sidingId, long depotId, LongPredicate canGoBack)`：depotId 为 0 时在车池中选（`choosePoolReturnSiding`），否则按回库指令选（`chooseOrderedReturnSiding`）。
- `private choosePoolReturnSiding(...)`：无运用或池少于 2 条时回自己；否则 `DepotDispatcher.chooseReturnSiding`（每条停车线的槽位与今天已运行按其自己的车厂），只选运用有回去之路、且有空位（容纳 + 正回来的 ≤ 车队，`FleetRules.hasRoom`，S7）的停车线。
- `private chooseOrderedReturnSiding(...)`：回到指令指定车厂的同池停车线（有路、有空位）；车厂或停车线不存在、或无合适停车线时记录未达（P1-B，不默默回别处）并改为车池选择。
- `private noteReturnOrderMissed(long sidingId, long depotId, String reason)`：打印并保留最近 `MAX_RUN_PROBLEMS` 条"停车线 车厂 原因"（高级编辑器同步面板），置 `runProblemsChanged`。
- `private countReturningTo(long sidingId)`：所有停车线中正回到此停车线的列车数。
- `findDuty(long dutyId)`：`runDuties.findSlot`。调用者：`TrainService.java:1848`。
- `noteReturnMissed(long sidingId, long targetSidingId, String reason)`。调用者：`TrainService.java:2006`。
- `getDutySlots(long sidingId)`。调用者：`DepotBoard.java:176`、`TrainService.java:1850`。
- `hasDutyRun` / `markDutyRun(long sidingId, int slot, long day)`：每个槽位当天是否已运行运用（"停车线:槽位"），使运用一天只运行一次。调用者：`PidsSchedules.java:44`。
- `keepsTrainsOnLine()`：`cycleRoutes`。

### `public void requestDeploy(long sidingId, TrainServer train)`
- 记下可出库的停车线及其列车。调用者：`TrainServer.java:444`。

### `public void deployTrain(RailwayData railwayData, Level world)`
- 作用：每 tick：车厂变化时重算临时发车；模拟器发车时（G1）清空返回；`dispatcher.startTick()`；有运行运用时 `deployDutyTrain`；支持时刻表的运输方式只按运用（R2），未算出运用前不发车；否则（连续移动、飞机）从 `deployIndex` 轮流在可出库的停车线中找：无时刻表时到发车时刻（`getMillisUntilDeploy(1) == 0`）则 `train.deployTrain()`；有时刻表时找下一未被取的班次，记下、保留并 `deployTrain(tripMillis, routeId)`；每 tick 最多发一列。最后清零偏移与可出库列表。
- 调用者：`RailwayData.java:626`。

### `private void deployDutyTrain(RailwayData railwayData)`
- 见 `DepotDispatcher#findDutyDeploy`：按放行间隔找到要发的运用（停车线、槽位、运用停车线、运营日、路径、起始段），`deployDuty`，晚了则 `joinDutyLate`。

### `public Set<String> getSharedTaken(DataCache dataCache)`
- 作用：运行本车厂某线路的另一车厂已经运行的班次：其设定运用中的全部，以及 ID 较小时其自动运用中的；本车厂自动运用让给它们，使两车厂共有的线路班次不跑两遍。按 ID 顺序计算，任何顺序重算结果相同。
- 返回："线路@班次" 集合。调用者：`SharedTakenTest.java:33`、`getDepotAutoDutiesKey`。

### `public String getAutoDutiesKey(DataCache, Set<Siding>)`
- 整个编排单元的键（单元内任何线路或车厂变化都会重算其所有车厂），不按线路为 null。调用者：本仓库主代码中未找到。

### `@Deprecated public String getDepotAutoDutiesKey(DataCache, Set<Siding>)`
- 按线路编排之前车厂单独编排时的键（线路时刻表哈希与站台、停车线容纳与车辆、共享已取、联络线及其释放的轨道），保留供附加模组参考。

### 编排单元与自动运用
- `private setLineUnit(LineDuties.Unit)`：设 `lineDepotIds` 与 `lineUnitKey`。
- `forgetAutoPlan()`：忘掉自动运用所依据的计划，下次找路重新编排而不保留（玩家要求，第 4 轮第 4 步）。调用者：`PacketTrainDataGuiServer.java:660`。
- `replanGroup(String key)`：下次找路重排该运用组，其他仍成立的保留（运用组第 4 步），不保存。调用者：`PacketTrainDataGuiServer.java:662`。
- `getLineUnitKey()`：编排单元键或空。调用者：`Siding.java:1880`。
- `takeAutoDutiesChange(DataCache, Set<Siding>)` / `takeAutoDutiesChange(LineDuties.Unit unit)`：设单元；返回自动运用是否需要重算（并视为已做）：单元键变化时为真；不再按线路（如线路被删或失去时刻表）时只在仍有自动运用的情况下再找一次路（去掉不再运行的线路的自动运用），之后直到再次按线路前不再找。调用者：`RailwayData.java:796`。
- `getRunDuties()`：车厂运行所依的运用：设定的（图表模式），或按时刻表且无设定时算出的（非连续移动）；按频率发车为 null。调用者：`SimExport.java:59`、`EditorData.java:140`、`Siding.java:1706`。

### 时刻表与线路
- `findTimetable(DataCache)`：某条按时刻表运行线路的时刻表（显示发车按时刻表）。调用者：`LineDuties.java:169`、`EditDepotScreen.java:316`。
- `getTimetable(String trainId)`：发出此车辆的时刻表（其第一条线路的）。
- `private getActiveTimetable(Route)`：支持时刻表且时刻表生效时。
- `private findTimetabledRoute(List<Route>)`：第一条按时刻表运行的线路；车厂线路无序，任一有时刻表车厂即按时刻表运行。
- `runsByDutiesWith(List<Route>)`：图表模式，或非连续移动且有按时刻表的线路；此时列车只走运用路径，线路不连成一条路径。调用者：`EditorData.java:287`、`EditDepotScreen.java:372`。
- `getTrainRoutes(String trainId)`：此车辆依次运行的线路，按 tick 缓存。
- `getRoutes(DataCache)`：车厂线路（存在的）。调用者：极多，如 `EditorDuties.java:315`。
- `getSidingRoutes(Siding, DataCache)`：停车线列车运行的线路：车厂的，停车线指定了则取其子集，再按车辆服务类型过滤。调用者：`LineDuties.java:402`。
- `getSidingRouteIds(Siding, DataCache)`。调用者：`SimExport.java:73`。
- `static getSidingGroupKey(Set<String> serviceTypes, Siding siding)`："服务类型|停车线线路"，同组停车线一起编排。调用者：`LineDuties.java:397`。
- `getRoutes(String trainId, DataCache)` / `getRouteIds(String trainId, DataCache)`：车辆运行的线路（其路径上的站序按这些计数）；无服务类型时为全部线路 id。调用者：`TrainServer.java:741`。
- `getServiceTypes(String trainId)`、`static parseServiceTypes(String text)`（按中英文逗号、顿号、分号、空白拆分并小写）、`static filterRoutes(List<Route>, Set<String>)`（无服务类型的线路所有车辆都可跑，使线路不会因漏写而停运）。调用者：`EditorActions.java:187`。
- `setVehicleServiceTypes(String trainId, String text, Consumer)`：空则删除，然后 `setData` 发送。调用者：`EditorActions.java:188`。
- `static supportsTimetable(TransportMode)`：非连续移动且非飞机。调用者：极多，如 `SimExport.java:61`。

### 班次共享与发车时刻
- `shareServicesWith(Depot other)` / `clearSharedServices()`：两车厂共有线路，任一取的班次另一不再取。调用者：`DataCache.java:155`、`:147`。
- `reserveTrip(long routeId, long tripMillis)`、`isTripReserved(long routeId, long tripMillis)`。
- `findNextTrip(String trainId, long readyMillis)`：车辆第一条线路从此时起第一个未被取的班次，等待太久为 0。调用者：`TrainService.java:1057`。
- `findNextService(PlanRoute route, long readyMillis)`：同上（留在线上的列车无论多久都等）。调用者：`TimetableConflicts.java:145`、`DepotBoard.java:141`。
- `getNextDepartureMillis(String trainId, long leadMillis)`：有时刻表时跳过已取班次（`dispatcher.getNextDepartureMillis`），否则同无参版。调用者：`TrainServer.java:710`。
- `getNextDepartureMillis()`：递增偏移后距下一次发车的毫秒，无为 −1。
- `getMillisUntilDeploy(int offset[, int currentTimeOffset])`：`DepotDispatcher.getMillisUntilDeploy(tempDepartures, ...)`。调用者：`RailwayDataRouteFinderModule.java:213`、`EditDepotScreen.java:315`。
- `generateTempDepartures(Level world)`：现实时间（非连续移动）用发车列表；否则按每小时频率（游戏时间）生成一天的发车（连续移动每 8000 毫秒），排序，清除 dirty。调用者：`EditDepotScreen.java:314`。
- `private static getHour(Level, int offsetMillis)`、`private static wrapTime(float)`（加 6000 tick 换算到一天）。

### `void applyRules(DepotDuties planned, DepotDuties before, DataCache dataCache)`
- 作用（运用补完第 2 步）：把车厂出入库规则应用到刚编排的运用：按每天首个班次（其线路键与星期）匹配的规则，增加出库提前量、设出/回库车站、命名回送路径，以及回到的停车线（须为同池，且不留在线上）；然后保留之前同一运用号、同星期上设置的例外。
- 参数：`before` 为车厂原来的运用。调用者：`Depot.java:2816`、`:2866`。

### 车厂设置
- `setRules(DepotRules newRules, Consumer)`：替换出入库规则与命名回送路径并发送。调用者：`EditorActions.java:160`、`:408`。
- `setPoolFleets(Map<String, Integer>, Consumer)`：设置每个车池的列车数（P2-C，−1..999，最多 1024 项），未列出的池按其停车线车队之和；发送。调用者：`EditorActions.java:170`。
- `getPoolFleet(String poolKey, Collection<Siding> poolSidings)`：设置的值，否则停车线车队之和（`FleetRules.sumFleets`，有一条为 −1 则 −1）。调用者：`PoolFleetTest.java:40`。
- `getLinePoolFleet(Siding)`：池在整条线路上的列车总数（P2-C）：线路池按车厂分组后各车厂给的之和，或 −1。调用者：`Siding.java:1793`、`:1883`。
- `setVehicleSettings(Map<String, VehicleSettings>, Consumer)`：设每个池的车辆（R1，最多 1024），发送。调用者：`EditorActions.java:181`。
- `getVehicleSettings(Siding)`：为池设定的，或池中 ID 最小的停车线自己的设置。调用者：`Siding.java:1056`、`SidingScreen.java:89`。
- `private read/writeVehicleSettings`、`read/writePoolFleets`、`read/writeVehicleServiceTypes`：int 数量 + 各（字符串键, 值）；超过 1024 的读入丢弃。

### `private List<Siding> getSidingsInDepot(RailwayData railwayData)`
- 车厂区域内同运输方式的停车线，排序；按停车线数、数据版本、区域、运输方式与每 5 秒的时间缓存（重命名会改变顺序却不改版本）。

## public static final class DutyGroup
客户端所见的运用组（运用组第 4 步）。
- 字段：`key`（`LineDuties.Part#key`：车辆、停车线组摘要、星期）、`trains`（最多运行）、`needed`、`uncoveredPerDay`、`kept`（上次计划是否原样保留）。
- `getLabel()`：键中 '#' 之前（其停车线的车辆）。调用者：`EditorData.java:404`。
- `getWeekdays()`：键中最后一个 '|' 之后的星期位（位 0 为周一），解析失败为 0。调用者：`EditorData.java:405`。

## public static final class Shortfall
编排单元的线路列车是否足够（`LineDuties.Plan`）：运行所有班次所需列车、停车线容纳的、每天无车的班次、建议加车的地方。
- 字段：`lineNames`、`needed`、`available`、`uncoveredPerDay`、`suggestedSiding`、`replanHint`（保留的运用编排后设置已变，`LineDuties.Plan#replanHint`）、`pools`（分开编排的每组停车线：组间不借车，总数够时某组仍可能不足）、`groups`（运用组）、`unrunnablePerDay`（无论多少列车都跑不了的每天班次：无停车线可达、无车可转到，缺的是出库或绕行路）、`entrancesUnset`（车厂在多个车站接入、无规则的线路）。
- `Shortfall(LineDuties.Plan plan)`：每周数 /7 换成每天。调用者：`Depot.java:2823`、`LineDutiesKeepTest.java:145`。
- `private static perDay(List<Pool>)`。
- `getMissingTrains()`：各组分别计的缺车数；某组需要数未知为 −1。调用者：`EditorData.java:398`。
- `static write(Shortfall, FriendlyByteBuf)` / `static read(FriendlyByteBuf)`：boolean 是否有；线路名、需要、可用、每天无车、建议停车线、重排提示；varint 池数（读最多 256）与各（标签、需要、可用、无车）；varint 组数（读最多 4096）与各（键、列车、需要、每天无车、保留）；varint 不可运行；varint 未设入口数（读最多 4096）与各字符串。调用者：`Depot.java:627`、`:844`、`MTRClient.java:299`。

## private static final class Connection
找到的两班次间连接，以拷贝发出，保留连接与其折返线绕行共用哪些轨道。
- 字段：`path`、`section`（折返区段或 null）。
- `copyInto(List<PathData> connectingPath)`：把连接拷到参数中，返回绕行的拷贝（共用的段用同一拷贝）或 null。
- `private static copyOf(PathData, Map<PathData, PathData> copies)`：按身份去重拷贝并保留站序。

## private class MainPath
一组停车线（车辆运行同一组线路）共享的、经过车厂部分线路的路径，以及为其找运用路径、折返、连接所需的全部搜索。
### 字段
- `turnBackRailsOf` / `turnBackRails` / `nearbyTurnBackNodes` / `nearbyStablingNodes`：所搜索轨道上的折返线（起止），由它们找到的各站台附近尽头节点，及其中留置线节点（运用补完第 4 步）。
- `connectionsOf` / `connections`：本次搜索中找到的班次间连接（按所搜轨道）。
- `failure`、`sidings`、`lineSidings`（编排单元其他车厂的停车线）。
- `repeatPath`、`closesPath`（从末条线路折返回第一条线路首站，列车不经车厂循环）、`dutiesOnly`（按运用运行：每条线路只检查，列车走运用路径，线路顺序无关、无需首尾相接）。
- `loopRoute` / `loopEntryStop`：列车中途加入的环线及加入站。
- `mainRoutes`、`dataCache`、`routeSearch`（按时刻表运行时线路自己的路径，或 null 照旧逐线搜索）。
- `platformsMerged`、`platformsInRoute`、`pathsInRoute`（手绘路径的拷贝）、`waypointsInRoute`、`stopSettingsInRoute`。

### `private DepotDuties generateAutoDuties(MinecraftServer minecraftServer, LineDuties.Plan linePlan)`
- 作用（解耦文档步骤 D）：车厂按时刻表运行且没有设定运用时，从线路时刻表算出这些停车线的运用，并在服务器线程交给车厂。不按时刻表时清除这些停车线的自动运用（并通知客户端，否则编辑器继续显示不再运行线路的运用），返回 null。有单元计划时取本组停车线的槽位、`applyRules`，在服务器线程写入并保存计划记录、`lineShortfall`（含未设入口线路，打印）并发送。否则（单独编排）：线路最快时分取线路自己的（`RoutePath#getFastestTimes`，按跑它的最慢车辆，与编辑器显示一致），手绘路径沿本车厂路径计时；收集停车线输入、出入库连接、池车队、缓冲、最短折返时间、等待阈值，`AutoDuties.generate`，`applyRules`，打印结果并在服务器线程写入。
- 返回：运用或 null。调用者：`Depot.java:3563`、`:4196`。

### 构造函数 `MainPath(List<Route> routes, List<Siding> sidings, DataCache dataCache)`
- 作用：拷贝每条线路的手绘路径；无手绘路径时收集站台、途经点、停站设置；合并站台（相邻同站台合一）。`dutiesOnly = runsByDutiesWith(routes)`；`closesPath`：非按运用、循环线路、首尾站台不同时追加首站；`repeatPath`：重复、闭合，或有时刻表且首尾站台相同；单条按时刻表的环线且无手绘路径时可中途加入（`getLoopEntryStop`）。
- 调用者：`Depot.java:1135`、`:1581`、`:1602`。

### `private List<TripTail<PathData, SavedRailBase>> getTripTails(List<PathData> mainPath, rails, graph, boolean useFastSpeed)`
- 作用：找末条线路的班次接续到其他线路的地方；每种接续只需一条路径（无论多少班次用它）；不可达的线路略去，其班次留在主路径上。按时刻表链接链（`RouteTimetable.getLinkChain`）与键去重。调用者：`Depot.java:4194`。

### `private TripTail<PathData, SavedRailBase> getTripTail(Route lastRoute, List<RouteTimetable.ServiceLink> links, List<PathData> mainPath, rails, graph, boolean useFastSpeed)`
- 作用：沿链接逐条：在离开站（截断时为链接起站，否则末站）离开；不同站台时找连接（直通班次在线路列出的第一条折返线折返，不做选择；截断时直接找）；取下一线路自己的路径（或搜索并套停站设置）；站序接着之前所有线路完整计数；处理中途加入（环线：从加入轨道复制；否则记入加入站与引导路径）；按下一链接截断；末尾为环线时记圈起点与是否反向；`addToTail`。
- 返回：`TripTail`（键、线路、截断站、离开轨道、尾路径、末站台、圈起点、反向、加入轨道、加入站与引导），任何部分缺失为 null。

### `private List<DutyPlan<PathData, SavedRailBase>> getDutyPlans(DepotDuties dutiesToPlan, Siding siding, rails, graph, boolean useFastSpeed, Consumer<String> failed)`
- 作用（设计文档 6.12、E2）：停车线车池中每条停车线的槽位所运行的每种运用形状各一次（其列车可代替其他停车线的列车运行）：按 `DutyShapes.getSlotKey` 去重，`getDutyPlan`；失败时设 `failure` 为 DUTY 并告知 `failed`。
- 参数：`dutiesToPlan` 为车厂运用或其草稿。调用者：`Depot.java:1604`、`:1627`。

### `private List<Siding> getPoolSidings(Siding siding)`
- 返回：自己在前，然后同组同车辆的停车线、编排单元其他车厂同车辆的停车线，再加车厂中同池的其他停车线（无论指定什么线路，因为计划把列车送回最近的，第 4 轮第 3 步），各按 ID。调用者：`Depot.java:1629`、`:3147`、`:4207`。

### `private DutyPlan<PathData, SavedRailBase> getDutyPlan(String key, List<DepotDuties.Segment> segments, int weekday, boolean startsOnLine, boolean staysOnLine, rails, graph, boolean useFastSpeed)`
- 作用：把运用各段连起来：每个班次从加入线路处到离开处，按该班次的通过/停站（单个班次的跳站由此成真），再按车厂路径的折返方式接到下一段。每段模板（线路、起止站、限速、通过标志）缓存：线路自己路径的切片（切片从停着开始，运用列车从出库路或上一班次来到首站，要在那里停车等发车，否则会直接通过首站出库，U-L05），或搜索；套停站设置并按线路限速封顶。段间站台不同时找连接（`findConnection`），记录绕远（长于 max(300, 距离×5)），多种折返方式时记折返区段；与上一段在同一轨道上衔接时合并（从另一站台来到本线路首站的列车在此停车等发车），在站台上反向折返时该段首段只作出站；站前折返（`addFrontTurnBack`）；记首末站台、线路、截断与加入站；中途加入时为计划计时取引导路径。
- 返回：`DutyPlan`（含索引边界、折返区段、各段截断与加入），任何部分找不到为 null。

### `private void addFrontTurnBack(...)` / `private void addFrontTurnBackTimed(...)`
- 作用（站前折返）：线路在列出其他站台可供折返的车站结束、下一段线路从同一车站开始时，加入从倒数第二站经每个这样的站台到下一线路第二站的走法，按下一线路偏好的始发站台排序（种别优先）。列车通常在末站台折返时通常走法是其中之一；站后折返时通常走法排最后（先试站台，都不行才走站后）。找不到某站台的走法时记失败 `TURN_BACK_UNUSED`。至少两种可行走法时加入折返区段。`ConnectionStats.ON` 时计时。
- 参数：`previousStart` / `nextStart` 为主体中上一/下一线路开始的轨道。

### `private List<PathData> findFrontWay(SavedRailBase from, Platform platform, SavedRailBase to, fromWaypoints, toWaypoints, int dwellTime, List<PathData> usualWay, rails, graph, boolean useFastSpeed)`
- 返回：从一站进入该站台并在站台上折返出来到下一线路第二站的路（到达段带末站停站时间，反向段只作出站）；须与通常走法在相同轨道上离开与到达，否则 null。

### `private static int findStopRail(List<PathData> body, int from, long platformId)`
- 从 `from` 起第一条为该站台的站台轨道，或 −1。

### `private boolean usesRoutePaths()`
- 线路路径取自线路：有搜索、按时刻表、非连续移动、非飞机（这些照旧搜索全程）。

### `private boolean isCut(Route route, RouteTimetable.ServiceLink link)`
- 链接起站在线路末站之前（班次在中途截断）。

### `private int getStopCount(List<Route> routes)`
- 最后一条线路之前的站数（相邻线路首末站台相同时减一）。

### `private void addToTail(List<PathData> tailPath, PathData lastMainRail, List<PathData> newPath)`
- 接到尾路径：首段与上一段反向时只作出站（停站 0、站台 id 0）；同一轨道时去掉首段。

### `private int generateDutiesOnly(MinecraftServer minecraftServer, rails, graph, boolean useFastSpeed, LineDuties.Plan linePlan)`
- 作用：按运用运行的车厂：检查每条线路自己的路径（或搜索，失败时 `PathFailure.diagnose`），记下第一个找不到的；取运行运用（设定的或 `generateAutoDuties`）；每条停车线 `getDutyPlans`、`setTurnLinks`、`generateDutyRoute`（停车线本身为路径）。
- 返回：全部找到为 `DUTIES_ONLY_FOUND`，否则 0。调用者：`Depot.java:4103`。

### `private boolean addConnection(List<PathData> tempPath, SavedRailBase from, SavedRailBase to, int routeBefore, List<TurnBackSection<PathData>> turnBackSections, List<DepotReturn<PathData, SavedRailBase>> depotReturns, rails, graph, boolean useFastSpeed)`
- 作用：加入从一条线路末站到下一线路首站的路，若该线路在末站后列出折返线则绕经它们（再加上站台附近的留置线作为更多走法，运用补完第 4 步）；记折返区段；循环线路时为留在线上的列车记录回库点（`DepotReturn`）。
- 参数：`routeBefore` 为末站所属线路下标，无站为 −1。
- 返回：所有折返线都不可达时 false（设失败 TURN_BACK，清空路径）。调用者：`Depot.java:4114`、`:4163`。

### `private List<BlockPos> getTurnBackNodes(Route route, RailGraph<Rail> graph)`
- 线路在末站后列出的节点是可用折返线的端点，按列车尝试顺序；旧搜索（无图）不能跟随节点，为空。

### `private Map<String, List<PathData>> setTurnLinks(Siding siding, List<DutyPlan<PathData, SavedRailBase>> dutyPlans, Map<String, List<PathData>> cache, rails, graph, boolean useFastSpeed)`
- 作用：找停车线运用可提前折返（D4）的地方：从一段除末站外的每个停站，绕到下一段在同一车站停靠（除末站外）的每个站台，按运用在两段间折返的方式（`findConnection`），不超过 `MAX_TURN_LINK_BLOCKS`（按 "from>to" 缓存）。并为交接运用（D5）收集：除最后一段外每段结束处的回库目标，除第一段外每段开始处的出库目标（以折返开始的段为其到达的站台）。交给 `siding.setTurnLinks`。
- 返回：绕行表。调用者：`Depot.java:1628`、`:1643`、`:3569`、`:4206`。

### `private List<Long> getSegmentStops(DutyPlan, int segment)`
- 运用某段依次停靠的站台（去掉连续重复）。

### `private TurnBackSection<PathData> findConnection(List<PathData> connectingPath, SavedRailBase from, SavedRailBase to, Route routeBefore, rails, graph, boolean useFastSpeed)`
- 作用：两班次间的路，从一个结束处到下一个开始处，按运用所跑：绕经前一线路在终点后列出的折返线，或（未列出时）找到的路与绕经车站附近折返线（站后折返）中较短者。计划的站台间时间沿它测量（`turnTimesFor`），使计划与运行走同一条路。每次搜索中按"from>to>前线路>高速"缓存，每次请求得到自己的拷贝（运用路径会给站编号并改停站；大网络上 1900 条路被请求 13000 次，第 5 轮第 5 步）。
- 参数：`connectingPath` 填入路径（无则留空）；`routeBefore` 为跑到终点的前一线路或 null。
- 返回：有折返线时其走法，否则 null。

### `private TurnBackSection<PathData> findConnectionTimed(...)`
- 上述逻辑本体（列出的折返线加留置线 → `findTurnBackSection`；否则直接搜索并与附近折返线走法比较长度）。

### `private Map<Long, Map<Long, Long>> turnTimesFor(List<Route> routes, rails, graph, boolean useFastSpeed)`
- 作用：站内每次换站台按运用所跑（`findConnection`）需要多久：从每条线路末站台到同站其他线路首站台；无路的对略去（规划视为无路）。另外每个站台一次：绕经站台外折返线再回到本站台（列车可在那里等下一班次而让出站台，混合折返），记 `from→from` 总时间、`HOLD_IN_KEY`（进入段）、`HOLD_COUNT_KEY`（可同时等待的列车数，每条可用走法一列，运用补完第 4 步）、`HOLD_RAIL_KEY`（哪条折返线，同一折返线的站台共用，8 号线 2026-11-12）。换站台离站台超过 `MAX_PLATFORM_CHANGE_REACH` 的不算（会沿线跑出又回来与对向列车迎面，2026-11-09）。时间为两条线路车辆中最慢者的进入段 + 出来段（不含在折返处停留，第 4 轮，由计划加上网络最短换端时间）。`-Dmtr.debugTurnTimes` 时打印。
- 返回：从站台 → 到站台 → 毫秒。调用者：`Depot.java:1181`、`:1581`。

### `private Set<PathTiming.Vehicle> vehiclesOf(Route before, Route after)`
- 两条线路计时用的车辆；无搜索时为运输方式默认车辆。

### `private long[] legMillis(PathTiming.Vehicle vehicle, List<PathData> connectingPath)`
- 作用（第 4 轮）：把绕行在折返处切开：进入段从出发到停在折返处，出来段从再出发到到达；不含停留。从停在站台（车头在站台末端）起算。无折返时全为进入段。
- 返回：{进入毫秒, 出来毫秒}。

### `private void debugTurnTime(Platform from, Platform to, Route before, Route after, List<PathData> way, long[] legs)`
- `-Dmtr.debugTurnTimes`：打印第 4 轮前的算法（30 秒 + 全程 + 30 秒）与现在（最短换端 + 进入 + 出来）。

### `private double farthestFrom(SavedRailBase platform, List<PathData> path)` / `private int countReversals(List<PathData> path)`
- 路径离站台中点最远的距离；路径折返次数。

### `private long connectionMillis(PathTiming.Vehicle vehicle, List<PathData> connectingPath)`
- 从停在站台起走完整条路的毫秒（只在调试中使用）。

### `private List<BlockPos> getNearbyTurnBackNodes(SavedRailBase platform, rails)` / `getNearbyTurnBackNodesTimed(...)` / `findNearbyTurnBackNodes(...)`
- 返回：站台可达范围（200 格）内折返线的尽头节点，最近的在前，最多 6 个（线路未列出时用于折返）；然后每个留置区（`dataCache.stablingRails`）一个节点，最近的在前，最多 6 个（运用补完第 4 步，同时记入 `nearbyStablingNodes`）。所搜轨道在找路期间不变，折返线只列一次、每个站台只查一次（第 5 轮第 3 步：原来每次读全世界轨道，大网络上约 13000 次）。`ConnectionStats.ON` 时计时。

### `private List<BlockPos> withStabling(List<BlockPos> listed, SavedRailBase platform, rails)`
- 线路列出的折返节点之后加上站台附近的留置区节点（玩家把它们标为该处折返的任何线路都可用，运用补完第 4 步）。

### `private BlockPos stablingNode(List<BlockPos[]> blockRails, BlockPos center, rails)` / `private boolean isBefore(...)`
- 留置区中列车折返处：其中折返线的尽头（最近的），否则离站台最远的折返线端点（列车从站台驶入）；距离相同按位置长整数。

### `private TurnBackSection<PathData> findTurnBackSection(SavedRailBase from, SavedRailBase to, List<BlockPos> nodes, int start, rails, graph, boolean useFastSpeed)`
- 作用：经每个折返节点找从一站到下一站的路；每种走法都必须与第一条可用走法从同样的轨道离开第一站并到达下一站，使列车可从该站选任何一种。不可用的记 `TURN_BACK_UNUSED` 失败。
- 参数：`start` 为走法在主路径上开始处。
- 返回：走法（不可用为 null 项）与默认走法；都不可用为 null。

### `private List<PathData> getLoopJoinPath(List<PathData> mainPath)`
- 列车加入环线的站到路径末尾的部分（拷贝），从首站加入为空。

### `private int generate(MinecraftServer minecraftServer, rails, graph, boolean useFastSpeed, LineDuties.Plan linePlan)`
- 作用：按运用运行时 `generateDutiesOnly`。否则依次连接各线路：线路间 `addConnection`；手绘路径、线路自己的路径（找不到则失败）或搜索（失败 `diagnose`，套停站设置与限速）；与上一段反向时首段只作出站、同一轨道时去掉重复；闭合路径时从末站接回首站（无路时失败 `CYCLE_BACK`）。编站序（通过的站台无停站时间但仍计数，因为线路下标包含它们）；折返区段编站；闭合时圈尾站序同首站；回库点找站。取环线加入路径、班次尾、运行运用；每条停车线 `getDutyPlans`、`setTurnLinks`、`Siding.generateRoute(...)`。
- 参数：`linePlan` 为编排单元的运用或 null。
- 返回：各停车线成功段数的最小值。调用者：`Depot.java:1243`。
