# Boundaries.java

寻路时轨道网络的切分处（设计文档 5.13）：在某个节点处切，或在两相连节点之间的轨道上切。路线搜索不跨越边界，边界之间剩下的部分即"区域"（region）。被 `NetworkBuilder`、`RegionComputer`、`RegionData` 使用；游戏侧 `mtr.advanced.PathBoundaries` 继承本类，`NodeAngleScreen` 读写它。

## 类 Boundaries（可继承）

### 字段
- `nodes`（`Set<Long>`）：作为边界的节点 id。
- `segments`（`Set<UUID>`）：作为边界的两节点间轨道（两个方向合一），键为 `RailKeys.of(node1, node2)`。

### `boolean isEmpty()`
两个集合都为空时为真。调用者：`NetworkBuilder.java:82`、`NetworkBuilder.java:129`、`NodeAngleScreen.java:209`。

### `boolean isSegment(long node1, long node2)`
两节点之间的轨道是否为边界（与方向无关）。调用者：`NodeAngleScreen.java:106`、`NodeAngleScreen.java:216`。

### `Boundaries copy()`
返回两个集合的浅拷贝（新 `Boundaries`，即便本对象是子类也只拷成基类）。调用者：`PathBoundaries.java:29`、`RegionComputer.java:22`。
