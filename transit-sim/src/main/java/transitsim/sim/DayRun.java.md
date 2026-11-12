# DayRun.java

用世界数据仿真一个运营日，供编辑器与计划并排显示（S5）：每个服务实际在何时何地停站，以及途中出了什么问题。它在独立的世界中运行，游戏中什么都不会改变。

从一天开始运行时，每列车都从车厂出发，所以过夜留在线上的列车改为从车厂（或其运用起点，见 `startOfDay`）开始。从当天较晚时刻开始运行时，它从数据中的列车状态续跑（G0），只报告此后发生的事。

在整体中的位置：游戏侧 `SandboxRuns.java:146`（服务器后台运行并 `toBytes` 发送）、编辑器 `EditorSimulation.java:209`（`fromBytes` 显示）；组件 `PlanCheck.java:109`、`:185`（计划检查）；工具 `SimulateDay.java:113`（`startOfDay`）。内部使用 `EventSimulator`、`TickRunner`、`SimDataset`、`HostInput`、`SimEvent`，以及 plan 包的 `OperatingDay`、`PlanRoute`、`RouteTimetable`。

## 类 DayRun（final，不可变结果）

### 字段
- `trips`（不可修改 List<Trip>）：被报告线路的服务，按首次停站顺序。
- `problems`（不可修改 List<Problem>）：问题，按发生时间排序；未运行的服务在最后加入（排序后与时间混排）。
- `dayStart`（long）：运营日开始时刻（毫秒）。
- `fromMillis`（long）：仿真开始时刻（运营日开始，或续跑时更晚）。
- `cancelled`（boolean）：是否在一天结束前被停止。

### 常量
- `REPORT_MILLIS = 30000`：晚点或比计划多站/多等超过此值才报告。
- `OVERRUN_MILLIS = 1 小时`（private）：一天结束后继续仿真的时长，让最后的服务到站。
- `STEP_MILLIS = 10 分钟`（private）：每仿真这么久询问一次是否停止。
- `VERSION = 2`（private）：报告字节格式版本。

### 私有构造函数
按字段赋值，列表包成不可修改。调用者：`run`、`fromBytes`。

### `static Supplier<TickRunner<SimDataset.DataTrack>> startOfDay(byte[] bytes, long dayStart)`
- 作用：返回一个从一天开始运行的运行器工厂：用 `SimDataset.start(bytes, dayStart)` 建运行器，再 `placeOvernightTrains()` 把过夜留在线上的列车放在其运用起点（2026-11-09），每次以同样方式重建。
- 调用者：`SimulateDay.java:113`、`OvernightPlacementTest.java:73`；本类 `run`。
- 注：源码在此方法上方有一段孤立 javadoc（`dataset`/`dayStart`/`reportRoutes`/`inputs`/`cancelled` 参数），描述的是 `run`。

### `static DayRun run(SimDataset dataset, long dayStart, Set<Long> reportRoutes, List<HostInput> inputs, BooleanSupplier cancelled)`
从一天开始运行（`fromMillis = dayStart`）的便捷形式。调用者：`PlanCheck.java:109`、`DayRunTest.java:32`。

### `static DayRun run(SimDataset dataset, long dayStart, long fromMillis, Set<Long> reportRoutes, List<HostInput> inputs, BooleanSupplier cancelled)`
- 作用：
  1. 建 `Recorder`，把数据集序列化。
  2. `fromMillis > dayStart` 且数据集带列车时用 `EventSimulator.resume` 续跑，否则 `new EventSimulator<>(startOfDay(...))`。
  3. 设列车事件监听为 recorder，提交所有宿主输入。
  4. 每 10 分钟一步推进到 `dayStart + 一天 + 1 小时`，每步前询问 `cancelled`。
  5. 结束未结束的停留（`endHolds`），把仿真中的 `DEADLOCK` 事件转为问题，计算晚点（`addLateness`），未被取消时找出未运行的服务（`addNotRun`），问题按时间排序。
- 参数：`dataset` 世界（草稿已用 `SimDataset.replaceRoute`/`replaceDuties` 放入）；`dayStart` 运营日开始；`fromMillis` 开始时刻（晚于 dayStart 时从 `SimDataset.trains` 续跑，其允许的过期程度见 `EventSimulator.resume`）；`reportRoutes` 要报告的线路，空 = 全部；`inputs` 宿主途中的输入（如外部占用轨道）；`cancelled` 每 10 仿真分钟询问一次，true 则停止。
- 返回：新 `DayRun`。副作用：无（独立世界）。
- 调用者：`SandboxRuns.java:146`、`PlanCheck.java:185`。

### `byte[] toBytes()`
- 作用：服务器发给编辑器的报告：版本、dayStart、fromMillis、cancelled；每个服务（线路、服务时刻、列车、各停站：站序、站台、是否通过、实际到/发、计划到/发）；每个问题（种类序号、时间、时长、线路、服务、列车、站序、站台、UTF-8 详情）。时刻相对一天开始编码（`writeTime`）。
- 调用者：`SandboxRuns.java:148`。

