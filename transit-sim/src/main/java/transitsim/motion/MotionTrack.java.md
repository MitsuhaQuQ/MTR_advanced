# MotionTrack.java

运动规则眼中的列车路径：按路径下标排列的轨道。宿主每次都读自己的路径，所以宿主替换路径后立即可见。实现：游戏侧 `Train.Motion`（`Train.java:1288`）、组件 `TickRunner.HeadlessTrain`（`TickRunner.java:1680`）。使用者：`TrainMotion`、`MotionConstants.findIndex/getReversalAdvance`、`PlatformDwell`、`SpeedCurves` 等。

## 接口 MotionTrack
- `int size()`：路径上的轨道条数。
- `int getDistanceCount()`：距离表的项数；正常等于轨道数，除非宿主的两个列表不同步。
- `double getDistance(int rail)`：该轨道终点沿路径的累计距离（方块），单调不减。
- `default double getStopOffset(int rail)`：在该轨道末端停车时车头距轨道终点的后退量（方块，优化第 4 轮"停车点"）。多数停站为 0；站台可设置让列车靠后停（如居中）。只有有停站时间的轨道才有。默认 0。调用者：本接口 `getStopDistance`、`MotionConstants.getReversalAdvance`、`Train.java:977`。
- `default double getStopDistance(int rail)`：停在该轨道末端时车头的位置 = `getDistance(rail) − getStopOffset(rail)`。调用者：`PlatformDwell.isAtStop`、`TrainServer.java:886`、`TrainMotion` 多处。
- `int getDwellTime(int rail)`：该轨道末端的停站时长（秒），不停为 0。
- `boolean canAccelerate(int rail)`：能否在此轨道加速；站台等轨道为 false，列车保持进入时的速度。
- `float getMaxSpeed(int rail)`：轨道限速（方块/tick）。
- `boolean isOppositeRail(int rail, int nextRail)`：第二条轨道是否沿第一条反向回去（列车在两者间折返）。
- `int getRepeatIndex1()`：循环路径下一圈的起点下标；不循环为 0。
- `int getRepeatIndex2()`：循环路径一圈的终点下标；不循环为 0。
