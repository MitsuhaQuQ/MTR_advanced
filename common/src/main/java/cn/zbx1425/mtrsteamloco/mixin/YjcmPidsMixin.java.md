# YjcmPidsMixin.java（mixin，兼容 YJCM）

客户端 mixin（`@Pseudo`），注入 YJCM（Joban Client Mod 1.2.4）的乘客信息屏渲染器 `RenderRVPIDS`、`RenderLCDPIDS`（Fabric 与 Forge 包名各一）的 `render` 方法。YJCM 对已到站列车的时间栏留空；本 mixin 让停在站台、且有已知或计划发车时间（`ScheduleEntry.departureMillis`）的列车显示"发车时间 HH:mm"，与本模组自己的 PIDS 一致（0.67.2）。按局部变量名 `currentSchedule`、`arrivalText` 挂钩；没有 YJCM 或版本方法不同时不改变行为（`require = 0`）。

## 类 `YjcmPidsMixin`

### 字段
- `mtr$DEPARTURE_TIME`（static）：`HH:mm` 格式。
- `mtr$currentSchedule`：最近一次读到的当前行时刻条目（实例字段，合并进 YJCM 渲染器对象；按行逐次覆盖）。

### `private ScheduleEntry mtr$keepSchedule(ScheduleEntry entry)`
- 注入：`@ModifyVariable` 在 `currentSchedule` 赋值时。
- 作用：记下该条目并原样返回。

### `private Component mtr$departureText(Component arrivalText)`
- 注入：`@ModifyVariable` 在 `arrivalText` 赋值时。
- 作用：仅当 YJCM 给出的到站文字为 null（已到站）、有条目、`departureMillis != 0` 且到站时间不晚于当前时间时，返回 `gui.mtr.departure_time` 翻译文字，参数为 `|departureMillis|` 在运营时区（`OperatingDay.getTimeZone()`）的 `HH:mm`；否则原样返回。`departureMillis` 取绝对值，负值的含义（推测为"计划值"标记）未确定。
