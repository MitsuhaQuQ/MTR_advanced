# MTR 运行模拟组件：分离现状与目标架构

- **最后更新**：2026-11-03。F1、F2、S0、C、S1 已完成，契约见 `MTR-模拟组件契约.md`；F1 复审的两处问题已修（7.4 节）。开发顺序已按架构审查调整（第 5 节），审查的处理见第 7 节
- **相关文档**：
  - 设计文档 `MTR-3.x改造记录与寻路方案.md`：第 11 节当前优先级是开发顺序；11.5 节是架构摘要；8.5 节是速度曲线；6.12 节是运行图和运用。
- **维护规则**：每完成一个 S 或 G 步骤，更新第 2 节的分离状态表和第 6 节的进度。

## 1. 目标和判断标准

**目标**：一个可复用的组件，脱离 Minecraft 执行运行图和交通网络模拟。

- MTR 只是这个组件的一个宿主。它把轨道、线路、车厂换算成组件的数据，按现实时间查询每列车该在哪里，自己负责画车、开门、乘客和同步。
- 同一个组件也能直接运行在网页编辑器的服务端、命令行工具和单元测试里。

**分离程度分三级**，第 2 节的表按这个标：

| 级别 | 含义 | 能否进组件 |
|---|---|---|
| **A 纯逻辑** | 不引用 Minecraft、netty，也不引用 MTR 中依赖 Minecraft 的类 | 原样搬入 |
| **B 无世界** | 运行时不需要 `Level`、实体、玩家、方块和服务端 tick，但用到 Minecraft 的值类型（`BlockPos`、`FriendlyByteBuf`）或 MTR 的数据类（`Rail`、`Route`、`PathData`） | 要先换成组件自己的类型，再搬入 |
| **C 绑定世界** | 读写世界、实体或玩家，或者由服务端 tick 驱动 | 拆成"规则"和"表现"两部分，规则进组件，表现留在宿主 |

B 级的类已经能在单元测试里不开游戏运行，因为测试的类路径上有 Minecraft。但它们还不能给别的程序复用。

## 2. 当前分离情况

### 2.1 各类的级别

路径都相对于 `common/src/main/java/mtr/`；已经在组件里的写包名，源码在 `transit-sim/src/main/java/`。行数是 2026-11-03 的。

**线网和寻路**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `transitsim.motion.PathRelocator`（原 `path/PathRelocator`） | 82 | 无 | A | **已在组件**（S1） |
| `transitsim.network.RailGraph<R>`（原 `path/RailGraph`） | 175 | `Boundaries`、`PathRegions` | A | **已在组件**（S1）；轨道的载荷 `R` 仍由宿主给，MTR 用 `Rail` |
| `transitsim.network.RailRouter`（原 `path/RailRouter`） | 250 | `RailGraph`、`PathRegions` | A | **已在组件**（S1） |
| `path/DeterministicPathFinder` | 128 | `BlockPos`、`Rail`、`Platform`、`SavedRailBase` | B | 留在宿主：从 `Rail` 建 `RailGraph` 的适配器，寻路部分已在组件（`RailRouter`） |
| `path/PathData` | 121 | `BlockPos`、`Rail`、NBT、`FriendlyByteBuf` | B | 留在宿主，适配器把它换成组件的 `TrackView`（S1） |
| `path/PathFailure` | 86 | `BlockPos`、`Rail` | B | 留在宿主：带位置，给车厂界面显示；失败类型以后需要时再进组件 |
| `path/PathFinder` | 266 | `Rail`、`Vec3` | B | 旧寻路，留在宿主，不迁移 |
| `advanced/PathBoundaries` | 119 | 组件的 `Boundaries`、`FriendlyByteBuf`、msgpack | B | S1 后只剩存档和网络包，数据部分 `transitsim.network.Boundaries`（36 行）已在组件，`PathBoundaries extends Boundaries` |
| `transitsim.network.PathRegions`（原 `advanced/PathRegions`） | 251 | `RailGraph`、`Boundaries` | A | **已在组件**（S1） |
| `transitsim.network.RegionComputer`（原 `advanced/RegionComputer`） | 约 60 | `RailGraph`、`PlatformGraph` | A | **已在组件**（S1）；不再逐站算候选，改为算 `PlatformGraph` |
| `transitsim.network.PlatformGraph`（新） | 约 300 | `RailGraph`、`PathRegions` | A | **组件里的新规则**：站台之间不掉头怎么走，按线网（轨道连通分量）分开；有无头测试 `PlatformGraphTest` |
| `transitsim.network.Straightener`（新） | 约 150 | 无 | A | **组件里的新规则**：沿一条路径把轨道拉直成（里程，侧向偏移），给线路配线图用；宿主的 `advanced/RouteLayouts` 取点和写网络包；有无头测试 `StraightenerTest` |
| `transitsim.network.RegionData`（原 `advanced/RegionData`） | 约 40 | `Boundaries`、`PlatformGraph` | A | **已在组件**（S1）；带上 `PlatformGraph`，网络包读写在宿主的 `advanced/RegionDataPackets` |
| `transitsim.network.Network`、`NetworkBuilder`、`Resource`、`SectionType`、`RailAttributes`（原 `advanced/PhysicalGraph`） | 约 590 | 无 | A | **已在组件**（S1）：线网快照、压缩算法（对任意 `RailGraph<R>`，轨道属性由宿主通过 `RailAttributes` 给）、资源、版本。宿主只剩 `advanced/RailNetworks`（用 `Rail` 实现属性） |
| `advanced/GraphRecords` | 126 | `RailwayDataStore`、`PathData`、组件的 `Network` | B | 留在宿主：SQLite 读写，内容格式由组件的 `Network.Section`、`SectionPath` 定义 |
| `advanced/PathRegionSync` | 132 | `MinecraftServer`、`ServerPlayer`、网络包 | C | 留在宿主：后台计算触发和同步 |

**速度和运动**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `transitsim.motion.SpeedProfile`（原 `advanced/SpeedProfile`） | 234 | 无 | A | **已在组件**（S0） |
| `transitsim.motion.SpeedCurves`、`TrackView`（原 `advanced/SpeedCurves`） | 168 + 57 | 无 | A | **已在组件**（S1），改读 `TrackView`；宿主的 `advanced/TrackViews` 从 `PathData` 构造 |
| `data/TickCompensator` | 29 | 无 | A | G4 之后只给旧逻辑用，留在宿主 |
| `data/TransportMode` | 24 | 无 | A | 组件只需要其中"是否连续运动"等几个属性，用组件自己的枚举 |
| `data/RailType` | 46 | `MaterialColor` | B | 组件只要限速和能否加速，由适配器写进区间 |

**运行图、时刻和运用**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `transitsim.planning.*`（原 `operation/*`，7 个类） | 958 | 无 | A | **已在组件**（S0），S6 收拢 |
| `transitsim.plan.RouteTimetable`（原 `data/RouteTimetable`） | 473 | `ServiceExceptions`、`OperatingDay`、`PlanStop`、`PlanRoute` | A | **已在组件**（S3a）；`Route` 实现 `PlanRoute`，`Route.RoutePlatform` 实现 `PlanStop`；`StopPlan.route` 改为 `PlanRoute` |
| `transitsim.plan.ServiceExceptions`（原 `advanced/ServiceExceptions`） | 233 | `RouteTimetable`、`PlanStop`、`transitsim.io` | A | **已在组件**（S3a）；读写用组件的 `BinaryReader`、`BinaryWriter`，宿主用 `advanced/PacketBinary` 包住 `FriendlyByteBuf`，字节不变 |
| `transitsim.plan.TimetableBoundaries`（原 `advanced/TimetableBoundaries`） | 82 | `PlanRoute`、`RouteTimetable` | A | **已在组件**（S3a）；站台是不是时刻边界由宿主传入（`advanced/PlanAdapters.boundaryPlatforms`） |
| `transitsim.plan.TimetableConflicts`（原 `data/TimetableConflicts`） | 562 | `RouteTimetable`、`PlanStop` | A | **已在组件**（S3a） |
| `data/TimetableConfig` → `transitsim.plan.OperatingDay` | 124 | Gson（只在宿主） | A | **已拆开**（S3a）：时区、日起点和星期的换算在组件 `OperatingDay`，配置文件读写留在宿主 `TimetableConfig`，它的方法转调组件 |
| `transitsim.plan.DepotDuties`（原 `advanced/DepotDuties`） | 243 | `transitsim.io` | A | **已在组件**（S3a）；存成字节时用组件的 `ByteArrayBinary`，和 Minecraft 的 `FriendlyByteBuf` 逐字节相同（`PacketBinaryTest`），已有存档照常读入 |
| `transitsim.plan.DutyShapes`（原 `advanced/DutyShapes`） | 161 | `PlanRoute`、`DepotDuties`、`OperatingDay` | A | **已在组件**（S3a） |
| `transitsim.plan.TripPath`、`StopTimes`、`TimeSegment`（原 `Siding` 的内部类） | 约 800 | `PlanTrack`、`PlanRoute`、`RouteTimetable`、`ServiceExceptions`、`TimetableConflicts`、`transitsim.io` | A | **已在组件**（S3b）；路径的元素类型是泛型 `T extends PlanTrack`，宿主用 `TripPath<PathData>`；存档用组件的 `KeyedReader`、`KeyedWriter`，宿主 `advanced/MessagePackKeyed` 包住 msgpack，键和顺序都不变，路径本身仍由宿主写在最后；跑一趟要的时刻由宿主通过 `TimeGenerator` 算 |
| `transitsim.plan.TripTail`、`DutyPlan`、`TurnBackSection`、`DepotReturn`、`TurnBacks`、`TurnBackPoint`（原 `Siding` 的内部类） | 约 400 | `PlanTrack` | A | **已在组件**（S3b）；站台类型也是泛型（宿主用 `SavedRailBase`），组件只存不读；`DepotReturn` 改为给出折返的轨道序号，节点坐标由宿主取 |
| `transitsim.plan.PlanTrack` | 新 | 无 | A | S3b 新增：路径上一条轨道的视图（长度、停站时间、是否站台、站台或存车线 ID、停站序号、是否反向、轨道键），`PathData` 实现它。计划里叫 `PlanPath`，实际做成单条轨道的视图，路径就是 `List<T>` |
| `advanced/DutyGenerator` | 349 | `transitsim.planning` | B（取数据时用 MTR 类） | 规划部分进组件 `planning`，取数据的部分是宿主适配器（S6） |
| `data/Route` | 1111 | `BlockPos`、NBT、`FriendlyByteBuf`、`PathData` | C 的数据类 | 留在宿主，适配器换成组件的线路 |

