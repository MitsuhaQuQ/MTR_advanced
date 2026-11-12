# LineDuties.java

按"运营线路"而不是按车厂编排车辆运用（解耦文档 5.4，L1 之后）。运营线路是一个线路组（`lineKey`：交通方式 + `Route#getLineKey`），包括两个方向、所有服务类型和支线。按时刻表运行且未手动设置运用的车厂，以及它们运行的线路，通过各车厂列出的线路和班次之间的贯通链接连成一个"编排单元"，一次整体编排：一个班次无论哪个车厂列出它的线路都只编排一次；只列出某线一个方向的车厂也运行另一个方向（列车从自己线路的首站出发，沿线折返）。

单元中每个车厂都在自己的找路线程上算出整个单元并保留自己停车线的槽；编排器对同一单元给出同样的运用，所以各车厂一致（`PlanCache` 让同时找路的车厂共享一次计算）。编排算法在组件 `transitsim.planning.DutyGenerator`，检查在 `DutyCheck`。被 `Depot`（`:1103`、`:1189`、`:1209`、`:1986`、`:2358`、`:2859` 等）、`RailwayData.java:794`（`unitsOf`）、`EditorDuties`（草稿）、`SimExport`/`RunningSides`（`getLineKey`）使用。

## final class LineDuties

### 常量
- `MILLIS_PER_BLOCK = 50`：选最近停车线时假定列车每块 50 ms（每秒 20 块）。
- `ESTIMATE_MARGIN_MILLIS = 30000`：按距离估计出库时间时另加的余量（离开停车线、弯道）。
- `CUSTOM_TRAIN_PREFIX = "mtr_custom_train_"`：显示车辆名时去掉的前缀。
- `LOGGED_PASSES`（0.73.2）：每条停车线上次打印的"先到先出"那行，只在内容变化时再打印（检查运用每几秒一次，Q 盘世界 25 分钟打了 250 行，L14）。
- `UNRUNNABLE = "UNRUNNABLE"`（public）：计划问题类型：任何列车数都无法运行的班次数（按每周运行日计）。

### `private LineDuties()`
工具类。

### `public static Map<String, Integer> poolFleetsOf(List<DutyGenerator.SidingInput> sidings, LongFunction<Depot> depotOf)`
- 返回：每个车池的列车总数（P2-C，R3）：每个车厂为该池设定的数（每车厂每池只计一次），否则各停车线车数，按组件规则相加（`FleetRules.sumFleets`，S7）；某车厂设为"足够多"（结果为负）的池不列入。
- 调用者：`EditorDuties.java:388`、`Depot.java:2859`、本类 `inputsOf`。

### `public static Map<Long, Long> sidingLinks(Siding siding, Collection<Long> platformIds, DataCache dataCache)`
- 返回：停车线列车到各站台的出库时间（毫秒）：找路时在轨道区段上测得的（`Siding#getRecordedLegs`，第 4 轮），否则估计值；测得无路的站台排除（该停车线不从那里发车，P2-A）。
- 调用者：`EditorDuties.java:384`、`Depot.java:2855`、本类 `Unit`。

### `public static Map<Long, Long> sidingBackLinks(Siding siding, Collection<Long> platformIds, DataCache dataCache)`
- 返回：从各站台回到停车线的时间，同样方式；无回库路的标为 `OperationNetwork.UNREACHABLE`（编排器会把列车送回同池另一停车线，P2-A）。

### `public static Map<Long, Map<Long, Long>> backLinksOf(Collection<Siding> sidings, Collection<Long> platformIds, DataCache dataCache)`
- 返回：每条停车线的回库时间表。调用者：`EditorDuties.java:388`、`Depot.java:2859`。

### `public static long estimateMillis(Siding siding, long platformId, DataCache dataCache)`
- 返回：停车线与站台之间出/回库时间估计：直线距离 × 50 ms × 1.5 + 30 秒；站台不存在为 30 秒。

### `private static Map<Long, Long> legsOrEstimates(Siding siding, Map<Long, Long> kept, Collection<Long> platformIds, DataCache dataCache)`
- 对存在的站台取记录值或估计值。

### `public static Map<Long, Map<Long, Long>> turnTimesOf(Collection<Depot> depots)`
- 返回：各车厂测得的站内换站台时间合并（来自 `depot.clientTurnTimes`）；都未测量时 `null`（由编排器估计）。
- 调用者：`EditorDuties.java:341`、`:388`、`Depot.java:2859`。

