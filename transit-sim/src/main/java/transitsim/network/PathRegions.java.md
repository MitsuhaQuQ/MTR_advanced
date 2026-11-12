# PathRegions.java

寻路边界把轨道网络切成的区域（设计文档 5.13）。在某节点相接的轨道属于同一区域，除非该节点是边界；边界轨道不连接成自己的区域。边界属于它接触到的所有区域。没有边界时，区域就是网络的连通分量。

在整体中的位置：`RegionComputer.compute` 计算并放入 `RegionData`；`RailGraph` 持有它；`RailRouter`（`RailRouter.java:146`、`RailRouter.java:242`）用 `legRegions`/`allows`/`nodeRegions`/`railRegions` 限制搜索范围；`NetworkBuilder` 用 `regionsOf` 标注区段区域；`PlatformGraph` 使用；客户端 `DashboardScreen` 用 `shares`。

## 类 PathRegions（final）

### 字段
- `count`（int）：区域总数（含为孤立边界轨道新开的区域）。
- `uncutNodes`、`uncutSegments`：没有起到切分作用的边界节点/轨道（两侧仍由别的路连通），供界面提示。
- `missingNodes`、`missingSegments`：已不存在的节点/轨道上的边界。
- `connectionRegions`（private int[][]）：按 `RailGraph.Connection.index()` 索引的每个连接所属区域。
- `nodeRegions`（private Map<Long,int[]>）：节点 → 所属区域（边界节点可多个）。
- `NONE`：空数组常量。

### 私有构造函数 `PathRegions(int count, int[][] connectionRegions)`
调用者：`compute`（第 112 行）。

### `static <R> PathRegions compute(RailGraph<R> graph, Boundaries boundaries)`
- 作用：
  1. 每条轨道（两方向合一）编号为边，记录节点 → 关联边。
  2. 标记边界轨道；在非边界节点处把非边界的关联边用并查集合并。
  3. 按排序后连接的顺序为每个根编号（同一网络总得同样编号），边界边区域为 −1。
  4. 节点的"内部区域" = 其非边界关联边的区域集合。边界边的区域 = 两端节点内部区域的并集，若为空则新开一个区域。
  5. 记录每个连接、每个节点（所有关联边区域之并）的区域。
  6. 检查边界节点：不存在 → `missingNodes`；有 ≥ 2 条非边界关联边但内部区域只有 1 个 → `uncutNodes`。边界轨道：不存在 → `missingSegments`；两端区域有重叠 → `uncutSegments`。
- 参数：`graph` 轨道图；`boundaries` 边界。
- 返回：新的 `PathRegions`。副作用：无。
- 主要调用者：`RegionComputer`（经 `PathRegionSync.java:68`）、`RailNetworksTest.java:44`。

### `int[] regionsOf(RailGraph.Connection<?> connection)`
计算所用图中某连接的区域；下标越界（不同图）返回空。调用者：`NetworkBuilder.java:136`；本类 `allows`、`railRegions`。

### `int[] nodeRegions(long pos)`
节点所在区域（边界节点可多个），无轨道的节点为空。调用者：`RailRouter.java:242`、`PathRegionsTest.java:81`。

### `int[] railRegions(RailGraph<?> graph, long pos1, long pos2)`
两节点间轨道（任一方向的连接）的区域，没有该轨道为空。调用者：`RailRouter.java:242`、`RegionComputer.java:18`。

### `static BitSet legRegions(int[] regions1, int[] regions2)`
- 作用：两地之间搜索可走的区域：两者共有的区域；没有共同区域时取两者之并（路线仍可越过边界直接进入相邻区域）。任一为空时返回 null（不限）。
- 调用者：`RailRouter.java:146`。

### `boolean allows(RailGraph.Connection<?> connection, BitSet allowed)`
`allowed` 为 null 或连接任一区域在其中时为真。调用者：`RailRouter.java:152`。

### `static boolean shares(int[] regions1, int[] regions2)`
两区域数组是否有共同元素。调用者：`DashboardScreen.java:325`、`PathRegionsTest.java:83`。

### `private static BitSet toBitSet(int[] regions)`
数组转位集。调用者：`legRegions`。

### `private static int[] union(int[] regions1, int[] regions2)`
两区域数组的有序去重并集（一方为空时直接返回另一方，不拷贝）。调用者：`compute`。

### `private static int find(int[] parents, int index)` / `private static void union(int[] parents, int index1, int index2)`
数组并查集，路径压缩，较小根为根。调用者：`compute`。
