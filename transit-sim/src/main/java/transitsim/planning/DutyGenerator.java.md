# DutyGenerator.java

用 `DutyPlanner` 生成车厂运用的初稿（设计文档 6.12，E1 块）：列车最少，相同时刻表得到相同结果。每条停车线对规划器来说是一个"车厂"；直通链接把多个班次组成一个行程（`Chain`）；列车可以在同一车站的站台之间折返。一周的计划随后被切成运营日、分配给槽位，相同运用的日子合并。另提供：从路线时刻表生成班次输入（`servicesOf`）、构建 `OperationNetwork`（`networkOf`，也供 `DutyCheck` 复用）、列车不足时均匀抽稀班次（`generateSpread`）、按"日型"分别规划（运用组第 2 步）、所需列车数与无法运行班次数的探测。由 `AutoDuties`、`PlanCheck`、`DutyCheck`，以及游戏侧 `mtr.data.LineDuties`、`mtr.data.Depot`、编辑器 `mtr.client.editor.EditorDuties`/`EditorData` 使用。

## 类 `DutyGenerator`（final，工具类）

### 常量
- `MIN_TURNAROUND_MILLIS = 30000`：折返处换端的最短时间（换端最短时间，优化第 4 轮），除非网络另设（`PlanInputs.minReverseMillis`）。使用者：`common/src/main/java/mtr/data/LineDuties.java:1236`、`mtr/data/NetworkSettings.java:21`。
- `TURN_BACK_MILLIS = 120000`：在车厂路径尚未找到、轨道上还没有测量时，估计转到同站另一站台折返的时间。
- `HOLD_IN_KEY = Long.MIN_VALUE`：在 `PlanInputs.turnTimes` 某站台下，表示"从站台驶入其外折返线"所需时间的键（第 4 轮）；站台自身键存的是驶入+驶回总时间，驶回即其差。
- `HOLD_COUNT_KEY = Long.MIN_VALUE + 1`：该站台外能同时等待的列车数（运用补完第 4 步），未给为 1。
- `HOLD_RAIL_KEY = Long.MIN_VALUE + 2`：该站台等待用的折返轨编号（同一轨的各站台相同）。三个键的使用者：`common/src/main/java/mtr/client/editor/EditorData.java:324`–`328`。
- 私有：`MAX_LINKS = 8`（链最多跟随的直通次数）、`UNLIMITED_TRAINS = DepotDuties.MAX_SLOTS`、`MAX_SPREAD_OFFSETS = 8`。

### `private DutyGenerator()`
禁止实例化。

### `public static List<ServiceInput> servicesOf(List<? extends PlanRoute> routes, Function<PlanRoute, long[][]> fastestTimes, int dayStartMillis, Consumer<PlanRoute> missingTimes)`
- 作用：把各路线每张表的每个班次转为 `ServiceInput`，每个班次只在"没有更早的表占用的那些星期"出现一次；停运班次跳过。各站时间与通过标志按 `StopRules.times`（与列车和时刻表页一致），有例外的班次单独计算。附带直通链接，以及运行图上的派车指令（加入站必须早于末站）与回库指令（结束站必须晚于加入站，夹到末站）。`startMillis = floorMod(service - dayStartMillis, 一天)`，即从运营日开始算起。
- 参数：`fastestTimes` 路线 → 各站最快 {到达, 出发}，未知为 null；`dayStartMillis` 运营日开始（一天中的时刻）；`missingTimes` 对因无最快时间（或长度与站数不符）被略去的路线回调。站数 < 2 的路线直接跳过（不回调）。
- 返回值：班次输入列表。
- 主要调用者：`common/src/main/java/mtr/data/LineDuties.java:630`、`LineDuties.java:697`、`mtr/client/editor/EditorDuties.java:308`；`AutoDuties`、`PlanCheck`。