**列车运行**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `transitsim.sim.TrainService`（原 `data/TrainService`） | 1139 | `PlanTrack`、`PlanRoute`、`TripPath`、`StopTimes`、`RouteTimetable`、`DepotDuties`、`DutyShapes`、`transitsim.io` | A | **已在组件**（S3c）；路径元素是泛型 `T extends PlanTrack`，宿主用 `TrainService<PathData>`；`Vehicle<T>`、`Board` 接口用组件的 `PlanRoute`；存档用 `KeyedReader`、`KeyedWriter`，`TrainServer` 照旧写同样的 12 个键 |
| `Siding.StopTimes`、`Siding.TripPath`、`TurnBack*`、`DepotReturn`、`DutyPlan`、`TimeSegment` | 约 1200（在 `Siding` 内） | 嵌在依赖 Minecraft 的 `Siding` 里 | B（逻辑本身不需要世界） | 抽成组件 `plan` 和 `sim` 的独立类（S3），`Siding` 只留路径生成的调度和存档 |
| `transitsim.sim.RunReservations`（原 `advanced/RunReservations`） | 168（F1 后） | 组件的 `RailKeys` | A | **已在组件**（S0） |
| `transitsim.network.RailKeys` | 16 | 无 | A | **已在组件**（S0）：两个节点之间轨道的键，原来是 `PathBoundaries.segmentKey`，`RunReservations` 搬进组件时发现它暗中依赖这个方法 |
| `data/SignalBlocks` | 293 | `BlockPos`、`DyeColor`、NBT | C 的数据类 | 占用规则抽成组件的 `Occupancy`（S2），信号颜色和存档留在宿主 |
| `data/Train` | 932 | `Level`、`Player`、物品、方块、NBT | C | 推进规则拆成组件的 `TrainMotion`（S2），见 2.2 节 |
| `data/TrainServer` | 852 | `Level`、方块实体、漏斗、玩家 | C | 占用、出车、方向预约、扣车拆进组件（S2 至 S4）；门、乘客、传感器、漏斗留在宿主 |
| `data/TrainClient` | 402 | 客户端渲染、声音 | C | 留在宿主；G2 起按运行段自己算位置 |
| `data/Siding` | 2343 | `Level`、`MinecraftServer`、网络包 | C | 内部的时刻和路径类拆出（S3），其余留在宿主 |
| `data/Depot` | 1372 | `Level`、`MinecraftServer`、网络包 | C | 出车判断已拆成组件的 `transitsim.sim.DepotDispatcher`（S3c）：车次预约、各线路上次派出的班次、运用的出车记录、下一班的出车时间、按运用出车时选哪个库位；`Depot` 留 `MainPath` 寻路调度、存档（这些记录仍存在原来的键里）和让列车出库 |
| `data/RailwayData` | 1196 | 服务端、玩家、存档 | C | 留在宿主：持有组件实例，每 tick 驱动和查询 |

**合计**：
- A 级约 1500 行，可以直接搬，已在 S0 搬进组件；S1 又把线网、寻路、区域和速度曲线约 1900 行搬了进去（含新写的快照、资源和 `TrackView`）；
- B 级原有约 5000 行，S1 后还剩约 3100 行，主要是运行图（S3）和 `TrainService`；
- C 级里要拆出规则的，主要是 `Train.simulateTrain` 的推进部分、`TrainServer` 的占用和出车，以及 `Siding` 里约 1200 行的时刻和路径类。

### 2.2 `Train.simulateTrain` 的组成

`Train.java:588-767` 现在把规则和表现写在一起：

| 行 | 内容 | 归属 |
|---|---|---|
| 602-612 | 不在线上时：复位，等 `canDeploy` 后 `startUp` | 规则（出车） |
| 618-623 | 跑到路径末端时判回库（`keepsOnLineAtEnd` 例外） | 规则 |
| 625-658 | 停站：环线续圈、`onStopReached`、停站计时、`isHeldAtStop`、前方占用、起步 | 规则 |
| 660-672 | 闭塞前瞻：`findBlockedIndex` 定下一个停车点 | 规则（占用） |
| 674-698 | 速度：速度曲线、旧的制动和加速、手动驾驶档位 | 规则 |
| 700 | 连续运动的交通方式边走边开门 | 表现 |
| 704-719 | 推进：环线不折返的续圈、越过停车点时停下或通过 | 规则 |
| 721-729 | 门的开度 | 表现 |
| 731-763 | 各节车厢坐标、`handlePositions`、`calculateCar`、`simulateCar` | 表现 |

规则部分（602-719）通过这些钩子和子类交互：

- `canDeploy`、`startUp`；
- `onStopReached`、`isHeldAtStop`、`onLapComplete`；
- `isRailBlocked`、`keepsOnLineAtEnd`、`openDoors`。

S2 把这些钩子整理成一个接口，规则搬进组件的 `TrainMotion`。`speed`、`railProgress`、`doorValue` 等状态字段仍留在 `Train` 上，由一个状态接口读写。

**S2a 以后（2026-11-03）**：上表的规则部分已在组件的 `transitsim.motion.TrainMotion`（S2c 起服务端开速度曲线的自动列车改沿运行段 `RunSegment` 走，见 3.5 节），通过 `MotionState`（状态）、`MotionTrack`（路径）、`MotionVehicle`（车辆参数）、`MotionHooks`（上面的钩子，加上 `isClientSide`、`getTotalDwellTicks`、`isRepeat`、清空乘客）、`MotionCurves`（速度曲线）读写宿主。`Train` 用内部类 `Motion` 实现这五个接口，字段和可覆盖的方法都不变，`simulateTrain` 只剩"调规则、算车厢"两步。原来的规则逐字保留为 `transitsim.motion.legacy.LegacyTrainMotion`，仪表盘设置菜单里可以切换（`MotionConfig`，`config/mtr_motion.json`），S2 测完后删掉。

### 2.3 已经有的接口缝

下面这些接口已经在用，迁移时沿用，不重新设计：

- **`TrainService.Vehicle`**：列车的路径、停车点、换路径、折返预约、移到某站、回库。
- **`TrainService.Board`**：车厂提供时刻表、线路、空闲班次、车次预约、运用库位和出车记录。
- **`TrainService` 的时钟**：可替换，测试里用固定时间。
- **`Train` 的钩子**：见 2.2 节。
- **`RailwayData.simulateTrains(RailNearPlayer)`**：每 tick 的入口，按"轨道是否靠近玩家"参数化。ANTE 的 mixin 只传入它自己的判断。
- **`RailwayDataStore` 的线网读写**：`replaceGraphSections`、`writeGraphPath`、`forEachGraphSection`、`getGraphFingerprint`。SQLite 的 `graph_sections`、`graph_paths` 表以后改由组件的 `io` 定义内容格式。

### 2.4 不开游戏就能跑的测试

宿主的测试在 `common/src/test/java`，组件的测试在 `transit-sim/src/test/java`，分别用 `./gradlew :common:test` 和 `./gradlew :transit-sim:test` 运行，1.20.1 构建时两边都跑。下面这些覆盖的是将来进组件的逻辑，标"组件"的已经搬过去，其余搬类的时候跟着移：

| 测试 | 覆盖 |
|---|---|
| `transitsim.planning.OperationSimulatorTest`（组件） | 运用规划、确定性、跨午夜、晚点恢复、对齐 |
| `transitsim.network.RailRouterTest`、`transitsim.motion.PathRelocatorTest`（组件）、`path/StopSettingsTest`、`path/PathFinderTest` | 确定性寻路、刷新时列车保留原位、停站设置、旧寻路 |
| `transitsim.network.PathRegionsTest`（组件） | 寻路边界和区域划分 |
| `transitsim.network.NetworkTest`（组件，7 项）、`advanced/RailNetworksTest`（5 项） | 区间压缩、限速分段、ID 稳定、路径换算、读写、旧格式读入、四种资源的划分、版本和只读；`Rail` 的适配 |
| `transitsim.motion.SpeedProfileTest`（组件，10 项）、`transitsim.motion.SpeedCurvesTest`（组件，5 项）、`advanced/SpeedCurvesTest`（2 项） | 速度曲线、巡航上限；按轨道限速和折返分段；从任意位置和速度起步（S2b）；`PathData` 的适配 |
| `transitsim.motion.TrainMotionRestartTest`（组件，2 项） | 区间里被拦停后重新起步：时间够时准点到，不够时和原来一样尽快跑 |
| `transitsim.motion.MotionEventTest`（组件，6 项）、`transitsim.sim.OccupancyTest`（组件，4 项） | 事件的顺序、同样的运行得到同样的事件、旧规则和客户端没有事件、排序规则；司机命令；占用的两个 tick 和"先占先得" |
| `transitsim.motion.RunSegmentTest`（组件，7 项） | 运行段：推进步长不影响位置、换段时位置和速度连续、来不及停车时紧急制动且不越过停车点、到站时刻和曲线一致且不晚于旧规则、被占轨道前停住、运行中切换新旧规则不跳 |
| `transitsim.sim.RunReservationsTest`（组件） | 带方向的区间预约 |
| `advanced/ServiceExceptionsTest`、`advanced/TimetableBoundariesTest` | 班次例外、时刻边界 |
| `advanced/DutyGeneratorTest`、`advanced/DutyShapesTest` | 运用生成、运用形状、旧格式读入 |
| `transitsim.sim.TrainServiceTest`、`DutyRunTest`（原在 common） | 班次决策、停服对齐、环线全天、按运用运行；S3c 起在组件里跑，用组件的测试替身 `TestRoute`、`TestTrack`，不需要 Minecraft |
| `data/RouteTimetableTest`、`data/TimetableConflictsTest`、`data/TimetableConfigTest` | 时刻表、冲突检查、时区 |

