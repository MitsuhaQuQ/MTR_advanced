# PathTiming.java

某种车辆沿一条路径所需的时间：时间分段（`TimeSegment`）、各停站时刻（`StopTimes`）、相邻站台之间的时间。停车线用自己的车辆给自己的路径计时，线路路径则用运行它的每种车辆计时（`RoutePaths`），规则相同，所以无论在哪里算，时间都一致。有速度曲线时委托组件（`SpeedCurves`、`PathTimes.curveTimes`），没有时按旧规则逐 tick 模拟。被 `Depot`（`:3873`、`:3944`）、`RoutePaths`（`:1160-1164`、`:1540`）、`PacketTrainDataGuiServer.java:1047` 使用。

## final class PathTiming

### `private PathTiming()`
工具类，禁止实例化。

### `public static StopTimes calculateStopTimes(Vehicle vehicle, List<PathData> path)`
- 作用：只要停站时刻：`generateTimeSegments(vehicle, path, 临时列表, 临时映射, stopTimes, 0, 0)`。
- 返回：新 `StopTimes`。
- 主要调用者：`RoutePaths.java:1160`、`RoutePaths.java:1540`、`PacketTrainDataGuiServer.java:1047`。

### `public static void generateTimeSegments(Vehicle vehicle, List<PathData> path, List<TimeSegment> timeSegments, Map<Long, Map<Long, Float>> platformTimes, StopTimes stopTimes, int joinIndex, int lapEndIndex)`
- 参数：`timeSegments` 输出的时间分段（先清空）；`platformTimes` 输出：前一站台 id → 下一站台 id → 两者之间的时间（tick）；`stopTimes` 输出（先清空，设置 `joinIndex`）；`joinIndex` 路径接入环线的位置，或 0；`lapEndIndex` 环线一圈结束的位置，或 0。
- 速度曲线路径（`vehicle.useSpeedCurve`、非连续运动、路径非空）：用计划速度（低于列车可跑速度，给追赶留余地）构建 `TrackView`（含列车长度），`SpeedCurves.of(..., 起始位置 = (停车线长 + 车长)/2, 车长, 加速度, 减速度)`，`PathTimes.curveTimes` 填输出，`stopTimes.linkJoinStops(lapEndIndex)`。
- 无速度曲线（旧规则，逐 tick）：
  - 先算所有停站点距离（停站轨道末端减去停车偏移，第 4 轮）；
  - 列车从 `(railLength + 车长)/2` 出发，每段限速 = `vehicle.limit(计划速度)`（若该轨道类型沿用前段限速则取 `max(当前速度, 默认限速)`）；
  - 每 tick：超速或需要为下一停车点制动（`剩余距离 + 1 < v²/(2a)`）则减速（不低于 a），低于限速则加速，否则匀速；速度变化方向改变（或强制）时开新分段；推进到本段运行终点（停站时为停车位置）；
  - 分段记录：结束进度/时间；在站台末端且该站台为通过时 `pass = true` 并强制下一段新开；停站或通过时记 `savedRailBaseId`；
  - 停站或通过站台：`stopTimes.add(下标, 站台 id 或 0, 非站台折返时的轨道积, 到达 time, 离开 time + dwellTime×10, 是否站台停站)`；
  - 时间加 `dwellTime×5`，记录站台间时间，再加 `dwellTime×5`（停站时间单位为 0.5 秒？即 dwellTime×10 tick，未确定原单位）；停站后速度归零；
  - 与下一段反向（折返）时进度加 `车长 + 2×停车偏移`（车头到原车尾的位置）。
  - 最后 `stopTimes.linkJoinStops(lapEndIndex)`。
- 副作用：只写输出参数。
- 主要调用者：`Depot.java:3873`、`Depot.java:3944`、`RoutePaths.java:1164`。

## public static final class Vehicle
影响路径时间的车辆参数；相等的车辆给出相等的时间，因此时间可以按车辆缓存（`equals`/`hashCode` 覆盖全部字段）。

### 字段
- `railLength`：列车出发停车线的长度（列车从其中点出发）。
- `trainCars`、`spacing`：车厢数、每节车厢长度（方块）。
- `acceleration`：m/tick²，与停车线保存的一致（`SidingScreen` 显示为 ×400 m/s²）。
- `deceleration`：减速度（同单位）。
- `useSpeedCurve`：是否使用速度曲线。
- `transportMode`。
- `maxSpeed`：车辆最高速度（方块/tick），0 表示不限。

### 构造函数
- 7 参数版：`maxSpeed = 0`。8 参数版：全部赋值。
- 主要调用者：`Depot.java:3871`、`Depot.java:3942`、`RoutePaths.java:546`。

### `public float limit(float speedLimit)`
- 返回：不超过车辆最高速（换算为计划速度 `SpeedRules.plan(maxSpeed, mode)`）的限速。
- 主要调用者：`Siding.java:800`、`PacketTrainDataGuiServer.java:469`、本类。

### `equals(Object)` / `hashCode()`
- 按全部 8 个字段。用作 `RoutePaths`、`Depot`、`SandboxRuns` 中按车辆分组/缓存的键（例如 `Depot.java:398`、`:796`）。
