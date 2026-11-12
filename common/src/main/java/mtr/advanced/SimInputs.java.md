# SimInputs.java

组件模拟器不负责移动、但需要知道的列车（G0，契约文档 3.1 规则 4）：手动驾驶的列车，以及仍在旧规则下的车厂的列车。每个 tick 检查游戏中这些列车占用的轨道，只有在占用变化时（或列车消失时以空集合）才上报；同样对它们持有的"运行预留（runs）"做变化上报。G1 把这些输入送入正在运行的模拟器。由 `LiveSimulation` 持有并调用（`LiveSimulation.java:270`、`:331`）。产生的是组件 `HostInput.ExternalTrain` / `HostInput.ExternalRun`。

## final class SimInputs

### 字段
- `reported`：每列车上次上报的占用轨道集合（列车 id → 轨道 UUID 集合）。
- `reportedRuns`：每列持有运行预留的列车上次上报的 `RunReservations.Move` 列表。
- `sequence`：输入序号，自增；所有输入共享。

### `public void reset()`
- 作用：忘记已上报内容（给一个什么都不知道的新模拟器用）。不重置 `sequence`。
- 主要调用者：`LiveSimulation.java:331`；测试 `SimInputsTest.java:87`。

### `public long nextSequence()`
- 返回：下一个序号（`sequence++`），供宿主的其它输入使用，避免与这里的输入撞号。
- 主要调用者：`LiveSimulation.java:159`、`:172`、`:185`。

### `public List<HostInput.ExternalTrain> update(long time, Map<UUID, Long> occupancy, LongPredicate isExternal)`
- 参数：`time` 本 tick 时间；`occupancy` 本 tick 被占用的轨道 → 占用列车 id（来自游戏列车位置）；`isExternal` 判断模拟器是否不管这列车。
- 返回：占用自上次以来有变化的列车（按 id 升序），每列一个 `ExternalTrain(time, seq, trainId, rails)`；已消失的列车上报空集合。
- 副作用：更新 `reported`（空集合不存、消失的移除）；`sequence` 递增。
- 主要调用者：`LiveSimulation.java:270`；测试 `SimInputsTest.java:33`。

### `public List<HostInput.ExternalRun> updateRuns(long time, Map<Long, List<RunReservations.Move>> runs)`
- 参数：`runs` 模拟器不管的列车当前持有的运行预留（来自游戏的 `RunReservations`）。
- 返回：运行预留有变化的列车（按 id 升序），`ExternalRun(time, seq, trainId, moves)`；不再持有的列车上报空列表。
- 副作用：更新 `reportedRuns`、`sequence`。
- 主要调用者：测试 `SimInputsTest.java:84`（游戏代码中的调用未在脚本结果中出现，可能在 `LiveSimulation` 中经变量调用，未确定）。
