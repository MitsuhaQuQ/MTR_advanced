# RailKeys.java

给两节点之间的轨道命名，从哪一端看都相同，因此轨道的两个方向共用一个键。在组件和游戏侧大量使用（`NetworkBuilder`、`PathRegions`、`Network`、`Boundaries`、`GraphRecords.java:550`、`PathBoundaries.java:25`、`RouteLayouts.java:77` 等）。

## 类 RailKeys（final，不可实例化）

### `static UUID of(long node1, long node2)`
- 作用：返回 `new UUID(min(node1,node2), max(node1,node2))`：高 64 位为较小节点 id，低 64 位为较大节点 id。
- 参数：两端节点 id（顺序无关）。返回：轨道键。副作用：无。
- 主要调用者：`NetworkBuilder.java:23`、`PathRegions.java:45`、`Boundaries.java:27`、`GraphRecords.java:550`。
