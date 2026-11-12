# DoorAnimationType.java（上游文件，本分支有改动）

客户端：列车车门动画方式。给出车门在某一时刻的横向（X，塞拉门向外移出）与纵向（Z，沿车身滑动）位移。调用者：`common/src/main/java/mtr/model/ModelTrainBase.java:47`–`:50`（每次渲染车厢）；各模型在构造时选择类型（如 `ModelA320.java:308` 用 `PLUG_SLOW`）；资源包车辆由 `CustomResources` 读取；`ResourcePackCreatorProperties.java:114` 列出所有值。

本分支改动：`PLUG_SLOW` 的 `maxTime` 由 2 改为 1；`PLUG_SLOW` 的外移改为线性；`PLUG_FAST` 与 `PLUG_SLOW` 的滑动改为匀速（`PLUG_SLOW` 开门在 0.05–0.5、关门在 0.05–0.8 的进度区间内一次滑完，注释称开 1.6 秒、关 2.4 秒、共 4 秒）。

## 枚举 `DoorAnimationType`

### 值（括号内为 `maxTime`）
- `STANDARD(0.5)`、`STANDARD_SLOW(0.5)`、`CONSTANT(0.5)`、`PLUG_FAST(0.5)`、`PLUG_SLOW(1)`、`BOUNCY_1(0.5)`、`BOUNCY_2(0.5)`、`MLR(0.7)`、`R179(0.6)`、`R211(0.7)`。

### 字段 `maxTime`
- 车门进度值的上限：资源包制作界面预览时车门进度增加到此为止（`common/src/main/java/mtr/screen/ResourcePackCreatorScreen.java:445`）。单位与 `value` 相同（进度，按 `1 / Train.DOOR_MOVE_TIME` 每 tick 递增）。

### `static float getDoorAnimationX(DoorAnimationType, float value)`
- 横向位移：`PLUG_FAST`：`value < 0.05` 时 `-value*20 - 0.01`，否则 `-1.01`；`PLUG_SLOW`：在 0–0.1 内从 −0.01 线性到 −1.01；其他为 0。
- 参数：`value` 车门进度。返回：位移（模型单位，负为向外，具体方向由模型决定）。

### `static float getDoorAnimationZ(DoorAnimationType, int doorMax, float duration, float value, boolean opening)`
- 纵向位移：
  - `CONSTANT`：`doorMax * clamp(value,0,1) / duration`。
  - `PLUG_FAST`：在 0–`duration` 内线性到 `doorMax`。
  - `PLUG_SLOW`：开门 0.05–0.5、关门 0.05–0.8 内线性到 `doorMax`。
  - `BOUNCY_1`、`BOUNCY_2`、`R179`：分段余弦缓动，开门末端有回弹，关门时先停在 1–3 处（模拟门夹/回弹）。
  - `MLR`：开门 0.2–0.7 内线性；关门在接近关闭时停在 1.5 处（`stoppingPoint`）再关闭。
  - `R211`：0.2–0.7 内线性（开关相同）。
  - `STANDARD_SLOW`：关门时先在 0–0.1 移到 1，0.1–0.2 停在 1，之后缓动到 `doorMax`；开门同默认。
  - 默认（`STANDARD` 等）：0–`duration` 内余弦缓动到 `doorMax`。
- 参数：`doorMax` 门最大开度（模型单位）；`duration` 动作时长（进度单位，`ModelTrainBase.getDoorDuration()`）；`value` 进度；`opening` 是否在开门。

### `private static float linear(startValue, endValue, startTime, endTime, time)`
- 在时间区间内线性插值，区间外取端值。

### `private static float smoothEnds(startValue, endValue, startTime, endTime, time)`
- 余弦缓动（两端速度为 0），区间外取端值。