### `public static Map<Long, Long> waitThresholdsOf(Collection<Route> routes, Collection<Depot> depots, DataCache dataCache)`
- 返回：站台 → 等候下一班次多久以上时在其后的折返线而不是站台等候（"运用补完"第 2 步）：线路所属线的某车厂规则（任一星期匹配且设置了）优先，否则车站自己的，否则全网的；只列出不等于运行时默认值（`TrainService.LONG_TURN_BACK_WAIT_MILLIS`）的。
- 调用者：`Depot.java:2859`、本类 `Unit`。

### `public static String getLineKey(Route route)`
- 返回：`交通方式 + "|" + route.getLineKey()`。
- 调用者：`RunningSides.java:92`、`SimExport.java:50`、`Depot.java:2358`。

### `public static boolean plansByLine(Depot depot, DataCache dataCache)`
- 返回：车厂运用是否按线编排：未手动设置运用（非 `diagramMode`）且 `canPlanByLine`。

### `private static boolean canPlanByLine(Depot depot, DataCache dataCache)`
- 非连续运动、非飞机、且有时刻表（`depot.findTimetable` 非空）。

### `public static Unit of(Depot depot, DataCache dataCache, Collection<Siding> sidings)` / `of(..., boolean forDraft)`
- 作用（服务器线程读取）：车厂所属的编排单元；不按线编排时 `null`。`forDraft` 用于编辑器"一键生成运用"草稿：即使车厂已手动设置运用也按线编排，且其自己的设置不算已占用。
- 调用者：`EditorDuties.java:312`、`Depot.java:1103`、`Depot.java:1986`。

### `public static Map<Long, Unit> unitsOf(Collection<Depot> depots, DataCache dataCache, Collection<Siding> sidings)`
- 作用：一次读取世界的线路和车厂，为每个单元只构造一次，供检查所有车厂自动运用变化时使用。
- 返回：车厂 id → 单元（同单元车厂共享对象；不按线编排的不列出）。
- 调用者：`RailwayData.java:794`。

## private static final class Lines
某一交通方式的线路与按线编排的车厂，按其列出的线路和班次贯通链接相连。
- 字段：`lineRoutes`（线键 → 线路）、`lineDepots`（线键 → 车厂）、`depotLines`（车厂 → 线键）、`linkedLines`（有贯通链接相连的线）。
- `Lines(TransportMode transportMode, DataCache dataCache, Depot draftDepot)`：建立上述映射（`draftDepot` 即使已设置运用也计入）。
- `unitOf(Depot depot, DataCache dataCache, Collection<Siding> sidings, long draftDepotId)`：从该车厂出发广度优先，经其线、线上的车厂、贯通的线到达的一切，构造 `Unit`。

## public static final class Unit
一起编排的线路、车厂和停车线（服务器线程读取），以及使运用变化的因素。

### 字段
- `key`（public）：完整键：线路时刻表与停站、分组与停车线车数、已占用班次、附加时间、最短换端时间、等候阈值、联络线及其释放轨道。
- `basis`（public）：运用由什么构成（不含任何时间，第 4 轮第 4 步）：时刻表与停站、停车线与车数、留给他人的班次、各车厂车池车数、运营日起点（摘要）。由同样基础编排的运用，在新时间下仍成立时保留（`keep`）。`-Dmtr.debugPlanBasis=true` 时打印。
- `buffers`：单元各车厂采用的出库/折返/回库附加时间（同键取最大，D1，第 4 轮第 6 步）；`buffersKey`：其摘要（无为空）。
- `minReverseMillis`：全网最短换端时间。
- `waitThresholds`：见 `waitThresholdsOf`。
- `name`（public）：单元的线名（日志与缺口提示）。
- `depotIds`（public）。
- `routes`：单元中有时刻表且至少 2 站的线路（按 id）。
- `groups`：按车辆服务类型（以及只给部分线路的停车线）分的停车线组。
- `taken`：已由手动设置运用的车厂运行的班次（`route@service`），留给它们。
- `staysOnLine`：任一车厂 `cycleRoutes`（决定最长停留时间）。
- `stationOf`：站台 → 车站。
- `backLinks`：每条停车线从各站台回库的时间（上次找路测得）。
- `sidingFirsts` / `sidingLasts`：每条停车线的首站（列车出到这里）与末站（从这里回）。