**列车推进**（2.2 节的规则部分）：S2a 起有组件里的逐 tick 对照测试 `TrainMotionEquivalenceTest`（12 项），新旧规则在合成路径上跑同一组场景（站停、折返、两种环线、闭塞、扣车、速度曲线和巡航上限、手动驾驶、缆车、回库、留在线上、客户端），每 tick 比较全部状态，步长 1 和 1.7 各一遍。宿主的钩子由测试替身 `MotionFixture` 模拟。

## 3. 目标架构

### 3.1 组件和宿主

```text
┌──────────────────────── 组件 transit-sim（不依赖 Minecraft）────────────────────────┐
│ network   线网：节点、区间、边界、区域、区间级寻路                                  │
│ plan      运行图：线路、时刻表、例外、时刻边界和直通、车厂、库位、运用              │
│ motion    运动：TrackView、速度曲线、运行段                                         │
│ sim       模拟：事件推进、占用、方向预约、班次决策、扣车、锁死检测                  │
│ planning  规划：运用生成、时刻冲突检查                                              │
│ io        组件自己的二进制格式                                                      │
└────────────────────────────────────▲──────────────────────────────────────────────┘
                                     │ 构造数据、送事件、按时间查询
┌──────────────────────── 宿主 MTR ──┴──────────────────────────────────────────────┐
│ 适配器：Rail→线网，Route/Depot/Siding/DepotDuties→运行图，PathData→TrackView       │
│ 游戏侧：车厢坐标、门、乘客、传感器、漏斗、渲染、声音、网络同步、存档               │
│ 旧逻辑：没开速度曲线的车厂、缆车、飞机、手动驾驶（按 tick，作为外部车登记占用）    │
└───────────────────────────────────────────────────────────────────────────────────┘
          其他宿主：网页编辑器服务端、命令行工具、单元测试
```

### 3.2 组件的硬约束

- 包名 `transitsim`，下分 `network`、`plan`、`motion`、`sim`、`planning`、`io`。
- 不引用 Minecraft、netty、msgpack、Gson 和 MTR 的任何类。
- ID 一律用 `long`，坐标 ID 由宿主编码，组件不解释。
- 默认用 Java 8 源码级别，需要时可以提高（1.16.5 不是硬性要求 Java 8）；提高前在文档里写明原因和受影响的版本（2026-11-03 调整）。
- 独立的 Gradle 子项目。common 依赖它，Fabric 和 Forge 的 jar 都要把它打进去。
- 日志、时钟、存储都由宿主通过接口提供。
- 结果确定：同样的输入和时间，算出同样的结果，和调用顺序、线程无关。

构建接入（S0 已实现）：

- `settings.gradle` 引入 `transit-sim`。它是普通的 `java-library` 项目，自己的 `build.gradle` 只有 JUnit。
- 根目录 `build.gradle` 用 `isComponent` 把它排除在 Loom、shadow、Minecraft 依赖、architectury 插件、Manifold、`setupFiles` 和 Java 版本设置之外。
- 组件自己的 `build.gradle` 写了仓库、JUnit 和按 Java 8 编译（`options.release = 8`，需要时可以提高，见上）。所以组件里写了 Java 8 用不了的 API，或者引用了 MTR 的类，编译时就会报错。
- 组件有自己的 `settings.gradle`，可以不经过模组单独构建：`./gradlew -p transit-sim test --offline`。依赖缓存过以后不用联网，也不需要 Minecraft 的任何东西，大约 7 秒。在模组里构建时，它仍是 `:transit-sim` 子项目。
- common 用 `api project(":transit-sim")` 依赖它。Fabric 和 Forge 各加一行 `implementation`，再加一行 `shadowCommon`，把组件的类打进完整包、`-dev-shadow` 包和服务端精简包。

### 3.3 数据对应

| 组件 | 宿主 MTR 的来源 |
|---|---|
| 线网节点 | 轨道节点的 `BlockPos.asLong()` |
| 区间 | `PhysicalGraph` 现在的压缩结果：节点序列、按限速分段的长度、类型、站台或存车线 ID、区域、可走方向 |
| 边界、区域 | `PathBoundaries`、`PathRegions` |
| 线路 | `Route`：站台序列、停或过、停站时间、必经节点、折返线 |
| 时刻表 | `RouteTimetable`、`ServiceExceptions`、`TimetableBoundaries`、`TimetableConfig` 的时区和日起点 |
| 车厂、存车线、库位、运用 | `Depot`、`Siding` 的设置、`DepotDuties` |
| 列车路径 | `Siding` 的主路径和车次路径，按 `GraphRecords` 记录的区间序列，或由 `PathData` 构造的 `TrackView` |
| 列车车辆参数 | 编组长度、加速度、减速度、是否用速度曲线 |

### 3.4 对外接口（草案）

- **`Network`、`Plan`**：由宿主构造，或从组件格式读入。数据变化时整体替换，或按区间、线路增量更新。
- **`Simulator`**：
  - `advanceTo(time)`：推进到这个时间，期间的事件按时间先后处理。
  - `query(trainId, time)`：返回路径序号和路径上的位置、速度、是否停站、门的状态、扣车原因、当前班次和段。
  - `events(from, to)`：返回到站、发车、通过、扣车、冲突、锁死这些事件。
- **宿主回调**：
  - 外部占用：手动驾驶和旧逻辑的列车占着哪些区段；
  - 世界事件：改轨道、刷新路径、传感器条件、玩家挡车；
  - 时钟；
  - 日志。

### 3.5 图级模拟

列车位置由实际时间算，不靠 tick 累加。

- **运行段**：两站之间的一次运行是一个运行段，包括起点、出发时刻和 `TrackView` 上的速度曲线。位置是时间的函数，`SpeedProfile.getPosition` 已经能算。
- **事件**：到站、发车、扣车解除、进出闭塞和预约区段。
- **占用**：记成区段上的时间区间。被挡的列车重算它这一段。
- **由此得到的好处**：
  - 停服对齐有了确定的基础，但要按明确的恢复策略做，不是"按现在的时间查一次"就行（A01）；
  - TPS 补偿不再需要；
  - 离玩家远的列车省掉渲染和高频的位置计算，但它们的事件仍要处理，因为会影响全网的占用和预测（A10）；
  - 预测到发、PIDS 和网页的实际运行线都读模拟，但预测在沙箱分支里算，不改实际运行。
- **重算**：被挡的列车从当前位置和速度出发，生成满足制动约束的新运行段；旧段未来的事件作废，只让依赖它的预测失效（A04）。
- **宿主每 tick 只做查询**：把结果写回 `Train` 现有的 `speed`、`railProgress`、`doorValue` 等字段，附属模组和 ANTE 读字段的方式不变。
- **客户端**：收运行段和事件，自己按时间算位置。

### 3.6 契约（C 阶段已写定）

架构审查（第 7 节）指出，只搬类和换类型不够。C 阶段把组件和宿主之间的约定写成了单独的文档 `MTR-模拟组件契约.md`，S1 起按它实现；要改契约，先改那份文档。要点：

- **时间**（A02）：模拟时间只进不退，组件不读系统时钟，现实时间到模拟时间的映射由宿主决定；时钟回拨就停住，前跳 60 秒以内正常推进，超过 60 秒按恢复处理；班次按（线路、表、营业日、本地时刻）标识；同一时刻的事件有固定顺序；确定性的说法是"相同初态、版本和有序输入，得到相同结果"。
- **状态归属**（A06）：每项状态只有一个写入方；G1 以前宿主拥有全部状态；G1 起自动列车的状态归组件，`Train` 的字段只是投影；手动接管、释放、移除、扣车都是命令。
- **资源**（A03）：分四种（轨道段、道岔冲突区、单线方向区段、站台泊位）和三级（实际占用、许可和锁闭、预测占用）；停车不等于离开；闭塞、方向预约和联锁共用一个仲裁器；申请全有或全无；防饥饿；锁死只报告和限制扩散，不擅自释放。
- **版本**（A05、R04）：线网、计划、路径、运行段都有版本；后台构造、校验后原子发布；运行实例钉住开始时的版本，只在交接点换；线网改了以后，按"路径仍有效、前方失效、脚下失效"三种情况处理。
- **运行、预测、恢复**（A01）：预测用 `fork` 出的沙箱；恢复沿用 11.2 节"不追赶、直接对齐"的规则，但加上物理校验，清掉许可，离线期间的事件不补发。
- **事件**（A07）：查询只读；事件有稳定 ID，宿主用游标消费并按 ID 去重；低 TPS 时也要按顺序处理全部事件；运行段被替换时，旧事件作废；游标和快照写在同一个事务里。
- **性能**（A10）：给出了指标和暂定目标，以及基准场景。
- **接口草案**：`Simulator` 提供 `submit`、`advanceTo`、`query`、`getSegment`、`events`、`fork`、`snapshot`。

