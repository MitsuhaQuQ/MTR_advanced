# PlatformDwell.java

列车在某站台的实际停站记录：到站时刻，以及发车被放行后把剩余停站时间换算成的预计发车时刻。不可变值对象 + 两个静态辅助函数。`TrainMotion`/`RunMotion` 每 tick 通过 `report` 把停站情况经 `MotionHooks.onDwell` 告诉宿主；游戏侧 `TrainServer` 用 `update` 维护每列车当前的 `PlatformDwell`（用于 PIDS 显示实际/预计时刻）。

## 类 PlatformDwell（final）

### 字段
- `platformId`（long）：站台 id。
- `arrivedMillis`（long）：到站时刻（毫秒，宿主时钟）。
- `departureMillis`（long）：预计发车时刻；扣车中或无法预测时为 0。

### 私有构造函数 `PlatformDwell(long, long, long)`
按字段赋值；仅 `update` 调用（第 24 行）。

### `static PlatformDwell update(PlatformDwell previous, long platformId, long now, float remainingTicks)`
- 作用：根据当前状态更新停站记录。`platformId == 0`（不在站台）返回 null；同一站台沿用原到站时刻，否则以 `now` 为到站时刻；`remainingTicks < 0` 时发车时刻为 0，否则为 `now + ⌈remainingTicks × 50⌉`。若与 `previous` 完全相同则返回 `previous` 本身（便于宿主判断是否变化）。
- 参数：`previous` 上次记录（可 null）；`platformId` 当前站台 id（0 = 无）；`now` 当前毫秒；`remainingTicks` 距发车剩余 tick，负数表示未确认。
- 返回：新的或原有记录，或 null。副作用：无。
- 主要调用者：`TrainServer.java:317`、`TrainServer.java:330`、`TrainServer.java:704`。

### `static void report(MotionState state, MotionTrack track, MotionHooks hooks, float departureTicks)`
- 作用：只在停车点（不是信号前的临时停车）报告停站。服务器端才执行：在站则 `hooks.onDwell(下一停站下标, 手动驾驶时 −1 否则 departureTicks)`，不在站则 `onDwell(−1, −1)`。
- 副作用：调用宿主回调。
- 主要调用者：`TrainMotion.java:22`、`TrainMotion.java:219`、`RunMotion.java:86`。

### `static boolean isAtStop(MotionState state, MotionTrack track)`
- 作用：判断列车是否正停在停站点：在线路上、速度为 0、下一停站下标有效、该轨道有停站时间，且车头与 `getStopDistance` 的差 ≤ 1E-3 方块。
- 返回：是否在站。副作用：无。
- 主要调用者：`TrainClient.java:261`、`RunMotion.java:62`；本类 `report`（第 30 行）。
