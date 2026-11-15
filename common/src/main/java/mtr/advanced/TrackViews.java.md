# TrackViews.java

把一列车的路径（`List<PathData>`）转成 `transit-sim` 组件读取的 `TrackView`（每段长度、限速、是否停站、是否折返、停车偏移，加默认限速）。组件的速度曲线/运动计算（`TrainMotion`、时刻表计算）都读 `TrackView`。被 `Train`（运行）、`PathTiming`（计划时分）等使用。

## final class TrackViews

### `private TrackViews()`
工具类，禁止实例化。

### `public static TrackView of(List<PathData> path, TransportMode transportMode)`
- 等价 `of(path, transportMode, 0)`（无车辆最高速限制）。
- 主要调用者：`PathTiming.java:46`、`Train.java:801`；测试 `SpeedCurvesTest.java:38`（工具对重载未区分，行号为同名方法的调用处）。

### `public static TrackView of(List<PathData> path, TransportMode transportMode, float maxSpeed)`
- 参数：`maxSpeed` 车辆最高速度（方块/tick），0 表示不限。
- 等价 `of(..., maxSpeed, false)`（运行速度）。

### `public static TrackView of(List<PathData> path, TransportMode transportMode, float maxSpeed, boolean plan)`
- 参数：`plan` 为 `true` 时用编排时刻表的计划速度，否则用列车实际运行速度（优化文档 5.6）。
- 等价 `of(..., plan, rail -> 0)`（停车偏移全 0）。

### `public static TrackView of(List<PathData> path, TransportMode transportMode, float maxSpeed, boolean plan, double trainLength)`
- 参数：`trainLength` 列车长度（方块）；站台停车位置取决于它（第 4 轮）。
- 作用：停站段的停车偏移取 `path.get(i).getStopOffset(trainLength)`。若 `path` 是组件的 `IndexedPath`，结果按键 `["host-track", transportMode, maxSpeed, plan, trainLength, PlatformStops.fingerprint()]` 缓存在路径对象上（`compiled`）。

### `public static TrackView of(List<PathData> path, TransportMode transportMode, float maxSpeed, boolean plan, IntToDoubleFunction stopOffsets)`
- 作用：真正的构建。车速上限 `vehicleSpeed` = `SpeedRules.plan(...)` 或 `SpeedRules.run(...)`。逐段：
  - `lengths[i]` = 轨道长度；
  - `limits[i]` = 若 `SpeedRules.keepsLimitBefore(railType, mode)` 为真则 −1（沿用之前的限速），否则 `min(段计划/运行速度, vehicleSpeed)`；
  - `stops[i]` = `dwellTime > 0`；
  - `reversals[i]` = 与下一段是反向轨道（`isOppositeRail`）；
  - `offsets[i]` = 停站时 `stopOffsets(i)`，否则 0。
  - 最后一个参数是该交通方式默认限速与车速上限的较小值。
- 返回：新的 `TrackView`。
- 副作用：无。

### `private static float limit(float speedLimit, float maxSpeed)`
- 返回：`maxSpeed > 0 ? min(speedLimit, maxSpeed) : speedLimit`。