契约文档每一节都附有"现状对照"，写明现在的代码还差什么、由哪一步补上。

### 3.7 参考 MTR4 的分离做法（2026-11-03）

MTR4 把模拟拆成独立的 Transport Simulation Core（`org.mtr.core`，MIT 协议），模组只和它交换消息。这里只借它的边界做法，不照搬整个架构。依据是 `P:\MTR4` 的模组侧调用（`Init`、`MinecraftOperationProcessor`、`VehicleExtension`、`PersistentVehicleData`）和核心 jar 的类名；核心源码不在这份检出里，细节要以它的源码为准。

**MTR4 的做法**：
- 核心自己拥有全部数据（车站、线路、车厂、列车）、寻路、出车和存档，还自带网页服务器。
- 模组用 `sendMessageC2S(key, 数据, 回调)` 发命令，例如 `SET_TIME`、封锁轨道 `BlockRails`、信号 `SignalBlockUpdate`。
- 每个世界 tick 结束时，模组用 `processMessagesS2C` 取回核心的消息，主要是按玩家发出附近列车的 `VehicleUpdate`。
- 不开线程模式时，模组每 tick 调一次 `manualTick()`；开了线程模式，核心自己推进。
- 客户端的 `VehicleExtension` 继承核心的 `Vehicle`，用同一份 `simulate(millisElapsed)` 在本地外推；服务端更新到达后，偏差慢慢抹平，不跳车。

**借用的**：

| MTR4 的做法 | 落到哪一步 |
|---|---|
| 命令和事件都是可序列化的对象，进队列；宿主在 tick 末统一取回事件，回调回到服务端线程执行 | S2 起 `submit` 和 `events`（3.4 节、契约 A07）就用这个形状，先同步执行；以后真要开线程，只换执行方式，接口不变 |
| 客户端继承核心的列车类，用同一套代码外推，加平滑校正（偏差每帧按固定量收回，超过一节车长就直接对齐） | G2 客户端按运行段计算时照着做；S2 先让服务端和客户端共用 `TrainMotion` |
| 只有 Minecraft 知道的事实（方块信号、被封锁的轨道）由宿主作为数据推给核心，核心不读世界 | S2 的 `Occupancy` 接口：宿主推入外部占用，组件只读这份数据 |
| 时间由宿主用 `SetTime`（游戏时刻、一天多少毫秒、昼夜是否推进）告诉核心 | 和契约的时间规则（A02）一致：组件不数 tick，也不读系统时钟，由宿主映射 |

**不借的**：
- **数据和存档整体搬进核心，外加网页服务器**：MTR4 是整体重写，旧存档靠 `legacy` 转换。这里要保已有的 3.x 存档内容和附属模组 ABI，宿主仍拥有数据，组件拿快照计算；也仍不做独立进程（第 4 节）。
- **它的列车规则**：`Vehicle` 仍按车厂路径逐步推进加轨道占用，没有运行图、运用、方向预约，也没有"出发时刻加曲线得到位置"。车辆规则按本文 S2、S3 和图级模拟（3.5 节）来，不以它为模板。
- **一次性切换**：S2 要求新旧逻辑逐 tick 对照一致，按步迁移。

## 4. 约束和风险

- **自己的接口**（2026-11-03）：本分支（MTM）自己实现的部分可以在 MTR 的基础上定义自己的接口（类、数据结构、网络包、命令、配置、编辑器接口），不必沿用 MTR 原有的定义。附属模组直接用到的 MTR 公开字段和方法仍然保留（见下一条 ABI），新功能不必再套进 MTR 的旧接口里。
- **附属模组 ABI**：附属模组直接读写 `Train` 的字段和方法。字段保留，只改写入方式；每一步都扫描 MSD、YJCM、Metropolis、SHMetro。
- **ANTE 的运行逻辑**（2026-11-03 调整）：ANTE 里和列车运行有关的注入（`TrainMixin`、`TrainClientMixin`、`SidingMixin` 对推进和调度的注入，`RailwayDataMixin` 的每 tick 入口）不再要求保持兼容，我们的实现取代它们，需要时可以直接删掉或改写。ANTE 的渲染、模型、声音、脚本等其余部分照旧保留，改到它们用到的类时仍要核对注入点。
  - 2026-11-03 核对时的注入点：`TrainMixin` 注入构造、`toMessagePack`、`messagePackLength`、`writePacket`、`scanDoors`、`convertMaxManualSpeed`；`TrainClientMixin` 注入 `TrainClient.simulateTrain` 的入口；`SidingMixin` 注入 `Siding.simulateTrain` 的入口；`RailwayDataMixin` 委托给 `RailwayData.simulateTrains(RailNearPlayer)`，并按名字和类型引用 `trainPositions`。
- **存档**（2026-11-03 调整）：已有的存档内容保持不变，旧存档照常读入；新功能需要时可以主动新增存档内容（新的键、新的表），旧版本读到时忽略。原来写的"S3 之前存档字节不变"是这份文档自己定的，不是需求，已经取消。改变已有内容的格式（如列车运行状态换成组件格式）仍放到 G4，并加独立于协议版本的存档版本号。
- **服务端和客户端一致**：两边用同一个运动内核。服务端开速度曲线的列车按运行段走，客户端沿同一条曲线逐帧跟随、由服务端校正；G2 起客户端也按运行段算。
- **性能**：事件推进要比逐 tick 便宜。S4 用大线网基准对比，确认全网一天的模拟在秒级以内。
- **模组构建要联网**：组件已经能单独、离线构建（R05 已修）。模组本身仍在配置阶段联网，读 Minecraft、Fabric、Forge 的版本元数据并下载映射等文件，现在每次最多试 5 次，每次都有超时，没有网络时会报错停止，不再无限等待。
- **附属模组的行为兼容**：字段和方法保留只能保证二进制兼容。G1 以后字段由组件投影写入，附属模组直接写的值可能马上被覆盖，所以除了 ABI 扫描还要做行为验证（A06）。
- **版本混用**：改表、改运用、改轨道以后，正在跑的列车不能混用新旧版本（R04、A05）。F2 先修运用这一项，S1、S3 再做完整的版本。
- **独立线程和进程**（2026-11-03 调整）：有必要就做。接口已经按 3.7 节的队列形状设计，切换时只换执行方式。

## 5. 开发顺序

2026-11-03 按架构审查（`MTR-设计与架构审查评估-2026-11-03.md`，处理见第 7 节）调整。

### 5.1 开发顺序的规则（2026-11-03 调整）

**现在的规则**：

- **留断点，不设闸门**：每一大步（S3 起）开始前打标签（如 `pre-s3`）、存下旧 jar，必要时加新旧对比开关；不再等前面的实测全部做完。实测照常进行，结果用来区分问题是新引入的还是原来就有的。
- **解冻，规则写在组件里**：S2 以后推进规则在组件里。新的运行规则功能（站前折返、通过站限速、闭塞分区、联锁、晚点换车、切断直通、回送）直接写在组件里，配无头测试，宿主只写适配。
- **修 bug 修在规则现在所在的地方**：推进规则在组件 `TrainMotion`；旧内核 `LegacyTrainMotion` 只作对照，不修，S2 测完、用户确认后和开关一起删掉。
- **对照**：tick 模式的部分保持和旧内核一致（`TrainMotionEquivalenceTest`），直到删掉旧内核。
- **穿插**：界面、编辑器、PIDS、放送音频、OBJ、性能、MTR 4 资源包等照常穿插。
- **并行的会话不构建**（2026-11-03）：同时推进的会话只写代码和测试，不构建模组、不启动测试服务端、不放测试 jar；改动和要验证的内容记在 `MTR-待验证.md`，做完以后由一个会话统一合并、构建、测试。组件的单元测试可以在自己的临时目录里单独离线跑。协议版本统一构建时再加。

以下是 S2 之前的规则和理由，留作记录。

#### 原来的规则：先收口现有实现，同时冻结新功能，再分离

**游戏内实现的现状**：

- 已写完、但没进游戏测：高级编辑器、班次例外、时刻边界、站后多条折返线、车厂循环、速度曲线、E2 等，见测试清单。
- 已知有问题：E2 的方向预约（R01、R02），运用没有快照（R04）。
- 还没写的功能：站前折返、通过站限速、闭塞分区、联锁、晚点换车、切断直通、回送、配线图。它们都要改运行规则，大多要用资源语义（A03）。

**为什么不先把游戏内全部做完**：

- 闭塞分区和联锁按 tick 逻辑写一遍，分离时还要按资源契约重写一遍。
- 在没有资源模型的结构上做，闭塞、预约、联锁会各批各的。
- `Train`、`TrainServer`、`Siding` 越写越大，越难拆。

**为什么不先全部分离**：

- S2 要求新旧逻辑一致。可旧逻辑没进游戏验证过，"一致"就没有意义。
- 重构中出的问题，分不清是重构引入的还是原来就有的。
- E2 的安全问题会一直留着。

**做法**：

- **收口**：先修 F1、F2，把已写完的功能测完、修完，作为分离的基线。
- **冻结**：从现在起，新的运行规则功能不再写进现有的 tick 代码，等组件。修 bug 照常在现有代码里修，搬类时一起带过去。
- **并行**：不改行为的分离（S0 补完、C、S1）可以和实测并行，不影响实测结果。
- **闸门**：速度曲线和 E2 的实测（T79 至 T89）通过，或者剩下的问题已经列明、不影响基线，才开始 S2 和 S3。这两步要动 `Train`、`TrainService`、`Siding`。
- **穿插**：不碰运行规则的项目照常做，比如界面和编辑器修正、PIDS 显示、放送音频、OBJ 解析、性能。

### 5.2 步骤

**每步的通用条件**：

