# MTR 运行模拟组件：分离现状与目标架构

- **最后更新**：2026-11-03。S0 的搬类部分已完成，独立构建待补。开发顺序已按架构审查调整（第 5 节），审查的处理见第 7 节
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
| `path/PathRelocator` | 82 | 无 | A | 组件 `motion`（S1） |
| `path/RailGraph<R>` | 177 | `PathBoundaries`、`PathRegions` | B | 组件 `network`，类型参数换成组件的区间（S1） |
| `path/RailRouter` | 251 | `RailGraph`、`PathRegions` | B（只差 `RailGraph`） | 组件 `network`（S1） |
| `path/DeterministicPathFinder` | 128 | `BlockPos`、`Rail`、`Platform`、`SavedRailBase` | B | 拆开：从 `Rail` 建图的部分是宿主适配器，寻路部分进组件（S1） |
| `path/PathData` | 121 | `BlockPos`、`Rail`、NBT、`FriendlyByteBuf` | B | 留在宿主，适配器把它换成组件的 `TrackView`（S1） |
| `path/PathFailure` | 86 | `BlockPos`、`Rail` | B | 失败类型进组件，定位信息留在宿主（S1） |
| `path/PathFinder` | 266 | `Rail`、`Vec3` | B | 旧寻路，留在宿主，不迁移 |
| `advanced/PathBoundaries` | 131 | `FriendlyByteBuf`、msgpack | B | 边界集合进组件，存档读写留在宿主（S1）。`segmentKey` 已改为调用组件的 `RailKeys` |
| `advanced/PathRegions` | 252 | `RailGraph` | B（只差 `RailGraph`） | 组件 `network`（S1） |
| `advanced/RegionComputer` | 151 | `RailGraph` | B | 组件 `network`（S1） |
| `advanced/RegionData` | 167 | `FriendlyByteBuf` | B | 同步包，留在宿主 |
| `advanced/PhysicalGraph` | 352 | `Rail`、`RailType`、`RailGraph` | B | 拆开：从 `Rail` 压缩的部分是宿主适配器；`Section`、`SectionPath` 和二进制格式进组件 `network` 和 `io`（S1） |
| `advanced/GraphRecords` | 126 | `RailwayDataStore`、`PathData` | B | 记录队列进组件；SQLite 读写留在宿主，经组件的 `io` 格式（S1） |
| `advanced/PathRegionSync` | 132 | `MinecraftServer`、`ServerPlayer`、网络包 | C | 留在宿主：后台计算触发和同步 |

**速度和运动**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `transitsim.motion.SpeedProfile`（原 `advanced/SpeedProfile`） | 234 | 无 | A | **已在组件**（S0） |
| `advanced/SpeedCurves` | 174 | `PathData`、`RailType`、`TransportMode` | B | 改读 `TrackView` 后进组件 `motion`（S1） |
| `data/TickCompensator` | 29 | 无 | A | G4 之后只给旧逻辑用，留在宿主 |
| `data/TransportMode` | 24 | 无 | A | 组件只需要其中"是否连续运动"等几个属性，用组件自己的枚举 |
| `data/RailType` | 46 | `MaterialColor` | B | 组件只要限速和能否加速，由适配器写进区间 |

**运行图、时刻和运用**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `transitsim.planning.*`（原 `operation/*`，7 个类） | 958 | 无 | A | **已在组件**（S0），S6 收拢 |
| `data/RouteTimetable` | 473 | `ServiceExceptions` | B（只差 `ServiceExceptions`） | 组件 `plan`（S3） |
| `advanced/ServiceExceptions` | 233 | `FriendlyByteBuf`、`Route`、`RouteTimetable` | B | 组件 `plan`，读写换成组件格式（S3） |
| `advanced/TimetableBoundaries` | 82 | `Platform`、`Route`、`RouteTimetable` | B | 组件 `plan`（S3） |
| `data/TimetableConfig` | 124 | Gson | A（不依赖 Minecraft） | 时区和日起点进组件，配置文件读写留在宿主（S3） |
| `advanced/DepotDuties` | 243 | `FriendlyByteBuf` | B | 组件 `plan`，读写换成组件格式，存档字节保持兼容（S3） |
| `advanced/DutyShapes` | 161 | `Depot`、`Route`、`RouteTimetable`、`TimetableConfig` | B | 组件 `plan`（S3） |
| `advanced/DutyGenerator` | 349 | `transitsim.planning` | B（取数据时用 MTR 类） | 规划部分进组件 `planning`，取数据的部分是宿主适配器（S6） |
| `data/Route` | 1111 | `BlockPos`、NBT、`FriendlyByteBuf`、`PathData` | C 的数据类 | 留在宿主，适配器换成组件的线路 |

