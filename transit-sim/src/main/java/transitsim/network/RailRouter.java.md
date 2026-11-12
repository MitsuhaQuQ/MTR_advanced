# RailRouter.java

对整条线路做确定性 A* 寻路（逐条轨道，而非 `LegRouter` 的区段级）。状态为"已完成目标数 + 当前连接"，因此列车到达某段的方向会延续到下一段。平局按排序后的连接顺序（下标）打破，保证确定性。

在整体中的位置：游戏侧 `DeterministicPathFinder.java:82` 调用 `route` 为线路/停车线寻路；`Target.rail/node` 被 `DeterministicPathFinder`、`LegalNetworks` 等构造；代价常量 `TURN_BACK_PENALTY`、`OTHER_PLATFORM_PENALTY` 被 `LegRouter`、`LineDepotWays` 共用。使用 `RailGraph`、`PathRegions`。

## 类 RailRouter（final，不可实例化）

### 常量
- `TURN_BACK_PENALTY = 600`：折返（同一轨道掉头）的额外代价。
- `OTHER_PLATFORM_PENALTY = 600`：经过非目标站台的额外代价。
- `INTERRUPT_CHECK_INTERVAL = 1024`：每扩展多少个状态检查一次线程中断。

### `static <R> Result<R> route(RailGraph<R> graph, List<Target> targets)`
不排除任何连接的便捷形式。调用者：`RailNetworksTest.java:40`。

### `static <R> Result<R> route(RailGraph<R> graph, List<Target> targets, Predicate<Connection<R>> avoid)`
- 作用：求依次经过所有目标的最低代价路径。
  1. 目标少于 2 个或首目标不是轨道时抛 `IllegalArgumentException`。
  2. 任一目标与首目标不连通时直接返回失败结果（`completedTargets` = 该目标下标）。
  3. 寻路边界只缩小搜索范围（用户决定）：先在每段两端的区域内搜索（`getLegRegions`）；成功、无边界、或搜索没有因区域被剪枝时直接返回；否则再在全网搜索，成功则标记 `crossedRegions = true`；都失败时返回完成目标更多的那个。边界从不阻止有路可走的线路——让列车远离某些轨道是联络线的职责。
- 参数：`graph` 轨道图；`targets` 目标序列（首个必须是保存轨道）；`avoid` 线路不可使用的连接（如其他线路要避开的联络线轨道），可 null；目标本身总是允许。
- 返回：`Result`。副作用：线程被中断时抛 `CancellationException`。
- 主要调用者：`DeterministicPathFinder.java:82`、`RailNetworksTest.java:161`。

### `private static <R> Result<R> search(RailGraph<R> graph, List<Target> targets, Predicate<Connection<R>> avoid, BitSet[] legs)`
调用 `searchPruned`，若期间有连接因区域被剪掉，则在结果上置 `prunedByRegions`。调用者：`route`（第 52、58 行）。

### `private static <R> Result<R> searchPruned(RailGraph<R> graph, List<Target> targets, Predicate<Connection<R>> avoid, BitSet[] legs, boolean[] pruned)`
- 作用：A* 主循环。启发 = 当前连接到下一目标的直线距离 / `maxChordPerCost` + 之后各目标间直线距离之和（避免长线路前段的搜索扩散到全网）。起点为首目标轨道的两个方向（都存在才搜索，否则失败）。每次出队：跳过过期状态；每 1024 次检查中断；全部完成即 `unwind` 返回。刚完成一个轨道目标的状态可以立即在该轨道掉头（+`TURN_BACK_PENALTY`）。否则沿出发连接扩展：当前为折返轨时任意方向均可，否则须 `continuesFrom`；直接掉头加 `TURN_BACK_PENALTY`；进入非目标站台加 `OTHER_PLATFORM_PENALTY`。
- 返回：成功结果或空步骤结果（`completedTargets` = 最多完成数）。
- 副作用：可能设置 `pruned[0]`；中断时抛 `CancellationException`。调用者：`search`。

### `private static <R> BitSet[] getLegRegions(RailGraph<R> graph, List<Target> targets)`
有寻路边界时，为前往每个目标 i 的那一段给出允许区域 `PathRegions.legRegions(目标 i−1 区域, 目标 i 区域)`；无边界返回 null。调用者：`route`（第 51 行）。

### `private static <R> void push(... State<R> parent, Connection<R> connection, int completedBefore, double cost, boolean reversal, Predicate<Connection<R>> avoid, boolean[] pruned)`
- 作用：尝试加入新状态：连接不在当前段允许区域内则记 `pruned` 并放弃；在 `avoid` 中且不是任何目标则放弃；非折返时推进完成的目标数（记录刚完成的是否为轨道目标及其下标）；按键 `(completed<<32 | index<<1 | reversal)` 比较，已有更低代价则放弃，否则旧状态置 stale、入队新状态。
- 调用者：`searchPruned`（第 92、93、115、125 行）。

### `private static <R> List<Step<R>> unwind(State<R> last)`
从终态回溯为步骤列表（起点在前）。调用者：`searchPruned`（第 108 行）。

## 内部类 Target（public static final）
目标：保存轨道（站台/停车线）须沿其运行（任一方向）；节点须经过。
- 字段：`pos1`、`pos2`（节点；节点目标两者相同）、`x1,z1,x2,z2`（坐标）、`rail`（是否轨道目标）。
- `static Target rail(long pos1, int x1, int z1, long pos2, int x2, int z2)`：轨道目标。调用者：`DeterministicPathFinder.java:72`、`LegalNetworks.java:235`。
- `static Target node(long pos, int x, int z)`：节点目标。调用者：`DeterministicPathFinder.java:76`。
- `boolean isRail()`、`long getPos1()`、`long getPos2()`：访问器。调用者：`RouteLegsTest.java:85`、`RouteLegsTest.java:90`。
- `private int[] getRegions(RailGraph<?> graph, PathRegions regions)`：轨道目标取轨道区域，节点目标取节点区域。调用者：`getLegRegions`。
- `private boolean matches(Connection<?> connection)`：轨道目标：连接为该轨道任一方向；节点目标：连接终点为该节点。
- `private double chordFrom(Connection<?> connection)`：连接终点到目标两端的较近直线距离。
- `private double chordTo(Target next)`：本目标两端与下一目标两端间的最短直线距离（列车运行距离不可能更短，启发不高估）。

## 内部类 Step<R>（public static final）
路径一步：`connection`、`completedTarget`（此步完成的轨道目标下标，否则 −1）、`reversal`（是否由原地折返进入）、`cost`（从起点到此步含此步的累计代价）。私有构造，由 `unwind` 创建。

## 内部类 Result<R>（public static final）
- `steps`（不可修改 List）：路径步骤，失败为空。
- `completedTargets`（int）：完成的目标数（失败时为最多到达数，或不连通目标的下标）。
- `crossedRegions`（boolean）：只有越出寻路边界区域才找到。
- `prunedByRegions`（private boolean）：搜索中有路因区域被剪掉。
- 三个私有构造函数逐级补默认值。
- `boolean isSuccess()`：步骤非空。调用者：`DeterministicPathFinder.java:83`。

## 内部类 State<R>（private static final）
搜索状态：`parent`、`connection`、`completed`、`cost`、`estimate`、`justCompletedRail`（刚完成一个轨道目标，可立即掉头）、`completedTarget`、`reversal`、`stale`。
