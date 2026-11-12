# RenderDrivingOverlay.java

客户端：人工驾驶列车时屏幕底部的驾驶面板（牵引/制动档位 B2…P2、车门状态、速度、本站/下一站/线路/终点站）。Fabric 在 HUD 回调中调用（`fabric/src/main/java/mtr/MTRFabricClient.java:30`），Forge 经 `ForgeUtilities.renderGameOverlayAction(RenderDrivingOverlay::render)`（`forge/src/main/java/mtr/MTRForge.java:69`）；数据由 `TrainClient` 每帧设置（`common/src/main/java/mtr/data/TrainClient.java:363`）。本分支改动：所有绘制改用 `GuiCompat`（1.20 的 `GuiGraphics`/`PoseStack` 兼容）；新增 `render(Object)` 入口；在这里顺带调用 `RenderTrainDebug.render`（刷子看向列车时的调试信息，0.65.31），使两个加载器都会绘制它。

## 类 `RenderDrivingOverlay`（implements `IGui`）

### 静态字段
- `accelerationSign`（−2…2：B2、B1、N、P1、P2）、`doorValue`（0 关、1 全开、之间为开关中）、`speed`（格/秒，显示时 ×3.6 为 km/h）、`thisStation`、`nextStation`、`thisRoute`、`lastStation`（已格式化的名称或 null）、`coolDown`（剩余显示帧数）。
- 常量：`HOT_BAR_WIDTH = 182`、`HOT_BAR_HEIGHT = 22`、`WIDGETS_LOCATION = textures/gui/widgets.png`。

### `static void render(Object matrices)`
- 接受 `GuiGraphics` 或 `PoseStack`（`GuiCompat.getPose`），转调下一个。

### `static void render(PoseStack matrices)`
- 作用：先 `RenderTrainDebug.render(matrices)`；`coolDown > 0` 时减 1 并绘制，否则不画（`setData` 每帧把它设为 2，停止驾驶后两帧内消失）。绘制：在快捷栏上方位置用原版快捷栏纹理拼出面板与两个选框（档位、车门），写档位文字（当前档且门关时白色）、DC/开度/DO，左侧显示速度，右侧显示本站、`> 下一站`、线路、`> 终点站`。

### `static void setData(int accelerationSign, TrainClient trainClient)`
- 作用：记录档位、门值、速度（`getSpeed() × 20`）及车站/线路名称（下一站为空时用下一线路名），`coolDown = 2`。