**列车运行**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `data/TrainService` | 1093 | `PathData`、`Siding.TripPath`、`Route`、`RouteTimetable`、`DepotDuties`、`DutyShapes`、msgpack | B | 组件 `sim`（S3）。`Vehicle` 接口改用 `TrackView` 和组件的车次路径 |
| `Siding.StopTimes`、`Siding.TripPath`、`TurnBack*`、`DepotReturn`、`DutyPlan`、`TimeSegment` | 约 1200（在 `Siding` 内） | 嵌在依赖 Minecraft 的 `Siding` 里 | B（逻辑本身不需要世界） | 抽成组件 `plan` 和 `sim` 的独立类（S3），`Siding` 只留路径生成的调度和存档 |
| `transitsim.sim.RunReservations`（原 `advanced/RunReservations`） | 168（F1 后） | 组件的 `RailKeys` | A | **已在组件**（S0） |
| `transitsim.network.RailKeys` | 16 | 无 | A | **已在组件**（S0）：两个节点之间轨道的键，原来是 `PathBoundaries.segmentKey`，`RunReservations` 搬进组件时发现它暗中依赖这个方法 |
| `data/SignalBlocks` | 293 | `BlockPos`、`DyeColor`、NBT | C 的数据类 | 占用规则抽成组件的 `Occupancy`（S2），信号颜色和存档留在宿主 |
| `data/Train` | 932 | `Level`、`Player`、物品、方块、NBT | C | 推进规则拆成组件的 `TrainMotion`（S2），见 2.2 节 |
| `data/TrainServer` | 852 | `Level`、方块实体、漏斗、玩家 | C | 占用、出车、方向预约、扣车拆进组件（S2 至 S4）；门、乘客、传感器、漏斗留在宿主 |
| `data/TrainClient` | 402 | 客户端渲染、声音 | C | 留在宿主；G2 起按运行段自己算位置 |
| `data/Siding` | 2343 | `Level`、`MinecraftServer`、网络包 | C | 内部的时刻和路径类拆出（S3），其余留在宿主 |
| `data/Depot` | 1415 | `Level`、`MinecraftServer`、网络包 | C | 出车判断拆成组件的 `DepotDispatcher`（S3）；`MainPath` 寻路调度留在宿主 |
| `data/RailwayData` | 1196 | 服务端、玩家、存档 | C | 留在宿主：持有组件实例，每 tick 驱动和查询 |

**合计**：
- A 级约 1500 行，可以直接搬，其中约 1290 行已在 S0 搬进组件；
- B 级约 5000 行，要先换类型；
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
| `path/RailRouterTest`、`path/PathRelocatorTest`、`path/StopSettingsTest` | 确定性寻路、刷新时列车保留原位、停站设置 |
| `advanced/PathRegionsTest` | 寻路边界和区域划分 |
| `advanced/PhysicalGraphTest` | 区间压缩、限速分段、ID 稳定、路径换算、读写 |
| `transitsim.motion.SpeedProfileTest`（组件，5 项）、`advanced/SpeedCurvesTest`（2 项） | 速度曲线、巡航上限；按轨道限速和折返分段 |
| `transitsim.sim.RunReservationsTest`（组件） | 带方向的区间预约 |
| `advanced/ServiceExceptionsTest`、`advanced/TimetableBoundariesTest` | 班次例外、时刻边界 |
| `advanced/DutyGeneratorTest`、`advanced/DutyShapesTest` | 运用生成、运用形状、旧格式读入 |
| `data/TrainServiceTest`、`data/DutyRunTest` | 班次决策、停服对齐、环线全天、按运用运行 |
| `data/RouteTimetableTest`、`data/TimetableConflictsTest`、`data/TimetableConfigTest` | 时刻表、冲突检查、时区 |

