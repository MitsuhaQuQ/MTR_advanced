# StationCall.java

一列真实列车的"下一个载客停站"，在其进站和停站期间保持；或者（2026-11-12 起）它在途中不停站通过的某个站台（`isPassing`），保持到它通过为止。由 `TrainServer` 创建并更新（`TrainServer.java:783`、`:891`），被列车时刻传感器 `BlockTrainScheduleSensor` 查询以决定是否触发（例如报站、广播）。字段 `routeId`、`approachMillis`、`dwell` 为包内可见，由 `TrainServer` 直接写入。

## final class StationCall

### enum Trigger
触发时机：`LEGACY`（旧行为，本类判断永不到期）、`BEFORE_ARRIVAL`（到站前 N 秒）、`AFTER_ARRIVAL`（到站后 N 秒）、`BEFORE_DEPARTURE`（发车前 N 秒）。

#### `public static Trigger read(String value)`
- 作用：按枚举名解析，不匹配返回 `LEGACY`。
- 主要调用者：`BlockTrainScheduleSensor.java:82`、`BlockTrainScheduleSensor.java:98`、`TrainServer.java:227`。

#### `public boolean isDue(long now, int seconds, long approachMillis, PlatformDwell dwell)`
- 参数：`now` 当前毫秒；`seconds` 提前/延后秒数；`approachMillis` 预计到站时刻（毫秒）；`dwell` 当前停站信息，未到站时为 `null`。
- 返回：
  - `BEFORE_ARRIVAL`：未停站、预计到站在未来且距现在 ≤ seconds；
  - `AFTER_ARRIVAL`：已停站且到站后已过 ≥ seconds（`dwell.arrivedMillis`）；
  - `BEFORE_DEPARTURE`：已停站、发车时刻 `dwell.departureMillis` 在未来且距现在 ≤ seconds；
  - 其它 `false`。
- 主要调用者：本类 `trigger`。

### 字段
- `public final long platformId`：站台 id。
- `private final TrainServer train`：所属列车。
- `private final Set<BlockPos> triggered`：已触发过的传感器位置，同一传感器对同一停站只触发一次。
- `long routeId`：本次停站所属线路 id。
- `long approachMillis`：预计到站时刻（毫秒）。
- `PlatformDwell dwell`：停站信息，到站后由 `TrainServer` 设置。
- `private final boolean passing`：是否为通过（不停）。

### `StationCall(long platformId, TrainServer train)` / `StationCall(long platformId, TrainServer train, boolean passing)`
- 作用：新建；前者 `passing = false`。
- 主要调用者：`TrainServer.java:783`、`TrainServer.java:891`；测试 `PassingStationCallTest.java:19`。

### `public boolean isPassing()`
- 返回：列车是否不停站通过此站台；此时只有 `BEFORE_ARRIVAL` 可能到期（`dwell` 永不设置）。
- 主要调用者：`BlockTrainScheduleSensor.java:162`。

### `public long getRouteId()`
- 返回：`routeId`。
- 主要调用者：`BlockTrainScheduleSensor.java:162`。

### `public boolean trigger(BlockPos pos, Trigger trigger, int seconds, long now)`
- 作用：位于 `pos` 的传感器询问是否该触发。
- 返回：`trigger.isDue(...)` 为真且此 `pos` 未触发过时返回 `true`（同时记入 `triggered`）。
- 副作用：可能向 `triggered` 加入 `pos.immutable()`。
- 主要调用者：`BlockTrainScheduleSensor.java:162`；测试 `PassingStationCallTest.java:23`。

### `public void forEachRider(Consumer<Player> consumer)`
- 作用：对列车上的每个玩家调用（委托 `TrainServer.forEachRider`）。
- 主要调用者：未在仓库中找到外部直接调用，未确定（可能为传感器/广播用）。

### `public boolean isRider(Player player)`
- 返回：该玩家是否在此列车上（`TrainServer.isPlayerRiding`）。
- 主要调用者：未在仓库中找到直接调用，未确定。