- 构建通过；
- 单元测试全过；
- ABI 扫描没有新的缺失；
- Fabric 和 Forge 服务端能启动。

F1、F2 会改游戏里的行为，要补游戏内测试。S0 至 S3 不改行为。

**阶段一：收口（游戏内）+ 不改行为的分离（并行）**

| 步骤 | 内容 | 完成标准 |
|---|---|---|
| **F1 预约安全修复** | R01：按运用跑的车在线上每 tick 续订，速度为 0 也续（区间挡停、站内扣车），到站、回库、删除或换路径时显式释放。R02：从车尾所在的轨道开始续订，车尾清出后才放掉。R03：存车线按 ID 排序后遍历；同 tick 的出站申请先收集，再统一仲裁，顺序是计划发车时刻、已等待时间（防饥饿）、列车 ID。仲裁写在组件的 `RunReservations` 里 | 组件单元测试覆盖：区间挡停续订、车尾交接、同 tick 对向申请、等待老化、乱序申请结果相同；游戏内 T86 加测区间挡停和长列车 |
| **F2 运用快照** | R04 的最小修复：列车出库或接过夜运用时，复制当天的运用段，此后只读这份；改了运用，第二天或刷新路径后才生效。时刻表的版本隔离放到 S3 | `DutyRunTest` 加"跑到一半改运用"的场景 |
| **S0 骨架** | 建子项目并接进构建；原样搬入 `SpeedProfile`、`RunReservations`、`mtr/operation`。**补完**（R05）：组件能单独、离线构建和测试（`transit-sim/settings.gradle`，仓库和 Java 8 设置写在组件自己的 `build.gradle` 里）；根 `build.gradle` 的 `getJson`、`getXml` 改为有限重试并设超时 | jar 里有组件的类；五个版本都能编译；断网时 `./gradlew -p transit-sim test` 能跑 |
| **C 契约**（已完成） | 写定契约，只写文档和接口草案：`MTR-模拟组件契约.md` | S1 至 S4、G0 至 G4 的每一步都能对上契约里的条款（契约文档第 10 节） |
| **S1 线网和资源**（已完成） | 组件的线网：`RailGraph`、`RailRouter`、`Boundaries`、`PathRegions`、`RegionComputer`、`RegionData`；线网快照 `Network`（区间、资源、版本，不可变）和压缩 `NetworkBuilder`；`TrackView`、`SpeedCurves`、`PathRelocator`。宿主留下适配器 `RailNetworks`、`TrackViews`、`RegionDataPackets` | 线网相关测试都在组件里通过；`graph_*` 表的内容由组件格式写入，旧数据能读 |

**闸门**（已取消，见 5.1 节）：原来要求 T79 至 T89 实测通过才开始 S2 和 S3。速度曲线 T79 至 T82 已通过（2026-11-03）；S2 没等 E2 就做了，S3 也不等。

**闸门的调整（2026-11-03）**：速度曲线 T79 至 T82 通过后，不再等 E2 的 T83 至 T89 测完才开始 S2。先留断点：标签 `pre-s2`（`acf7fda`），对应的测试 jar 存在 `P:/mtr-ante/test-jars/baseline-pre-s2`（1.20.1 的 Fabric 和 Forge）。S2 以后测到和运行相关的问题，先换回这组 jar 在旧版本上复现：旧版本也有，就是 S2 之前就有的问题，照 E2 的问题处理；旧版本没有，就是 S2 引入的，按 S2 的问题修。换 jar 前先备份存档；S2 不改存档，所以同一个存档两边都能打开（协议版本不同，客户端和服务端要用同一组 jar）。不换 jar 时，先在仪表盘的设置菜单里勾"使用旧的列车推进逻辑"切到旧规则复现；它只区分推进规则本身，S2 改到的适配代码两边都会走，排除不了的再换 jar。

**阶段二：分离（游戏内只修 bug，不加运行规则功能）**

| 步骤 | 内容 | 完成标准 |
|---|---|---|
| **S2 运动内核** | `TrainMotion` 进组件，分两种模式：tick 模式行为不变；曲线模式是出发时刻加曲线得到位置。`Train` 通过状态和钩子接口调用它；加 `Occupancy` 接口。`SpeedProfile` 支持从任意位置、非零速度起步（A04），并去掉 E2 里"中途重启不限速"的变通。运行段带版本；区间重算从当前位置和速度出发，满足制动约束，来不及停车时有明确的异常规则。接口按 3.7 节借 MTR4 的形状：命令和事件走队列、宿主在 tick 末取回；外部占用由宿主推入 `Occupancy` | 新旧逻辑在合成路径上逐 tick 对照一致，场景包括站停、通过、折返、环线、闭塞前瞻、速度曲线、手动驾驶；非零初速的曲线有测试 |
| **S3 运行图模型** | 时刻表、例外、时刻边界、`StopTimes`、`TripPath`、运用、`DutyShapes` 的组件版；`TrainService` 和 `DepotDispatcher` 进组件。计划带版本、不可变，列车绑定计划版本，在定义好的交接点切换（R04 的完整修复）。停站、通过、直通、最短用时的规则只有一套，规划器、网页、运行都调用它（A09） | `TrainServiceTest`、`DutyRunTest` 在组件里通过；已有的存档内容照常读入（需要时可以新增内容）；改表后正在跑的列车仍用旧版本跑完 |
| **全链路场景** | 组件里一套固定的无头场景：双向单线、会让站、长列车、晚点、在线改表、外部车、重启恢复 | 场景能运行，结果固定；S4 起每步都要跑 |
| **S4 全网模拟** | 事件驱动的 `Simulator`：实际占用、进入许可和锁闭、预测占用分开（A03）；方向预约；扣车；锁死只报告和限制扩散。失效传播只更新依赖链；预测在沙箱分支里算。附命令行工具，读 `mtr.db` 模拟一天 | E2 全天测试通过；和 S2 的逐 tick 结果对照，到发误差在一个 tick 以内；审查第 6 节的性质都写成测试（见 7.3 节）；性能指标有基准（A10） |
| **S5 网页模拟运行** | 编辑器服务端用沙箱实例调组件，运行图上叠加模拟出的运行线和问题列表 | 网页显示晚点、扣车、对顶、站台冲突；不影响真实运行 |
| **S6 规划器** | 规划器保留简化的优化模型，输出交给运行内核验证；规则统一已在 S3 完成；删掉 `transitsim.planning` 里重复的部分 | 生成结果都能通过内核验证，有测试对照 |
| **G0 接管前提** | 外部车（手动驾驶、旧逻辑车厂）的占用输入；接管和释放接管的交接；最小可恢复快照，先证明现有存档格式足够恢复，否则在这里设计新格式（A08） | 混跑场景里组件能看到外部车；保存再恢复与连续运行结果一致 |
| **G1** | 曲线车厂的服务端列车由 `Simulator` 驱动，`Train` 的字段只是投影（A06） | 游戏内：曲线车厂列车按时刻运行；停服重进位置正确；附属模组的行为验证 |
| **G2** | 客户端按运行段和事件自己算位置；同步包改为运行段，带版本；时钟同步和纠偏；运行段被替换后不再消费失效事件 | 游戏内：列车不抖、不跳位；ANTE 渲染正常 |
| **G3** | 世界事件（改轨道、传感器、玩家挡车）送进模拟 | 游戏内：改轨道、触发传感器时列车反应正确 |
| **G4** | 曲线车厂去掉 TPS 补偿；运行状态存档换成运行段格式，加存档版本号 | 旧存档能升级；卡顿时时刻不漂移 |

**阶段三：新功能都写在组件里**，先写无头测试再接游戏：

- 通过站限速和曲率；
- 闭塞分区，和方向预约合并到资源模型；
- 站前折返；
- 联锁和缓急接续；
- 晚点换车和交换后半段；
- 切断直通和回送；
- 配线图网页。

G1 以前，这些功能只在组件和网页模拟里生效。要提前在游戏里用，只能在 S4 之后，通过 G0、G1 的接管路径接入，不另写 tick 版本。

其余四个版本暂时都不编译，等用户说再恢复（2026-11-03）。

## 6. 进度

每一步按五级记录，每级写提交号和条件：

| 级别 | 含义 |
|---|---|
| 代码 | 实现写完 |
| 构建 | 写明哪几个版本编译通过 |
| 单测 | 写明测试数和结果 |
| 小场景实测 | 进游戏，按测试清单的条目 |
| 规模实测 | 大线网、长时间运行 |

S0 之前，已经不依赖世界的部件：

| 部件 | 代码 | 构建 | 单测 | 小场景实测 | 规模实测 |
|---|---|---|---|---|---|
| 班次决策移到 `TrainService`（`Vehicle`、`Board` 接口，可换时钟） | 完成 | 1.20.1 | 通过 | 通过（2026-11-02） | 未做 |
| 速度曲线（`SpeedProfile`、`SpeedCurves`） | 完成（`79599eb`） | 1.20.1 | 通过 | 实测通过（T79 至 T82，2026-11-03） | 未做 |
| 线网压缩和路径区间序列（`PhysicalGraph`、`GraphRecords`、SQLite 表） | 完成（`a5fe8ce`） | 1.20.1 | 通过 | 未测（C15） | 未做 |
| 方向预约（`RunReservations`） | 完成（`a5fe8ce`），R01 至 R03 由 F1 修好 | 1.20.1 | 通过 | 未测（T86） | 未做 |
| ANTE 每 tick 入口改为委托（`RailwayData.simulateTrains(RailNearPlayer)`） | 完成（`a5fe8ce`） | 1.20.1 | 不适用 | 未测（T89） | 未做 |

迁移步骤：

