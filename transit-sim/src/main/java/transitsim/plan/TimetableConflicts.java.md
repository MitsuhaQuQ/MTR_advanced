# TimetableConflicts.java

时刻表冲突检查：找出同一时间需要同一站台（或折返线、或站场咽喉道岔）的班次，让跑不通的时刻表在列车运行之前就显露出来。每个星期几用当天运行的表检查，跨午夜的时间仍归入同一天的表。以发车表而非时刻表运行的车厂不参与。由服务器 `mtr.packet.PacketTrainDataGuiServer`（`:850`–`:906`）组装 `RouteTimes` 并调用 `check`，结果经 `Result` 发送到客户端（`PacketTrainDataGuiClient:383`），时刻表界面 `mtr.screen.TimetableScreen:734` 显示。依赖 `StopRules`（计算各班时间）、`TerminalPairs`（确定配对）、`transitsim.network.ThroatRoutes`（咽喉道岔，L2）。

## 类 `TimetableConflicts`（final，工具类）

### 常量
- `MIN_PLATFORM_GAP_MILLIS = 30 秒`：前车离开站台到后车到达的最小间隔（列车有长度，需要出清）。
- `THROAT_MILLIS = 30 秒`：列车通过站场咽喉的时间（到站前、离站后各算这么久），是近似值，因为路线时间只在停站处已知（L2）。
- `MAX_CONFLICTS = 256`：最多报告的冲突数。
- `DAYS_PER_WEEK = 7`（私有）。

