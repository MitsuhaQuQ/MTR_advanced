# PathTimes.java

列车按自己的速度曲线以最快方式跑一条路径时沿途的时间，是计划的出发点（S4a）。游戏的停车线和组件的模拟器用同样的方法计算。输入是 `transitsim.motion.SpeedCurves`（每个停站之间一次 "run" 的最快速度曲线 `SpeedProfile`），输出 `TimeSegment` 列表、站台间时间和 `StopTimes`。被游戏侧 `mtr.data.PathTiming`（`PathTiming.java:47`）、`mtr.data.Siding` 和组件 `transitsim.sim.SimDataset:618` 调用。

## 类 `PathTimes`（final，工具类）

### `private PathTimes()`
禁止实例化。

### `public static boolean isPassingPlatform(List<? extends PlanTrack> path, int index)`
- 作用：判断列车是否从另一段轨道驶入、不停车通过此站台：`index > 0`、停站时间为 0、属于某站台（`getStopId() != 0` 且 `isPlatformTrack()`），且上一段的 stop ID 不同（即刚进入这个站台）。
- 返回值：是通过站台时 true。
- 主要调用者：`common/src/main/java/mtr/data/PathTiming.java:111`、`PathTiming.java:118`、`mtr/data/Siding.java:2193`。

### `public static void curveTimes(List<? extends PlanTrack> path, SpeedCurves curves, List<TimeSegment> timeSegments, Map<Long, Map<Long, Float>> platformTimes, StopTimes stopTimes)`
- 作用：用速度曲线给路径计时（设计文档第 8 节），产出与逐步计时同类的结果：
  1. 求每段轨道末端的累计距离 `railEnds` 与总长。
  2. 求每个 run 的出发时刻 `runStarts`：前面各 run 的时长加上各自停站时间（`getDwellTime()*10`，半秒 → tick）。
  3. 对每个 run，把"通过的站台末端"和"停车位置（可能不在轨道末端，第 4 轮停车点）"作为切点，再按曲线的分段（`getPieces()`，每段一个加速度）切分，逐段调用 `addCurveSegment` 生成 `TimeSegment`。
  4. 再沿路径逐段：对有停站时间或为通过站台的轨道，向 `stopTimes.add(...)` 加一项（站台停车给出 stop ID；非站台且下一段为反向时视为折返，给出轨道 key；到达时间与离开时间 = 到达 + 停站 tick；最后一个参数表示是否为站台停车）；对属于站台/停车线的轨道，记录相邻两站（取停站中点时间）之间的时间到 `platformTimes[上一站][本站]`。
- 参数：
  - `path` 路径；`curves` 该路径的速度曲线，`runs` 与路径停站对应（`run.stopRailIndex` 为该 run 停车的轨道）；
  - `timeSegments` 输出，追加；
  - `platformTimes` 输出，上一站 ID → (下一站 ID → 时间差，tick)；
  - `stopTimes` 输出，追加。
- 时间单位：tick（停站时间半秒 × 10 推出 1 tick = 1/20 秒）。
- 返回值：无。
- 主要调用者：`common/src/main/java/mtr/data/PathTiming.java:47`、`transitsim/sim/SimDataset.java:618`。

### `private static void addCurveSegment(List<TimeSegment> timeSegments, SpeedProfile profile, double[] piece, double start, double end, double runStart, List<? extends PlanTrack> path, int railIndex, double totalDistance)`
- 作用：从曲线的一个分段 `piece`（`piece[3]` 为加速度）截取 `[start, end]` 生成一个 `TimeSegment`：加速度 > 1e-9 为加速，< -1e-9 为减速，否则匀速；起点速度/时间和终点时间取自 `profile`，加上 `runStart`。若 `railIndex ≥ 0`（区间在某轨道末端结束），设置 `pass`（是否通过站台），以及 `savedRailBaseId`：通过站台，或在非路径终点处停车时为该轨道的 stop ID，否则 0。
- 参数：`railIndex` 区间结束于其末端的轨道下标，或 -1。
- 副作用：追加到 `timeSegments`。
