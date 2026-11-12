# MotionCommand.java

司机能对列车下达的指令。宿主（游戏）在一个 tick 内收集按键，在列车移动前通过 `TrainMotion.applyCommand(MotionState, MotionCommand)` 交给列车（解耦文档 3.7）。调用者：`Train.java:875`（按加速/制动键时）。

## 枚举 MotionCommand
- `ACCELERATE`：加速（升一级手柄）。
- `BRAKE`：制动（降一级手柄）。
- `TOGGLE_DOORS`：开关门切换。
具体每个指令对状态的修改见 `TrainMotion.applyCommand`。