### `private Unit(Set<String> lines, Map<String, List<Route>> lineRoutes, Set<Long> depotIds, DataCache dataCache, Collection<Siding> sidings, long draftDepotId)`
- 作用：收集线路与名称；记录手动运用占用的班次（草稿车厂除外）；对每个车厂按名称排序的本车厂停车线（按游戏分配，含高度区分，`isOwnSiding`）：按服务类型和指定线路分组（`Depot.getSidingGroupKey`），记录首/末站，出库链接（`sidingLinks`）与回库链接，`SidingInput`（车数、链接、车厂）；出库路在终点站折返前经过的站台（`outPasses`/`outPassNodes` 转为站台，供"先到先出"，有则打印，同一停车线内容不变时不重复打印）；车池键；车辆名；可用车数（车厂为池设定的数每车厂每池计一次，否则各停车线车数，无限按 `MAX_SLOTS`）。各组的线路按服务类型过滤（`Depot.filterRoutes`）并限于其指定线路。最后计算 `key` 与 `basis`。

### `Set<String> getTaken()` / `public List<Route> getRoutes()`
- 只读视图。调用者：`EditorDuties.java:315`、`EditorData.java:287`（getRoutes）。

### `public Plan plan(Function<Route, long[][]> fastestTimes, int dayStartMillis)` / `plan(..., Map<Long, Map<Long, Long>> turnTimes)` / `plan(..., turnTimes, Map<Long, DepotLegs> measured)`
- 参数：`fastestTimes` 线路最快时分（未知为 `null`，该线路被略去）；`dayStartMillis` 运营日起点；`turnTimes` 站内换站台时间（缺失的对表示无路，`null` 为估计）；`measured` 本次找路在轨道区段上测得的各停车线出/回库（第 4 轮第 3 步），代替保存的；`null` 用保存的。
- 作用：在服务器线程之外编排整个单元：`build(..., inputsOf(turnTimes, measured), measured, null, null)`。
- 调用者：`EditorDuties.java:343`、`Depot`（找路时）。

### `private DutyGenerator.PlanInputs inputsOf(Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured)`
- 编排输入：换站台时间、回库链接（测得的覆盖：无路为 `UNREACHABLE`）、车池车数、附加时间、最短换端、等候阈值。

### `static String waysKey(Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured)`
- 返回：轨道给出了哪些路（不含时长）的摘要：测得的换站台/折返对（不含 `HOLD_IN_KEY`），以及每条停车线有出/回库路的首/末站、无路的站、`outPasses`；`measured` 为 `null` 时为空串。

### `private long getMaxLayover()`
- `staysOnLine ? MAX_STAY_MILLIS : MAX_LAYOVER_MILLIS`。

### `public Plan keep(Map<Long, byte[]> records, Map<Long, List<DepotDuties.Slot>> slots, Function<Route, long[][]> fastestTimes, int dayStartMillis, Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured, Consumer<String> why)` / `keep(..., Set<String> forced, Consumer<String> why)`
- 参数：`records` 各车厂保存的其运用来源计划记录（`Plan#toBytes`）；`slots` 各停车线现有运用；`forced` 玩家要求重新编排的运用组键（运用组第 4 步）；`why` 接收"为何重新编排"的说明（日志）。
- 作用（第 4 轮第 4 步）：保留以前编排的运用，条件是它由单元现在的构成编排、并在这些时间下仍成立（按编排器的规则 `DutyCheck` 检查——时间小变化不动，违反任何规则就重新编排）：
  - 每个有停车线的车厂都必须有记录（否则 `null`）；记录早于"运用补完"第 1 步（`beforeItems`）则重排；`basis` 不同时尝试只保留部分运用组（`keepParts`）；
  - 用现有运用组成检查组并 `DutyCheck.check`；无违规但有强制组时 `keepParts`；有违规时打印前 5 条并 `keepParts`；
  - 全部保留时标记 `kept`，并给出"重新编排提示" `replanHint`：最短换端变化（`minReverse:旧:新`）、附加时间变化（`buffers`）、有未覆盖班次且轨道给出的路变化（`ways`，2026-11-09：终点站新增折返）——只在玩家要求时重排（用户 2026-11-08），运用不会自行改变。
- 返回：保留的计划，或 `null` 表示重新编排。
- 调用者：`Depot.java:1209`；测试 `DutyGroupsKeepTest`。

