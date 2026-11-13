# SimDataset.java

仿真器需要的已保存世界数据，采用组件自己的格式（S4e）：线路及其停站与时刻表，车厂及其线路与运用，停车线及其列车与路径。游戏保存时写入数据库，日仿真工具不启动游戏就能读取。由它建立的每个世界都有自己的线路和列车，因此从它开始的仿真分叉之间不共享任何东西。

在整体中的位置：游戏侧 `SimExport`（`SimExport.java:49`/`:59`/`:89`/`:103` 构造 RouteData、DepotData、DataTrack 并 `applyStopPositions`）、`Siding.java:1872`/`:2017`（构造 SidingData、TripPathData）、`SandboxRuns`（`:127` `fromBytes`、`:197` `replaceRoute`、`:207` `replaceDuties`）、`LiveSimulation`（`:359` `buildRunner`、`:535`/`:540` `start`）、`EditorSimulation.java:141`（`RouteData.toBytes`）；组件 `DayRun`、`SimScope`、`PlanCheck`（`:82` `getFastestTimes`、`:97`/`:101`）、`SimulateDay`、`CheckDutyLayers`。构建运行器时使用 `TickRunner`、`DepotBoard`、`WorkPool`、`LockNetworks` 以及 plan 包的 `TripPath`、`PathTimes`、`StopTimes`、`TimeGenerator`、`TurnBacks`、`TimetableBinary`、`IndexedPath`、`StopPositions`。

## 类 SimDataset（final）

### 常量
- `VERSION = 19`：数据格式版本。2 加入列车状态（G0），3 信号闭塞区（G1），4 车厂按频率发车与停车线顺序、手动驾驶的停车线（G1），5 发车间隔与停车线池，6 线路自身最快时间（L1），7 跨运营线路车厂的停车线池，8 线路的运营线路，9 线路的终点/起点站台，10 联锁区标志，11 池总车数，12 只按运用发车，13 提前折返路径（D4），14 去运用分段起点的出库路径（D5），15 车辆最高速度，16 站台停车位置，17 早到阈值，18 最快时间多列（每时刻表一对），19 分段索引路径（`IndexedPath`），20 车厂采用的余量（0.75.0，`DepotData.addedBuffers`，`buildRunner` 交给 `DepotBoard.dispatcher.addedBuffers`，出库时按它提前发车）；更旧的数据缺少这些读取。（javadoc 只列到 15，其余由代码推断。）
- `UNLIMITED_TRAINS = 256`：不限车数的停车线最多发出的列车数（与游戏一致）。

### 字段
- `routes`（List<RouteData>）、`depots`（List<DepotData>）、`sidings`（List<SidingData>）。
- `trains`（RunRecord）：写数据时的列车状态（G0），用于从它们继续仿真；无则 null。
- `signalBlocks`（List<List<UUID>>）：信号闭塞区及其轨道键（`DataTrack.getTrackKey`）。
- `interlockBlocks`（List<Boolean>）：每个闭塞区是否为联锁颜色（联锁区，咽喉进路整体占用，L2）；旧数据为空。
- `stopPositions`（TreeMap<Long, Integer>）：设置了停车位置的站台 id → 设置值（第 4 轮，`StopPositions`），由 `applyStopPositions` 写到轨道上。
- `earlyThresholdMillis`（long）：网络早到阈值（第 4 轮第 5 步），旧数据为默认值。

### `boolean isInterlockBlock(int index)`
该下标的闭塞区是否为联锁区（越界为 false）。调用者：`SignalBlockTest.java:123`；`toBytes`、`buildRunner`。

### `byte[] toBytes()`
- 作用：按版本 19 格式序列化：版本、线路、车厂、停车线、可选列车记录、信号闭塞区，然后按版本递增顺序追加各扩展字段（车厂发车与停车线顺序、停车线手动标志、发车间隔、池键、线路最快时间、线路池、线路运营线、终点/起点站台、联锁标志、池总车数、只按运用、提前折返路径、车辆最高速度、出库路径、停车位置、早到阈值）。
- 调用者：`SimExport`（导出）、`DayRun.java:103`、`PlanCheck.java:97`、`SimulateDay`。