### `public static Result generate(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis)` / `generate(…, PlanInputs inputs)`
- 作用：生成运用（`materialize = true`）。参数：`stationOf` 站台 → 车站（同站站台间可折返）；`maxLayoverMillis` 列车在线上等下一班的最长时间；`inputs` 实测输入（默认 `PlanInputs.NONE`）。
- 主要调用者：本类 `generateSpread`/`generateByDayType`；外部仅测试 `transit-sim/src/test/java/transitsim/planning/DutyGeneratorTest.java:78`。

### `private static Result generate(…, PlanInputs inputs, boolean materialize)`
- 作用：班次或停车线为空时返回空结果；否则 `buildChains` → `timetableServicesOf` → `networkOf` → `DutyPlanner.plan`；`NO_TRAIN` 计入 `uncovered`，除 `UNUSED_BLANK` 外的其它问题以文本加入 `issues`；`trains = plan.peakTrains`；`materialize` 时 `assignSlots`（容量探测时不生成槽位）。

### 内部类 `PlanInputs`（public static final）
路径生成在轨道上测得的数据，使规划依据实测而非估计（解耦文档"按实测数据规划"）。
- `NONE`：无实测（`turnTimes` 为 null，其它为空）。
- `turnTimes`：起站台 → 同站终站台 → 毫秒；未列出的对表示没有路。null 表示把同站内所有换站台都估计为可行（`TURN_BACK_MILLIS`）。还可含 `HOLD_*` 键。
- `backLinks`：停车线 → 站台 → 回库毫秒；`OperationNetwork.UNREACHABLE` 表示无路回去。
- `poolFleets`：池（`SidingInput.poolKey`）→ 列车总数（P2-C、R3），其停车线一周开始时共有这些车；未列出的池按各停车线容量。
- `buffers`：实测超时缓冲（D1），分别加到出库、折返、回库。
- `minReverseMillis`：换端最短时间（网络设置，≥0）。
- `waitThresholds`：站台 → 等待达到多久就去站台外折返线（运用补完第 2 步）。
- 构造：2、3、4、5、6 参数的重载链；null 映射替换为空，null 缓冲为 `Buffers.NONE`。主要创建者：`common/src/main/java/mtr/data/LineDuties.java:546`、`mtr/data/Depot.java:2859`、`mtr/client/editor/EditorDuties.java:388`。
- `withAdditionalPoolTrains(String pool, int added)`：库存增加探测：只把该池的明确列车数增加 `added`（无上限溢出）；`added ≤ 0`、池无设置或为负时返回自身。主要调用者：`common/src/main/java/mtr/data/LineDuties.java:1056`。（源码中其上方有一段 "the same with no pool held…" 的 Javadoc 实际描述的是下面的 `withoutPoolFleets`。）
- `withoutPoolFleets()`：去掉池列车数限制的副本（没有时返回自身），用于按"列车不限"规划。主要调用者：本类 `neededTrains`/`unrunnable`。

### `static List<TimetableService> timetableServicesOf(List<Chain> chains, List<SidingInput> sidings, List<String> issues)`（包内）
- 作用：把每条链转为规划器的 `TimetableService`（ID = 链下标）。运行图设置了派车车厂时，`startDepots` 为该车厂（0 表示任意）的停车线；玩家指定的车厂没有停车线在此时，集合为空（该班无车，而不是随便用一列，P1-A），并记 `DEPLOY_DEPOT …` 问题。设置了回库车厂时同理得到 `returnDepots`（P1-B），无停车线记 `RETURN_DEPOT …`。
- 主要调用者：本类 `generate`、`transitsim/planning/DutyCheck.java:211`。

