# RunSegment.java

列车从出发点开到必须停车处的一个"运行段"（解耦文档 3.5）：一条速度曲线 + 自开始以来经过的宿主 tick 数。列车的位置和速度就是曲线在该时刻的值，因此与时间如何被切成步长无关（契约 2.1 规则 6：跳 tick 与不跳结果相同）。

运行段只会被替换、不会被修改：停车点移动、所在 run 或巡航上限改变、列车被外力移动时，新建一段，从列车当前的位置和速度出发（审查 A04），只改变前方部分；新段版本号 = 被替换段 + 1。

在整体中的位置：由 `TrainMotion`（`TrainMotion.java:243`）和 `RunMotion`（`RunMotion.java:213`）创建和推进；曲线来自 `SpeedCurves.Run.fromTo` / `SpeedProfile`。`Spec` 是发给客户端（拥有同样速度曲线）的描述，客户端 `Train.java:755` 用 `rebuild` 复现同一条曲线（G2）；`TickRunner` 用它做无头运行和事件预测。

## 类 RunSegment（final）

### 常量
- `EMERGENCY_DECELERATION_FACTOR = 1.5`：自身制动来不及停车时，紧急制动比常用制动强的倍数。
- `POSITION_TOLERANCE = 1E-6`：列车实际位置与段内位置相差多少（方块）仍算"仍在此段上"；也用作判断紧急制动的容差。

### 字段
- `version`（int）：版本号，从 1 起，每替换一次 +1。
- `run`（`SpeedCurves.Run`）：所在的两停站间运行区间。
- `cap`（double）：巡航速度上限（方块/tick），无限制为正无穷。
- `target`（double）：段末停车位置（沿路径，方块），不超过 run 的终点。
- `emergency`（boolean）：是否为紧急制动段（自身制动停不住，改用更强制动，甚至仍不够）。
- `startPosition`、`startSpeed`（double）：出发位置和速度；与 run、cap、target 一起完整决定曲线（G2）。
- `profile`（private `SpeedProfile`）：本段的速度曲线。
- `elapsedTicks`（private double）：自段开始已过的 tick 数（唯一可变状态）。

### 私有构造函数 `RunSegment(int version, Run run, double cap, double target, boolean emergency, double startPosition, double startSpeed, SpeedProfile profile)`
按字段赋值，`elapsedTicks` 初值 0。仅由私有 `start` 调用（第 66 行）。

### `static RunSegment start(RunSegment previous, SpeedCurves.Run run, double position, double speed, double cap, double target)`
- 作用：开始一个新段，版本号为 `previous == null ? 1 : previous.version + 1`，委托私有 `start`。
- 参数：`previous` 被替换的段或 null；`run` 所在区间；`position` 当前车头位置（方块）；`speed` 当前速度（方块/tick）；`cap` 巡航上限；`target` 停车位置（不超过 run 终点）。
- 返回：新段。副作用：无。
- 主要调用者：`TrainMotion.java:243`、`RunMotion.java:213`、`EventSimulator.java:97`。

### `private static RunSegment start(int version, Run run, double position, double speed, double cap, double target)`
- 作用：以 run 的减速度 d 判断 `speed² > 2·d·(target − position) + 容差` 是否成立；成立则为紧急段并用 `d × 1.5` 制动。曲线由 `run.fromTo(position, speed, cap, target, 制动)` 生成。
- 调用者：公有 `start`（第 58 行）、`Spec.rebuild`（第 170 行）。

### `Spec describe(long nowMillis)`
- 作用：生成发送给客户端的段描述；开始时刻 = `nowMillis − round(elapsedTicks × 50)`。
- 参数：`nowMillis` 宿主当前时间（以 tick 毫秒计）。返回：新 `Spec`。
- 主要调用者：`TickRunner.java:1811`、`TickRunner.java:1936`、`TickRunner.java:2803`。

### `void advance(double ticks)`
- 作用：`ticks > 0` 时把 `elapsedTicks` 加上 ticks（负数或 0 忽略）。
- 副作用：改 `elapsedTicks`。
- 主要调用者：`TrainMotion.java:252`、`RunMotion.java:222`、`TickRunner.java:2015`。

### `double getPosition()` / `double getSpeed()`
- 作用：当前位置 = 曲线在 `elapsedTicks` 时的位置；当前速度 = 曲线在该位置的速度。
- 主要调用者：`Train.java:1208`、`TrainMotion.java:257`（位置）；`TrainServer.java:555`（速度）。

### `double getElapsedTicks()`
返回已过 tick 数。调用者：`TickRunner.java:2275`。

### `double getPositionAt(double ticks)` / `double getSpeedAt(double ticks)`
- 作用：段开始后 `ticks`（负数按 0）时的位置；以及该时刻的速度（≥ 段时长时为 0）。不改变列车状态。
- 主要调用者：`Train.java:756`、`Train.java:774`、`SegmentSpecTest.java:49`。

### `double getPositionAfter(double ticks)` / `double getSpeedAfter(double ticks)`
- 作用：再过 `ticks` 后的位置/速度，不移动列车。
- 主要调用者：`TickRunner.java:2278`、`TickRunner.java:2288`、`TickRunner.java:2328`。

### `double getDuration()`
整段所需 tick 数。调用者：`Train.java:763`、`TickRunner.java:2275`。

### `boolean isFinished()`
`elapsedTicks >= 段时长`。调用者：`TrainMotion.java:186`、`RunMotion.java:173`。

### `boolean matches(SpeedCurves.Run run, double cap, double target, double position)`
- 作用：本段是否仍描述这列车：同一 run 对象（引用相等）、同 cap、同 target，且当前位置与 `position` 相差 ≤ 容差。不匹配时调用方会新建段。
- 主要调用者：`TrainMotion.java:239`、`RunMotion.java:210`。

## 内部类 Spec（public static final）
宿主发给拥有同样速度曲线一方的段描述（G2）：版本、开始时刻、起点与起速、cap、停车位置。配合 run 用 `rebuild` 得到同一曲线。

- 字段：`version`、`startMillis`（宿主时钟毫秒）、`startPosition`、`startSpeed`、`cap`、`target`。
- 构造函数 `Spec(int, long, double, double, double, double)`：按字段赋值。调用者：`describe`（第 74 行）、游戏侧读包处。
- `RunSegment rebuild(SpeedCurves.Run run)`：用私有 `start` 以同一版本重建段（`elapsedTicks = 0`，调用方需自行 advance）。参数 `run` 应为 `MotionCurves.getRun(startPosition)` 找到的区间。调用者：`Train.java:755`、`TickRunner.java:2014`。
- `double getTicksAt(long millis)`：到宿主时刻 `millis` 时段已运行的 tick 数 = `(millis − startMillis) / 50`。调用者：`Train.java:756`、`TickRunner.java:2015`。
- `equals`/`hashCode`：equals 比较全部 6 个字段；hashCode 只用 `startMillis` 与 `version`。调用者：`TrainClient.java:301`（判断是否收到新段）。
