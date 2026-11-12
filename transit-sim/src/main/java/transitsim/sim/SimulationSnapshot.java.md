# SimulationSnapshot.java

仿真从原处继续所需的东西（契约文档 6.4，S4d）：时刻、宿主输入（按顺序）以及宿主已读到的事件游标。相同的起点和输入每次得到相同的仿真，因此从头重放就能精确得到当时的状态。

这是"如同从未停止"地继续仿真。长时间停机后把列车放到时刻表位置并清空批准（6.3）属于宿主的恢复，随接管游戏列车一起实现（G0，见 `EventSimulator.resume`）。

由 `EventSimulator.snapshot`（`EventSimulator.java:193`）创建，`EventSimulator.restore` 使用。

## 类 SimulationSnapshot（final，不可变）

### 字段
- `time`（long）：快照时刻。
- `inputs`（不可修改 List<HostInput>）：已生效与待生效的全部输入，按顺序。
- `eventCursor`（long）：宿主已读到的事件游标（快照时取仿真当前游标）。

### 构造函数（包内）`SimulationSnapshot(long time, List<HostInput> inputs, long eventCursor)`
拷贝输入列表。调用者：`EventSimulator.java:193`。