### `static SimDataset fromBytes(byte[] bytes)`
- 作用：读回；版本不在 [1, VERSION] 抛 `IllegalArgumentException("Unknown simulation data version …")`；按版本读取扩展字段（注意：版本 15 的车辆最高速度在版本 14 的出库路径之前读取，与写入顺序一致）；版本 ≥ 16 时读完停车位置后 `applyStopPositions`；早到阈值取 max(0, 值)。
- 调用者：`SandboxRuns.java:127`、`PlanCheck.java:97`、`SimulateDay`、`CheckDutyLayers`；本类 `start`。

### `void applyStopPositions()`
- 作用：把站台停车位置写到每条停车线所有路径（常用路径、服务路径、折返连接、回库路径、出库路径）的轨道上：站台轨道取 `stopPositions` 中其站台的设置（无为 0），其他为 0。路径是 `IndexedPath` 时更新其模板。修改 `stopPositions` 后需再次调用。
- 副作用：修改 `DataTrack.stopPosition`。调用者：`SimExport.java:89`、`StopPositionsTest.java:124`；`fromBytes`。

### `boolean replaceRoute(RouteData route)`
- 作用：用草稿替换同 id 的线路（S5）。草稿没有最快时间、且停站与通过标志相同、时刻表性能相同（`TimetableTimes.samePerformance`）时沿用原线路游戏算出的时间。
- 返回：无此 id 的线路时 false。调用者：`SandboxRuns.java:197`、`DayRunTest.java:72`。

### `boolean replaceDuties(long depotId, byte[] duties)`
- 作用：给车厂换一套运用（草稿，S5），保留其发车时刻、停车线顺序、发车间隔、只按运用标志。
- 参数：`duties` 为 `DepotDuties.toBytes` 的结果，空数组表示无。
- 返回：无此车厂时 false。调用者：`SandboxRuns.java:207`、`PlanCheck.java:101`。

### `static Supplier<TickRunner<DataTrack>> start(byte[] bytes, long startMillis)`
每次调用都从字节重新建立世界（不含列车），从该时刻开始。调用者：`LiveSimulation.java:535`、`DayRun.java:85`、`EventSimulator.resume` 的调用方。

### `TickRunner<DataTrack> buildRunner(long startMillis)`
- 作用：由数据建立独立世界：
  1. `buildRoutes` 生成线路，新建 `TickRunner`，设早到阈值、信号闭塞区、联锁区（非联锁区为空列表）。
  2. 每个车厂建 `DepotBoard`：循环线路、发车间隔（秒→毫秒）、只按运用、按频率发车时刻、停车线顺序、运用（`duties.fromBytes`）、车厂 id。
  3. 共同运行某线路的车厂互相 `shareServicesWith`（与游戏车厂一样共享服务）。
  4. 新旧逻辑不共用轨道：与不按运用运行车厂共轨的车厂关闭咽喉锁（`getLockNetworks().result().offDepots`）。
  5. 每条有路径的停车线：车辆线路键 `"siding:id"`；用 `untimed`+`timeGenerator` 生成时刻；建 `TickRunner.Siding`（路径、循环下标、停站时间、车厢数、车厢长、停车线长、加/减速度、最大车数、最低速度、车辆最高速度、速度曲线、手动、池键、线路池、池总车数）；服务路径在共享线程上各自计算时刻（`WorkPool.forEach`，第 5 轮第 4 步）并按顺序放入；设置 `pathVariants`（跳站 `passed`、加停 `withStop`、时刻生成器、折返目标/连接/回库、出库路径，D3–D5）；读取折返设置 `TurnBacks`；`runner.addSiding`。
