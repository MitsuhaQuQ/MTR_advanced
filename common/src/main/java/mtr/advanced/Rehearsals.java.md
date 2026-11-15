# Rehearsals.java

每个车厂计划的预演（0.83.0，用户 2026-11-15）：车厂运用或其线路时刻表改动并稳定后，在后台用组件 `transitsim.sim.Rehearsal` 仿真这些线路的今天，警告发给所有玩家，显示在高级编辑器同步面板。计划和游戏都不变。开关 `SecurityConfig.rehearsal`（`mtr_security.json` 的 `rehearsal`，默认开）。

## final class Rehearsals implements IPacket

- 常量：`CHECK_MILLIS = 15 s`（每个车厂最多这么久看一次）、`SETTLE_MILLIS = 10 s`（改动后稳定这么久才预演）、`TIME_LIMIT_NANOS = 120 s`、`MAX_LENGTH = 512`（每行最长）、`EXECUTOR`（单线程守护 "MTR-Rehearsal"，最低优先级）、`STATES`（车厂 → 签名、上次预演的签名、改动时刻、上次查看时刻、是否在跑）。
- `static void check(RailwayData railwayData, Level world, Depot depot, boolean resend)`：服务器线程，`RailwayData` 每 100 tick 的车厂循环（`HeldTrains` 之后）调用。开关关：清空并发空表。`resend` 且有警告：重发。签名变了记时刻；稳定且没预演过才跑：运用为空或没有线路时清空；没有线路时清空；运用为空（游戏按时刻表自动排班的车厂，0.83.1）照样预演；`SimExport.build` 拷世界（服务器线程），后台 `Rehearsal.run`（今天 `DutyShapes.getDayStart`，限时），回服务器线程记为已预演（超时也记，打日志 "didn't finish in time"），每次打日志 "the rehearsal of depot … gives N warnings"（0.83.1 起 0 条也打），结果变了存 `Depot.rehearsal`、发包、打日志 "the rehearsal of depot … gives N warnings"。
- `signature`：运用 `toBytes` 的哈希，加各线路 id、时刻表 `revision`、站数。
- `sendS2C(Level, long depotId, List<String>)` / `receiveS2C(FriendlyByteBuf)`：`PACKET_REHEARSAL`，车厂 id、条数（≤ `Rehearsal.MAX_WARNINGS`）、每条一行（`Rehearsal.Warning.encode`）；客户端存 `Depot.rehearsal`。注册：`MTRClient`。