| 步骤 | 代码 | 构建 | 单测 | 小场景实测 | 规模实测 |
|---|---|---|---|---|---|
| F1 预约安全修复 | 完成：`RunReservations` 改为持有加 tick 末统一仲裁，`TrainServer` 从车尾持有到运行终点、出库也要申请，存车线按 ID 遍历；复审的两处问题已修（7.4 节） | 1.20.1 | 组件 35 项、common 259 项全过（`RunReservationsTest` 13 项，覆盖区间挡停、车尾交接、同 tick 对向申请、等待老化、申请顺序无关、同向车流不饿死对向）；ABI 扫描无缺失；Fabric、Forge 服务端启动正常 | 未测（T86） | 未做 |
| F2 运用快照 | 完成：`TrainService` 出库和接过夜运用时复制当天的运用（`DepotDuties.DayDuty.copy`），快照不存档，重启后重读 | 1.20.1 | 组件 33 项、common 258 项全过（`DutyRunTest` 新增 3 项：跑到一半改或删运用、下次出库用新运用、过夜接班读新选择）；ABI 扫描无缺失；Fabric、Forge 服务端启动正常 | 不单独测（随 T84、T87、T88） | 未做 |
| S0 骨架 | 完成：搬类部分（`6052077`）；组件独立构建，根构建联网改为有限重试加超时（R05） | 五个版本编译通过（1.16.5、1.17.1、1.18.2、1.19.4 是 `e5673c0` 以后第一次）；六个 jar 都含组件的类 | 282 项全过（组件 27、common 255）；ABI 扫描无缺失；Fabric、Forge 服务端启动正常。补完后：组件在只有 `gradlew`、`gradle/`、`transit-sim/` 的目录里用 `--offline` 单独构建，测试全过，字节码是 Java 8（版本 52）；补完后五个版本都构建通过（组件 35 项、common 259 项全过），ABI 扫描无缺失，服务端启动正常 | 不改行为，不单独测 | 不适用 |
| C 契约 | 完成：`MTR-模拟组件契约.md`（时间、状态归属、资源、版本、运行/预测/恢复、事件、性能、接口草案，各节附现状对照） | 不适用 | 不适用 | 不适用 | 不适用 |
| S1 线网和资源 | 完成：线网、寻路、区域、速度曲线进组件；新的线网快照带资源（轨道段、道岔冲突区、单线方向区段、站台泊位）和发布序号；资源只定义，还没有地方用（S4 用）。路径到几何的对应仍是 `RailGraph` 里宿主给的轨道载荷 | 1.20.1 | 组件 71 项、common 233 项全过；组件单独离线构建通过；`SpeedCurvesTest`、`RailNetworksTest` 期望值不变；ABI 扫描无缺失；Fabric、Forge 服务端启动正常 | 不改行为，不单独测 | 不适用 |
| S2a 抽出推进规则 | 完成：规则进组件 `TrainMotion`，`Train` 通过五个接口调用；旧规则保留为 `LegacyTrainMotion`，仪表盘设置菜单可以切换（协议 27） | 1.20.1 | 组件 96 项（新增对照 12 项）、common 233 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（T90、T91） | 未做 |
| S2b 从任意状态起步的速度曲线 | 完成：`SpeedProfile` 可以从任意位置、非零速度起步（超过限速时按减速度降下来，来不及停车时从还能停住的速度起步），`SpeedCurves.Run.from`、`findCapFrom` 和 `SpeedCurves.findCruiseCap`；`TrainServer.getCruiseCap` 用新规则时，区间里被拦停后重新起步的车也按剩余时间设巡航上限（去掉 E2 的变通），旧规则不变 | 1.20.1 | 组件 105 项（新增 `SpeedProfileTest` 5 项、`SpeedCurvesTest` 2 项、`TrainMotionRestartTest` 2 项）、common 233 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（T92） | 未做 |
| S2c 运行段和曲线模式 | 完成：服务端开速度曲线的自动列车沿 `RunSegment` 走，位置由曲线在"出发后经过的 tick 数"处的位置得出，不再逐 tick 累加；停车点变化、运行段或巡航上限变化、列车被别处移动时从当前位置和速度换新段（版本加一）；按常用减速度停不住时用 1.5 倍，再停不住就从还能停住的速度起步，并标出紧急制动；到段末时位置对齐到停车点。客户端照旧沿曲线逐帧走。运行段不存档，读档后从当前位置和速度重建 | 1.20.1 | 组件 112 项（新增 `RunSegmentTest` 7 项；对照测试里开速度曲线的场景改在客户端比较）、common 233 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（T93） | 未做 |
| S2d 事件、命令和占用 | 完成：新规则在服务端发出事件 `MotionEvent`（停下、回库、续圈、越站、换段、紧急制动、发车），`Train` 收着，各存车线在列车走完后交出，`RailwayData` 在 tick 末按（时间、类别、列车、序号）排好统一处理，现在只把紧急制动写进日志，上一 tick 的事件可以取到；司机的加减档和开关门规则进组件 `TrainMotion.applyCommand`（`MotionCommand`），宿主照旧在本 tick 收集按键、在这列车走之前交给它，没有再延后一 tick，因为那样会改变手动驾驶的手感和返回值；占用的存放进组件 `transitsim.sim.Occupancy`（两 tick 的轨道 → 占用者），宿主的列车、手动驾驶、信号机闭塞和折返预约照旧写进去，查询经它判断，`trainPositions` 只是它的视图，所有公开方法签名不变 | 1.20.1 | 组件 122 项（新增 `MotionEventTest` 6 项、`OccupancyTest` 4 项）、common 233 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动，ANTE 的 mixin 没有失败 | 未测（T94） | 未做 |
| S3a 前半：时刻表、例外、运营日、运用进组件 | 完成：新包 `transitsim.plan`（`RouteTimetable`、`ServiceExceptions`、`OperatingDay`、`DepotDuties`、视图 `PlanRoute`、`PlanStop`）和 `transitsim.io`（`BinaryReader`、`BinaryWriter`、`ByteArrayBinary`）；宿主改为直接用组件的类，`TimetableConfig` 只读写配置文件，`advanced/PacketBinary` 把 `FriendlyByteBuf` 交给组件；网络包和存档字节不变，协议版本不变。顺手修了构建顺序：`common` 的生产转换要等组件的 jar 构建完（`common/build.gradle`） | 1.20.1 | 组件 122 项、common 243 项全过（新增 `PacketBinaryTest` 3 项，`RouteTimetableSaveTest` 照旧通过）；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（T95） | 未做 |
| S3a 后半：时刻边界、运用形状、冲突检查进组件 | 完成：`TimetableBoundaries`、`DutyShapes`、`TimetableConflicts` 进 `transitsim.plan`；`PlanRoute` 加站序、是否环线、运输方式；宿主 `advanced/PlanAdapters` 给出哪些站台是时刻边界。`DutyGenerator` 留在宿主，它是从车厂和存车线读数据、交给组件规划器的适配器。这些类的测试暂时还在 common，因为它们用 MTR 的 `Route`、`Platform` 造输入，S3c 有了组件自己的测试替身后再搬 | 1.20.1 | 组件 122 项、common 243 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（T95） | 未做 |
| S3b：车次和停站时刻进组件 | 完成：`Siding` 的九个内部类（`TripPath`、`StopTimes`、`TimeSegment`、`TripTail`、`DutyPlan`、`TurnBackSection`、`DepotReturn`、`TurnBacks`、`TurnBackPoint`）进 `transitsim.plan`；新增轨道视图 `PlanTrack`（`PathData` 实现）、`TimeGenerator`、`transitsim.io.KeyedReader`/`KeyedWriter`（宿主 `advanced/MessagePackKeyed`）。`Siding` 从 2355 行减到约 1140 行，只留路径生成、存档和同步。存档的键、顺序和数值写法不变 | 1.20.1 | 组件和 common 243 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（T95） | 未做 |
| S3c：班次决策和出车进组件 | 完成：`TrainService` 进 `transitsim.sim`（泛型 `T extends PlanTrack`，用 `PlanRoute`）；新增 `DepotDispatcher`，`Depot` 的车次预约、上次派出班次、运用出车记录和选库位都转调它；`PlanTrack` 加 `isSidingTrack`，`PlanRoute` 加默认的 `getLastPlatformId`，`KeyedWriter` 加 `writeLong`。`TrainServiceTest`、`DutyRunTest`（27 项）搬进组件，用测试替身 `TestRoute`、`TestTrack`。存档的键和写法不变 | 1.20.1 | 组件 152 项（含合并进来的寻路 3 项）、common 216 项全过；组件单独离线构建通过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（T95） | 未做 |
| S3d：计划版本 | 完成：组件 `transitsim.plan.PlanVersions` 按时刻表的 `revision` 给线路做不可变副本（时刻表和各张表、班次例外、各站逐表的到发和待避、通过），同一版本的列车共用一份，没有列车用时被回收；`RouteTimetable.copy()`。`TrainService` 取停站计划时用自己这一趟的副本，换班次、换线路、换运用段、换路径或回库时才取最新版本；选下一班、直通接续、查空闲班次仍看线路的当前状态。和计划里写的"全网一份带版本号的快照"不同，做成了按线路的版本：效果一样（列车绑定开始这一趟时的版本，交接时切换），但改一条线路不用复制全网 | 1.20.1 | 组件 156 项全过（新增：改表后正在跑的列车照旧、新出车的用新表，跑完一圈换新表；副本不跟着改、同版本共用）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（T96） | 未做 |
| S3e：一套停站规则 | 完成：组件 `transitsim.plan.StopRules` 是唯一的停站规则：`plan` 算一条线路一班的到发（原 `RouteTimetable.getStopPlans`，已删），`times` 再给出每站是否通过、是否待避、是否快过最快时刻，`passes` 是通过的判断；整趟车次的算术仍是 `RouteTimetable.applyPlan`，`plan` 也调它。列车（`StopTimes`）、游戏内行路单（`TimetableScreen`）、冲突检查、运用规划器的输入（`EditorDuties`）、编辑器数据都改调它。编辑器网页删掉自己那份 `computePlans`：游戏自己的时刻随数据一起发来（每表、每个有例外的班次的 `times`），草稿发到新接口 `api/times`（`EditorTimes`）由游戏算，回来之前先显示上次的时刻。**和原来不一样的地方**只有一处：运用规划器原来只看线路的通过设置，现在也看班次例外里的通过（和网页、按运用生成的路径一致） | 1.20.1 | 组件 158 项全过（新增 `StopRulesTest`：同一线路和例外，列车和规则给出的到发逐站相同；通过、待避、快过最快时刻的判断）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（U63） | 未做 |
| S4a：运行规则进组件，全链路场景 | 完成：`TrainServer` 的停站规则搬进组件 `transitsim.sim.TrainRules`（晚点缩短停站、按表和运用发车、待避、折返和回库的选择、方向预约、巡航限速、开门时机、下一停车点），`TrainServer` 通过 `TrainRules.Train` 调用，逻辑逐行不变；`Siding` 按速度曲线算各站最快时刻的部分搬成 `transitsim.plan.PathTimes`。新增 `TickRunner`：在组件里按 tick 跑整个车厂（`TrainMotion` + `TrainRules` + `TrainService`，`Occupancy`、`RunReservations`、`DepotBoard`/`DepotDispatcher`），顺序和游戏服务端一样；轨道用新接口 `RunTrack`。全链路场景 `WholeChainScenarioTest`：终点站单站台折返的线路、长线路长列车、区间被外部车挡住两分半、运行中改表，结果固定在 `src/test/resources/scenarios`，同一场景每次跑结果相同。双向单线加会让站（要按运用跑和方向预约）放到 S4b，重启恢复放到 S4d | 1.20.1 | 组件 163 项全过（新增场景 5 项）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（T97） | 未做 |
| S4b：资源仲裁器 | 完成：组件 `transitsim.sim.Arbiter`，按契约第 4 节：轨道段一列车独占、单线方向区段同向共用反向互斥、道岔冲突区同一时刻一条进路、站台泊位按容量计数；实际占用（宿主给出车头到车尾所在，外部车也算，没有许可也挡别人）、许可、预测占用（只记录，从不参与许可）分开；同一事件边界的申请一起批，顺序和全有或全无、老化和防饥饿沿用 F1 的规则；安全取消只在列车停着、车头没越过许可起点时允许；按等待图找锁死，报告一次（`takeDeadlocks`），不再把新许可批给锁死以外的车，不擅自释放，宿主移除其中一列车后自动解除。`RunReservations` 改为 `Arbiter` 的外壳（每条双向轨道是一个单线方向资源），游戏照旧调用，结果不变。双向单线加会让站的全链路场景要按运用跑，和 S4c 的模拟器一起做 | 1.20.1 | 组件 175 项全过（新增 `ArbiterTest` 12 项；`RunReservationsTest` 和全链路场景原样通过）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 不单独测（随 T97） | 未做 |
| S4c1：模拟器接口 | 完成：契约第 9 节的 `Simulator`（`submit`、`advanceTo`、`query`、`getSegment`、`events`），实现 `EventSimulator` 先架在 `TickRunner` 上，按从起始时刻算起的 tick 推进，所以宿主在哪里停一步都不影响结果。宿主输入 `HostInput`：扣车 `Hold`（在下一站开着门等）、放行 `Resume`、移除 `Remove`（放掉它持有的一切，存车线需要时再出新车）、外部占用 `ExternalOccupancy`、发布计划 `PublishPlan`；同一时刻按宿主序号生效，和交进来的先后无关。事件 `SimEvent`：到站、通过、回库、开门、关门、扣车（附原因，被前方挡住记为 blocked）、放行、锁死、运行段替换、出发，每个 tick 内按类别、列车、序号排好，带游标，按游标往下读；事件只在发生时给出，从不提前，所以没有要作废的。`query` 只读。`fork`、`snapshot` 在 S4d。拆成两半：S4c1 结果和 `TickRunner` 完全一样；**S4c2** 再加"没有变化时跳过 tick"，满足全天秒级的性能，并和 S4c1 对照，误差在一个 tick 以内 | 1.20.1 | 组件 184 项全过（新增 `SimulatorPropertiesTest` 9 项：步长不影响结果、时间只进不退、查询不改状态、同一时刻输入的顺序、按游标读、扣车和放行、移除、运行中发布计划、和 `TickRunner` 一致）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 游戏不用（G1 起） | 未做 |
| S4c2：跳过没有变化的 tick | 完成：`EventSimulator` 默认跳过 tick，`TickRunner` 自己仍逐 tick 跑，当对照基准。能跳过的情况：在运行段上行驶，车头车尾都不跨到下一条轨道、不到停车点和回库点、制动距离内没有被占的轨道；在站里停着，离下一次开关门、能放行或出发还有距离，而且不是晚点缩短停站；被前方挡住停着；在存车线里等，离最早能出车的时刻（`TrainService.getNextDeployMillis`）还远。跳过期间没有车换轨道，占用、预约和车厂的状态都不变，所以结果和逐 tick 完全一样（不只是一个 tick 以内）。按运用跑的车厂暂不跳过，照常逐 tick；手动和非速度曲线的车也不跳过 | 1.20.1 | 组件 189 项全过（新增 `SimulatorEquivalenceTest` 5 项：四个场景跳过和逐 tick 的事件逐条相同，包括扣车放行和外部占用；`SimulatorPropertiesTest` 在跳过时也全过）；长线路一天跳过 93.5% 的 tick，407 毫秒降到 61 毫秒（这个场景 8 点以后没有班次，大车厂全天的基准在 S4e 做）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 游戏不用（G1 起） | 未做 |
| S4d：预测分支、快照、线网变化 | 完成：`Simulator.fork()` 和 `snapshot()`。做法是重放：模拟对同样的起点和输入结果总相同，所以分支由起点工厂重建一份自己的线路和列车，再按顺序重放宿主输入到当前时刻，和真实运行没有任何共享的可变状态；分支里的事件都标为预测（`SimEvent.predicted`），可以投假设的命令、换计划。`PublishPlan` 改为对所在模拟的线路下手，分支重放时改的是它自己的线路。快照是时刻、全部宿主输入和事件游标，`EventSimulator.restore` 从快照接着跑，和一直跑的结果完全一样；停服很久后按时刻表重新安置、清许可（契约 6.3）按契约放在 G0。线网变化 `PublishNetwork`（契约 5.1 规则 5）：前方有被拆的轨道就在它前面停下并报 `CONFLICT`；脚下的轨道被拆就移除这列车交给宿主；存车线路径上有被拆的轨道就不再出车。重放的分支要从头跑，一天的线网约在一秒内，网页预测够用；以后需要时再换成写时复制 | 1.20.1 | 组件 194 项全过（新增 `ForkSnapshotTest` 5 项：分支预测和真实运行一致、分支里的改动不影响真实运行、快照接着跑结果一样、前方拆轨停车、脚下拆轨移除）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 游戏不用（G1 起） | 未做 |
| S4e：全天工具和基准 | 完成：组件的数据集 `transitsim.sim.SimDataset`（线路的站序、各表的到发和待避、时刻表 `TimetableBinary`；车厂的线路、循环和运用；存车线的车、路径、直通和折返路径 `BinaryKeyed`、多条折返线），每次构造一个独立的世界，所以分支也能用。游戏存档时由 `advanced/SimExport` 生成，写进 `mtr.db` 新表 `sim_data`（一张表一个块，和计划里写的四张表不同，更简单；没变化时不重写）；协议版本加到 31。工具 `transitsim.tools.SimulateDay`（`./gradlew -p transit-sim simulateDay --args="mtr.db 日期 时区 日起点"`，SQLite 只给工具用，不进模组；第一次要联网取 sqlite-jdbc），打印车数、各类事件数、锁死和冲突、耗时。按车型限定线路的车厂改为每辆车自己的线路表（`DepotBoard.setTrainRoutes`）。**性能没达标**：25 条长线、每 3 分钟一班、约 125 列车，一天要 52 秒（目标 200 列车 10 秒以内），只跳过 56% 的 tick，因为现在是全网一起跳，车多了总有车在换轨道。要达标需要按列车各自推进到自己的下一个事件、只唤醒受影响的列车，另作一步（S4f） | 1.20.1 | 组件 197 项全过（新增 `SimDatasetTest`：数据读写往返一致、从数据集建的世界和终点站场景的固定结果逐条相同；基准用 `-Dbenchmark=true` 才跑）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动；测试服务端保存后工具能读出并模拟（空世界） | 未测（T98） | 未做 |
| S4f：按列车推进的事件引擎（性能） | 完成：`TickRunner` 不再全网一起跳，每列车各自记下自己下一次要动的 tick，其余 tick 不动它，等下次要动或被查询时一次补上（`catchUp`）；没有车要动的 tick 整个跳过。占用改成持续保存（每条轨道按 tick 里的先后排好占用者，另存上一 tick 末的样子），和原来每 tick 重建的 `Occupancy` 判定完全一样。列车算"能放多久"时看过的轨道会被记下，这些轨道的占用一变就叫醒它；车厂的班次有变（新占、放掉、出车）叫醒在存车线等的车；任何宿主输入先把所有车补到当前再全部叫醒。放着不动的车每 tick 照常要占的班次，记成车厂的长期占用（`DepotDispatcher.addStandingTrips`），时机和逐 tick 一样。停站的车用和 `TrainMotion` 同一套规则按 tick 小步推算（停站时长、按表扣车、开关门判定抽成 `TrainRules` 里的纯函数，两边共用），一直放到开关门、出发，或要问一个会改东西的问题（选折返线、回库）为止；门的滑动一次算完。结果和每列车每 tick 都动完全一样。**基准**：40 条长线、每 3 分钟一班、200 列车、全天 91200 次发车，4.1 秒（S4e 时 125 列车 52 秒）；列车只在 0.4% 的 tick 里真正动；按游戏一样每 tick 推进一次，全天 3.6 秒，单步第 99 百分位 0.039 毫秒，最长 5.3 毫秒（偶发，JIT 或 GC）。按运用跑的车厂仍每 tick 都动 | 1.20.1 | 组件 198 项全过（新增：三条密集线路 4 小时，每 7 分钟比一次所有列车的状态，加全部事件，跳过和逐 tick 完全相同，真正动的次数不到三分之一；基准改为 200 列车并测单步耗时）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 游戏不用（G1 起） | 未做 |
| S5a：模拟一天并整理成报告 | 完成：组件 `transitsim.sim.DayRun` 在独立的世界里从运营日起点模拟一天（多跑一小时，让最后的班次到站），每推进 10 分钟问一次要不要停。按列车事件发生时的状态（`TickRunner.setTrainListener`，只读）记下每个班次在各站的实际到发和计划到发（计划取自 `TrainService.getPlanAt`，和游戏一致），站序按线路的站台依次对上。问题有六种：晚点超过 30 秒（每个班次报第一次和最大值）、等站台（停在站台前，站台被占）、区间扣停、站内久停（比计划多站 30 秒以上）、锁死、未开行（计划里有、一天下来没车跑，用新的 `RouteTimetable.getServicesBetween`）。草稿用 `SimDataset.replaceRoute`、`replaceDuties` 放进数据集，不改原来的。所有车从车厂出发，过夜留在线上的车按出库处理。晚点的车到下一条线路没有班次可接时，游戏按它准备好的时刻跑一个时刻表里没有的班次，报告里照记 | 1.20.1 | 组件 203 项全过（新增 `DayRunTest` 5 项：按计划运行的一天 24 个班次都准点、没有问题；外部占用挡车后报等站台和晚点，晚点一路传下去；长线路只有一列车时报大量未开行；草稿时刻表代替原来的、数据集不变；能中途停下）；common 216 项全过；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 游戏不用（S5b、S5c 接到网页） | 未做 |
| S5b：请求、后台模拟、回包 | 完成：网页 `/api/simulate`（POST 发起，GET 取状态和结果）。客户端 `EditorSimulation` 收下页面要应用的同一组修改：时刻表的修改放到线路的副本上（经数据包复制，用应用时同一个 `readTimetable` 检查和填入），做成组件的 `RouteData`；运用的修改用拆出来的 `EditorDuties.read` 检查后转成运用字节；改站序、新建或删除种别要服务端重算路径，不模拟，计数报给页面。服务端 `advanced/SandboxRuns`：权限和应用修改相同；在服务端线程里取一份 `SimExport` 的数据、放进草稿（线路不存在或站台和服务端不一致的草稿不用，计数报回），再交给一个守护、低优先级的后台线程跑 `DayRun`；每个玩家同时只有一个任务，新请求停掉旧的；排队超过 8 个回"太忙"，跑超过 60 秒停下也回"太忙"。日期取今天起第一个是所选星期的运营日。报告用组件的 `DayRun.toBytes` 编码，每包 512 KB 以内分包，客户端拼回后转成页面用的 JSON（时间是运营日内的毫秒，班次和时刻表一样）。协议版本加到 33 | 1.20.1 | 组件 204 项全过（新增报告和草稿线路的编码往返）；common 220 项全过（新增 `EditorSimulationTest` 4 项：复制的线路和原来对模拟完全一样；草稿只改副本；报告分包再拼回一致；所选星期的运营日起点）；ABI 扫描无缺失；Fabric、Forge 服务端能启动 | 未测（和 S5c 一起） | 未做 |
| S5c | 未开始 | | | | |
| S6 | 未开始 | | | | |
| G0 至 G4 | 未开始 | | | | |