### `private Plan build(Function<Route, long[][]> fastestTimes, int dayStartMillis, DutyGenerator.PlanInputs inputs, Map<Long, DepotLegs> measured, Map<String, Part> kept, Map<Long, List<DepotDuties.Slot>> slots)`
- 作用：按运用组编排（运用组第 2 步）：依次每个停车线组，把它占用的班次留给之后的组；组内每个日类型（`DutyGenerator.dayTypes`）分别编排。每个"部分"（组 × 日类型）有键（`partKey`）和基础（`partBasis`）；在 `kept` 中且基础相同则原样保留其运用（`slotsOn`）及当时的结果；否则 `DutyGenerator.generateDayType` 编排，有未覆盖班次时区分"任何列车数都无法运行的"（无停车线到达、无列车可转来，2026-11-09，记 `UNRUNNABLE n` 问题），并对最近的有限停车线二分求还需多少列车（`extraTrains`）得出所需数。合并部分运用（`mergeSlots`），按星期排序日运用，累计车数、所需、未覆盖、问题（`UNRUNNABLE` 计数累加），车不够时建议加车的停车线（离线路最近的有限停车线，"车厂名 停车线名"）。有 `NOT_PERIODIC` 问题（运用不按周重复）时所需设为 −1。`-Dmtr.debugPlanInputs=true` 时打印编排输入。
- 返回：`Plan`。

### `private static DutyGenerator.SidingInput nearestLimited(List<DutyGenerator.SidingInput> sidings)`
- 返回：车数有限、出库最快的停车线，或 `null`。

### `private static String partKey(Group group, int mask)`
- 返回：运用组键：`车辆/... # 组摘要前 8 位 | 日类型掩码`。

### `private String partBasis(Group group, List<DutyGenerator.ServiceInput> services, int mask, int dayStartMillis)`
- 返回：运用组的构成摘要（不含时间）：其日子上的班次（线路、班次、星期、开始时刻、站台、通过、链接、出入库指令）、服务类型与指定线路、停车线与车数、车池车数、运营日起点。

### `private static Map<Long, List<DepotDuties.Slot>> slotsOn(Group group, Map<Long, List<DepotDuties.Slot>> slots, int mask)`
- 返回：组内停车线在这些日子的运用拷贝（槽保持位置和编号）。

### `private static void mergeSlots(Map<Long, List<DepotDuties.Slot>> merged, Map<Long, List<DepotDuties.Slot>> part, int mask)`
- 作用：把一个运用组的运用并入停车线的槽（每个槽在各组中运行其同位置的运用，日子按掩码裁剪），去掉末尾空槽。

### `private Plan keepParts(Plan record, Map<Long, List<DepotDuties.Slot>> slots, Function<Route, long[][]> fastestTimes, int dayStartMillis, Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured, Consumer<String> why, List<DutyCheck.Break> breaks, Set<String> forced)`
- 作用（运用组第 2 步）：保留由相同班次和停车线编排、其运用仍成立的运用组，其余重排：记录中没有部分则 `null`；候选 = 组的运用都落在某日类型内、且部分基础相同；违规所在（日子与停车线）的部分去掉；玩家强制的去掉；一个重排的组会把之后同日子同线路的组一起带走（它可能运行别的班次）。无候选则 `null`。用候选 `build`，再整体检查：重排的部分像从头编排一样只作为问题报告，只有保留的部分违规才 `null`。打印保留与重排的组。

### `private List<DutyGenerator.SidingInput> withMeasured(List<DutyGenerator.SidingInput> sidings, Map<Long, DepotLegs> measured)`
- 返回：用刚测得的出库时间替换的停车线输入：到每个首站有路则用测得值，测得无路则去掉，未测则沿用。

### `public Map<Long, List<List<Long>>> getSidingStops()`
- 返回：每条停车线的 `[首站列表, 末站列表]`，供找路测量出/回库。调用者：`Depot.java:1189`。

### `private int extraTrains(...)` / `private int uncoveredWith(...)`
- `extraTrains`：二分求给某停车线最少加几列车，就能运行任意列车数所能运行的同样多班次。
- `uncoveredWith`：把该停车线车数设为某值（并给其车池加相应列车）时，该日类型的未覆盖班次数（`DutyGenerator.uncoveredOnDayType`）。

## private static final class Group
- `serviceTypes`、`routeIds`（指定线路，空为全部）、`sidings`、`routes`、`depotOf`（停车线 → 车厂）、`sidingNames`、`available`、`countedPools`、`vehicles`（组内停车线的车辆，单元多于一组时用来命名）。

### `private static boolean isOwnSiding(DataCache dataCache, Depot depot, Siding siding)`
- 返回：停车线是否属于该车厂：按游戏的分配（含高度）；尚未分配（如数据拷贝中）时车厂区域水平包含它即算。

### `static String digest(String key)`
- 返回：SHA-256 前 16 字节的十六进制（用于存档的短摘要）；无此算法时用 `hashCode`。

