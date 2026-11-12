# Simulator.java

整个网络的一次仿真（契约文档第 9 节，S4c）。真实运行只有一个；预测在其分叉中运行（S4d）。时间只向前走，宿主输入与输出事件每次按相同方式排序，因此相同起点和相同输入不论宿主如何分步，结果都相同（2.1）。唯一实现：`EventSimulator`。

## 接口 Simulator
- `long getTime()`：当前仿真时刻，只增不减（2.1 规则 1）。
- `void submit(HostInput input)`：提交宿主输入，在其时刻按（时刻, 序号）顺序生效（2.1 规则 5）。
- `long advanceTo(long time)`：运行到该时刻；更早的时刻什么也不做。返回当前仿真时刻。
- `TrainState query(long trainId)`：列车当前状态，不改变任何东西也不推进（7.1 规则 1）；没有该列车或它不由仿真移动时为 null。
- `RunSegment getSegment(long trainId)`：列车所在运行段（由它可推出段内任意时刻的位置），或 null。
- `List<SimEvent> events(long afterCursor)`：游标之后、到当前时刻为止的事件，按顺序（7.1 规则 3）；`afterCursor` 为宿主已处理的最后事件游标，0 表示无。
- `Simulator fork()`：从当前状态分出一个独立仿真用于预测（6.2）：不共享任何会变的东西，事件标记为预测，可给它本仿真永远不会收到的输入。
- `SimulationSnapshot snapshot()`：以后从此刻继续所需的东西，如服务器停止后（6.4）。
