# MotionCurves.java

列车路径上的速度曲线，由宿主保存，并在路径或制动参数变化时重新计算。`TrainMotion` 通过此接口取当前所在运行段的曲线。实现：游戏侧 `Train.Motion`（`Train.java:1288`）和组件 `TickRunner.HeadlessTrain`（`TickRunner.java:1680`）。

## 接口 MotionCurves
- `SpeedProfile getProfile(double railProgress)`：车头在 `railProgress`（方块）处所在运行段的速度曲线；有富余时间时巡航速度会降低；没有时返回 null。
- `SpeedCurves.Run getRun(double railProgress)`：该车头位置所属的运行段（两停站之间），没有时 null。
- `double getCruiseCap()`：前往下一停站这段的巡航速度上限（方块/tick），没有上限时为正无穷。
