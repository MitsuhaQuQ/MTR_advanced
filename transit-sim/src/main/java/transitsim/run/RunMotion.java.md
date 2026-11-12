# RunMotion.java

运行层列车一个 tick 的移动方式（解耦文档 5.5）：沿运行段（`RunSegment`）开到"下一停站"与"行车许可终点"中较近者。与沿用自 MTR 的规则（`TrainMotion`）不同：下一停站每 tick 都按列车在路径上的位置重新求得，绝不沿用旧路径保留的值；被许可拦在停站前的列车在原地等待，不开门、不算到站，许可一延伸即继续前进；不会向前查看其他列车所在的轨道（由 `Authority` 负责）。

在停车线中的列车和回停车线的列车与以前相同。列车静止时保留最后的运行段，因此下一段版本号更高，客户端据此更新。

调用者：组件 `TickRunner.java:2070`（`tick`）、`:2026`/`:2028`（`findNextStop`/`findStopAfter`）、`:3135` 与游戏侧 `Train.java:1060`（`parkAtSiding`）。使用 motion 包的接口与 `RunSegment`、`PlatformDwell`。

## 类 RunMotion（final，不可实例化）

### 常量
- `AT = 1E-3`（private）：判断"在某位置"的容差（方块）。

### `static int findNextStop(MotionTrack track, double position)`
`moving = false` 的便捷形式。调用者：`isAtStop`、`tick`。

### `static int findNextStop(MotionTrack track, double position, boolean moving)`
- 作用：二分跳过终点在 `position − AT` 之前的轨道，然后找第一条有停站时间、且停车位置在当前位置之后（`moving` 时须严格在 `position + AT` 之后，避免把刚离开的站当成下一站；否则 ≥ `position − AT`）的轨道。
- 返回：停站轨道下标，没有则为最后一条轨道下标（路径终点）。
- 调用者：`TickRunner.java:2026`；本类 `tick`。

### `static int findStopAfter(MotionTrack track, int stop)`
`stop` 之后下一个有停站时间的轨道，没有则最后一条。调用者：`TickRunner.java:2028`。

### `static boolean isAtStop(MotionState state, MotionTrack track)`
列车速度 ≤ 0 且位于下一停站停车位置（±AT）。调用者：`PlatformDwellTest.java:17`。

### `static void parkAtSiding(MotionState state, MotionVehicle vehicle)`
- 作用：把列车放在停车线路径的居中停放位置：下一停站/站台 0，进度 `(停车线长 + 车长)/2`，不反向，速度 0，关门。
- 副作用：写 `state`。
- 调用者：`Train.java:1060`、`TickRunner.java:3135`；本类 `tick`。

### `static int tick(MotionState state, MotionTrack track, MotionVehicle vehicle, MotionHooks hooks, MotionCurves curves, double authorityEnd)`
- 作用：推进一个 tick（固定 1 tick）：
  1. 路径为空：报告不在站，返回 −1。
  2. 不在线路上：`parkAtSiding`；可出发（自动且 `canDeploy`，或手动且手柄 > 0）时 `startUp`，上线后设下一停站并发 `DEPARTED`；返回 0。
  3. 已到路径末尾且终点不是线上停站：下线、速度 0、清乘客、发 `RETURNED`、关门；返回 0。
  4. 计算下一停站：行驶中若原下一停站（尚未越过、有停站时间）比新求得的更近，则保留原值（第 4 轮，停车点在轨道末端之前时，刹车中的列车略短于停车点不应跳到再下一站）。
  5. **在站静止**：对齐到停车位置；首个停站 tick 发 `STOPPED` 并 `onStopReached`；"前方畅通" = 许可终点超过停车位置。停站计时在关门阶段前一直累加，之后只有未扣车且前方畅通才累加并给出 `departureTicks`；`openDoors()` 决定门；服务器端计时完成、畅通、未扣车时 `startUp`、重设下一停站并发 `DEPARTED`。
  6. **行驶或在许可终点等待**：目标 = min(停车位置, max(许可终点, 当前位置))。目标不超过当前位置时停住（恰在停车位置时对齐并清零停站计时）；否则 `follow` 前进（不超过目标），段结束时对齐目标并停住；停在停车位置则对齐并清零计时。由动转静但不在停车位置（被许可拦住）时手柄 −2、关门等待。
  7. 车门开度每 tick 变化 1/`DOOR_MOVE_TIME`；`PlatformDwell.report`。
- 参数：五个宿主接口；`authorityEnd` 列车沿路径可运行到的位置（来自 `Authority.update`）。
- 返回：总停站 tick（宿主用于车门），或 −1（无处可去）；在停车线/回库时返回 0。
- 副作用：写 `state`；调用 `hooks` 回调并发事件。
- 调用者：`TickRunner.java:2070`。

### `private static double follow(MotionState state, MotionCurves curves, MotionHooks hooks, double target)`
- 作用：沿运行段前往目标（不超过所在 run 终点）；目标、run 或巡航上限变化、或列车不在段所说位置时新开一段（起速 ≤ `ACCELERATION_DEFAULT` 视为 0），替换时发 `SEGMENT_REPLACED`，紧急段发 `EMERGENCY_BRAKE`。前进 1 tick，设速度与手柄。没有 run 时不动。
- 返回：前进距离。调用者：`tick`。

### `private static void emit(MotionHooks hooks, MotionEvent.Type type, int rail)`
直接转发 `hooks.emit`（与 `TrainMotion` 不同，不判断客户端）。调用者：`tick`、`follow`。
