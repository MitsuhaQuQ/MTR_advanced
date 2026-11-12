# MotionState.java

列车的运动状态，保存在宿主处（解耦文档 2.2）：运动规则通过本接口读写，因此读取宿主字段的附加模组看到的值和以前一样。实现：游戏侧 `Train.Motion`（`Train.java:1288`）、组件 `TickRunner.HeadlessTrain`（`TickRunner.java:1680`）。使用者：`TrainMotion`、`PlatformDwell`、`RunMotion`。

## 接口 MotionState
各 getter/setter 成对，setter 的副作用都是改写宿主对应字段。

- `getSpeed` / `setSpeed(float)`：速度，方块/tick。
- `getRailProgress` / `setRailProgress(double)`：车头沿路径的位置（方块）。
- `getDoorTarget` / `setDoorTarget(boolean)`：车门目标状态（true = 开）。
- `getDoorValue` / `setDoorValue(float)`：车门开度（动画值）。
- `getElapsedDwellTicks` / `setElapsedDwellTicks(float)`：本次停站已过 tick 数。
- `getNextStoppingIndex` / `setNextStoppingIndex(int)`：下一停站（或停车点）的轨道下标。
- `getNextPlatformIndex` / `setNextPlatformIndex(int)`：下一站台的轨道下标。
- `isReversed` / `setReversed(boolean)`：列车是否反向（车头车尾互换）。
- `isOnRoute` / `setOnRoute(boolean)`：是否已离开停车线在线路上运行。
- `isCurrentlyManual()`：当前是否手动驾驶。
- `getManualNotch` / `setManualNotch(int)`：司机手柄档位：正数加速、负数制动；−2 表示开着门，−3 表示为进站制动。
- `getRunSegment` / `setRunSegment(RunSegment)`：新规则下列车所在的运行段，可为 null；不保存，没有的列车从当前位置新开一段。
