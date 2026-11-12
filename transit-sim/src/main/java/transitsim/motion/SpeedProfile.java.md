# SpeedProfile.java

列车在一个运行区间（从停在一站到停在下一站）上的速度曲线（设计文档第 8 节）。限速是车头位置的阶梯函数；在限速下以固定加/减速度跑出的最快曲线，其速度平方 v² 对位置分段线性，因此以节点 (位置, v²) 保存，所有时间和速度都从节点精确算出。单位：方块、tick。

区间也可以从中途开始、带着当时的速度（审查 A04），比如途中被拦下后重新出发。起速高于限速的列车以自身制动率逐渐减到限速而不是瞬间降速；起速快到在终点停不住的，从"仍能停住的最高速度"开始。

在整体中的位置：由 `SpeedCurves.Run` 构建（`build`、`findCap`、`translated`），被 `RunSegment` 包装用于逐 tick 位置；`PathTimes` 用 `getTime`/`getPieces` 计时；游戏侧 `Train`、`Siding` 直接读位置/速度。

## 类 SpeedProfile（final，不可变）

### 字段与常量
- `startPosition`、`endPosition`（double）：曲线起止位置（已含平移 offset）。
- `positions`、`speedsSquared`（private double[]）：节点位置（未平移）和对应 v²。
- `times`（private double[]）：到达各节点的累计 tick 数。
- `offset`（private double）：平移量；平移实例与原实例共享数组。
- `EPSILON = 1E-9`：长度/速度的零阈值；加/减速度的下限。
- `CAP_SEARCH_STEPS = 40`：二分求巡航速度的迭代次数。

### 私有构造函数 `SpeedProfile(double[] positions, double[] speedsSquared)`
- 作用：保存节点，offset = 0，并用 `pieceTime` 累计每节点时间。调用者：`build`（第 70、139 行）。

### 私有构造函数 `SpeedProfile(SpeedProfile source, double shift)`
- 作用：共享源曲线数组，offset 累加 shift，重新算起止位置。调用者：`translated`（第 49 行）。

### `SpeedProfile translated(double shift)`
- 作用：同一条不可变曲线放在路径上另一个偏移处；shift 为 0 返回自身。用于 `SpeedCurves` 的模板共享。
- 调用者：`SpeedCurves.java:179`、`SpeedProfileTest.java:16`。

### `static SpeedProfile build(double[] breaks, double[] limits, double rawAcceleration, double rawDeceleration, double cap)`
起速为 0 的便捷形式（从静止出发）。参数见下。调用者：`SpeedCurves.java:167`、`SpeedCurves.java:176`、`SpeedCurves.java:202`。

### `static SpeedProfile build(double[] breaks, double[] limits, double rawAcceleration, double rawDeceleration, double cap, double startSpeed)`
- 作用：构建最快曲线。
  1. 加/减速度取下限 EPSILON；若 `breaks.length != limits.length + 1`、无小段、或总长 ≤ EPSILON，返回 [breaks[0], breaks[0]] 零长度曲线。
  2. 每小段允许 v² = min(限速, cap)²；起速 > 0 时允许值取"限速²"与"起速按自身制动减下来的值"中较大者（`allowed`）。
  3. 前向扫描（从起点加速）得每小段起点 v²；后向扫描（向终点制动）得每小段终点 v²。
  4. 每小段在候选点（端点、加速线与限速交点、制动线与限速交点、加速线与制动线交点；起速 > 0 时再加起始制动线与限速/加速线交点）处取 min(允许值, 加速线, 制动线) 作节点，相近节点合并取较小。
  5. 起速为 0 时首节点 v² 置 0；末节点 v² 必为 0。
- 参数：`breaks` 限速变化点（从起点到终点，≥ 2 个）；`limits` 每个断点后的限速（方块/tick），比 breaks 少一项；加/减速度（方块/tick²）；`cap` 巡航上限（无穷 = 无）；`startSpeed` 起点速度（0 = 从静止起步）。
- 返回：新曲线。副作用：无。
- 主要调用者：`SpeedCurves.java:219`（`fromTo`）、本类 `findCap`。

### `static double findCap(double[] breaks, double[] limits, double acceleration, double deceleration, double targetTicks, double minCap)`
起速 0 的便捷形式。调用者：`SpeedCurves.java:190`、`SpeedCurves.java:198`。

### `static double findCap(double[] breaks, double[] limits, double acceleration, double deceleration, double targetTicks, double minCap, double startSpeed)`
- 作用：二分（40 步）找使曲线用时恰为 `targetTicks` 的巡航速度（设计文档 8.2 时间约束）：时间多于最快所需时列车跑慢些、准点到达而非早到。若目标时间 ≤ 最快用时返回正无穷；若最高限速 ≤ `minCap` 或以 `minCap` 跑仍不超过目标时间，返回 `minCap`（列车会早到）。
- 返回：巡航上限（方块/tick）。副作用：无（多次构建临时曲线，较耗时）。
- 调用者：`SpeedCurves.java:227`（`findCapFrom`）。

### `double getDuration()`
整条曲线用时（tick）。调用者：`RunSegment.java:126`、`Train.java:763`。

### `double getSpeed(double position)`
- 作用：某位置的速度：在所在小段内对 v² 线性插值后开方；零长度小段取终点值。位置超出范围时被夹到首/末小段。
- 调用者：`RunSegment.java:88`、`Train.java:1005`。

### `double getTime(double position)`
- 作用：从起点出发到达某位置所需 tick（位置夹到曲线范围内）。
- 调用者：`PathTimes.java:89`、`PathTimes.java:112`；本类 `advance`。

### `double getPosition(double ticks)`
- 作用：出发后 `ticks` 时的位置：≤0 返回起点，≥时长返回终点，否则二分节点时间后按匀加速公式 `s0 + v0·t + a·t²/2` 计算（不超过该小段终点）。
- 调用者：`RunSegment.java:84`、`Train.java:1208`。

### `double advance(double position, double ticks)`
- 作用：曲线上的列车从 `position` 再走 `ticks` 后的位置（不超过终点）= `getPosition(getTime(position) + ticks)`。
- 调用者：`TrainMotion.java:252`、`TickRunner.java:2015`。

### `List<double[]> getPieces()`
- 作用：返回匀加速各小段 `{起点位置(含 offset), 起点速度, 起点时间, 加速度}`，跳过零长度段；供在中间位置计时。
- 调用者：`PathTimes.java:64`、`TickRunner.java:2422`。

### `private int findPiece(double position)`
二分找包含该（未平移）位置的小段下标，最大为倒数第二个节点。调用者：`getSpeed`、`getTime`。

### `private static double pieceTime(double length, double startSpeed, double endSpeed)`
匀加速时平均速度为两端均值，用时 = 2·长度 / (v0+v1)（分母下限 EPSILON；长度 ≤ EPSILON 返回 0）。调用者：构造函数、`getTime`。

### `private static double allowed(double limitSquared, boolean slowsDown, double startSquared, double deceleration, double start, double position)`
某位置允许的最高 v²：限速²；起速 > 0 时还取起速按制动下降的值 `startSquared − 2d(position − start)` 与之较大者。调用者：`build`。

### `private static double clamp(double value)`
夹到 [0, 1]。调用者：`getSpeed`。
