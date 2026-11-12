# LegRouter.java

寻找一条 leg（优化阶段文档第 2 轮）：线路一个停站到下一个停站、途经其间列出的关键节点的路径，在轨道区段（`TrackSections.Section`）上搜索而非逐条轨道。保持 `RailRouter` 的规则：列车经过节点时只能进入与来向不同方向离开的区段（折返轨末端可任意方向）；停站后可立即折返但有代价；经过其他站台有代价；区段代价 = 各轨道长度 / 限速之和。线路只能在其所属线路拥有的区段上运行（`TrackOwnership`）。

一条 leg 从首个停站按给定方向出发，到末停站两个方向都求，所以整条线路是每个停站处方向的最优组合（`RouteLegs`）。搜索记录触及的所有区段和继续出发过的所有节点；结果只依赖这些，因此别处的变化不会让它出错，这些地方的变化会让它重算（`LegCache`）。纯函数，基于快照，可在任意线程运行。

在整体中的位置：游戏侧 `GraphRecords`（`GraphRecords.java:276`）调用 `find` 并用 `LegCache` 缓存、用 `toBytes/fromBytes` 存档；组件 `LineDepotWays` 构造 `Request` 求出入库路径；`RoutePaths`、`RouteLegs` 使用 `Leg.getCost/getSteps`。

## 类 LegRouter（final，不可实例化）

### 常量
- `VERSION = 3`：规则版本；规则变化时改变，使旧规则算出的 leg 被重算（参与请求键哈希和存档头）。

### 内部接口 Coordinates
节点坐标，用于估计剩余代价（A* 启发）。`int getX(long node)`、`int getZ(long node)`。实现：`GraphRecords.java:97`。

### `static Leg find(TrackSections.Snapshot graph, long version, Map<Long, Set<String>> owners, Request request, Coordinates coordinates, double maxChordPerCost)`
- 作用：A* 搜索一条 leg。
  1. 起/终区段不存在：返回无路可走的 leg。
  2. 每个关键节点必须是区段交汇处（`getSectionsAt` 非空），否则返回 `badWaypoint = 该节点` 的 leg。
  3. 起终相同且无关键节点：代价 0、步骤为空，方向同起始方向。
  4. 起点按 `fromAlong` 方向入队；`turnAtStart` 时还以"区段代价 + `TURN_BACK_PENALTY`"入队反向（折返）状态。
  5. 需要找的方向数：终点为停车线（SIDING）只找 1 个；起终同区段找 2 个；否则为终点区段两端中可进入的端数（`canEnter`，至少 1）——尽头式终点只能从一端进入，避免搜遍全网（第 4 轮优化）。
  6. 找到一个方向后，若允许起点折返，则把上界设为 `该代价 + 终点区段代价 + TURN_BACK_PENALTY`，估计超过上界即停止（另一方向更贵时永远不会被采用）。
- 参数：`graph` 区段快照；`version` 快照版本；`owners` 区段 → 拥有线路集合；`request` 请求；`coordinates` 坐标；`maxChordPerCost` 单位代价可覆盖的最大直线距离（见 `getMaxChordPerCost`），保证启发不高估。
- 返回：`Leg`。副作用：无。
- 主要调用者：`GraphRecords.java:276`、`GraphRecords.java:289`、`RoutePaths.java:155`。

### `private static boolean canEnter(TrackSections.Snapshot graph, TrackSections.Section section, long node)`
在该端节点处是否还有别的区段相接（即列车能从这一端进入）。调用者：`find`（第 279 行）。

### `static double getMaxChordPerCost(TrackSections.Snapshot graph, Coordinates coordinates)`
- 作用：遍历所有区段的所有轨道，求 `两端直线距离 / 轨道代价` 的最大值，作为启发函数的速度上界。
- 调用者：`GraphRecords.java:177`、`MidLineDepotTest.java:126`。

### `private static List<long[]> unwind(State last)`
从终态回溯为步骤列表 `{区段, 是否顺序方向, 是否折返进入}`；起点按原方向那一步不算步骤，起点立即折返那一步算。返回不可修改列表。调用者：`find`（第 299 行）。

### `private static void writeLongs(DataOutputStream, long[])` / `private static long[] readLongs(DataInputStream)`
写/读 int 长度 + long 数组；读时长度 < 0 或 > 2²² 抛 `IOException("Bad count …")`。调用者：`Leg.toBytes`、`Leg.fromBytes`。

### `private static long[] toArray(Set<Long> values)`
集合转数组（TreeSet 时有序）。调用者：`find`。

### `private static long mix(long value1, long value2)`
SplitMix64 风格的哈希混合，用于请求键。调用者：`Request` 构造函数。

## 内部类 Request（public static final）
求 leg 的输入。
- `fromSection`（long）：首停站所在区段哈希。
- `fromAlong`（boolean）：首停站沿区段顺序方向运行。
- `toSection`（long）：末停站区段。
- `waypoints`（long[]）：途经关键节点（按序）。
- `line`（String）：线路名；null 表示联络线，可在任意轨道运行。
- `turnAtStart`（boolean）：首停站可否立即折返（有代价）；出入库路径不允许，按线路方向运行（第 4 轮第 3 步）。
- `avoid`（long[]）：不得以某方向运行的区段，成对 `{区段, 1=顺序方向}`；用于出入库路径避开逆向经过本线停站（0.65.26）。首末停站永不排除。
- `key`（long）：请求的哈希键（含 `VERSION`）；`turnAtStart` 为真且 `avoid` 为空时与旧版请求键相同。

