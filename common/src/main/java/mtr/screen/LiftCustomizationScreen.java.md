# LiftCustomizationScreen.java（上游文件，本分支有改动）

客户端界面：电梯设置——轿厢高、宽、深，偏移 x/y/z，最高速度、加速度，双面门，朝向旋转（样式按钮已创建但未加入界面）。关闭时发送电梯附加数据。打开：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:409`。

本分支改动：新增最高速度与加速度的 −/+ 按钮（速度每步 0.025 m/tick = 0.5 m/s，加速度每步 0.001 m/tick² = 0.4 m/s²，范围由 `Lift.clampMaxSpeed`/`clampAcceleration` 限制），其他控件下移；继承 `ScreenBase`。

## 类 `LiftCustomizationScreen`（extends `ScreenBase`，implements `IGui`, `IPacket`）

### 字段与常量
- `lift`：被编辑的 `LiftClient`（直接修改其字段）。
- 各 −/+ 按钮、`buttonIsDoubleSided`、`buttonLiftStyle`、`buttonRotateAnticlockwise`、`buttonRotateClockwise`。
- `width1`：−/+ 两按钮之间文字区宽度；`width2 = width1 + SQUARE_SIZE`（面板宽度）。
- `MIN_DIMENSION = 2`、`MAX_DIMENSION = 16`、`MAX_OFFSET = 16`、`MAX_SPEED_STEPS = 40`、`ACCELERATION_STEPS = 1000`。

### 构造函数 `LiftCustomizationScreen(LiftClient lift)`
- 按钮动作：高度以半格为单位（`liftHeight` 范围 4…32，显示时 /2）；宽、深 2…16；偏移 x、z 以半格为单位（−32…32，显示时 /2），偏移 y −16…16；速度与加速度按步长增减后限制；双面复选框；样式循环；左/右旋转朝向。宽度按最长文字计算。

### `protected void init()`
- 左侧一列：每行 − 按钮在 0、+ 按钮在 `width1`；第 10–13 行为双面、（样式，未加入）、两个旋转按钮。

### `void render(…)`
- 左侧半透明背景；每行中间显示数值：高度（格）、宽、深、偏移、最高速度（`gui.mtr.lift_max_speed`，m/s = 值 × 20）、加速度（`gui.mtr.lift_acceleration`，m/s² = 值 × 400）。

### `void onClose()`
- 发送 `lift.setExtraData` 的 `PACKET_UPDATE_LIFT`。

### `boolean isPauseScreen()`
- false。

### `private void updateControls()`
- 到达范围边界时禁用对应按钮；同步双面复选框与样式文字。

### `private static Component getMaxSpeedText(double maxSpeed)` / `private static Component getAccelerationText(double acceleration)`
- 参数单位：m/tick、m/tick²；显示保留一位小数。
