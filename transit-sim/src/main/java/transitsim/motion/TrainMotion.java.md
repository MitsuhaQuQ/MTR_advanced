# TrainMotion.java

列车移动规则（解耦文档 2.2），在 S2 中从宿主列车类中抽出。最初与 `LegacyTrainMotion` 完全相同（后者现只保留在测试中，R4），对比测试守住应保持不变的部分。

变化点：服务器上，按速度曲线运行的自动列车沿 `RunSegment` 运行，位置由出发以来的时间算出，而不是逐 tick 累加（S2c）；客户端仍在服务器发来的位置之间逐 tick 跟随曲线；服务器上规则通过事件告知宿主发生了什么（S2d）。

在整体中的位置：游戏侧 `Train.java:1073` 每 tick 调用 `tick`；组件 `TickRunner`（无头列车 `HeadlessTrain`）和 `RunMotion` 也调用 `tick`。通过 `MotionState`/`MotionTrack`/`MotionVehicle`/`MotionHooks`/`MotionCurves` 五个接口访问宿主；使用 `RunSegment`、`SpeedCurves`、`SpeedProfile`、`PlatformDwell`、`MotionConstants`。

## 类 TrainMotion（final，不可实例化）

### 常量
- `SECTION_STANDING_MARGIN = 1`（方块）：停着的列车前方多近处的闭塞分区被占用时不能起步。

### `static int tick(MotionState state, MotionTrack track, MotionVehicle vehicle, MotionHooks hooks, MotionCurves curves, float ticksElapsed)`
- 作用：推进一列车一个（或若干）tick。主要分支：
  1. 下一停站下标 ≥ 轨道数：报告不在站，返回 −1（无处可去）。
  2. **不在线路上（在停车线）**：车头放在停车线中央 `(railLength + 车长)/2`，取消反向、关门、速度 0、下一停站 0；自动列车 `canDeploy()` 或手动且手柄 > 0 时 `startUp`，成功上线则发 `DEPARTED`。
  3. **在线路上且已到路径末尾**（车头 ≥ 最后距离 − (停车线长 − 车长)/2）且终点不是线上停站：下线（onRoute=false，手柄 −2）、清乘客、发 `RETURNED`。
  4. **停着（速度 ≤ 0）**：判断下一轨道（折返时为 +2）是否被占用（`isTakenAtStart`），或车头所在轨道前方紧邻分区被占用（`isSectionTakenJustAhead`）。有停站时间时：循环线到达一圈末尾时把位置跳回圈起点（折返圈还反转方向），`onLapComplete` 并发 `LAP`；首个停站 tick 调 `onStopReached`；停站计时在关门前一直累加，进入关门阶段后只有未扣车且前方未占用才继续累加并给出 `departureTicks`；`openDoors()` 决定门。服务器上停站结束（或手动驾驶）且前方未占用（手动时还需手柄 > 0）则 `startUp` 并发 `DEPARTED`。
  5. **行驶中**：服务器上按制动距离（`0.5v²/减速度 + v·t + BRAKING_MARGIN`）向前找被占用轨道/分区，相应把 `nextStoppingIndex` 移到被占轨道前、或记录分区内停车位置 `sectionStop`；没有阻挡时恢复为下一站台（并把 −3 手柄归 0）。然后：遵循速度曲线时客户端用 `followSpeedCurve`、服务器用 `followRunSegment`；非连续运动、来不及停且必须停时按需减速（手柄 −3）；手动驾驶按手柄加减速；否则向轨道限速（`getRailSpeed`）加/减速。
  6. 无折返循环线越过圈终点时整体平移进度回圈起点，发 `LAP`，`continueWithoutStopping`。
  7. 更新进度；段结束时精确对齐到段 `target`；越过 `sectionStop` 时停在那里；越过停站点：必须停则停在停车位置，否则发 `PASSED` 并继续；由动变静发 `STOPPED`。车门开度按 `DOOR_MOVE_TIME` 线性变化。
  8. 写回车门目标与开度；门开着或未完全关上时手柄置 −2；`PlatformDwell.report`。
- 参数：五个宿主接口；`ticksElapsed` 本次经过的 tick 数（可为小数）。
- 返回：本 tick 开始时得到的总停站 tick（宿主用来驱动车厢门），或 −1。
- 副作用：大量写 `state`（速度、进度、门、手柄、下一停站、onRoute、reversed、runSegment、停站计时）；调用 `hooks` 的 startUp、clearRiders、onLapComplete、onStopReached、onDwell、emit。
- 主要调用者：`Train.java:1073`、`RunMotion.java:84`、`TickRunner.java:104`。

### `private static double followRunSegment(MotionState state, MotionCurves curves, MotionHooks hooks, float ticksElapsed, double stoppingPosition)`
- 作用：沿运行段移动（S2c）。取当前位置的 run（没有则按当前速度匀速前进）；停车目标 = `stoppingPosition`，但不超过 run 终点；巡航上限取 `curves.getCruiseCap()`。现有段为空或 `matches` 失败时新建段：起速 ≤ `ACCELERATION_DEFAULT`（宿主只为标记"在动"给的爬行速度）视为 0；替换旧段时发 `SEGMENT_REPLACED`，紧急段发 `EMERGENCY_BRAKE`。随后 advance，速度设为段速度（结束则 0），手柄按是否加速设 2 或 0。
- 返回：本 tick 前进距离（方块）。
- 副作用：写 `state` 的 runSegment、速度、手柄；可能发事件。
- 调用者：`tick`（第 144 行）。

