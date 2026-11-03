# MTR 运行模拟组件：分离现状与目标架构

- **最后更新**：2026-11-03，对应 `ante-merge` 分支的 `99329a8`（E2 加速度曲线之后，组件还没开始拆）
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

路径都相对于 `common/src/main/java/mtr/`。行数是 2026-11-03 的。

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
| `advanced/PathBoundaries` | 131 | `FriendlyByteBuf`、msgpack | B | 边界集合进组件，存档读写留在宿主（S1） |
| `advanced/PathRegions` | 252 | `RailGraph` | B（只差 `RailGraph`） | 组件 `network`（S1） |
| `advanced/RegionComputer` | 151 | `RailGraph` | B | 组件 `network`（S1） |
| `advanced/RegionData` | 167 | `FriendlyByteBuf` | B | 同步包，留在宿主 |
| `advanced/PhysicalGraph` | 352 | `Rail`、`RailType`、`RailGraph` | B | 拆开：从 `Rail` 压缩的部分是宿主适配器；`Section`、`SectionPath` 和二进制格式进组件 `network` 和 `io`（S1） |
| `advanced/GraphRecords` | 126 | `RailwayDataStore`、`PathData` | B | 记录队列进组件；SQLite 读写留在宿主，经组件的 `io` 格式（S1） |
| `advanced/PathRegionSync` | 132 | `MinecraftServer`、`ServerPlayer`、网络包 | C | 留在宿主：后台计算触发和同步 |

**速度和运动**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `advanced/SpeedProfile` | 234 | 无 | A | 组件 `motion`（S0 原样搬入） |
| `advanced/SpeedCurves` | 174 | `PathData`、`RailType`、`TransportMode` | B | 改读 `TrackView` 后进组件 `motion`（S1） |
| `data/TickCompensator` | 29 | 无 | A | G4 之后只给旧逻辑用，留在宿主 |
| `data/TransportMode` | 24 | 无 | A | 组件只需要其中"是否连续运动"等几个属性，用组件自己的枚举 |
| `data/RailType` | 46 | `MaterialColor` | B | 组件只要限速和能否加速，由适配器写进区间 |

**运行图、时刻和运用**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `operation/*`（7 个类） | 958 | 无 | A | 组件 `planning`（S0 原样搬入，S6 收拢） |
| `data/RouteTimetable` | 473 | `ServiceExceptions` | B（只差 `ServiceExceptions`） | 组件 `plan`（S3） |
| `advanced/ServiceExceptions` | 233 | `FriendlyByteBuf`、`Route`、`RouteTimetable` | B | 组件 `plan`，读写换成组件格式（S3） |
| `advanced/TimetableBoundaries` | 82 | `Platform`、`Route`、`RouteTimetable` | B | 组件 `plan`（S3） |
| `data/TimetableConfig` | 124 | Gson | A（不依赖 Minecraft） | 时区和日起点进组件，配置文件读写留在宿主（S3） |
| `advanced/DepotDuties` | 243 | `FriendlyByteBuf` | B | 组件 `plan`，读写换成组件格式，存档字节保持兼容（S3） |
| `advanced/DutyShapes` | 161 | `Depot`、`Route`、`RouteTimetable`、`TimetableConfig` | B | 组件 `plan`（S3） |
| `advanced/DutyGenerator` | 349 | `mtr/operation` | B（取数据时用 MTR 类） | 规划部分进组件 `planning`，取数据的部分是宿主适配器（S6） |
| `data/Route` | 1111 | `BlockPos`、NBT、`FriendlyByteBuf`、`PathData` | C 的数据类 | 留在宿主，适配器换成组件的线路 |

**列车运行**

