# SimEvent.java

仿真中发生的一件事（契约文档 7.1 规则 2）。其 id 为（列车, 该车序号）；游标按顺序为仿真所有事件计数，宿主据此继续读取。由 `EventSimulator.addEvent`（`EventSimulator.java:393`）创建，经 `Simulator.events` 交给宿主（游戏侧 `LiveSimulation`、`DayRun`）。

## 类 SimEvent（final）

### 枚举 Kind（同一时刻按此顺序排序）
`ARRIVE`（到站）、`PASS`（通过不停）、`RETURNED`（回到停车线）、`DOORS_CLOSE`、`HELD`（被扣或被前方挡住）、`RELEASED`（解除扣车）、`CONFLICT`（如轨道被删除）、`DEADLOCK`、`SEGMENT_REPLACED`、`DOORS_OPEN`、`DEPART`（出发）、`RECOVERED`（长时间停机后列车被放到计划中的位置，契约文档 6.3；原因中逐个列出 `列车 轨道`）。

### 字段
- `cursor`（long）：全局顺序号。
- `time`（long）：发生时刻（毫秒）。
- `kind`（Kind）。
- `trainId`（long）。
- `sequence`（long）：该车事件序号。
- `rail`（int）：沿列车路径的轨道下标，或 −1。
- `reason`（String）：扣车原因或冲突内容，或空串。
- `predicted`（boolean）：发生在仿真分叉中，属于预测，宿主不据此采取任何行动（6.2 规则 3）。

### 构造函数（包内）`SimEvent(long cursor, long time, Kind kind, long trainId, long sequence, int rail, String reason, boolean predicted)`
按字段赋值。调用者：`EventSimulator.java:393`。

### `String toString()`
`"时刻 种类 列车#序号 轨道 [原因]"`。调用者：`LiveSimulation.java:694`、测试中比对。