## 7. 架构审查的处理

审查文档是 `MTR-设计与架构审查评估-2026-11-03.md`，基线 `051f304`，做的是静态审查。下面逐条核对，并给出归属。

### 7.1 实现层问题

| 编号 | 核对 | 依据 | 处理 |
|---|---|---|---|
| R01 区间挡停时预约失效（高） | 属实 | `TrainServer.java:401` 只在 `speed > 0` 时续订；`RunReservations` 两个 tick 不续就失效。列车在区间被闭塞挡停后预约消失，对向车可以出站，最后会被物理闭塞挡住，两车在单线上对着停住，就是 E2 要防的锁死 | F1（已修） |
| R02 车尾还在时预约已释放（高） | 属实 | `TrainServer.java:402` 从车头所在的轨道开始续订 | F1（已修） |
| R03 调度依赖遍历顺序（中） | 属实 | `RailwayData.java:355` 遍历 `HashSet` 里的存车线；`canBook`、`book` 先到先得，没有仲裁和防饥饿 | F1（已修） |
| R04 旧计划没有版本隔离（高） | 属实 | `TrainService.getDutyDay`、`getDutySegments` 每次读车厂当前的运用；`getStopPlan` 读线路当前的时刻表。网页改了运用、还没刷新路径时，段数相同就会用新班次配旧路径 | F2（运用，已修）、S3（时刻表和完整版本）；**S3d 已修**：时刻表、例外和停站也按版本，列车跑完这一趟再换 |
| R05 组件构建依赖模组根项目（中） | 属实，而且更重 | 根 `build.gradle` 在配置阶段联网；`getJson` 失败后无限重试，计数也从不增加 | S0 补完（已修）：组件有自己的 `settings.gradle` 和完整的 `build.gradle`，能单独、离线构建；`getJson`、`getXml` 最多试 5 次并有超时，各处下载从无限重试改为 5 次并加超时 |

