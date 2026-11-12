# PathFinder.java

MTR 3 原有的寻路（服务端，深度优先搜索），保留给现有车厂使用；新车厂使用 `DeterministicPathFinder`（有编译好的图时 `findPath` 直接委托过去）。调用者：`common/src/main/java/mtr/data/Depot.java:3052`、`:3062`、`:3072`、`:3073`、`:3254`、`:3278`；`PathFailure.diagnose`；测试 `common/src/test/java/mtr/path/PathFinderTest.java`。本分支改动：已访问"位置+方向"用集合记录，避免重复探索（原来每次线性扫描路径）；搜索每 1024 步检查线程中断并抛 `CancellationException`；折返线停留时间改为 `rail.getTurnBackDwellTime()`（原为 1）；飞机航线方向取最近象限（`RailAngle.closestQuadrant`）；新增带图与途经点的重载、`findSectionWithWaypoints`、`applyStopSettings`；`appendPath` 改为一次 `addAll`。

## 类 `PathFinder`

### 常量 `MAX_AIRPLANE_TURN_ARC = 128`
- 飞机转弯弧长上限（格）。

### `static int findPath(List<PathData> path, Map<BlockPos, Map<BlockPos, Rail>> rails, List<SavedRailBase> savedRailBases, int stopIndexOffset, int cruisingAltitude, boolean useFastSpeed)`
- 作用：逐对相邻站台/停车轨搜索并拼接；飞机模式先收集跑道端点（只连一条跑道轨道的节点）。某段失败时清空路径并返回 `i + 1`。
- 参数：`stopIndexOffset` 停靠序号偏移；`cruisingAltitude` 飞机巡航高度（y）；`useFastSpeed` 飞机虚拟航段是否用快速类型（`AIRPLANE_DUMMY`）而非 `RUNWAY`。
- 返回：到达的站台数（全部成功为总数；少于 2 个站台为 0）。

### `static int findPath(List<PathData> path, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, List<SavedRailBase> savedRailBases, List<List<BlockPos>> sectionWaypoints, int stopIndexOffset, int cruisingAltitude, boolean useFastSpeed)`
- 作用：有图则 `DeterministicPathFinder.findPath`；否则旧搜索不能走途经点——若有任何段带途经点，清空路径并返回该段 +1（不悄悄走另一条路）；没有途经点则用旧搜索。

### `static <T> int findSectionWithWaypoints(List<List<T>> sectionWaypoints, int savedRailCount)`
- 第一个带途经点的段序号，没有返回 −1。

### `static void applyStopSettings(List<PathData> path, List<Route.RoutePlatform> routePlatforms)`
- 作用：按线路各站的停站设置修改已找到路径上对应站台段的停站时间（`RoutePlatform.getStopDwell(可通过, 原停站时间, 是否连续运动)`）；首末站不能通过；按顺序向后匹配。

### `static void appendPath(List<PathData> path, List<PathData> partialPath)`
- 作用：子路径为空则清空总路径；否则若总路径最后一段与子路径第一段是同一轨道就去掉重复后一次性 `addAll`（让 `JoinedPath` 把子路径保持为一块）。

### `private static List<PathData> findPath(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<BlockPos> runways, SavedRailBase savedRailBaseStart, SavedRailBase savedRailBaseEnd, int stopIndex, int cruisingAltitude, boolean useFastSpeed)`
- 作用：深度优先：先按"更靠近终点站中点"的起点方向试一次，不行再反向试；每个节点的候选按轨道限速降序、同速按离终点距离升序排列；到达终点站台的某端时构造 `PathData` 列表：普通轨道第一段记起点站台 id；折返线上紧接着反向返回的段记折返停留时间；飞机在两跑道间没有实际轨道时生成虚拟航线（爬升到巡航高度、转弯、直飞、转弯、下降）；最后加终点站台本身（站台记停站时间，`stopIndex + 1`）。找不到返回空列表。
- 异常：线程被中断时抛 `CancellationException`。

### `private static void addPathPart(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<BlockPos> runways, BlockPos newPos, BlockPos lastPos, List<PathPart> path, Set<BlockPos> turnBacks, Map<BlockPos, Set<RailAngle>> visited, Function<...> comparator)`
- 作用：在 `newPos` 处展开候选：从折返线到达且该节点尚未折返过时，所有连接都可选（含掉头）；跑道末端时所有跑道端点都可选；否则排除禁用轨道、掉头方向，以及已访问过的"位置+方向"。有候选才压栈并标记访问。

### `private static BlockPos addAirplanePath(RailAngle startAngle, BlockPos startPos, RailAngle expectedAngle, int turnArc, List<PathData> tempRailPath, RailType railType, int stopIndex, boolean reverse)`
- 作用：从起始方向每次转一个象限（向左或向右，按角度差符号）直到朝向期望方向，每步前进 `turnArc` 生成一段虚拟轨道；`reverse` 时插到列表前部并反向（用于降落端）。
- 返回：转完后的位置。

## 私有内部类 `PathPart`
- `direction`（到达该节点时的方向）、`pos`、`otherOptions`（待尝试的下一个节点）。