### `public static List<Conflict> check(long routeId, List<RouteTimes> routes)`
- 作用：检查一条路线的冲突。收集该路线的站台（含站前折返站台）、相关折返轨（该路线自己的或以该路线为下一路线的折返候选）；对一周 7 天，若当天各路线所用表的组合与已检查过的某天相同则跳过（结果相同），否则依次 `checkDay`（站台）、`checkTurnBacks`（折返线）、`checkThroats`（咽喉）。结果按表、班次、站序排序。
- 参数：`routeId` 被检查的路线；`routes` 该路线及所有与它共用站台的路线的时间。
- 返回值：冲突列表（最多约 `MAX_CONFLICTS` 条）。
- 主要调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:866`。

### `private static void checkDay(long routeId, List<RouteTimes> routes, Map<Long, RouteTimes> routeMap, Set<Long> platformIds, int weekday, Set<Conflict> conflicts)`
- 作用：一天的站台占用检查。
  1. 先用确定的配对（玩家设置或运用中前后相连，`TerminalPairs`）生成原地折返的占用（`linkFixedTurnBacks`）。
  2. 对没有经由折返线接续到 `nextRouteId` 的路线，按最近原则配对原地折返（`linkTurnBacks`）。
  3. 其余每班每站（在相关站台、有时间、未被配对占用）生成一次占用 [到达, 出发]。
  4. 可在多个站台折返（站前折返）的占用按到达顺序依次选第一个空闲站台，都不空时留在第一个（从而冲突）。
  5. 对每个站台 `findConflicts`。

### `private static void linkTurnBacks(RouteTimes route, RouteTimes nextRoute, int weekday, Set<Long> platformIds, Map<Long, List<Occupation>> occupations, Set<ServiceStop> linkedStops, List<Occupation> choosingOccupations)`
- 作用：在到达站台原地折返的列车一直占用站台直到下一班出发，所以下一班能否插入取决于两者如何对齐，而不仅是频率。仅当本路线末站与下一路线首站是同一站台且属于检查范围时进行：按到达顺序，每班取下一路线中尚未被占用的最早一班（`findNextService`），并 `addTurnBackStay`。已确定配对的跳过。

### `private static void linkFixedTurnBacks(…同上参数，routeMap 代替 nextRoute…)`
- 作用：下一班已确定（`RouteTimes.fixedNext`）且从本班到达的站台出发的列车，留在该站台直到下一班出发，不论下一班属于哪条路线。

### `private static void addTurnBackStay(RouteTimes route, long platformId, ServiceTimes serviceTimes, RouteTimes nextRoute, ServiceTimes nextServiceTimes, Map<Long, List<Occupation>> occupations, Set<ServiceStop> linkedStops, List<Occupation> choosingOccupations)`
- 作用：生成占用 [本班末站到达, 等到下一班时刻 + 下一班首站停站]，包含两个 `ServiceStop`，并把两者记入 `linkedStops`。路线有站前折返站台时放入 `choosingOccupations`（候选站台：本站台 + `terminalPlatforms`），否则直接占用该站台。

### `private static void checkTurnBacks(long routeId, List<RouteTimes> routes, Map<Long, RouteTimes> routeMap, Set<UUID> turnBackRails, int weekday, Set<Conflict> conflicts)`
- 作用：折返线检查。列车停在折返线上直到该跑去下一班首站的时候，所以与站台折返一样看两条路线如何对齐；这些班次的站台照常计算。只有一条候选轨的折返直接按轨检查；有多条候选的按到达顺序取第一条空闲轨，全部被占才记冲突（站台号记 0）。

### `private static Occupation findOverlap(Occupation occupation, List<Occupation> otherOccupations)`
返回与之间隔不足 `MIN_PLATFORM_GAP_MILLIS` 的占用（考虑前后一天），没有为 null。

### `private static void addTurnBack(RouteTimes route, TurnBack turnBack, RouteTimes nextRoute, int weekday, List<Occupation> occupations, Set<ServiceStop> linkedStops)`
- 作用：对在 `turnBack.stopIndex` 有出发时间的各班（按到达折返线时刻排序）：到达 = `turnBack.getArrival`；最早可出发 = 到达 + 停留 + 驶出时间；找下一路线中最早未被占用的班次，若有则离开时间延至该班（加上等待），并把下一班首站记入 `linkedStops`。生成带候选轨的占用。

### `private static void checkHeadways(RouteTimes route, int weekday, Set<Conflict> conflicts)`（B，0.82.0）
- 作用：被检查线路的班次按首站发车排好，每对前后相邻的班次，在每一站比较实际间隔（后车发车 − 前车发车）和这一站到下一站所需的最短间隔（`RouteTimes.setHeadways`，宿主用 `transitsim.run.Headways` 按运行这两班的车型算出），不够的取差得最多的那一站记一条冲突（`headwayMillis`、`neededMillis`）。只提示，不改时刻（用户定）。只比同一线路的班次。`firstDeparture`：班次第一个有发车时刻的站。

### `private static void checkThroats(long routeId, List<RouteTimes> routes, int weekday, Set<Conflict> conflicts)`
- 作用：站场咽喉检查（L2）：两列车不能同时以不同进路使用同一道岔。每站的进站进路在到达前 `THROAT_MILLIS` 内占用，出站进路在出发后同样长时间占用；被检查路线的每个使用与同一道岔（或联锁区，区的 key 与节点分开）上其它列车的使用比较，进路冲突（`item.conflicts`）且时间重叠（考虑前后一天）时记冲突，`turnoutNode` 为 `item.getPlace()`。同一班次自身的进、出不算冲突。

### `private static String useKey(ThroatRoutes.Item item)`
`"zone:"` 或 `"node:"` 前缀 + 节点。

### `private static ServiceTimes findNextService(RouteTimes nextRoute, List<ServiceTimes> nextServices, long earliestMillis, ServiceStop stop, Set<ServiceStop> linkedStops)`
- 返回值：从 `earliestMillis` 起等待最短、不超过 `RouteTimetable.MAX_LAYOVER_MILLIS`、尚未被配对且不是自身的下一班；没有为 null。

### `private static void findConflicts(long routeId, long platformId, List<Occupation> occupations, Collection<Conflict> conflicts)`
- 作用：按开始时间排序，对每个占用向后（环绕到次日）检查与之间隔不足最小间隔的占用，逐对 `addConflict`；超过下一个不冲突的开始就停止。
- 副作用：对 `occupations` 排序。

### `private static void addConflict(long routeId, long platformId, Occupation occupation1, Occupation occupation2, Collection<Conflict> conflicts)`
两个占用不共享 `ServiceStop` 时，以属于被检查路线的那个为主生成 `Conflict`（另一方取其第一个停站的路线与班次）。

## 内部类 `RouteTimes`（public static）
一条路线的计划时间，复制出来以便离开服务器线程使用。
- `routeId`（final）。
- `nextRouteId`：同一列车接着跑的路线。由 `PacketTrainDataGuiServer.java:858` 设置。
- `terminalPlatforms`：末站所在车站中可折返的其它站台（站前折返），按顺序。
- `fixedNext`：班次 → 确定的下一 `{路线, 班次}`（见 `TerminalPairs.fixedNext`）；其它班次与 `nextRouteId` 中最近的配对。由 `PacketTrainDataGuiServer.java:859` 填写。
- 私有：`stops`、`platformIds`、`tables`（`TableTimes`）、`turnBacks`、`throats`（每站进出站经过的道岔，未知为空）。
- `RouteTimes(long routeId, List<? extends PlanStop> stops)`：复制停站与站台 ID。主要调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:850`。
- `RouteTimes(routeId, stops, services, marginPercent, times)`：只有一张每天运行的表（用于测试等，未确定）。
- `addTable(int table, int weekdays, List<Integer> services, int marginPercent, long[][] times)`：无例外。
- `addTable(…, Map<Integer, Map<Integer, StopException>> exceptions)`：用 `StopRules.plan` 计算本表的到达/出发；有例外的班次各自另算。`times` 为每站最快（到达, 出发），未找到为 -1。
- `addTable(…, exceptions, deployOrders, returnOrders)`：另外记录运行图上中途加入/离开的班次所用站区间（区间外的站视为未到达）。主要调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:856`。
- `private static long[][] getTimes(long[][] times, List<StopPlan> plans)`：取计划到达/出发，路径未到达的站为 -1。
- `addTurnBack(TurnBack)`：主要调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:862`。
- `setHeadways(Map<String, long[]> headways, Map<Integer, String> pools)`（0.82.0）：每对车型（前车池键>后车池键，"" 为默认车型；">" 为所有组合的最大值，车型未知时用）每一站到下一站所需的最短间隔，以及每个班次由哪个车池的车跑（来自运用）。私有 `getHeadways(前班, 后班)` 按两班的车池取，没有这一对时用 ">"。
- `setThroats(List<ThroatRoutes.StopThroat>)`：主要调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:861`。
- `private String getTableKey(int weekday)`：当天运行的表号组合。
- `private List<ServiceTimes> getServices(int weekday)`：当天各班的时间，每班取第一张含它的当天表（与列车实际所跑一致），有例外用例外时间，中途加入/离开的班次把区间外的站置 -1。

## 内部类 `TurnBack`（public static）
路线在某站之后使用的折返线及到达/离开所需时间。
- `routeId`、`rail`（折返轨 key）、`stopIndex`（折返前的本路线站序）、`nextRouteId`（下一路线，0 表示列车只是继续）、`runInMillis`（前站出发到折返线）、`dwellMillis`（在折返线停留）、`runOutMillis`（离开折返线到下一路线首站）。
- `candidates`（私有）：可用的折返轨（按尝试顺序），默认只有 `rail`。
- `setCandidates(List<UUID>)`：包含 `rail` 时采用，否则只保留 `rail`。主要调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:906`。
- `getCandidates()`：主要调用者：`PacketTrainDataGuiServer.java:760`、`:765`。
- `private long getArrival(ServiceTimes)`：一天中到达折返线的时刻 = 班次 + 该站出发 + `runInMillis`（取模一天）。
- `equals`/`hashCode`：按 routeId、rail、stopIndex、nextRouteId。
- 创建者：`transitsim/plan/StopTimes.java:567`。