| 类 | 行数 | 依赖 | 级别 | 去向 |
|---|---|---|---|---|
| `data/TrainService` | 1093 | `PathData`、`Siding.TripPath`、`Route`、`RouteTimetable`、`DepotDuties`、`DutyShapes`、msgpack | B | 组件 `sim`（S3）。`Vehicle` 接口改用 `TrackView` 和组件的车次路径 |
| `Siding.StopTimes`、`Siding.TripPath`、`TurnBack*`、`DepotReturn`、`DutyPlan`、`TimeSegment` | 约 1200（在 `Siding` 内） | 嵌在依赖 Minecraft 的 `Siding` 里 | B（逻辑本身不需要世界） | 抽成组件 `plan` 和 `sim` 的独立类（S3），`Siding` 只留路径生成的调度和存档 |
| `advanced/RunReservations` | 80 | 无 | A | 组件 `sim`（S0 原样搬入） |
| `data/SignalBlocks` | 293 | `BlockPos`、`DyeColor`、NBT | C 的数据类 | 占用规则抽成组件的 `Occupancy`（S2），信号颜色和存档留在宿主 |
| `data/Train` | 932 | `Level`、`Player`、物品、方块、NBT | C | 推进规则拆成组件的 `TrainMotion`（S2），见 2.2 节 |
| `data/TrainServer` | 852 | `Level`、方块实体、漏斗、玩家 | C | 占用、出车、方向预约、扣车拆进组件（S2 至 S4）；门、乘客、传感器、漏斗留在宿主 |
| `data/TrainClient` | 402 | 客户端渲染、声音 | C | 留在宿主；G2 起按运行段自己算位置 |
| `data/Siding` | 2343 | `Level`、`MinecraftServer`、网络包 | C | 内部的时刻和路径类拆出（S3），其余留在宿主 |
| `data/Depot` | 1415 | `Level`、`MinecraftServer`、网络包 | C | 出车判断拆成组件的 `DepotDispatcher`（S3）；`MainPath` 寻路调度留在宿主 |
| `data/RailwayData` | 1196 | 服务端、玩家、存档 | C | 留在宿主：持有组件实例，每 tick 驱动和查询 |

**合计**：
- A 级约 1500 行，可以直接搬；
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

现有测试都在 `common/src/test/java`，用 `./gradlew :common:test` 运行。下面这些覆盖的是将来进组件的逻辑，搬的时候跟着移到组件的测试里：

| 测试 | 覆盖 |
|---|---|
| `operation/OperationSimulatorTest` | 运用规划、确定性、跨午夜、晚点恢复、对齐 |
| `path/RailRouterTest`、`path/PathRelocatorTest`、`path/StopSettingsTest` | 确定性寻路、刷新时列车保留原位、停站设置 |
| `advanced/PathRegionsTest` | 寻路边界和区域划分 |
| `advanced/PhysicalGraphTest` | 区间压缩、限速分段、ID 稳定、路径换算、读写 |
| `advanced/SpeedProfileTest` | 速度曲线、巡航上限 |
| `advanced/RunReservationsTest` | 带方向的区间预约 |
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

- 不引用 Minecraft、netty、msgpack、Gson 和 MTR 的任何类。
- ID 一律用 `long`，坐标 ID 由宿主编码，组件不解释。
- Java 8 源码级别，1.16.5 也能用。
- 独立的 Gradle 子项目。common 依赖它，Fabric 和 Forge 的 jar 都要把它打进去。
- 日志、时钟、存储都由宿主通过接口提供。
- 结果确定：同样的输入和时间，算出同样的结果，和调用顺序、线程无关。

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
  - 停服对齐就是按现在的时间查一次；
  - TPS 补偿不再需要；
  - 离玩家远的列车不花开销；
  - 预测到发、PIDS 和网页的实际运行线都直接读模拟。
- **宿主每 tick 只做查询**：把结果写回 `Train` 现有的 `speed`、`railProgress`、`doorValue` 等字段，附属模组和 ANTE 读字段的方式不变。
- **客户端**：收运行段和事件，自己按时间算位置。

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
- **不做**：独立进程。独立线程等图级模拟落地、确实有性能需求时再考虑。

## 5. 迁移步骤

