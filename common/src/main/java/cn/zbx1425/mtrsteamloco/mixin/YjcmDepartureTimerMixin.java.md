# YjcmDepartureTimerMixin.java（mixin，兼容 YJCM）

客户端 mixin（`@Pseudo`），注入附属模组 YJCM（Joban Client Mod 1.2.4）的发车倒计时器渲染器 `fabric.com.jsblock.render.RenderDepartureTimer` / `forge.com.jsblock.render.RenderDepartureTimer` 的 `render` 方法。YJCM 倒计时从已到站的第一列车开始倒数站台停站时间；本分支里列车可能停得更久（例如首站按时刻表等候），在开始最后一段停站时间之前，它会一直显示满停站时间。本 mixin 把还在等待开始倒计时的列车（`ScheduleEntry.waitingToCountDown`）从计时器读取的列表里去掉，使计时器在那之前保持空白，到时从停站时间开始倒数（0.67.3）。按 `render` 中局部变量名 `scheduleList` 挂钩；没有 YJCM 时不改变任何行为（`require = 0`）。

## 类 `YjcmDepartureTimerMixin`

### `private List<ScheduleEntry> mtr$leaveOutWaiting(List<ScheduleEntry> scheduleList)`
- 注入：`@ModifyVariable` 在局部变量 `scheduleList` 赋值时。
- 作用：列表为 null 或没有任何 `waitingToCountDown` 条目时原样返回；否则返回去掉这些条目的新列表。
- 参数/返回：`mtr.data.ScheduleEntry` 列表（客户端时刻数据）。
