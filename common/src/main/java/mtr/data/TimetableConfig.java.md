# TimetableConfig.java

时刻表时区设置。时间一律以 UTC 保存；时区只决定显示方式和一个班次属于星期几，使不同地方的玩家看到同一张时刻表。设置来自配置目录下的 `mtr_timetable.json`，服务端发给玩家（`PacketTrainDataGuiServer.java:82-83` 发送，`PacketTrainDataGuiClient.java:108` 接收后 `set`）。所有时间计算都委托给组件的 `transitsim` `OperatingDay`，本类只负责读写文件。

## final class TimetableConfig

### 字段与常量
- `configPath`（volatile static）：配置文件路径，由 `load` 设置。
- `FILE_NAME = "mtr_timetable.json"`。
- JSON 键：`KEY_TIME_ZONE = "timeZone"`（如 `Asia/Shanghai` 或 `+08:00`）、`KEY_DAY_START_HOUR = "dayStartHour"`（运营日分界的小时，缺省 `OperatingDay.DEFAULT_DAY_START_HOUR`）。

### `private TimetableConfig()`
工具类，禁止实例化。

### `public static void load(Path path)`
- 作用：记住路径并 `reload()`。
- 主要调用者：`MTR.java:590`；测试 `TimetableConfigTest.java:29`。

### `public static void reload()`
- 作用：文件存在则读取并 `set`；不存在则用本机时区和默认分界小时 `set`，并写出一个格式化的新文件（创建父目录）。读取失败打印 "Failed to read ... timetable settings" 与堆栈，设置保持原样。
- 主要调用者：`DashboardScreen.java:86-87`。

### `public static void set(String newTimeZone, int newDayStartHour)`
- 作用：`OperatingDay.set(...)`，修改全局时区与分界小时。
- 主要调用者：`PacketTrainDataGuiClient.java:108`；多处测试。

### `getTimeZoneId()` / `getTimeZone()` / `getDayStartHour()`
- 返回：当前时区 id 字符串 / `java.util.TimeZone` / 分界小时。
- 主要调用者：`PacketTrainDataGuiServer.java:82`（id）、`TrainServer.java:1260`、`EditDepotScreen.java:466`（TimeZone）、`EditorData.java:68`（小时）。

### `public static long getTimeInDay(long millis)`
- 委托 `OperatingDay.getTimeInDay`（含义见组件文档）。
- 主要调用者：`EditDepotScreen.java:465`、`TimetableScreen.java:897`。

### `public static int getDayStartMillis()`
- 委托 `OperatingDay.getDayStartMillis`。
- 主要调用者：`EditorData.java:63`、`EditorDuties.java:299`、`Depot.java:1199`。

### `public static int getWeekday(long millis)`
- 委托 `OperatingDay.getWeekday`（运营日所属星期几）。
- 主要调用者：测试 `TimetableConfigTest.java:37` 等；游戏代码调用处未逐一确认。
