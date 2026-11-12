# OperationTime.java

规划器的周时间：时刻表每周重复。"周偏移"是从服务器挂钟周一 00:00 起的毫秒，超过 `WEEK_MILLIS` 的值属于下一周。提供时间常量和周偏移换算。被 `DutyPlanner`、`DutyCheck` 使用（主要用常量与 `normalize`）。

## 类 `OperationTime`（final，工具类）

### 常量
- `SECOND_MILLIS`、`MINUTE_MILLIS`、`HOUR_MILLIS`、`DAY_MILLIS`、`WEEK_MILLIS`：时间长度（毫秒）。
- `ALL_DAYS = 0b1111111`、`WEEKDAYS = 0b0011111`（周一至周五）、`WEEKENDS = 0b1100000`（周六、日）：星期位掩码，最低位为周一。

### `private OperationTime()`
禁止实例化。

### `public static long weekOffset(long epochMillis, ZoneId zone)`
- 作用：绝对时间在 `zone` 时区挂钟上距本周一 00:00 的毫秒。
- main 代码中 grep 未见调用（未确定是否仅测试使用）。

### `public static long plannedTimestamp(long weekAnchorEpochMillis, ZoneId zone, long weekOffset)`
- 作用：把周偏移换算为完整时间戳，所在周为包含 `weekAnchorEpochMillis` 的那一周（`zone` 挂钟）。
- main 代码中 grep 未见调用（未确定）。

### `public static long normalize(long weekOffset)`
- 返回值：`floorMod(weekOffset, WEEK_MILLIS)`。
- 主要调用者：`transitsim/planning/DutyPlanner.java:848`。

### `private static LocalDate mondayOf(LocalDate date)`
该日期所在周的周一（含当天）。