### `static OperationNetwork networkOf(List<TimetableService> timetableServices, List<SidingInput> sidings, Map<Long, Long> stationOf, PlanInputs inputs)`（包内）
- 作用：构建规划所依据的网络（规划与 `DutyCheck` 检查共用）：
  - 车站、停车线（容量 -1 视为 `UNLIMITED_TRAINS`，并夹到上限）、池列车数、等待阈值；
  - 出库：停车线没有自己的 `links` 时可在任何首站出发（时间 0）；有时只在其中的首站出发，加上出库缓冲，并登记经过站台（`passes`）；
  - 运行图派车到线上某站的班次：停车线到该站的时间取其最近首站的时间 + 缓冲；
  - 回库：按实测 `backLinks`（加回库缓冲，不可达保持不可达）；
  - 每个终点站台：折返时间 = （非"仅环线结束于起点"的站台时）`minReverseMillis` + 原地折返缓冲（环线列车只是再绕一圈，不换端，2026-11-09）；若测得站台→自身（经站台外折返线）的时间，登记折返线等待（驶入取 `HOLD_IN_KEY` 或一半，容量 `HOLD_COUNT_KEY`，轨 `HOLD_RAIL_KEY`）；对同站的其它首站台，登记连接：无实测时 `TURN_BACK_MILLIS` + 缓冲，有实测时只登记测到的对。
- 返回值：网络。
- 主要调用者：本类 `generate`、`transitsim/planning/DutyCheck.java:211`。

### `static List<Chain> buildChains(List<ServiceInput> services)`（包内）
- 作用：把一个班次与其直通链接带到的后续班次组成一条链（一个行程）。被链接的班次在两者都运行的日子作为链的一部分，其它日子单独成链。先计算每个班次被并入其它链的日子；再对每个班次剩余的"自己的日子"建链：从派车加入站（若有）开始，沿链接最多 `MAX_LINKS` 次，遇到自身、派车班次或日子不交时停止；每次在离开站截断前一班次、从加入站接入下一班次；最后一个班次若设置了回库站，在那里截断。链记录派车车厂、是否结束运用、回库车厂。按开始时刻、首段路线排序。
- 主要调用者：本类 `generate`、`transitsim/planning/DutyCheck.java:210`。

### `private static ServiceInput findLinked(Map<String, List<ServiceInput>> byService, ServiceInput service, int days)`
返回链接目标班次中与 `days` 有交集的第一个，没有为 null。

### `private static void assignSlots(OperationPlan plan, List<Chain> chains, List<SidingInput> sidings, Result result)`
- 作用：把一周的运用切成运营日，按停车线分配到槽位，使留在线上过夜的列车第二天保持同一槽位；同一槽位中运用相同（`sameAs` 且回库停车线相同）的日子合并为一个 `DayDuty`。
  - 周末仍在外的运用先处理，使其在周一接续的运用能保持槽位；接续上周的运用沿用其前一运用的槽位（若当天未被占用），否则取第一个空闲槽位。
  - 每个行程按开始时刻落入星期几；同一天多个行程之间的等待方式取自 `duty.waits`，直通链内部各段之间为 `WAIT_ANY`（运用补完第 1 步）。
  - 除最后一天外都 `stayOnLine`；有 `nextDuty` 时最后一天也留线上。首日的 `outLeadMillis` 取 `duty.leaveMillis`（非接续时），末日的 `returnSidingId` 取 `duty.returnDepotId`（无接续时）；回到其它停车线时记入 `result.returnSidings`（`停车线/槽位/星期 → 停车线`）。
  - 槽位编号用 `DepotDuties.defaultId`（P2-D），段数、等待数截到上限。
- 副作用：写入 `result.sidings`、`result.returnSidings`。

### 内部类 `Piece`（private static final）
一个运用在某天的部分：`segments`、`waits`、`stayOnLine`、`outLeadMillis`（默认 -1）、`returnSidingId`。

