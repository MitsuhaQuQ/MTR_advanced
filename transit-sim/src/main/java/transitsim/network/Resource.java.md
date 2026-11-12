# Resource.java

列车需要、但不能同时都拥有的东西（契约文档 4.1）。网络只说明有哪些资源、哪些区段使用它们；如何分配由仿真决定。由 `NetworkBuilder.add`（`NetworkBuilder.java:192`）创建，`Network` 建立区段 → 资源索引。

## 类 Resource（final）

### 字段
- `id`（long）：资源 id（由种类和所在区段/节点哈希得出，同网络稳定）。
- `kind`（`Kind`）：种类。
- `sections`（private long[]）：使用它的区段 id（按 id 顺序）。
- `capacity`（int）：泊位（BERTH）可容纳的列车数；其他种类为 1（由一列车或一个方向占用）。

### 构造函数 `Resource(long id, Kind kind, long[] sections, int capacity)`（包内可见）
按字段赋值，不拷贝数组。调用者：`NetworkBuilder.java:192`。

### `long[] getSections()`
返回区段数组拷贝。调用者：`Network.java:40`、`NetworkTest.java:118`。

## 枚举 Kind
- `TRACK`：一个区段，同一时间一列车。
- `TURNOUT`：轨道分叉节点处相接的各区段，同一时间只有一条经过它的进路可用。只计在节点处相连的轨道；无节点的平面交叉无法得知。
- `SINGLE_TRACK`：首尾相接、无处会车的双向区段，同一时间只能一个方向使用。
- `BERTH`：站台、停车线或折返轨，可容纳 `capacity` 列车。