审查说 S0"尚不能认定完成"，因为它写在 S0 验证之前。验证结果见第 6 节。但独立构建确实没做，所以 S0 在补完 R05 前不算完成。

### 7.2 架构问题

| 编号 | 核对 | 处理 |
|---|---|---|
| A01 计划、运行状态、恢复分开（高） | 成立：原设计把"按时间查位置"当成恢复手段，没考虑晚点和扣车的历史 | C（3.6 节）；S4 沙箱；G0 恢复 |
| A02 时间语义（高） | 成立：没定义查询是否只读、事件晚到、时钟回拨、夏令时 | C |
| A03 实际占用、进入许可、预测占用分开（高） | 成立：原设计只说"区段上的时间区间"，也没有道岔冲突区 | C；S1 资源模型；S4 |
| A04 区间重算的连续性（高） | 成立：`SpeedProfile` 只能从静止起步 | S2 |
| A05 线网和计划的原子发布（高） | 成立 | C；S1 线网快照版本；S3 计划版本 |
| A06 状态单一归属（高） | 成立：S2 的状态接口和 G1 的组件状态要明确交接 | C；G1 投影；附属模组做行为验证 |
| A07 事件去重和消费边界（中） | 成立 | C；S4；G2 |
| A08 混跑占用和恢复不能推后（高） | 成立：原 G3 才登记外部车，G4 才设计恢复 | 新增 G0，放在 G1 之前 |
| A09 规划和运行共享规则（中） | 成立：原来要到 S6 才收拢 | 规则统一提前到 S3；S6 只剩规划器 |
| A10 性能指标和锁死处置（中） | 成立：原文"离玩家远的列车不花开销"说得过头 | C；S4 基准 |

### 7.3 要写成测试的性质（审查第 6 节）

S4 起都要有测试覆盖：

- 打乱集合的遍历顺序，只要输入事件的顺序固定，结果就不变；
- 一次推进较长时间，和分多次推进，在相同的事件边界得到相同状态；
- 查询不改运行状态，也不触发宿主的副作用；
- 按规定的恢复策略，保存再恢复和连续运行结果一致；
- 改表或改图后，活动列车不会混用不兼容的版本；
- 没清出的实际资源，不会因为停车、断了续订或预测重算而被释放；
- 客户端只显示已授权的运行段；运行段被替换后，不再消费已失效的事件。

### 7.4 F1 的复审（2026-11-03）

F1（`29d4e74`）提交后的复审又发现两处问题，都属实，已修：

| 编号 | 问题 | 核对 | 修法 |
|---|---|---|---|
| F1-P1（高） | 出库申请要等下一个 tick 才批，第一次必然没批，列车留在存车线；同一 tick 的 `service.update(false)` 把它当成已回库，清掉了运用。下一 tick 它不再算运用列车，绕过预约按普通路径出库，车厂却已把这份运用记为跑过 | 属实（`TrainService.update` 不在线上时清空运用） | `TrainService` 区分"已派出、在存车线等批准"和"回到车厂"：`deployDuty` 后到真正上线前不清运用；读存档时，在车厂里还带着运用的车也按等待出库处理 |
| F1-P2（中） | 等待老化只把等得久的申请排到前面，它批不下来时，同向的新车照样获批，区间一直清不空。复审的复现场景跑 180 秒，对向获批 0 次 | 属实 | 等了 60 秒以上的申请批不下来时，它要的轨道记为"被等待"：不再按相反方向批给还没持有其中轨道的车；已经持有其中轨道的车照常批，好让它们开出去，否则在区间中间有车站时会互相卡死 |

新增测试：`RunReservationsTest` 加"同向车流不能让对向车一直等"（每秒一列新车，对向车在老化后几秒内获批）和"已在线上的车可以继续开出"；`DutyRunTest` 加"等待出库时保留运用"。