### `static DayRun fromBytes(byte[] bytes)`
- 作用：读回 `toBytes` 结果；版本不符抛 `IllegalArgumentException("Unknown day report version …")`；未知问题种类序号按最后一种处理。
- 调用者：`EditorSimulation.java:209`。

### `private void writeTime(BinaryWriter writer, long millis)` / `private static long readTime(BinaryReader reader, long dayStart)`
时刻编码为"距一天开始的毫秒 + 1"（int），−1（无）编为 0。

## 枚举 ProblemKind
- `LATE`：在某站比计划晚超过 `REPORT_MILLIS`；每个服务只报一次（第一次晚点的站），时长为最大晚点。
- `WAITING_FOR_PLATFORM`：停在被其他列车占用的站台前。
- `HELD_ON_LINE`：在线路上停在被占轨道前。
- `LONG_DWELL`：在站停留比计划长（前方被占或被扣车）。
- `DEADLOCK`：列车环形互相等待（如单线上两车对头）。
- `NOT_RUN`：计划中的服务没有列车运行。

## 内部类 Trip（public static final）
一个实际运行的服务。
- `routeId`、`serviceMillis`（时刻表中的服务，完整时间戳）、`trainId`、`stops`（List<Stop>）、`nextStopIndex`（private，下一个待匹配的线路站序）。
- 私有构造函数。

## 内部类 Stop（public static final）
服务的一次停站，附计划值；时间为完整时间戳，无为 −1。
- `stopIndex`（线路中的站序，不是线路的站为 −1）、`platformId`、`passed`（通过不停）、`arrivalMillis`、`departureMillis`（初值 −1）、`plannedArrivalMillis`、`plannedDepartureMillis`。
- 私有构造函数。

## 内部类 Problem（public static final）
- `kind`、`time`、`durationMillis`（持续时长；LATE 为最大晚点）、`routeId`（无服务为 0）、`serviceMillis`、`trainId`、`stopIndex`（所在或之前的站序，或 −1）、`platformId`、`detail`（死锁时为相关列车）。
- 私有构造函数。

## 内部类 Recorder（private static final）
列车行动时记录（只读每列车本身）。
- 字段：`reportRoutes`；`routePlatforms`（线路 → 站台 id 数组）；`trips`（"线路@服务时刻" → Trip，保持插入顺序）；`problems`；`standing`/`standingTrips`（列车 → 正在停的站及其服务）；`holds`（列车 → 中途停住的记录）。
- 构造函数：从数据集读取各线路站台。
- `onEvent(MotionEvent event, HeadlessTrain train)`：`STOPPED` 于有停站时间的轨道 → `arrive`；否则记为中途停住（下一条是站台则为等待站台）。`PASSED` 于站台 → 到达并离开（通过）。`DEPARTED`：结束中途停住（`endHold`），否则（非出库）`depart`。`RETURNED`：清理该车记录。调用者：作为列车监听器（`run` 中 `setTrainListener`）。
- `arrive(...)`：取当前服务；以 `service.getPlanAt(rail)` 求计划到/发，新增 `Stop`。
- `depart(...)`：若停站结束了一个服务、开始了下一个，则在新服务中添加一个首站（只有计划发车）；否则记录发车时刻，若扣除已有晚点后仍比计划多停 > `REPORT_MILLIS`，记 `LONG_DWELL`。
- `endHold(Hold hold, long trainId, long endMillis)`：停住超过 `REPORT_MILLIS` 时记 `WAITING_FOR_PLATFORM` 或 `HELD_ON_LINE`。
- `endHolds(long endMillis)`：结束时仍停住的列车（始终未能前进）全部结算。
- `addLateness()`：每个服务找第一个晚点 > `REPORT_MILLIS` 的站，记 `LATE`（时长为最大晚点，到达和发车都算）。
- `addNotRun(LongFunction<? extends PlanRoute> routes, long fromMillis, long beforeMillis)`：对每条被报告且时刻表有效的线路，在时间范围内的每个服务若未出现在 trips 中，记 `NOT_RUN`。
- `getTrip(HeadlessTrain train)`：列车当前服务（`service.getTripRouteId/getTripMillis`），无服务或不报告则 null；首次出现时新建 Trip。
- `isReported(long routeId)`：报告集合为空或包含该线路。
- `findStopIndex(Trip trip, long platformId, boolean advance)`：从 `nextStopIndex` 起找线路中该站台的站序；`advance` 时把 `nextStopIndex` 移到其后。
- `static getKey(long routeId, long serviceMillis)`：`"线路@服务时刻"`。

## 内部类 Hold（private static final）
中途停住：`startMillis`、`platformAhead`（前方是站台）、`trip`、`nextPlatformId`。