### 内部类 `Chain`（包内 static final）
- `weekdays`：运行星期。
- `deployDepot`（私有）：列车必须来自的车厂，0 任意，-1 表示任意列车均可（无派车指令）。
- `endsDuty`（私有）：结束后回库。
- `returnDepot`（私有）：回到的车厂，0 任意，-1 表示不结束运用。
- `startMillis`：首站出发时刻（运营日内毫秒），-1 表示未设。
- `segments`：各段（`DepotDuties.Segment`）。
- `stops`：整条链的停站（`TimetableService.Stop`，偏移相对 `startMillis`）。
- 私有：`inputs`、`fromStops`、`offsets`（用于截断最后一个班次）。
- `private Chain(int weekdays)`。
- `private void add(ServiceInput service, int fromStop)`：追加一个班次，从 `fromStop` 起的站；偏移 = 本班开始 − 链开始，若因跨午夜显得早于上一站出发则加一天直到不早于。追加一段 `Segment(route, service, from, -1)`。
- `private void cut(int toStop)`：在 `toStop` 截断最后一个班次（`toStop` 越界或为末站时不变）：删除其后的站，段的 `toStop` 设为该站（至少保留一站）。
- `private TimetableService toTimetableService(int id)`：用链的星期、开始时刻和停站构造（非空白）。

### 内部类 `ServiceInput`（public static final）
一张表的一个班次。
- `routeId`、`service`（表中的班次值）、`weekdays`。
- `startMillis`：从运营日开始到首站出发（实际取首站计划到达所对应的班次时刻）的毫秒。
- `platformIds`、`plans`（每站计划 {到达, 出发}，相对首站）、`pass`（每站是否通过）。
- `linkRoute`、`linkService`、`linkFromStop`、`linkToStop`：直通链接（无链接时 `linkRoute = 0`，`linkFromStop = -1`）。
- `deployStop`（-1 为无）、`deployDepot`、`returnStop`（-1 为无）、`returnDepot`：运行图设置的中途派车/回库站与车厂（0 为任意）。
- `withWeekdays(int newWeekdays)`：只保留部分星期的副本（含指令）。主要调用者：本类 `onDays`。
- 构造（11 参数）：原样赋值，数组不复制。主要创建者：本类 `servicesOf`。

### `public static Result generateSpread(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis)` / `generateSpread(…, PlanInputs inputs)`
- 作用：与 `generate` 相同，但停车线列车不够跑全部班次时，让列车均匀地跑每条路线每第 k 个班次，而不是早上全跑、后面没车。先完整生成；若无未覆盖、或有停车线不限车、或总容量 ≤0，直接返回。再计算 `unrunnable`（列车无限也跑不了的班次，例如没有停车线能到、也没有列车能折返到的起点——这不是列车数问题，2026-11-09 某玩家 256 列车的线路曾因此几乎全部无车）；若未覆盖全部属于此类，直接返回。否则按 (路线, 星期) 分组、按出发排序，k 从 2 开始（<16 时每次 +1，之后每次 +k/8），每个 k 尝试最多 `MAX_SPREAD_OFFSETS` 个偏移（第一组不偏移，其它组偏移），保留 `i ≡ shift (mod k)` 的班次再生成；第一次使未覆盖数 ≤ `unrunnable` 的结果，把被略去的班次（按其运行星期数）加入 `uncovered` 后返回。都不行则返回完整生成的结果。
- 主要调用者：`transitsim/planning/AutoDuties.java:68`、`transitsim/planning/PlanCheck.java:87`、`PlanCheck.java:165`。

### `public static List<Integer> dayTypes(List<ServiceInput> services)`
- 作用：运行相同班次集合的星期归为一个日型（运用组第 2 步），每个日型为星期位掩码，按其第一天排序；没有班次的日子不出现。
- 主要调用者：`common/src/main/java/mtr/data/LineDuties.java:708`、`:720`、`:924`。

### `public static Result generateByDayType(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs)` / `generateByDayType(…, boolean spread)`
- 作用：每个日型单独作为一组运用规划（运用组第 2 步，方案 B），再合并：槽位 i 在每个日型中跑该日型第 i 个运用。列车不跨日型留在线上，也不跨整整一天；同一日型内相邻两天照旧可以。只有一个日型时就是 `generateSpread`（或 `spread = false` 时 `generate`）的结果。合并时 `trains` 取最大，`uncovered` 相加，问题去重合并，日运用的星期与日型掩码按位与，最后按星期排序。
- 主要调用者：`common/src/main/java/mtr/client/editor/EditorDuties.java:395`。

