# ScriptedTrainRenderer.java（ANTE 脚本）

客户端列车渲染器：外观由资源包脚本决定的列车。每帧把各节车厢的位置交给脚本，并绘制脚本"上一次运行"要求画的内容；与此同时脚本的 `render` 函数在脚本线程上异步再跑一次。由 `ScriptedCustomTrains` 为带脚本的自定义列车创建（`common/src/main/java/cn/zbx1425/mtrsteamloco/data/ScriptedCustomTrains.java:68`）；F3 调试热键重载脚本时通过 `typeScripting` 访问（`cn/zbx1425/mtrsteamloco/mixin/KeyboardHandlerMixin.java:42`）。本分支改动：车厢位姿加入 ANTE 滚转；车厢间连接的渲染矩阵以相机为原点（世界坐标经 `translateToWorld` 转换），给脚本的仍是世界原点位姿。

## 类 `ScriptedTrainRenderer`（extends `TrainRendererBase`）

### 字段
- `typeScripting`（public final）：该车型的脚本宿主（同一车型共享）。
- `baseRenderer`（public final）：可选的底层渲染器（例如 MTR 自带模型），脚本绘制叠加其上；可为 null。
- `train`：所属客户端列车（模板实例为 null）。
- `trainScripting`：本列车的脚本上下文 `TrainScriptContext`（模板为 null）。

### 构造函数 `ScriptedTrainRenderer(ScriptHolderBase typeScripting, TrainRendererBase baseRenderer)`
- 车型模板实例。

### 私有构造函数 `ScriptedTrainRenderer(ScriptedTrainRenderer base, TrainClient trainClient)`
- 列车实例：底层渲染器也创建列车实例；新建 `TrainScriptContext(trainClient)`。

### `TrainRendererBase createTrainInstance(TrainClient trainClient)`（@Override）
- 返回上面的列车实例。调用者：MTR 为每列客户端列车创建渲染器时。

### `void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen)`（@Override）
- 作用：
  1. 不需要跳过渲染时（`RenderUtil.shouldSkipRenderTrain`）先让底层渲染器画；半透明批次到此为止；
  2. `applyAverageTransform` 求车厢平均位置（不可见时为 null）；计算车厢位姿 `carPose`：平移到车厢、加上乘车偏移、绕 Y 转 `π + yaw`、按交通方式是否允许俯仰绕 X 转 `pitch`、绕离轨面 1 格处的 Z 轴转滚转角（列车反向时取反）；
  3. 写入脚本可读的 `TrainWrapper`：左右门开、世界位姿、位置、欧拉角、`shouldRender`、`isInDetailDistance = true`；
  4. 不可见时：若是最后一节，`extraFinished()` 并异步调用脚本 `render`，返回；
  5. 可见且需渲染时：用世界位姿再乘车厢变换得到 `basePose`，在同步块内 `trainScripting.commitCar(...)` 把脚本上次结果提交到 `MainClient.drawScheduler`（带方块/天空光照）；
  6. `matrices.popPose()`（与 `applyAverageTransform` 中的 push 配对，推测）；
  7. 最后一节时 `extraFinished()` 并 `tryCallRenderFunctionAsync`。
- 参数：`carIndex` 车厢序号；`x,y,z` 车厢中心世界坐标；`yaw/pitch` 弧度；左右门是否开。

### `void renderConnection(Vec3 prevPos1..4, Vec3 thisPos1..4, double x, double y, double z, float yaw, float pitch)`（@Override）
- 作用：跳过渲染时返回；先让底层渲染器画；非半透明批次且可见时：取世界原点位姿给脚本；在连接中心按偏航/俯仰建立位姿；同步块内 `scriptResult.commitConn(0, …)`（进调度器）和 `commitConnImmediate(0, matrices, vertexConsumers, 8 个角点, light)`（立即画连接通道）。
- 参数：8 个角点为前后车端的连接面四角（世界坐标）；`x,y,z,yaw,pitch` 连接中心和朝向。

### `void renderBarrier(...)`（@Override）
- 作用：只转给底层渲染器（脚本不画挡板）。
