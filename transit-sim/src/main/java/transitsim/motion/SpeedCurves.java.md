# SpeedCurves.java

列车整条路径的速度曲线（设计文档第 8 节）：每两个停站之间一段（`Run`），各有一条 `SpeedProfile`。停站 = 有停站时间的轨道末端，路径最后一条轨道也视为停站。折返时车头跳到列车另一端，所以下一段从"一个车长之后"开始（与游戏其他部分一致）。

某车头位置的限速 = 列车所压所有轨道限速的最小值，所以列车在车头进入慢速轨道前减速、车尾离开后才加速。不能加速的轨道（站台、停车线）沿用前一轨道的限速。

在整体中的位置：`of` 由游戏侧 `Siding`（`Siding.java:1198`）、`Train`（`Train.java:801`）、`PathTiming`、组件 `TickRunner`/`RunTrack` 调用，输入 `TrackView`；`Run` 被 `RunSegment`、`TrainMotion`、`TrainRules`（`findCruiseCap`）使用；`PathTimes`/`Siding.java:87` 用 `Run.from` 计时。

## 类 SpeedCurves（final）

### 字段
- `runs`（不可修改 `List<Run>`）：按路径顺序的各运行区间；底层是懒加载的 `RunIndex`。
- `index`（private `RunIndex`）：同一对象，用于访问原始 spec 做二分。
- `acceleration`、`deceleration`（double）：加/减速度，方块/tick²。
- `minSpeed`（double）：最低巡航速度，取自 `TrackView.minSpeed`。

### 私有构造函数 `SpeedCurves(RunIndex runs, double acceleration, double deceleration, double minSpeed)`
按字段赋值，`runs` 包成不可修改列表。调用者：`build`（第 69 行）。

### `static SpeedCurves of(TrackView track, double startPosition, double trainLength, double acceleration, double deceleration)`
- 作用：获取（必要时构建）路径的速度曲线；结果按 (起点, 车长, 加速度, 减速度) 缓存在 `TrackView` 内（最多 16 份）。
- 参数：`track` 路径视图；`startPosition` 车头在停车线中的起点（方块）；`trainLength` 车长（折返时车头移动距离）；加/减速度（方块/tick²）。
- 返回：`SpeedCurves`（可能是缓存的同一实例）。副作用：写 `TrackView` 缓存。
- 主要调用者：`Siding.java:1198`、`Train.java:801`、`PathTiming.java:46`。

### `private static SpeedCurves build(TrackView track, double startPosition, double trainLength, double acceleration, double deceleration)`
- 作用：先累计每条轨道终点距离 `ends[]`，并算每条轨道的有效限速 `limits[]`（限速 < 0 的轨道沿用上一条，且不低于 minSpeed）。再在每个停站轨道或最后一条轨道处切出一段 spec `{runStart, max(runStart, 终点−停车偏移), 轨道下标}`；下一段起点：若该轨道后折返则为 `终点 + 偏移 + 车长`，否则为本段停车位置。Run 对象不在此创建，交给 `RunIndex` 按需生成。
- 调用者：`of`（第 38 行，通过 Supplier）。

### `int getCachedRunCount()`
- 作用：当前已实例化（缓存中）的 Run 个数，与整日运用长度无关；用于测试懒加载上限。
- 调用者：`SpeedCurvesTest.java:24`。

### `Run getRun(double position)`
- 作用：二分找车头位置所属的 run：所在的那段，或停在站点时即将开始的下一段（判据 `position < spec 终点 − 1E-6`）。超过全部时返回最后一段。
- 返回：Run，路径没有 run 时 null。
- 主要调用者：`Train.java:748`、`Train.java:1582`；本类 `findCruiseCap`（第 128 行）。

### `double getRunTicks(int runIndex)`
- 作用：第 runIndex 段最快曲线的时长（tick，不含停站）。注：javadoc 写"从路径起点到各段末"，但代码只返回该段自身时长。
- 调用者：仓库内未找到调用者。

### `double findCruiseCap(double position, double speed, int stopRailIndex, double ticks)`
- 作用：求使列车在给定 tick 数内到达下一停站的巡航速度（设计文档 8.2）。若 run 不存在或其停站轨道 ≠ `stopRailIndex`，返回正无穷。列车在 run 起点附近（±1 方块）时用 `run.findCap`，否则（如被前车拦住后中途起步）用 `run.findCapFrom` 从当前位置和速度算（审查 A04）。
- 参数：`position` 车头位置；`speed` 当前速度；`stopRailIndex` 下一停站轨道下标；`ticks` 期望用时。
- 返回：巡航上限，无需限速时正无穷。
- 主要调用者：`TrainRules.java:230`、`SpeedCurvesTest.java:87`。

