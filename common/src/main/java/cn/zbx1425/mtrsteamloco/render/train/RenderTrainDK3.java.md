# RenderTrainDK3.java（ANTE）

客户端：ANTE 内置车型 DK3 有轨电车的渲染器，使用 ANTE 自己的多部件动画模型（`.animated` 车身、`MI` 辅助模型），按停站时间播放车门/车内人物等关键帧动画，并画转向架。由 ANTE `CustomResources` 注册（`common/src/main/java/cn/zbx1425/mtrsteamloco/CustomResources.java:70`、`:83`）；`RenderTrainDK3Mini` 继承它，`RenderTrainD51` 牵引 DK3 时会识别它（`render/train/RenderTrainD51.java:78`）。本分支改动（0.65.30）：停站结束时间按服务器告知的关门时刻（`getDoorsCloseTicksLeft()`）计算，而不是站台轨道的停站时间（时刻表推迟发车时后者太短）；服务器不知道时车门保持打开画面；车厢渲染加入 ANTE 滚转；渲染矩阵以相机为原点，用 `translateToWorld` 转换世界坐标。

## 类 `RenderTrainDK3`（extends `TrainRendererBase`）

### 常量与静态字段
- `models[4]`：共享模型，下标 `MODEL_BODY_HEAD = 0`（车头车身 `dk3/ch.animated`）、`MODEL_BODY_TAIL = 1`（车尾 `dk3/c.animated`）、`MODEL_AUX_HEAD = 2`（`alex/dk3auxhead.json`）、`MODEL_AUX_TAIL = 3`（`alex/dk3auxtail.json`）。
- `MODEL_BOGIE`：MTR 的转向架模型。

### 实例字段
- `train`：所属列车（模板为 null）。
- `updateProp`：多部件模型的更新参数（关键帧时间、隐藏部件等）。
- `scheduleHelper`：MI 动画关键帧播放助手。
- `HIDE_LIST_MIDDLE`（隐藏 `conductor driver cabdoorl cabdoorr`）、`HIDE_LIST_TAIL`（隐藏 `cabdoorlnm cabdoorrnm`）、`HIDE_LIST_HEAD`（隐藏 `cabdoorl cabdoorr`）。
- `elapsedDwellTicks`、`totalDwellTicks`、`lastRenderedTick`：停站计时（同 `DwellTimeBveTrainSound`）。

### `static void initGLModel(ResourceManager resourceManager)`
- 作用：加载 DK3 纹理图集和四个模型到 `MainClient.modelManager`；IO 失败写错误日志。调用者：`cn/zbx1425/mtrsteamloco/CustomResources.java:70`（重载时）。

### `protected MultipartContainer getModel(int index)`
- 返回共享模型；子类可覆盖换模型。

### 构造函数 `RenderTrainDK3(TrainClient train)`

### `TrainRendererBase createTrainInstance(TrainClient trainClient)`（@Override）
- 新建列车实例。

### `void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen)`（@Override）
- 作用：
  1. 需跳过或半透明批次时返回；按是否反向求车号 `carNum`，奇数号或末节为"尾车"（D51 牵引、`spacing == 20` 时第 3 节也为尾车）；不可见（`applyAverageTransform` 返回 null）时返回；
  2. 停站计时：按帧累加，刚开门时重置并读总停站 tick；服务器给出剩余关门 tick 时 `total = elapsed + 20 + 剩余 + DOOR_MOVE_TIME`；否则门开着时至少 `elapsed + 20 + 12×20 + 1`（保持开门画面）；`dwellTicks = total − 20`（去掉开门前 1 秒延迟）；
  3. 门值为 0 时视为两侧都关；反向时左右互换；按停站阶段播放关键帧区间：最后 12 秒播关门动画（左门 6–26 帧区、右门 34–54），前 6 秒播开门（0–6 / 28–34），其余停在开门帧；门关后 8 秒以上回到 0 帧；
  4. 变换矩阵：`translateToWorld` 到 `(x, y-1, z)`、绕 Y 转 `π + yaw`、按交通方式俯仰、在离地 1 格处绕 Z 滚转（反向时符号相反）；反向或车头时再转 180°；
  5. 隐藏部件：人工驾驶时用中间车列表；否则末车/首车/中间车各用对应列表；
  6. 非 Mini 版本：沿车长平移 1 格，D51 牵引时再调整 −1.5 或 −0.5；
  7. 车头画 `MODEL_BODY_HEAD` + `MODEL_AUX_HEAD`，车尾画两个 TAIL 模型，提交到 `MainClient.drawScheduler`；非 Mini 画前后两个转向架（位置取列车属性 `bogiePosition × 16`）；
  8. `scheduleHelper.elapse()`；弹出两层矩阵（其中一层由 `applyAverageTransform` 压入，见 `common/src/main/java/mtr/render/TrainRendererBase.java:109`）。

### `void renderConnection(...)` / `void renderBarrier(...)`（@Override）
- 空实现（DK3 不画车厢连接和挡板）。
