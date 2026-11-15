# MotionConstants.java

运动规则共用的常量和小工具，取值与宿主（游戏 `Train`）一致。被 `TrainMotion`、`SpeedCurves`、`PlatformDwell`、`TickRunner` 以及游戏侧 `Train`/`TrainServer` 使用。

## 类 MotionConstants（final，不可实例化）

### 常量
- `ACCELERATION_DEFAULT = 0.01F`：默认加速度，单位 方块/tick²；同时也是列车起步时给的初速度。
- `DOOR_MOVE_TIME = 64`：车门开/关动作时长（tick）。
- `DOOR_DELAY = 20`：关门前的延迟（tick）。`TrainMotion.java:95` 用 `总停站 tick − DOOR_MOVE_TIME − DOOR_DELAY` 判断何时开始关门；`TrainServer.java:330` 用它推算预计发车时刻。
- `MILLIS_PER_TICK = 50`：每 tick 毫秒数。
- `BRAKING_MARGIN = 2`：制动余量（方块），速度曲线/停车计算用（具体用处见 `SpeedCurves`、`TrainMotion`）。
- `CURVE_SPEED_TOLERANCE = 1E-3`：比较曲线速度时的容差。

### 私有构造函数
仅作命名空间。

### `static float clamp(float value, float min, float max)`
- 作用：与 Minecraft `Mth.clamp`（float 版）相同：小于 min 取 min，否则取 `min(value, max)`。
- 返回：夹紧后的值。副作用：无。
- 主要调用者：`TrainMotion.java:154`、`TrainMotion.java:209`、`TrainServer.java:647`。

### `static double getReversalAdvance(MotionTrack track, int stop, double trainLength)`
- 作用：列车在停站点折返时车头前移的距离：车头移到原车尾位置、并在反向轨道上，等于车长加两倍该站停车偏移（优化第 4 轮）。
- 参数：`track` 路径；`stop` 停站轨道下标（越界时偏移按 0）；`trainLength` 车长（方块）。
- 返回：前移距离（方块）。副作用：无。
- 主要调用者：`TrainServer.java:349`、`TickRunner.java:2943`。

### `static int findIndex(MotionTrack track, double tempRailProgress, boolean roundDown)`
- 作用：二分查找第一条"终点距离 > 进度"的轨道（`roundDown` 为真时"≥"也算，即进度恰在轨道终点时归入该轨道）；路径上距离单调不减。找不到时返回最后一条轨道下标。
- 参数：`track` 路径；`tempRailProgress` 沿路径的位置（方块）；`roundDown` 恰在边界时是否取前一条。
- 返回：轨道下标 ∈ [0, size−1]（size 为 0 时返回 −1）。副作用：无。
- 主要调用者：`Train.java:468`、`Train.java:895`；`TrainMotion`、`TickRunner` 中多处。
