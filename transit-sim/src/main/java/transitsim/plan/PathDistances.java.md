# PathDistances.java

路径的累计距离表，表现为只读 `List<Double>`：第 i 项是从路径起点到第 i 段轨道末端的累计长度。对 `IndexedPath`（由模板片段重复拼成的路径）做了优化：每个不同模板只算一次局部累计长度，再给每次出现记一个前缀偏移，避免长路径（多圈环线等）展开计算。由 `TripPath` 持有（`TripPath.java:47`），路径变化时 `reset`（`TripPath.java:969`）。

## 类 `PathDistances`（extends `AbstractList<Double>`, implements `RandomAccess`）

### 字段
- `indexed`：当前路径若为 `IndexedPath` 则指向它，否则 null。
- `flat`：非索引路径时的完整累计距离数组。
- `prefixes`：索引路径时，每个片段（segment）开始前的累计距离。
- `ends`：索引路径时，每个片段所用模板的局部累计距离数组（同一模板共享同一数组对象）。

### `public void reset(List<? extends PlanTrack> path)`
- 作用：根据新路径重建内部数组。普通列表：计算 `flat`；`IndexedPath`：对 `segments()` 逐个求模板局部累计（按模板对象缓存在 `HashMap` 中复用），并记录前缀。
- 参数：`path` 轨道序列，长度取 `PlanTrack.getTrackLength()`（单位由宿主约定，未确定）。
- 副作用：覆盖全部字段。
- 主要调用者：`transitsim/plan/TripPath.java:969`。

### `private static double[] cumulative(List<? extends PlanTrack> path)`
对给定列表求累计长度数组。

### `public int size()`
返回路径长度（轨道段数）。

### `public Double get(int rail)`
- 作用：返回到第 `rail` 段末端的累计距离。索引路径时用 `segmentAt(rail)` 找片段，再加该片段前缀。
- 参数：`rail` 轨道下标；越界时抛数组越界异常。
