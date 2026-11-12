# OperatingDay.java

运营日与时区。时间统一以 UTC 保存；时区只决定显示方式以及某班属于星期几，因此不同地方的玩家看到同一份时刻表。运营日在每天 `dayStartHour`（本地时）切换，凌晨后的末班车仍属于前一运营日。宿主从设置读取时区与运营日开始小时并调用 `set`：游戏侧 `mtr.data.TimetableConfig`、工具 `transitsim.tools.SimulateDay`。组件内 `DutyShapes` 用它换算服务时刻。

## 类 `OperatingDay`（final，全部静态）

### 常量与静态字段
- `MILLIS_PER_DAY = 86 400 000`。
- `DEFAULT_DAY_START_HOUR = 3`：默认凌晨 3 点切换运营日。
- `HOURS_PER_DAY`、`MILLIS_PER_HOUR`、`DAYS_PER_WEEK`（私有）。
- `FIRST_DAY_WEEKDAY = 3`：Unix 纪元第 0 天是星期四，从星期一 = 0 起算为 3。
- `timeZone`（volatile，默认 UTC）、`dayStartHour`（volatile，默认 3）：全局设置，进程内共享。

### `private OperatingDay()`
禁止实例化。

### `public static void set(String newTimeZone, int newDayStartHour)`
- 作用：设置时区与运营日开始小时。
- 参数：`newTimeZone` 形如 `Asia/Shanghai` 或 `+08:00`；`newDayStartHour` 小时，取 `floorMod(…, 24)`。
- 副作用：修改全局静态字段；时区非法时 `ZoneId.of` 抛 `DateTimeException`。
- 主要调用者：`common/src/main/java/mtr/data/TimetableConfig.java:67`、`transitsim/tools/SimulateDay.java:61`。

### `public static ZoneId getTimeZone()` / `public static int getDayStartHour()`
返回当前设置。主要调用者：`common/src/main/java/mtr/data/TimetableConfig.java:71`、`:75`。

### `public static long getTimeInDay(long millis)`
- 作用：返回该时刻距其运营日开始多少毫秒，使各班按列车实际运行顺序排序。
- 参数：`millis` UTC 毫秒（可以是一天内的时刻，也可以是绝对时间）。
- 返回值：`[0, MILLIS_PER_DAY)`。
- 注意：时区偏移取的是 **当前时刻（`Instant.now()`）** 的偏移，而非 `millis` 时刻的偏移；夏令时切换前后可能有一小时差异（代码如此，是否有意未确定）。
- 主要调用者：`transitsim/plan/DutyShapes.java:185`、`DutyShapes.java:192`、`common/src/main/java/mtr/data/TimetableConfig.java:86`。

### `public static int getDayStartMillis()`
- 返回值：运营日开始时刻在 UTC 中是一天中的第几毫秒，`[0, MILLIS_PER_DAY)`。同样用当前偏移。
- 主要调用者：`common/src/main/java/mtr/data/TimetableConfig.java:93`。

### `public static int getWeekday(long millis)`
- 作用：该时刻所属运营日是星期几（0 = 星期一）。这里用 `millis` 当时的时区偏移。
- 参数：`millis` 绝对 UTC 毫秒。
- 主要调用者：`common/src/main/java/mtr/advanced/SandboxRuns.java:281`、`mtr/data/PidsSchedules.java:41`、`PidsSchedules.java:64`。

### `private static long getOffsetMillis()`
当前时刻时区相对 UTC 的偏移毫秒。