**还没有测试的**：列车推进（2.2 节的规则部分）。它只能进游戏看，S2 要先补上新旧逻辑的逐 tick 对照测试。

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
- Java 8 源码级别，1.16.5 也能用。
- 独立的 Gradle 子项目。common 依赖它，Fabric 和 Forge 的 jar 都要把它打进去。
- 日志、时钟、存储都由宿主通过接口提供。
- 结果确定：同样的输入和时间，算出同样的结果，和调用顺序、线程无关。

构建接入（S0 已实现）：

- `settings.gradle` 引入 `transit-sim`。它是普通的 `java-library` 项目，自己的 `build.gradle` 只有 JUnit。
- 根目录 `build.gradle` 用 `isComponent` 把它排除在 Loom、shadow、Minecraft 依赖、architectury 插件、Manifold 和 `setupFiles` 之外，并固定按 Java 8 编译（`options.release = 8`）。所以组件里写了不能在 Java 8 上用的 API，或引用了 MTR 的类，编译时就会报错。
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

### 3.6 待写定的契约（C 阶段）

架构审查（第 7 节）指出，只搬类和换类型不够。下面这些契约要在 C 阶段写定，S1 起按它们实现。这里先列出要定的内容。

- **时间**（A02）：
  - 模拟时间只进不退；
  - 营业日和时区负责展开计划；
  - 现实时间到模拟时间的映射、时钟回拨和跳跃、停服后的处理，都由宿主决定；
  - 同一时刻的事件按（时间、序号）排序；
  - 夏令时重复的那一小时怎么标识班次；
  - 确定性的说法改为"相同初态、计划版本和有序输入，得到相同结果"。
- **状态归属**（A06）：
  - 迁移期组件读写 `Train` 的字段；
  - 组件接管后，自动列车的运行状态归组件，`Train` 的字段只是投影；
  - 手动接管、释放接管、附属模组改字段，都变成明确的输入命令。
- **资源**（A03）：
  - 分三级：实际占用（车尾清出或确认移除才释放）、进入许可和锁闭（安全取消或清出保护范围才释放）、预测占用（可以随晚点重算）；
  - 物理有向图和资源映射分开，道岔冲突区、单线方向区段、站台容量都是资源；
  - 闭塞、方向预约和以后的联锁共用这套语义。
- **版本**（A05、R04）：
  - 线网、路径、计划、运行段都带版本；
  - 新版本先构造和校验，再原子切换；
  - 活动列车继续用旧版本、安全停车或显式重定位，不能悄悄混用；
  - `GraphRecords` 的排队、指纹和落盘也纳入。
- **运行、预测、恢复三条路径分开**（A01）：
  - 正常运行从已知的实际状态往前推；
  - 预测在复制出来的分支里算；
  - 恢复按明确的策略保留状态、重新安置或跳过离线时间，并重新校验占用；
  - `PlanAlignment` 只用于时刻层。
- **事件和副作用**（A07）：
  - 查询只读；
  - 事件带稳定 ID、运行实例和序号，宿主记录消费到哪里，副作用尽量幂等；
  - 到站、开门命令、门动画分开；
  - 消费检查点和运行快照保持一致。
- **性能和锁死**（A10）：
  - 指标包括全天吞吐、在线推进耗时的分位数、事件队列大小、重算次数；
  - 锁死只报告和限制扩散，不擅自释放仍被占着的资源。

## 4. 约束和风险