- 返回：运行器（列车在运行时由停车线生成）。调用者：`LiveSimulation.java:359`、`FrequencyDeployTest.java:123`；`start`。

### `transitsim.network.LockNetworks getLockNetworks()`
车厂列车运行的轨道网络，并标出哪些车厂按运用运行：数据中有运用或只按运用（R2、R3）的车厂视为新逻辑。每条停车线常用路径与服务路径的节点加入网络。调用者：`SimulateDay.java:109`、`PlanCheckTest.java:170`；`buildRunner`。

### `private static long[] nodesOf(List<DataTrack> path)`
路径的节点序列（各轨道起点 + 最后终点）。

### `Map<Long, PlanRoute> getPlanRoutes()`
作为计划用的线路（标出只作为另一线路接续运行的服务，S6），TreeMap。调用者：`PlanCheck.java:75`、`OvernightPlacementTest.java:40`。

### `Map<Long, long[][]> getFastestTimes(long depotId)`
- 作用：车厂各线路每站最快到达/出发时间：优先用线路自身由游戏算出的时间（L1，拷贝）；没有的线路，沿该车厂各停车线路径按游戏停车线的方式计算，取第一个能到达所有站的停车线（S6）。
- 调用者：`PlanCheck.java:82`、`PlanCheckTest.java:83`。

### `private static void writeTimes(BinaryWriter, long[][])` / `private static long[][] readTimes(BinaryReader, int version)`
最快时间表：数量+1（0 表示 null），每站列数（版本 ≥ 18；之前固定 2）和各值；列数非法（< 2、> `MAX_TABLES×2` 或为奇数）抛 `IllegalArgumentException`。

### `private static long[][] copyTimes(long[][] times)`
深拷贝。

### `private Map<Long, DataRoute> buildRoutes()`
每条线路建 `DataRoute`；对每个时刻表的接续链接，在被接续线路的时刻表中登记该服务（只作为接续运行的服务不由任何车厂单独发出）。

### `private static TickRunner.Siding<DataTrack> untimed(SidingData sidingData)`
尚无停站时间的停车线列车模型（用于计算时间）：设最低速度与车辆最高速度。

### `private static StopTimes getStopTimes(SidingData sidingData)`
沿停车线常用路径生成停站时间（含循环接续下标）。调用者：`buildRunner`、`getFastestTimes`。

### `private static TimeGenerator<DataTrack> timeGenerator(TickRunner.Siding<DataTrack> siding)`
- 作用：按游戏停车线的方式在速度曲线上计算路径时间的生成器：`calculateStopTimes(path)` 和 `generateTimeSegments(path, timeSegments, stopTimes, joinIndex, lapEndIndex)`——清空后设接续下标，用 `RunTrack.toTrackView` + `SpeedCurves.of`（起点为停车线中央）+ `PathTimes.curveTimes` 计算，最后 `linkJoinStops(lapEndIndex)`。

### `private static List<PlanRoute> toRoutes(long[] routeIds, Map<Long, DataRoute> routeMap)`
按 id 取线路，缺失的忽略。

### `static String trackSignature(List<DataTrack> tracks)`
轨道列表序列化后的 Base64，用作 `IndexedPath` 模板签名。调用者：`readPath`。

### 私有 IO 辅助
- `writeTracks`/`readTracks`：varint 数量 + 每条 `DataTrack`。
- `writeLongs`/`readLongs`、`writeBytes`/`readBytes`：varint 长度 + 元素。
- `writeFloat`/`readFloat`：按位写 int。
- `writePath(BinaryWriter, List<DataTrack>)`：普通列表直接写；`IndexedPath` 写 −1 标记、去重的模板列表、每段（模板下标、站序偏移）。
- `readPath(BinaryReader, int version)`：读回；长度/数量超过 10⁶ 或标记非法（版本 < 19 或不是 −1）、模板下标越界时抛 `IllegalArgumentException`；分段路径还原为 `IndexedPath`（偏移用 `withStopIndexOffset`，签名用 `trackSignature`）。