每一步都要满足四条：
- 构建通过；
- 单元测试全过；
- ABI 扫描没有新的缺失；
- Fabric 和 Forge 服务端能启动。

S0 至 S3 不改游戏里的行为。

| 步骤 | 内容 | 完成标准 |
|---|---|---|
| **S0 骨架** | 建子项目并接进构建；原样搬入 A 级的 `SpeedProfile`、`RunReservations`、`mtr/operation`，MTR 改为引用 | jar 里有组件的类；其余四个版本至少编译一次 |
| **S1 线网** | 组件的 `Network`、区间、边界、区域、`RailGraph` 和 `RailRouter`；`PhysicalGraph` 拆成适配器和组件模型；`TrackView`、`SpeedCurves` 进组件 | 线网相关测试全部移到组件里通过；`graph_*` 表由组件格式写入，旧数据能读 |
| **S2 运动内核** | `TrainMotion` 进组件，分 tick 模式（行为不变）和曲线模式（出发时刻 + 曲线 → 位置）；`Train` 通过状态和钩子接口调用它；`Occupancy` 接口 | 新旧逻辑在合成路径上逐 tick 对照一致，场景包括站停、通过、折返、环线、闭塞前瞻、速度曲线、手动驾驶 |
| **S3 运行图模型** | 时刻表、例外、时刻边界、`StopTimes`、`TripPath`、运用、`DutyShapes` 的组件版；`TrainService` 和 `DepotDispatcher` 进组件 | `TrainServiceTest`、`DutyRunTest` 在组件里通过；存档字节不变 |
| **S4 全网模拟** | 事件驱动的 `Simulator`，包括占用时间区间、方向预约、扣车、锁死检测；命令行工具读 `mtr.db` 模拟一天 | E2 全天测试通过；和 S2 逐 tick 结果对照，到发误差在一个 tick 以内 |
| **S5 网页模拟运行** | 编辑器服务端调组件，运行图叠加模拟线和问题列表 | 网页显示晚点、扣车、对顶、站台冲突 |
| **S6 规划收拢** | `DutyGenerator` 和冲突检查用组件的真实用时；删掉 `mtr/operation` 里重复的简化模型 | 生成结果和原来一致或更准，有测试对照 |
| **G1** | 曲线车厂的服务端列车由 `Simulator` 驱动，`Train` 按现实时间查询并写回字段 | 游戏内：曲线车厂列车按时刻运行；停服重进位置正确 |
| **G2** | 客户端按运行段和事件自己算位置，同步包改为运行段 | 游戏内：列车不抖、不跳位；ANTE 渲染正常 |
| **G3** | 手动驾驶和旧逻辑车厂作为外部车登记占用；世界事件送进模拟 | 游戏内：两种列车混跑时闭塞正确 |
| **G4** | 曲线车厂去掉 TPS 补偿；运行状态存档换成运行段格式，加存档版本号 | 旧存档能升级；卡顿时时刻不漂移 |

S6 之后的新功能直接写在组件里，先写无头测试再接游戏：
- 通过站限速和曲率；
- 闭塞分区，和方向预约合并；
- 站前折返；
- 联锁和缓急接续；
- 晚点换车和交换后半段；
- 切断直通和回送；
- 配线图网页。

## 6. 进度

| 步骤 | 状态 |
|---|---|
| 运行逻辑独立第一步：班次决策移到 `TrainService`（`Vehicle`、`Board` 接口，可换时钟） | 已完成，2026-11-02 实测通过 |
| 速度曲线（`SpeedProfile`、`SpeedCurves`） | 已完成，未进游戏实测 |
| 线网压缩和路径区间序列（`PhysicalGraph`、`GraphRecords`、SQLite 表） | 已完成，未进游戏实测 |
| 方向预约（`RunReservations`） | 已完成，未进游戏实测 |
| ANTE 每 tick 入口改为委托（`RailwayData.simulateTrains(RailNearPlayer)`） | 已完成，未进游戏实测 |
| S0 至 S6 | 未开始 |
| G1 至 G4 | 未开始 |
