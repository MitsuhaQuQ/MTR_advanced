# TrainSensorScreenBase.java（上游文件，本分支有改动）

客户端界面基类：列车传感器类方块（时刻传感器、列车广播器等）的设置界面——若干文本框、"只在停车时/只在行驶时"复选框、线路过滤（打开线路选择界面），关闭时把设置发给服务器。子类：`common/src/main/java/mtr/screen/TrainScheduleSensorScreen.java`、`common/src/main/java/mtr/screen/TrainAnnouncerScreen.java:38`。打开前检查：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:183`。

本分支改动：可选的"时间选项"行（`hasTimingOptions`，`optionsY`）和触发方式按钮（`addTriggerButton`，`StationCall.Trigger`）；保存包附加子类的 `getNumber()` 与 `getTimingSettings()`；继承 `mtr.compat.ScreenBase`。

## 抽象类 `TrainSensorScreenBase`（extends `ScreenBase`，implements `IGui`, `IPacket`）

### 字段
- `stoppedOnly`、`movingOnly`：两个互斥的复选框状态。
- `pos`：方块位置；`textFields`、`textFieldLabels`、`textFieldCount`。
- `optionsY`：时间选项行的 y（无文本框时为 `SQUARE_SIZE`，否则在文本框下方）。
- `filterRouteIds`：过滤的线路 id（直接引用方块实体的集合，由选择界面修改）。
- `stoppedOnlyCheckbox`、`movingOnlyCheckbox`、`filterButton`、`hasSpeedCheckboxes`、`yStart`（过滤区起始 y）。

### 构造函数
- `TrainSensorScreenBase(BlockPos pos, boolean hasSpeedCheckboxes, Tuple<WidgetBetterTextField, Component>... textFieldsAndLabels)`：无时间选项。
- `protected TrainSensorScreenBase(BlockPos pos, boolean hasSpeedCheckboxes, boolean hasTimingOptions, Tuple<…>...)`：从方块实体读线路过滤与两个复选框；过滤按钮打开 `DashboardListSelectorScreen`（全部线路排序后）。

### `protected void init()`
- 文本框均分宽度排一行；有复选框时放在过滤区上方；"编辑"过滤按钮。

### `void tick()`
- 文本框光标闪烁。

### `void render(PoseStack, int, int, float)`
- 文本框标签、过滤线路数与条件说明、每条过滤线路名；子类附加内容 `renderAdditional`。

### `void onClose()`
- 发送 `PacketTrainDataGuiClient.sendTrainSensorC2S(pos, 过滤线路, stoppedOnly, movingOnly, getNumber(), [文本框值…, 时间设置…])`。

### `boolean isPauseScreen()`
- false。

### `protected void renderAdditional(PoseStack)` / `protected int getNumber()` / `protected String[] getTimingSettings()`
- 子类可覆盖：额外绘制；数值（默认 0）；附加字符串（默认空）。

### `protected void addTriggerButton(Supplier<StationCall.Trigger> getTrigger, Consumer<StationCall.Trigger> setTrigger, int buttonWidth)`
- 在 `optionsY` 处加按钮，点击循环所有触发方式，文字 `gui.mtr.station_trigger_<小写名>`。

### `private static Component triggerText(StationCall.Trigger)`
- 上述文字。

### `private void setChecked(boolean newStoppedOnly, boolean newMovingOnly)`
- 两项互斥：勾选一项时取消另一项，并同步复选框。