### `private static double followSpeedCurve(MotionState state, MotionVehicle vehicle, MotionCurves curves, float ticksElapsed, double stoppingDistance)`
- 作用：客户端（及旧规则）逐 tick 跟随速度曲线。在曲线上且前方无阻挡时完全按曲线走（准点到达）；偏离曲线（被前车拦住或追赶时）以自身加/减速度向 min(曲线速度, 阻挡制动速度 `√(2d·剩余距离)`) 靠拢，且不快于曲线。未到停车点时速度至少 `ACCELERATION_DEFAULT/10`（保持"在动"）。没有曲线时按当前速度前进。
- 参数：`stoppingDistance` 实为停车位置（沿路径），前方被占时比 run 终点近。
- 返回：前进距离。副作用：写速度和手柄（0 或 2）。
- 调用者：`tick`（第 142 行）；对比测试 `LegacyTrainMotion.java:105`。

### `static boolean applyCommand(MotionState state, MotionCommand command)`
- 作用：执行司机指令。`ACCELERATE`：门完全关闭且手柄在 [−2, 2) 时 +1；`BRAKE`：手柄 > −2 时 −1；`TOGGLE_DOORS`：静止时切换车门目标并置手柄 −2，行驶中只把车门目标设为关并返回 false。
- 返回：是否有改变。副作用：写 `state` 的手柄/车门目标。
- 主要调用者：`Train.java:875`、`Train.java:879`、`MotionEventTest.java:74`。

### `private static void emit(MotionHooks hooks, MotionEvent.Type type, int rail)`
仅服务器端转发 `hooks.emit`（客户端只跟随，无事件）。调用者：`tick`、`followRunSegment`。

### `private static float getRailSpeed(MotionState state, MotionTrack track, MotionVehicle vehicle, int railIndex)`
- 作用：轨道目标速度：能加速的轨道取其限速；否则取 max(前一条轨道限速（前一条也不能加速则用 `getDefaultMaxSpeed`）, 当前速度)，即保持进入时的速度。调用者：`tick`（第 157 行）。

### `private static boolean mustStop(MotionState state, MotionTrack track, MotionHooks hooks, int stopIndex)`
- 作用：自动列车总是必须停；手动列车只有在轨道到头（循环线圈末折返、路径最后一条或接着折返）或下一轨道被占时才必须停。调用者：`tick`（第 147、196 行）。

### `static boolean isSectionTakenJustAhead(MotionHooks hooks, int headIndex, double railProgress)`
- 作用：停着的列车车头所在轨道上、前方 `SECTION_STANDING_MARGIN` 内的闭塞分区是否被占（被占则不能起步）。
- 主要调用者：`TickRunner.java:2367`；`tick`（第 74 行）。

### `static boolean isTakenAtStart(MotionHooks hooks, MotionTrack track, int rail, boolean taken)`
- 作用：被切成闭塞分区的轨道，只有更远的分区被占时停着的列车仍可驶入（之后在其前停车）。`taken` 为假或下标越界时原样返回；否则看该轨道起点处的 `getSectionBlockedPosition`：NaN（整条占用）或距起点 ≤ 1 方块时视为被占。
- 参数：`taken` 该轨道整体是否被视为占用。返回：在列车驶入处是否被占。
- 主要调用者：`TickRunner.java:2367`；`tick`（第 74 行）。

### `private static boolean loopsWithoutReversing(MotionTrack track, MotionHooks hooks)`
循环路径、圈终点下标 > 起点、在范围内，且圈尾接圈首不折返时为真。调用者：`tick`（第 175 行）。

### `private static void continueWithoutStopping(MotionState state, MotionHooks hooks)`
调 `hooks.startUp(false)` 推进到下一停站，再恢复原速度（宿主 startUp 会改速度）。调用者：`tick`（第 179、202 行）。

### `private static boolean isOppositeRail(MotionState state, MotionTrack track)`
列车恰在下一停站停车位置且之后的轨道反向（需折返）时为真。调用者：`tick`（第 43、70 行）。

### `private static int getIndex(MotionState state, MotionTrack track, int car, int trainSpacing, boolean roundDown)` / `private static int getIndex(MotionTrack track, double tempRailProgress, boolean roundDown)`
第 car 节车厢（0 = 车头）所在轨道下标，委托 `MotionConstants.findIndex`。调用者：`tick`（第 71、79、114、157 行）。

### `private static int findBlockedIndex(MotionTrack track, int headIndex, int lastIndex, double railProgress, double lookAhead, MotionHooks hooks)`
- 作用：从车头下一条轨道开始向前，在 `lookAhead`（制动距离）范围内且不超过 `lastIndex` 找第一条被占轨道；紧邻的下一条总会检查。
- 返回：被占轨道下标，或 −1。调用者：`tick`（第 117 行）；对比测试 `LegacyTrainMotion.java:92`。