### `public static List<ServiceInput> onDays(List<ServiceInput> services, int mask)`
返回在这些日子运行的班次，只保留这些日子（必要时 `withWeekdays`）。主要调用者：`common/src/main/java/mtr/data/LineDuties.java:721`、`:745`、`:935`。

### `public static Result generateDayType(List<ServiceInput> services, int mask, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs, boolean spread)`
- 作用：单独规划一个日型：只用这些日子的班次，最长停留按 `layoverOf`（列车可过夜到次日，但不超过整天，因为中间的日子属于另一日型，其运用从停车线开始）。
- 主要调用者：`common/src/main/java/mtr/data/LineDuties.java:735`。

### `public static long layoverOf(int mask, long maxLayoverMillis)`
日型是整周时为 `maxLayoverMillis`，否则不超过一天。主要调用者：`common/src/main/java/mtr/data/LineDuties.java:745`。

### `public static int neededTrains(…4 参数)` / `neededTrains(…, PlanInputs inputs)`
- 返回值：停车线列车不限、且不限池列车数时，这些班次最多需要多少列车（不生成槽位），用于与实有列车比较。
- 主要调用者：main 代码中 grep 未见（未确定，可能仅测试）。

### `public static int unrunnable(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs)`
- 返回值：列车再多也跑不了的班次数（按运行星期计）——没有停车线能到、也没有列车能折返到；只有增加出库或折返路才能改变。
- 主要调用者：本类 `generateSpread`、`common/src/main/java/mtr/data/LineDuties.java:745`。

### `private static List<SidingInput> unlimited(List<SidingInput> sidings)`
每条停车线 `withCapacity(-1)` 的副本。

### `public static int uncoveredOnDayType(List<ServiceInput> services, int mask, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs)`
该日型按 `generate`（不生成槽位）规划后的未覆盖数，用于容量探测。主要调用者：`common/src/main/java/mtr/data/LineDuties.java:1056`。

### 内部类 `SidingInput`（public static final）
- `sidingId`、`maxTrains`（容量，-1 不限）。
- `links`：其列车可出发的站台及到达所需毫秒；null 表示所有首站且时间为 0。按运营线规划时，停车线从自己车厂路线的首站出发，越近越优先。
- `depotId`：所属车厂（用于运行图派车指令），未知为 0。
- `poolKey`（可变）：互相跑对方运用、回到下一个需要列车的停车线所属的池（运行时的同一池：相同规划单元与车型）；null 为单独一池。
- `passes`（可变）：首站台 → 出库路折返进入它之前经过的站台（见 `OperationNetwork.addDepotPassing`），可为 null。
- `withCapacity(int capacity)`：只改容量、保留池与出库规则的副本。主要调用者：`common/src/main/java/mtr/data/LineDuties.java:1054`、本类 `unlimited`。
- 构造：`(sidingId, maxTrains)`、`(…, links)`、`(…, links, depotId)`。主要创建者：`common/src/main/java/mtr/data/Depot.java:2855`、`mtr/data/LineDuties.java:412`、`mtr/client/editor/EditorDuties.java:384`。

### 内部类 `Result`（public static final）
- `sidings`：停车线 → 槽位列表。
- `issues`：问题文本。
- `trains`：需要的列车数（高峰）。
- `uncovered`：没有空闲列车的行程数（按星期计），需要更多列车才能跑。
- `unrunnable`：已测得的"列车不限时"未覆盖数；-1 表示未计算。
- `returnSidings`：运用的列车按运行时规则回到的非本停车线：`停车线/槽位/星期(0=周一)` → 停车线。仅计算展示，不保存；运行时自行选择。使用者：`common/src/main/java/mtr/client/editor/EditorDuties.java:368`、`:410`。