## public static final class PlanCache<T>
最近算出的计划，按其来源键：同时找路的同一单元车厂只编排一次——第一个请求者编排，其它在此期间请求者等待它的结果（找路时多个车厂并行）。
- `MAX_PLANS = 64`；`plans`：访问序 `LinkedHashMap`。
- `trim()`：超出上限时淘汰最久未用的已完成结果（进行中的保留共享）。
- `T get(String key, Callable<T> planner)`：没有则自己创建并运行任务，否则等待已有任务；执行异常时移除该任务；若原因是取消：自己是创建者则抛出，否则重试（由自己重新计算）；等待被中断则抛 `CancellationException`。
- 调用者：`Depot`（找路时共享单元计划，具体行号未逐一确认）。

## public static final class Part
一个运用组（停车线组 × 日类型）的编排记录（运用组第 2 步）：`key`、`basis`、`trains`、`needed`、`uncovered`（无车的班次，按每周运行日计）、`issues`、`returnSidings`。只要其自己的班次和停车线不变且运用仍成立就可保留，无论其它组如何变化。

## public static final class Pool
单独编排的一组停车线（一个车辆池）的需求：`label`、`needed`、`available`、`uncovered`。`toString()`："标签: needs N（未知为 ?）, has M[, k services a day with no train]"。

## public static final class Plan
一个单元的运用：每条停车线的槽，以及列车是否足够。

### 字段
- `lineNames`、`sidings`（停车线 → 槽）、`basis`、`beforeItems`（记录早于"运用补完"第 1 步：照常显示但不保留，重排一次）、`kept`、`minReverseMillis`、`buffersKey`、`waysKey`（记录 7 起）、`replanHint`、`trains`、`needed`（−1 未知）、`available`、`uncovered`、`missingTimes`、`issues`（编排器的问题，如 `SHORT_TURNAROUND <站台> <实有毫秒> <需要毫秒>`）、`returnSidings`（运用列车不回本停车线时回哪里，按运行时规则）、`suggestedSiding`、`pools`、`parts`（记录 5 起）、`keptParts`、`plannedParts`。

### `private Plan(String lineNames)`

### `public byte[] toBytes()` / `public static Plan fromBytes(byte[] bytes, String lineNames)`
- 车厂存档中的计划记录（不含运用本身，运用由车厂保存）。格式（组件 `ByteArrayBinary`）：`byte 版本(7), String basis, long minReverseMillis, String buffersKey, int trains, int needed, int available, int uncovered, int missingTimes, varint n + String×n issues, varint n + (String, long)×n returnSidings, String suggestedSiding`；版本 4 起 `varint n + (String label, int needed, int available, int uncovered)×n pools`；版本 5 起 `varint n` 个部分（`String key, String basis, int trains, int needed, int uncovered, varint + String×issues (≤256), varint + (String, long)×returnSidings (≤256)`）；版本 7 起 `String waysKey`。各列表最多 4096 项，字符串最多 65536 字节（变长整数长度 + UTF-8 字节）。
- `fromBytes`：空、版本 <3 或 >7、或格式错误返回 `null`；版本 <6 标记 `beforeItems`。
- 调用者：`Depot.java:556`（toBytes）、`Depot.java:396-405`（fromBytes）。

### 私有：`writeString` / `readString`（长度越界抛 `IllegalArgumentException`）；常量 `RECORD_VERSION = 7`（6：此后编排的运用保留列车在哪等、何时出发、去哪回库；7：轨道给出的路 2026-11-09）、`MAX_PART_ITEMS = 256`、`MAX_RECORD_ITEMS = 4096`、`MAX_STRING_BYTES = 65536`。

### `public int getUnrunnable()`
- 返回：`UNRUNNABLE n` 问题的总数（不超过 `uncovered`）。调用者：`Depot.java:2636`。

### `public String describe()`
- 返回：日志用描述：需要/已有列车（运用不按周重复时说明未知）、每天无车班次及其中无法运行的、建议加车的停车线、因时分未知被略去的线路数、回同池其它停车线的运用数、多组时各组情况，以及折返时间过短与折返能力不足的提示（`shortTurnarounds`）。
- 调用者：`Depot.java:1213`、`:1217`、`:1221`。

### `private String shortTurnarounds()`
- 返回：`SHORT_TURNAROUND`（站台、实有秒、需要秒）与 `TURN_BACK_CAPACITY`（到达间隔秒、折返所需秒，2026-11-12 七号线）问题的文字。