## 内部类 RouteData（public static final）
一条线路：按序停站、每个时刻表每站设定的时间、时刻表字节。
- `id`、`transportModeId`、`platformIds`、`passes`（各站是否通过不停）。
- `arrivals`、`departures`（int[时刻表][站]）、`overtakes`（boolean[时刻表][站]：是否等待被越行）。
- `timetable`（byte[]）：`TimetableBinary` 格式。
- `fastestTimes`（long[][]）：游戏沿线路自身路径、按最慢车辆算出的各站最快到发时间（从首站离开起算，L1），无为 null。
- `lineKey`（String）：所属运营线路（交通方式与路线组，同游戏 `LineDuties`），未知为空；同一线路的车厂一起编制运用。
- `terminalPlatforms`、`startPlatforms`（long[]）：见 `PlanRoute.getTerminalPlatforms/getStartPlatforms`。
- 构造函数 `RouteData(PlanRoute route)`：从计划线路复制各项并序列化时刻表。调用者：`SimExport.java:49`、`EditorSimulation.java:141`。
- 私有构造函数：读档用。
- `byte[] toBytes()` / `static RouteData fromBytes(byte[])`：单独一条线路（如发送草稿，S5）。
- `private void write(BinaryWriter)` / `private static RouteData read(BinaryReader)`：id、交通方式、站台、时刻表数、每站（通过、各表到/发/越行）、时刻表字节。

## 内部类 DepotData（public static final）
- `id`、`routeIds`、`cycleRoutes`、`duties`（`DepotDuties.toBytes` 或空）。
- `departures`（int[]）：按频率发车的一天中时刻。
- `sidingOrder`（long[]）：游戏车厂轮流发车的停车线顺序。
- `releaseInterval`（int）：按运用发车的最小间隔（秒），0 为无。
- `dutiesOnly`（boolean）：只按运用发车（R2）。
- `addedBuffers`（`Buffers`，默认 `NONE`）：车厂采用的余量（版本 20），游戏 `SimExport` 填 `Depot.getAddedBuffers()`；`replaceDuties` 照抄。
- 构造函数 `DepotData(long id, long[] routeIds, boolean cycleRoutes, byte[] duties)`。调用者：`SimExport.java:59`。
- `write`/`read`（private）：基本字段。

## 内部类 SidingData（public static final）
一条停车线：它生成的列车、列车运行的线路、常用路径和离开它的服务路径。
- 车辆参数：`trainCars`、`spacing`、`railLength`、`acceleration`、`deceleration`、`maxTrains`、`usesSpeedCurve`、`minSpeed`、`vehicleMaxSpeed`（方块/tick，0 为无）。
- 路径：`path`（常用路径）、`repeatIndex1`、`repeatIndex2`、`loopJoinIndex`、`tripPaths`（List<TripPathData>）。
- `turnBacks`（byte[]）：`TurnBacks.write` 写入 `BinaryKeyed.Writer` 的结果，或空。
- `manual`：其列车可由玩家驾驶，只有玩家开出才离开。
- `poolKey`：本车厂中可互相执行运用的停车线的共同键，空为自成一池。
- `linePool`：同一运营线路一起编制的跨车厂停车线池（解耦文档 5.4，L1 之后）：编制单元键 + 池键，不按线路编制时为空；列车运用结束可回到其中任一停车线。
- `poolFleet`：池总车数（P2-C），−1 为按需。
- `turnLinks`（TreeMap<long[2], List<DataTrack>>，按两站台排序）：路径生成找到的从一个站台到同站另一站台的折返路径（D4）。
- `turnReturns`（Map<Long, List<DataTrack>>）：从折返到达的站台空车回停车线的路径（D4）。
- `wayOuts`（Map<Long, List<DataTrack>>）：出库到运用各分段起点站台的路径，供备用车接管和晚发列车使用（D5）。
- `void addTurnLink(long from, long to, List<DataTrack> tracks)` / `List<DataTrack> getTurnLink(long from, long to)` / `Collection<Long> getTurnTargets(long from)`：登记/查询折返路径，列出从某站台出发可折返去的站台。调用者：`Siding.java:1889`、`EarlyTurnTest.java:43`；`buildRunner` 的 `pathVariants`。
- 构造函数（16 参数）。调用者：`Siding.java:1872`。
- `write`/`read`（private）：基本字段、路径（`writePath`/`readPath`）、服务路径、折返设置；扩展字段由外层按版本写读。

