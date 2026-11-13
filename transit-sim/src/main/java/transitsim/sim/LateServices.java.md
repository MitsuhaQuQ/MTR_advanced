# LateServices.java（0.78.0 新增）

车厂的晚点改挂登记（用户 2026-11-14：晚点超过 10 分钟改挂同线路最近一班还赶得上、且没人跑的班次）。一个班次由线路、所在运用段的起点站（`fromStop`）和班次时间（完整时间戳）确定。由 `DepotDispatcher.lateServices` 持有，宿主按 `toList()` 存档（组件 `RunRecord` 版本 7、游戏 `Depot` 的 `late_services`）。

- 已放弃（`giveUp`）：原车到段首站时晚点超过 10 分钟并改挂了别的班次，本班未运行。
- 预计赶不上（`expect`，按车号）：原车途中晚点超过 10 分钟；追回后撤销。
- 已接走（`take`）：被另一列车接走，不再给别的车；原车到站时发现后自己再找。
- `findFree(...)`：在已放弃和预计赶不上中找比 `afterMillis` 晚、在 `[fromMillis, untilMillis]` 内、未被接走的最早一班。
- `prune(now)`：清掉一天以前的。
- `toList()` / `addAll(list)`：每行 `g 线路:起点站:时间`、`e 线路:起点站:时间 车号`、`t 线路:起点站:时间`，顺序固定；不认识的行跳过。
