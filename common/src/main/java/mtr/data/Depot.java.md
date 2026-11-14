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
`route_ids`、`use_real_time`、`frequencies`、`headway_minutes`、`departures`、`last_deployed`、`last_deployed_trips`、`vehicle_service_types`、`pool_fleets`、`measured_buffers`、`added_buffers`、`depot_rules`、`area_heights`、`auto_duties_basis`（自动运用的计划依据与结果，依然成立时保留，第 4 轮第 4 步）、`vehicle_settings`（每项 6 个值：加速度、减速度、手动、手动最高速、手动转自动时间、最高速——最高速自 0.34.0）、`frequencies_migrated`、`deploy_index`、`repeat_infinitely`、`cruising_altitude`、`use_deterministic_path`、`cycle_routes`、`speed_curves`、`release_interval`、`duties`（二进制）、`auto_duties`（二进制）、`duty_mode`（"diagram"/"online"）、`duty_slot`（仅 update 键）、`duty_run_days`、`duty_takeovers`（备车接替的运用，D5）、`late_services`（晚点改挂登记，0.78.0）。

### `private static double pathLength(List<PathData> path)`
- 各段轨道长度之和。

### 构造函数
- `Depot(TransportMode)`：新车厂，非连续移动时用现实时间，开启速度曲线。
- `Depot(long id, TransportMode)`：开启速度曲线。
- `Depot(Map<String, Value> map)`：从存档读出所有键；频率读失败打印堆栈；无 `headway_minutes` 时由频率得出；车辆设置读失败的忽略；运用、自动运用、规则、高度、自动计划、运用运行日、接替、实测缓冲（交给记录器）、采用的缓冲（`limitAdded`）。调用者：`RailwayDataFileSaveModule.java:183`（`Depot::new`）、`RailwayData.java:389`。
- `@Deprecated Depot(CompoundTag)`：旧 NBT；`useDeterministicPath = false`。调用者：`RailwayData.java:437`。
- `Depot(FriendlyByteBuf packet)`：客户端与服务端接收编辑；字段顺序见 `writePacket`。调用者：`ClientEditing.java:327`、`ClientData.java:391`、`MTR.java:495`（`Depot::new`）。

### `public void toMessagePack(MessagePacker)`
- 精简部分 + `deploy_index`、`last_deployed`（距现在的毫秒）、`last_deployed_trips`、`duty_run_days`、`duty_takeovers`、`late_services`（0.78.0；精简长度因此少 6 项）。

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
- 0.76.0：到某首站没有出库路时，试从首站开始的线路列出的同站可始发站台（`startPlatformsOf`，按列出顺序），第一个有路的记入 `startVia`，首站出库时间按到它算；一个首站都出不去的停车线在日志说"has no way out to any first stop"（收进来的车出不去，要在车厂里加调车线）。
- 参数：`self` 为进行测量的车厂。
- 返回：按停车线的 `DepotLegs`。

### `private static List<Long> startPlatformsOf(Collection<Route> routes, long firstPlatformId, DataCache dataCache)`（0.76.0）
从该首站开始的线路列出的、同一车站的可始发站台，按列出顺序。

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
- `giveUpService` / `expectLate` / `isServiceTaken` / `takeFreeService`（0.78.0）：晚点改挂，转给 `dispatcher`。
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

## 路径构建（已移出）
`MainPath` 和它用的 `Connection` 在 2026-11-13（拆分车厂第 1 步）原样移成同包的独立类 `DutyPathBuilder`，见 `DutyPathBuilder.java.md`。`Depot` 在 `generateMainRoute`、`measureTurnTimes`、`findDutyPaths` 等处用 `new DutyPathBuilder(this, …)`；为此 `autoPlanRecord`、`pathDetours`、`pathLength`、`findTimetabledRoute`、`getActiveTimetable` 和几个常量（`NEARBY_TURN_BACK_BLOCKS`、`MAX_NEARBY_TURN_BACKS`、`DETOUR_MIN_BLOCKS`、`DETOUR_FACTOR`、`MAX_TURN_LINK_BLOCKS`、`MAX_PLATFORM_CHANGE_REACH`）由 private 改为包内可见。

## 存车线顺序（0.75.2）
- `generateMainRoute` 的存车线参数（世界的 `HashSet<Siding>`，按对象身份散列）在入口按 ID 排成 `LinkedHashSet` 再用；以前每次开服分组和分配运用的顺序不同，同一份规划让存车线拿到不同的运用。

## 寻路计时和路径导出（2026-11-13）
- `generateMainRoute` 结束时的 `Finished path generation` 一行带上分段用时：总计、量折返用时、找出入库路、规划单元排运用（planned / partly planned / kept / shared）、拼运用路径及路径条数。yel 世界开服 13 个车厂：每个 3–912 毫秒，排运用最多 771 毫秒，拼运用路径最多 125 毫秒。
- `private dumpDutyPaths(List<DutyPathBuilder>, turnTimes, measuredWays)`：`-Dmtr.dumpDutyPaths=<文件夹>` 时写 `depot-<id>.txt`（折返用时、出入库用时和不可达、每条存车线每条运用路径的键、分段起点、出库用时、总用时、轨道和停站），排好序，两次构建可逐行比较；拆分车厂每一步都用它验收。0.76.0 起在服务器线程上、存车线装上新路径之后才写（以前在找路线程上写，读到的可能是上一次找路的路径，取决于这次找路花了多久；所以 0.76.0 之前的导出不能和之后的比）。

## 折返线比列车短的提示（0.79.4）
- `tellShortTurnBacks(mainPaths)`：找完车厂路径后，对每条存车线的每条运用路径用组件 `TurnBackLengths.find`（折返线 = `RailType.TURN_BACK`，列车长 = `Siding.getTrainLength`），结果和上次不同才打一行日志：哪里（坐标）、折返线多长、车多长，提醒把折返线做得至少和车一样长。字段 `shortTurnBacks` 记上次的结果。