## 内部类 TripPathData（public static final）
离开停车线的一条服务路径：`values`（`TripPath.write` 写入 `BinaryKeyed.Writer` 的字节）与 `path`（轨道）。构造调用者：`Siding.java:2017`。

## 内部类 DataTrack（public static final，实现 `RunTrack`）
保存路径中的一条轨道。
- 字段：`startNode`、`endNode`、`length`、`platform`、`siding`、`stopId`、`dwellTime`、`maxSpeed`、`canAccelerate`、`hasSignal`、`twoWay`、`convention`（仅双向轨道保留，否则 `CONVENTION_NONE`）、`stopIndex`（可变）、`stopPosition`（站台停车位置设置，可变，由 `applyStopPositions` 设）。
- 构造函数（12 参数，无约定）与（13 参数，带约定）。调用者：`SimExport.java:103`。
- `DataTrack withStopIndexOffset(int offset)`：同一轨道、站序加偏移的拷贝（`IndexedPath` 展开分段时使用）。（javadoc 写"列车通过不停的同一轨道"，与代码不符，疑为复制错误。）
- `DataTrack passed()`：停站时间为 0 的拷贝（跳站，D3）。
- `DataTrack withStop(int newDwellTime, long newStopId)`：在末端以该时长停于该站台的拷贝（D5）；站台不变时保留停车位置。
- `DataTrack withStopPosition(int setting)`：站台轨道带停车位置设置的拷贝（第 4 轮）。调用者：`PlatformStopsTest.java:59`。
- `double getStopOffset(double trainLength)`：站台且有设置时为 `StopPositions.getOffset(设置, 轨道长, 车长)`，否则 0。
- `write`/`read`（private）：节点、长度、标志字节（bit0 站台、bit1 停车线、bit2 可加速、bit3 有信号、bit4 双向、bit5 顺约定、bit6 逆约定；旧记录无后两位）、站台 id、停站时间、站序、限速。
- `RunTrack`/`PlanTrack` 访问器：`getMaxSpeed`、`canAccelerate`、`hasSignal`、`isTwoWay`、`getConvention`、`getStartNode`、`getEndNode`、`getTrackLength`、`getDwellTime`、`isPlatformTrack`、`isSidingTrack`、`getStopId`、`getStopIndex`/`setStopIndex`、`isOppositeOf`（另一条 DataTrack 起止节点互换）、`getTrackKey`（`UUID(较小节点, 较大节点)`）。

## 内部类 DataRoute（private static final，实现 `PlanRoute`）
由数据建立、拥有独立时刻表的线路。字段 `id`、`transportModeId`、`timetable`（从字节读入）、`stops`、`terminalPlatforms`、`startPlatforms`、`timingBaseline`（最快时间拷贝）。方法：`getTimingBaseline`、`getRouteId`、`getTimetable`、`getStops`、`isLoop`（超过 2 站且首站等于末站）、`getTerminalPlatforms`、`getStartPlatforms`、`getTransportModeId`。

## 内部类 DataStop（private static final，实现 `PlanStop`）
线路数据中第 `index` 站的视图：`getPlatformId`、`isPass`、`getArrival(table)`/`getDeparture(table)`（表越界为 −1）、`getOvertake(table)`（越界为 false）。
