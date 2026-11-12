# TickCompensator.java

服务器掉帧补偿。原 MTR 每个服务器 tick 让列车走一步，服务器变慢时列车就落后于真实时间；本类把距上次 tick 的真实时间换算成"本次应走的 tick 数"，并把舍入误差（债务 debt）带到后面的 tick，避免正常抖动让列车变快。由 `RailwayData` 持有一个实例（`RailwayData.java:292`），每 tick 调用。组件侧 `TickRunner` 有自己的步进，不直接使用本类。

## class TickCompensator

### 字段与常量
- `lastMillis`：上次调用的时间戳（毫秒），0 表示尚未初始化。
- `debt`：累计未消化的 tick 数（浮点），限制在 `[MIN_DEBT, MAX_DEBT]`。
- `MAX_TICKS_ELAPSED = 4`（public）：单次最多返回 4 个 tick。
- `MAX_DEBT = 8`、`MIN_DEBT = -1`：债务上下限（防止长时间卡顿后列车猛冲、或超前过多）。

### `public float nextTick(long currentMillis)`
- 作用：计算本 tick 应推进的 tick 数。
- 参数：`currentMillis` 当前真实时间（毫秒，通常 `System.currentTimeMillis()`）。
- 返回：首次调用返回 1；之后 `debt += Δms / Depot.MILLIS_PER_TICK`（上限 8），返回 `clamp(debt, 1, 4)`，再从 debt 中扣掉返回值（下限 −1）。返回值至少为 1。
- 副作用：更新 `lastMillis` 与 `debt`。
- 主要调用者：`RailwayData.java:578`；测试 `BlockingTest.java:88`。