构造函数：
- `Request(fromSection, fromAlong, toSection, waypoints, line)`：`turnAtStart = true`。
- `Request(…, line, turnAtStart)`：`avoid` 为空。
- `Request(…, line, turnAtStart, avoid)`：拷贝数组、计算 key；`avoid` 长度为奇数抛 `IllegalArgumentException`。
- 调用者：`LineDepotWays.java:46`、`LineDepotWays.java:68`、`LineDepotWays.java:143`；`Leg.fromBytes`（第 214 行）。

## 内部类 Leg（public static final）
找到的 leg，末停站按区段顺序方向和逆向各一份结果。
- `request`：对应请求。
- `version`（long）：所基于的网络版本。
- `costs`（private double[2]）：下标 0 = 逆向到达末停站的代价，1 = 顺向；无路为正无穷。
- `steps`（private List<long[]>[2]）：对应步骤，无路为 null。
- `domainSections`、`domainNodes`（long[]）：搜索触及的所有区段（有序）与继续出发过的节点。
- `badWaypoint`（long）：不在区段交汇处、无法经过的关键节点，否则 0。

### 私有构造函数
按字段赋值。调用者：`find`、`fromBytes`。

### `byte[] toBytes()`
- 作用：按存档格式序列化：`VERSION`、请求字段、两方向的代价与步骤（每步：区段 long + 标志字节 bit0 顺向 bit1 折返；无路时数量 −1）、domain、`badWaypoint`，最后（仅非空时）`avoid`——放在最后使旧存档读法不变。
- 副作用：`IOException` 包成 `UncheckedIOException` 抛出（实际不会发生）。
- 调用者：`GraphRecords.java:365`、`GraphRecords.java:405`。

### `static Leg fromBytes(byte[] data, long version) throws IOException`
- 作用：读回 `toBytes` 的结果，版本号不等于 `VERSION` 返回 null（旧规则算出，需重算）。步骤数 > 2²⁰ 抛 `IOException("Bad step count")`；还有剩余数据时读 `avoid`，长度为奇数抛 `IOException`。
- 参数：`version` 收入时的网络版本（覆盖存档内容）。
- 调用者：`GraphRecords.java:188`、`GraphRecords.java:508`。

### `double getCost(boolean toAlong)`
到末停站（`toAlong` 方向）的代价，无路为无穷。调用者：`RoutePaths.java:1019`、`LineDepotWays.java:324`。

### `List<long[]> getSteps(boolean toAlong)`
首停站之后运行的每个区段 `{区段, 1=顺向, 1=折返进入}`，最后一项为末停站；无路为 null。调用者：`RoutePaths.java:1022`、`LineDepotWays.java:114`。

## 内部类 State（private static final）
搜索状态：`parent`、`section`、`along`（运行方向）、`stage`（已达成的目标数：关键节点…末停站）、`cost`、`estimate`（cost + 剩余估计）、`reversal`（是否由折返进入）、`stale`（被更优状态取代，出队时跳过）。
- `private long exit()`：离开区段的节点（顺向为终点，逆向为起点）。调用者：`Search.expand`。

## 内部类 Search（private static final）
一次 A* 搜索的上下文。
- 字段：`graph`、`owners`、`request`、`coordinates`、`speed`（= maxChordPerCost）、`domainSections`（共享集合，边搜边记录）、`targetCount`（关键节点数 + 1）、`remainingAfter[i]`（第 i 个目标之后到最终目标的直线距离下界 / speed）、`best`（键 "stage/区段/方向/折返" → 最优状态）、`queue`（按 estimate 升序，平局按 stage 降序、区段哈希、方向、折返排序，保证确定性）。
- 构造函数：计算 `remainingAfter`。调用者：`find`（第 264 行）。
- `private void expand(State state, Set<Long> domainNodes)`：从离开节点出发，枚举在该节点开始（按方向）的每个区段；非折返轨时不得进入与到达方向同侧"朝向"的区段（`getEndFacing`）；同区段反向算折返，加 `TURN_BACK_PENALTY`；经过非目标的站台/停车线加 `OTHER_PLATFORM_PENALTY`；然后 `push`。副作用：记录节点和区段到 domain。调用者：`find`（第 307 行）。
- `private boolean avoids(long section, boolean along)`：该区段该方向是否在 `request.avoid` 中。调用者：`push`。（注：其 javadoc 的 `@param start` 与参数不符，为遗留注释。）
- `private void push(State parent, Section section, boolean along, int stageBefore, double cost, boolean reversal, boolean start)`：记录区段到 domain；非起点时检查区段能否按该方向运行（`canRun`）、非停站区段须线路可运行（`TrackOwnership.mayRun`）且不在 avoid 中；非折返非起点时推进已匹配的目标数；若已有同键更低代价状态则放弃，否则标记旧状态 stale，按直线距离启发计算 estimate 入队。调用者：`find`（第 266、268 行）、`expand`（第 474 行）。
- `private boolean matches(int stage, Section section, boolean along)`：第 stage 个目标若为关键节点，要求离开节点等于它；若为末停站，只要区段相同（两方向均可）。
- `private boolean isNextTarget(int stage, Section section, boolean along)`：stage 未完成且 matches。调用者：`expand`（判断是否为"其他站台"）。
- `private double chordFrom(long node, int stage)`：节点到第 stage 个目标的直线距离（末停站取两端较近者）。
- `private double chordBetween(int stage1, int stage2)`：两目标端点间最短直线距离。
- `private long[] endsOf(int stage)`：目标的端点（关键节点本身或末停站区段两端）。
- `private double distance(long node1, long node2)`：两节点 XZ 平面距离。
