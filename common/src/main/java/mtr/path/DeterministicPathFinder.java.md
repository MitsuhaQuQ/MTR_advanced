# DeterministicPathFinder.java（本分支新增）

把仿真组件的确定性寻路器 `transitsim.network.RailRouter` 接到游戏的轨道数据上（服务端）。图（`RailGraph`）必须在服务器线程上编译；之后搜索可以在寻路线程上运行，不再访问 `rails`。新车厂用它寻路，旧车厂仍可用原版 `PathFinder`（`PathFinder.findPath` 内部会委托到这里，`common/src/main/java/mtr/path/PathFinder.java:52`）。调用者：`common/src/main/java/mtr/data/Depot.java:1657`（编译）、`common/src/main/java/mtr/advanced/PathRegionSync.java:55`、`common/src/main/java/mtr/data/RailwayData.java:1274`（`findNodes`）。

## 类 `DeterministicPathFinder`（final，私有构造）

### `static RailGraph<Rail> compile(Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：每条有向轨道加入图：起点（`asLong`、x、z、起点朝向 ×10 取整，即 0.1° 精度）、终点（`asLong`、x、y、z、终点朝向 ×10）、种类（`getKind`）、代价 = 长度 / 该轨道类型的 `maxBlocksPerTick`（即按限速的行驶 tick 数）、附带 `Rail` 对象。
- 返回：构建好的图。

### `static RailGraph<Rail> compile(Map<BlockPos, Map<BlockPos, Rail>> rails, PathBoundaries boundaries)`
- 同上，并按寻路边界设置区域（`graph.setRegions`），只用于缩小搜索范围。

### `static int findPath(List<PathData> path, RailGraph<Rail> graph, List<SavedRailBase> savedRailBases, List<List<BlockPos>> sectionWaypoints, int stopIndexOffset)`
- 不排除任何轨道的重载。

### `static int findPath(List<PathData> path, RailGraph<Rail> graph, List<SavedRailBase> savedRailBases, List<List<BlockPos>> sectionWaypoints, int stopIndexOffset, Predicate<Connection<Rail>> avoid)`
- 作用：清空 `path`；站台/停车轨少于 2 个返回 0。依次把每个站台/停车轨作为"轨道目标"（其两端节点），每段之后的途经点作为"节点目标"，调 `RailRouter.route(graph, targets, avoid)`。失败时返回已到达的站台数（与原版 `PathFinder.findPath` 相同的含义：没有完成任何目标返回 1）。成功时按步骤生成 `PathData`：第一步属于第一个站台；完成某站台目标的那一步记该站台 id 和停站时间（站台为 `getDwellTime()`，停车轨 0）；折返（`reversal`）步记当前站台 id、停站 0；折返线上紧接着反向返回的那一步记折返停留时间（`rail.getTurnBackDwellTime()`）；其余为 0。`stopIndex` 为 `stopIndexOffset + 已到达站台序号`。
- 参数：`sectionWaypoints[i]` 为第 i 个站台之后到下一站台之间必须经过的节点（可为 null）；`avoid` 为不许使用的连接（例如联络线自身的轨道），可为 null。
- 返回：到达的站台/停车轨数（成功为全部数量）。

### `static long[] findNodes(RailGraph<Rail> graph, List<SavedRailBase> savedRailBases, List<List<BlockPos>> sectionWaypoints)`
- 作用：求出路径并返回依次经过的节点（`BlockPos.asLong`），包括最后一个终点；失败时（路径为空）返回空数组。调用者：`common/src/main/java/mtr/data/RailwayData.java:1274`。

### `private static Kind getKind(RailType railType)`
- `NONE` → `DISABLED`（反向记录，不可行驶）；`TURN_BACK` → `TURN_BACK`；有站台/停车轨的类型 → `PLATFORM`；其他 `NORMAL`。
