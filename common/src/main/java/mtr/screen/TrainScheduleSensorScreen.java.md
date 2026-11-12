# TrainScheduleSensorScreen.java（上游文件，本分支有改动）

客户端界面：时刻传感器（`BlockTrainScheduleSensor`）的设置界面——秒数、触发方式、列车种类，以及基类提供的线路过滤等。打开：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:188`。保存由基类 `TrainSensorScreenBase` 发包。

本分支改动：增加触发方式按钮（`StationCall.Trigger`，默认 `LEGACY`）与列车种类按钮（全部/只停站的/只通过的，2026-11-12）；新触发方式下秒数不允许为负。

## 类 `TrainScheduleSensorScreen`（extends `TrainSensorScreenBase`）

### 字段与常量
- `seconds`：方块当前秒数（世界为空为 0，方块不对为 10）。
- `trigger`：触发方式；`trainKind`：列车种类（`KIND_ALL` / `KIND_STOPPING` / `KIND_PASSING`）。
- `MAX_SECONDS_LENGTH = 5`（文本框最大长度）、`DEFAULT_SECONDS = 10`。
- `buttonTrainKind`：种类按钮。

### 构造函数 `TrainScheduleSensorScreen(BlockPos pos)`
- 基类参数：不显示"只在停车时/只在行驶时"复选框（`hasSpeedCheckboxes = false`）、留出一行时间选项（`hasTimingOptions = true`）、一个整数文本框（`gui.mtr.station_trigger_seconds`）。从方块实体读秒数、触发方式与种类。

### `protected void init()`
- 填入秒数；加入触发方式按钮（`addTriggerButton`）；加入种类按钮（点击循环 0→1→2）。

### `protected int getNumber()`
- 文本框解析的秒数（解析失败为 10）；非 `LEGACY` 触发方式时不小于 0。基类保存时调用。

### `protected String[] getTimingSettings()`
- `{trigger.name(), String.valueOf(trainKind)}`，随保存包发送。

### `private Component kindText()`
- 种类按钮文字：`gui.mtr.station_train_kind_stopping` / `_passing` / `_all`。