## 内部类 RunIndex（private static final，`AbstractList<Run>`、`RandomAccess`）
按需构建 Run 的列表，避免一次性为整日长路径生成所有曲线。
- 字段：`specs`（每段 `{start, end, stopRail}`）、`ends`/`limits`（轨道终点与有效限速）、`length`（车长）、`acceleration`、`deceleration`；`cache`（LRU，最多 8 个 Run）；`templates`（LRU，最多 1024 条 `SpeedProfile` 模板，键为平移到 0 后的断点坐标（1e-7 精度取整）+ 限速位模式，使形状相同的区间共享曲线）。
- 构造函数：按字段赋值。调用者：`build`（第 69 行）。
- `size()`：spec 个数。
- `synchronized Run get(int i)`：从缓存取，没有则新建 Run 并放入缓存。

## 内部类 Run（public static final）
两停站之间的一段运行。
- 字段：`stopRailIndex`（段末停站所在轨道下标）、`fastest`（不限巡航速度的最快曲线）、`breaks`（限速变化点，含起止）、`limits`（各小段限速）、`acceleration`、`deceleration`。

### 私有构造函数 `Run(double start, double end, int stopRailIndex, double[] railEnds, double[] railLimits, double trainLength, double acceleration, double deceleration, Map templates)`
- 作用：在 [start, end] 内收集限速可能变化的点：车头进入每条轨道处（轨道终点）和车尾离开处（终点 + 车长）；每小段限速 = 小段中点处车尾到车头覆盖轨道限速最小值（`getLimit`）。断点不足 2 个时 `fastest` 为零长度曲线；否则把断点平移到 0 作为模板键，从 `templates` 取或新建模板曲线，再 `translated(start)` 得到 `fastest`。
- 副作用：可能写入共享的 `templates`。调用者：`RunIndex.get`（第 93 行）。

### `SpeedProfile withTime(double ticks, double minSpeed)`
以 `findCap` 求巡航上限后 `withCap`；断点不足时返回 `fastest`。仓库内未找到外部调用者。

### `double findCap(double ticks, double minSpeed)`
- 作用：整段用时恰为 `ticks` 的巡航速度（`SpeedProfile.findCap`），无富余时间返回正无穷。调用者：`SpeedCurves.findCruiseCap`（第 132 行）、`SpeedCurvesTest.java:92`。

### `SpeedProfile withCap(double cap)`
- 作用：以巡航上限重建整段曲线；cap 无穷或断点不足时返回 `fastest`。调用者：`Train.java:843`、`TickRunner.java:3083`。

### `SpeedProfile from(double position, double speed, double cap)`
- 作用：从段中某处以某速度出发到段终点的剩余曲线（用本段常用制动）。调用者：`Siding.java:87`、`PathTimes.java:12`。

### `SpeedProfile fromTo(double position, double speed, double cap, double end, double deceleration)`
- 作用：从中途出发、恰好停在 `end`（可短于段终点，前方被占时）的曲线；`deceleration` 可大于自身制动（紧急）。剩余部分为空时返回 [end, end] 的零长度曲线。
- 调用者：`RunSegment.java:65`、本类 `from`（第 209 行）。

### `double findCapFrom(double position, double speed, double ticks, double minSpeed)`
- 作用：从中途位置与速度出发，使剩余部分用时为 `ticks` 的巡航速度；无剩余部分返回正无穷。调用者：`findCruiseCap`（第 132 行）。

### `double getDeceleration()`
返回本段常用减速度。调用者：`RunSegment.java:62`、`TrainMotion.java:113`、`Siding.java:1017`。

### `private double[][] getRest(double position, double rawEnd)`
- 作用：截取从 `position` 到 `min(rawEnd, 段终点)` 的断点与限速子数组 `{restBreaks, restLimits}`；中间没有内容（位置已到终点 − 1E-6）时返回 null。调用者：`fromTo`（第 218 行）、`findCapFrom`（第 226 行）。

### `private static void addIfInside(TreeSet<Double> points, double point, double start, double end)`
点严格在 (start, end) 内（±1E-6）才加入。调用者：构造函数（第 157、158 行）。

### `private static double getLimit(double[] railEnds, double[] railLimits, double tail, double head)`
- 作用：车尾到车头之间所有轨道限速的最小值；一条都不覆盖时取最后一条轨道限速（无轨道为 0）。调用者：构造函数（第 164 行）。

### `private static int firstRailEndingAfter(double[] railEnds, double position)`
二分找第一条终点 > position 的轨道下标（可能等于长度）。调用者：构造函数（第 156 行）、`getLimit`（第 273 行）。
