# MotionVehicle.java

运动规则需要知道的列车自身属性。制动参数和"是否遵循速度曲线"在运行中可能变化，因此每次都重新读取。实现：游戏侧 `Train.Motion`（`Train.java:1288`）、组件 `TickRunner.HeadlessTrain`（`TickRunner.java:1680`）。使用者：`TrainMotion`。

## 接口 MotionVehicle
- `int getTrainCars()`：车厢数。
- `int getSpacing()`：每节车厢长度（方块）。车长 = 车厢数 × spacing。
- `float getRailLength()`：列车出发的停车线（siding）长度（方块）。
- `float getAcceleration()`：加速度，方块/tick²。
- `float getDeceleration()`：减速度，方块/tick²。
- `boolean usesSpeedCurve()`：本 tick 是否遵循速度曲线；手动驾驶或连续运动的列车不遵循。
- `boolean isContinuousMovement()`：是否像缆车一样连续通过停站不停。
- `float getDefaultMaxSpeed()`：从不能加速的轨道驶入站台时在站台内保持的速度。
- `float getManualMaxSpeed()`：手动驾驶允许的最高速度。
