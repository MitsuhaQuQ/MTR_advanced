# TrainState.java

查询时刻一列车正在做什么（契约文档第 9 节）。不可变值对象，由 `EventSimulator.query`（`EventSimulator.java:163`）创建；游戏侧调度台 `DispatchConsole.java:121` 读取。

## 类 TrainState（final）

### 字段
- `trainId`（long）。
- `rail`（int）：车头所在的路径轨道下标。
- `position`（double）：车头沿路径的位置（方块）。
- `speed`（double）：速度（方块/tick）。
- `onRoute`（boolean）：是否在线路上。
- `stopped`（boolean）：是否静止（速度为 0）。
- `doorsOpen`（boolean）：车门目标是否为开。
- `heldBy`（String）：宿主扣车原因，或 null。
- `tripMillis`（long）：所运行服务的时间戳，或 0。
- `takenOver`（boolean）：是否已被宿主接管（此时状态为被接管时的样子，G0）。

### 构造函数（包内）
按字段赋值。调用者：`EventSimulator.java:163`。

### `boolean equals(Object)` / `int hashCode()`
equals 比较全部字段；hashCode 只用 trainId 与 position。

### `String toString()`
`"id rail R at P speed S[ in depot][ doors open][ held 原因][ taken over]"`。
