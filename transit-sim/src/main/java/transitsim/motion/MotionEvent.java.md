# MotionEvent.java

列车移动过程中发生的事件，宿主在 tick 末收集（解耦文档 3.7）。事件按 时间 → 类型 → 列车 → 该车内的发生顺序 排序（契约 2.1 规则 5），保证确定性。由 `MotionHooks.emit` 触发后由宿主补上时间与列车 id 构造：游戏侧 `Train.java:1576`，组件 `TickRunner.java:3064`；`EventSimulator`、`TickRunner` 对其排序。

## 类 MotionEvent（final，实现 `Comparable<MotionEvent>`）

### 枚举 Type（同一时刻按此声明顺序排序）
- `STOPPED`：列车在某轨道末端停下（到站或停在被占用轨道前）。
- `RETURNED`：列车在路径终点回到其停车线（siding）。
- `LAP`：循环运行的列车开始下一圈。
- `PASSED`：手动驾驶的列车通过停站点未停车。
- `SEGMENT_REPLACED`：列车开始了新的运行段（RunSegment），替换原来所在的段。
- `EMERGENCY_BRAKE`：列车用自身制动来不及停车（紧急制动）。
- `DEPARTED`：列车从停站点或停车线出发。

### 字段
- `millis`（long）：事件时刻（毫秒）。
- `type`（Type）：事件类型。
- `trainId`（long）：列车 id。
- `sequence`（long）：该列车事件的递增序号。
- `rail`（int）：事件发生处的轨道，按路径下标。
- `segmentVersion`（int）：当时所在运行段的版本号，没有为 0。

### 构造函数 `MotionEvent(long millis, Type type, long trainId, long sequence, int rail, int segmentVersion)`
- 作用：按字段赋值。调用者：`Train.java:1576`、`TickRunner.java:3064`、`MotionEventTest.java:61`。

### `int compareTo(MotionEvent other)`
- 作用：依次比较 `millis`、`type`（枚举序）、`trainId`、`sequence`。不比较 `rail`、`segmentVersion`。
- 主要调用者：排序时隐式调用，`EventSimulator.java:360`、`TickRunner.java:1209`。

### `String toString()`
- 作用：格式化为 "`TYPE train ID rail R at MILLIS (segment V, #SEQ)`"，用于日志与测试比对。