- **附属模组 ABI**：附属模组直接读写 `Train` 的字段和方法。字段保留，只改写入方式；每一步都扫描 MSD、YJCM、Metropolis、SHMetro。
- **ANTE 注入点**（2026-11-03 已核对）：
  - `TrainMixin` 只注入构造、`toMessagePack`、`messagePackLength`、`writePacket`、`scanDoors`、`convertMaxManualSpeed`；
  - `TrainClientMixin` 注入 `TrainClient.simulateTrain` 的入口；
  - `SidingMixin` 注入 `Siding.simulateTrain` 的入口；
  - `RailwayDataMixin` 委托给 `RailwayData.simulateTrains(RailNearPlayer)`。
  - S2 拆走推进规则碰不到这些注入点。G2 改客户端同步时要再核对 `TrainClientMixin`。
- **构建**：子项目要接进五个版本的 Gradle 配置和并行构建脚本，Forge 的 shadow 也要包含它。S0 先单独验证这件事，再搬任何逻辑。
- **存档**：S3 之前存档字节不变，组件格式只用于 `graph_*` 表。G4 才换列车运行状态的格式，并加独立于协议版本的存档版本号。
- **服务端和客户端一致**：S2 起两边用同一个运动内核，G2 起客户端按同一份运行段计算。
- **性能**：事件推进要比逐 tick 便宜。S4 用大线网基准对比，确认全网一天的模拟在秒级以内。
- **组件独立构建**：组件源码不依赖 Minecraft，但现在的构建仍要由模组的根项目配置，而根项目在配置阶段就要联网。S0 补完前，组件还不能单独、离线测试（R05）。
- **附属模组的行为兼容**：字段和方法保留只能保证二进制兼容。G1 以后字段由组件投影写入，附属模组直接写的值可能马上被覆盖，所以除了 ABI 扫描还要做行为验证（A06）。
- **版本混用**：改表、改运用、改轨道以后，正在跑的列车不能混用新旧版本（R04、A05）。F2 先修运用这一项，S1、S3 再做完整的版本。
- **不做**：独立进程。独立线程等图级模拟落地、确实有性能需求时再考虑。

## 5. 开发顺序

2026-11-03 按架构审查（`MTR-设计与架构审查评估-2026-11-03.md`，处理见第 7 节）调整。

### 5.1 先收口现有实现，同时冻结新功能，再分离

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
| **C 契约** | 写定 3.6 节列出的契约，只写文档和接口草案 | S1 至 S4 的设计都能引用到对应条款 |
| **S1 线网和资源** | 组件的 `Network`：有向拓扑、区间、边界、区域、`RailGraph`、`RailRouter`、路径到几何的映射，以及资源定义（轨道段、道岔冲突区、单线方向区段、站台）；线网快照带版本，不可变。`PhysicalGraph` 拆成适配器和组件模型；`TrackView`、`SpeedCurves` 进组件 | 线网相关测试都移到组件里通过；`graph_*` 表由组件格式写入，旧数据能读 |

**闸门**：T79 至 T89 实测通过，或者剩下的问题已经列明、不影响基线；之前测出的 bug 都已在现有代码里修好。

**阶段二：分离（游戏内只修 bug，不加运行规则功能）**

| 步骤 | 内容 | 完成标准 |
|---|---|---|
| **S2 运动内核** | `TrainMotion` 进组件，分两种模式：tick 模式行为不变；曲线模式是出发时刻加曲线得到位置。`Train` 通过状态和钩子接口调用它；加 `Occupancy` 接口。`SpeedProfile` 支持从任意位置、非零速度起步（A04），并去掉 E2 里"中途重启不限速"的变通。运行段带版本；区间重算从当前位置和速度出发，满足制动约束，来不及停车时有明确的异常规则 | 新旧逻辑在合成路径上逐 tick 对照一致，场景包括站停、通过、折返、环线、闭塞前瞻、速度曲线、手动驾驶；非零初速的曲线有测试 |
| **S3 运行图模型** | 时刻表、例外、时刻边界、`StopTimes`、`TripPath`、运用、`DutyShapes` 的组件版；`TrainService` 和 `DepotDispatcher` 进组件。计划带版本、不可变，列车绑定计划版本，在定义好的交接点切换（R04 的完整修复）。停站、通过、直通、最短用时的规则只有一套，规划器、网页、运行都调用它（A09） | `TrainServiceTest`、`DutyRunTest` 在组件里通过；存档字节不变；改表后正在跑的列车仍用旧版本跑完 |
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