## 内部类 `Conflict`（public static）
- `table`、`stopIndex`、`service`：被检查路线的表、站序、班次。
- `otherRouteId`、`otherService`：冲突的另一方。
- `platformId`：发生地站台；折返线或咽喉为 0。
- `turnoutNode`：两车以不同进路使用的咽喉道岔（或区），否则 0。
- `headwayMillis`、`neededMillis`（0.82.0）：班次比前一班跟得比列车能跑的还近时，两班从该站起的实际间隔和最少要的间隔，否则都为 0；`isHeadway()` 判断。九个参数的构造函数带这两项，其余构造函数为 0。`equals`/`hashCode` 含这两项。
- 两个构造函数（无 / 有 `turnoutNode`）；`equals`/`hashCode` 按全部字段。

## 内部类 `Result`（public static）
检查结果：`conflicts`；`uncheckedRoutes` 同站台上还没有时间、无法检查的路线数。创建者：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:383`。

## 私有内部类
- `TableTimes`：一张表的 `table`、`weekdays`、`services`、`arrivals`/`departures`（相对班次的毫秒）、`serviceTimes`（有例外班次的时间）、`servedStops`（中途加入/离开班次的首末站）；`runsOn(weekday)`。
- `ServiceTimes`：一班的 `table`、`service`、`arrivals`、`departures`；`getArrival(stopIndex)` 为一天中的到达时刻。
- `Occupation`：一次占用 `[start, end]`、涉及的 `stops`、多折返轨时的 `candidates`、多站台时的 `platforms`；`getStop(routeId)` 取属于该路线的停站。
- `ThroatUse`：一次咽喉使用 `[start, end]`、`item`、`stop`。
- `ServiceStop`：(路线, 表, 班次, 站序)，`equals`/`hashCode` 按四个字段。
