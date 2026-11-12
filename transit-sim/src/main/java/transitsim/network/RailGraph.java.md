# RailGraph.java

轨道连接的只读快照，与 Minecraft 无关，因此可以在其他线程上搜索。搜索状态是有向连接（同时确定节点和行驶方向）。泛型 `R` 为宿主轨道对象。

在整体中的位置：游戏侧 `DeterministicPathFinder.java:31`、`RailNetworks.java:53`、`SandboxRuns.java:127`、`PathRegionSync` 用 `Builder` 构建；`RailRouter`、`PathRegions`、`PlatformGraph`、`NetworkBuilder`、`TrackSections` 读取；`Depot` 等用 `find`。

## 类 RailGraph<R>（final）

### 字段
- `connections`（不可修改 List）：所有有向连接，已按 (fromX, fromZ, fromPos, toX, toZ, toPos) 排序，下标即 `Connection.index`。
- `outgoing`（private）：节点 → 从它出发的连接。
- `reverse`（private）：连接下标 → 反向连接（同两端反向）。
- `byNode`（private）：节点 → 到达它的连接。
- `components`（private）：节点 → 连通分量根（构造完成后每个节点直接指向根，之后不再写入，可多线程共享）。
- `maxChordPerCost`（包内 final double）：所有连接中"起点到终点直线距离 / 代价"的最大值，作为 A* 启发的速度上界。
- `regions`（private `PathRegions`）：搜索限定的区域，无寻路边界时为 null。

### 私有构造函数 `RailGraph(List<Connection<R>> sortedConnections)`
- 作用：设置下标，建立 outgoing/byNode/reverse 索引，求 `maxChordPerCost`，用并查集求连通分量（单向轨道也连通两端，所以只排除不可能存在的路线），最后把每个节点直接指向根。
- 调用者：`Builder.build`（第 191 行）。

### `private long findComponent(long pos)`
构造期间使用的并查集查找（带路径压缩，会写 `components`）。调用者：构造函数。

### `boolean isConnected(long pos1, long pos2)`
两节点都在图中且属于同一连通分量。调用者：`RailRouter.java:43`、`PathFailure.java:84`。

### `List<Connection<R>> outgoing(long pos)`
从节点出发的连接，无则空列表。调用者：`RailRouter.java:120`、`PlatformGraph.java:128`。

### `PathRegions getRegions()`
当前区域（可 null）。调用者：`RailRouter.java:140`、`RailRouter.java:146`。

### `void setRegions(Boundaries boundaries)`
- 作用：设定搜索区域：边界为 null 或空时为 null（区域只会重复连通分量），否则 `PathRegions.compute(this, boundaries)`。
- 副作用：改写 `regions`（注意：这是快照中唯一可变字段）。
- 调用者：`DeterministicPathFinder.java:45`、`PathRegionsTest.java:71`。

### `Connection<R> reverseOf(Connection<R> connection)`（包内可见）
反向连接或 null。调用者：`RailRouter.java:113`。

### `Connection<R> find(long fromPos, long toPos)`
线性查找 fromPos → toPos 的连接，无则 null。调用者：`Depot.java:1230`、`RouteLayouts.java:63`、`PathRegions.railRegions`。

### `boolean hasNode(long pos)`
节点是否作为任一连接的起点或终点出现。调用者：`RoutePaths.java:629`、`PathFailure.java:72`。

## 枚举 Kind
连接种类：`NORMAL`（普通）、`PLATFORM`（站台）、`TURN_BACK`（折返轨）、`DISABLED`（禁用，不可通行）。

## 内部类 Connection<R>（public static final）
一条有向轨道连接。
- `fromPos`、`toPos`（long）：起止节点。
- `rail`（R）：宿主轨道对象。
- `kind`（Kind）、`cost`（double，寻路代价）。
- `fromX`、`fromZ`、`toX`、`toY`、`toZ`（包内 int）：端点坐标（排序与启发用）。
- `startFacing`、`endFacing`（private int）：轨道离开起点时的朝向、离开终点（指回起点）时的朝向。
- `index`（private int）：在 `connections` 中的下标。

方法：
- 私有构造函数：按字段赋值。调用者：`Builder.add`。
- `int index()`：下标。调用者：`PathRegions.java:148`、`PlatformGraph.java:81`。
- `int getStartFacing()` / `int getEndFacing()`：朝向。调用者：`TrackSections.java:244`、`TrackSectionsTest.java:46`。
- `boolean continuesFrom(Connection<R> previous)`：本连接未禁用、且起点朝向 ≠ 上一连接的终点朝向（即不是从来路方向折回），列车可从 previous 接着驶入。调用者：`RailRouter.java:121`、`PlatformGraph.java:129`。
- `double chordTo(int x, int z)`（包内）：终点到 (x, z) 的直线距离。调用者：`RailRouter.java:74`、`RailRouter.java:250`。
- `private long key()`：反向表的键（= index）。

## 内部类 Builder<R>（public static final）
- `Builder<R> add(long fromPos, int fromX, int fromZ, int startFacing, long toPos, int toX, int toY, int toZ, int endFacing, Kind kind, double cost, R rail)`：添加一条连接，返回 this。调用者：`PathRegionSync.java:60`、`RouteLayouts.java:66`。
- `RailGraph<R> build()`：按坐标与节点排序后构建图（同一网络总得到同样顺序与下标）。调用者：`RailNetworks.java:53`、`SandboxRuns.java:127`、`DeterministicPathFinder.java:37`。