其余四个版本在 S2 之后再编译一次。

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
| 速度曲线（`SpeedProfile`、`SpeedCurves`） | 完成（`79599eb`） | 1.20.1 | 通过 | 未测（T79 至 T82） | 未做 |
| 线网压缩和路径区间序列（`PhysicalGraph`、`GraphRecords`、SQLite 表） | 完成（`a5fe8ce`） | 1.20.1 | 通过 | 未测（C15） | 未做 |
| 方向预约（`RunReservations`） | 完成（`a5fe8ce`），R01 至 R03 由 F1 修好 | 1.20.1 | 通过 | 未测（T86） | 未做 |
| ANTE 每 tick 入口改为委托（`RailwayData.simulateTrains(RailNearPlayer)`） | 完成（`a5fe8ce`） | 1.20.1 | 不适用 | 未测（T89） | 未做 |

迁移步骤：

| 步骤 | 代码 | 构建 | 单测 | 小场景实测 | 规模实测 |
|---|---|---|---|---|---|
| F1 预约安全修复 | 完成：`RunReservations` 改为持有加 tick 末统一仲裁，`TrainServer` 从车尾持有到运行终点、出库也要申请，存车线按 ID 遍历 | 1.20.1 | 组件 33 项、common 255 项全过（`RunReservationsTest` 11 项，覆盖区间挡停、车尾交接、同 tick 对向申请、等待老化、申请顺序无关）；ABI 扫描无缺失；Fabric、Forge 服务端启动正常 | 未测（T86） | 未做 |
| F2 运用快照 | 未开始 | | | | |
| S0 骨架 | 搬类部分完成，独立构建（R05）未做 | 五个版本编译通过（1.16.5、1.17.1、1.18.2、1.19.4 是 `e5673c0` 以后第一次）；六个 jar 都含组件的类 | 282 项全过（组件 27、common 255）；ABI 扫描无缺失；Fabric、Forge 服务端启动正常 | 不改行为，不单独测 | 不适用 |
| C 契约 | 未开始 | | | | |
| S1 至 S6、全链路场景 | 未开始 | | | | |
| G0 至 G4 | 未开始 | | | | |

## 7. 架构审查的处理

审查文档是 `MTR-设计与架构审查评估-2026-11-03.md`，基线 `051f304`，做的是静态审查。下面逐条核对，并给出归属。

### 7.1 实现层问题

| 编号 | 核对 | 依据 | 处理 |
|---|---|---|---|
| R01 区间挡停时预约失效（高） | 属实 | `TrainServer.java:401` 只在 `speed > 0` 时续订；`RunReservations` 两个 tick 不续就失效。列车在区间被闭塞挡停后预约消失，对向车可以出站，最后会被物理闭塞挡住，两车在单线上对着停住，就是 E2 要防的锁死 | F1（已修） |
| R02 车尾还在时预约已释放（高） | 属实 | `TrainServer.java:402` 从车头所在的轨道开始续订 | F1（已修） |
| R03 调度依赖遍历顺序（中） | 属实 | `RailwayData.java:355` 遍历 `HashSet` 里的存车线；`canBook`、`book` 先到先得，没有仲裁和防饥饿 | F1（已修） |
| R04 旧计划没有版本隔离（高） | 属实 | `TrainService.getDutyDay`、`getDutySegments` 每次读车厂当前的运用；`getStopPlan` 读线路当前的时刻表。网页改了运用、还没刷新路径时，段数相同就会用新班次配旧路径 | F2（运用）、S3（时刻表和完整版本） |
| R05 组件构建依赖模组根项目（中） | 属实，而且更重 | 根 `build.gradle` 在配置阶段联网；`getJson` 失败后无限重试，计数也从不增加 | S0 补完 |

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
