# RunReservations.java

防止按运用运行的列车对头相遇（设计文档 6.12 E2 块，以及架构审查 R01–R03 的修复 F1）。列车连同方向持有从车尾到所获运行区间终点的双向轨道；其中任何一条被以另一方向持有时，其他列车不会获得经过它的运行。同向列车不互相阻挡，由信号闭塞负责。

持有不会因停车而放弃：在站等待或在站间被信号挡住的列车保留所持。宿主每 tick 设定每列车持有的内容，所以轨道只有在车尾离开后才释放；已离开（不再设定任何东西）的列车在 tick 结束时放弃全部。

准备离站的列车申请下一段运行的轨道。同一 tick 的申请在其结束后一起按固定顺序处理，结果与列车运行顺序无关：等待 ≥ `AGING_MILLIS` 的优先（等得越久越先），其余按计划发车时刻，再按列车。超龄请求无法批准期间，不让尚未在其轨道上的列车以相反方向进入，这样已在上面的列车会离开，反方向源源不断的列车不能让它永远等下去。

实现上是 `Arbiter<UUID>` 的包装：每条双向轨道是仲裁器的一个带方向的单线资源（S4b），另有道岔、联锁区和运行层的分区声明。

在整体中的位置：游戏侧 `RailwayData.java:160` 持有一个（`:334`/`:617` `resolve`、`:619` `takeDeadlocks`），`TrainServer`（`:1096` `hold`、`:1405` `request`、`:1384` `takeGrant`、`:1094` `getHeld`、`:1078` `release`、`:1702` `isBlockingOthers`、`:1861` `isHeldByOther`）；组件 `TickRunner.java:62`、`ThroatLocks`（`:162`、`:178`、`:84`、`:193`）、`run.Authority`（`holdClaims`、`requestGroups`、`canHoldClaims`、`takeGrantedGroups`、`peekGrantedGroups`、`isHeldByOther`、`release`）。

## 类 RunReservations（final）

### 字段与常量
- `arbiter`（private `Arbiter<UUID>`）。
- `AGING_MILLIS`（= `Arbiter.AGING_MILLIS`，60 秒）：等待多久后优先于计划更早发车的列车。

### `boolean isBlockingOthers(long trainId)`
上次 resolve 中该列车是否阻碍了别人的申请。调用者：`TrainServer.java:1702`、`TickRunner.java:2583`。
（源码在此方法前有一段孤立 javadoc "设定列车本 tick 持有的内容，替换之前的"，描述的是 `hold`。）

### `void hold(long trainId, List<Move> moves)`
设定列车本 tick 持有的轨道/道岔/联锁区（替换之前的），`toClaims` 转换。调用者：`TrainServer.java:1096`、`TickRunner.java:557`、`TickRunner.java:2504`。

### `void holdClaims(long trainId, List<Arbiter.Claim<UUID>> claims)`
按运行层（5.5）的声明形式设定持有（分区、方向、道岔、联锁区）。调用者：`Authority.java:148`。

### `void requestClaims(long trainId, List<Arbiter.Claim<UUID>> claims, long plannedMillis)`
按运行层形式申请下一 tick 起持有的内容（整体批准或不批）。仓库内未找到外部调用者。

### `void setOccupied(long occupant, Collection<UUID> resources)`
运行层不移动的东西（外部占用的轨道、宿主驾驶的列车）当前所在，替换之前的；不会把这些资源批给任何列车。调用者：`TickRunner.java:497`。

### `void requestGroups(long trainId, List<List<Arbiter.Claim<UUID>>> groups, long plannedMillis)`
按到达顺序申请多组，尽可能多地按序批准前面的组。调用者：`Authority.java:154`。

### `boolean canHoldClaims(long trainId, List<Arbiter.Claim<UUID>> claims)`
现在能否持有这些声明（没有别的持有或占用与之冲突）。调用者：`Authority.java:126`。

### `int peekGrantedGroups(long trainId)` / `int takeGrantedGroups(long trainId)`
上次分组申请获批的组数（不取走 / 取走，只返回一次）。调用者：`Authority.java:210`、`Authority.java:101`。

### `boolean isHeldByOther(long trainId, UUID resource)`
是否有其他列车以任意方式持有该资源（或有占用者）。调用者：`Authority.java:169`、`TickRunner.java:3170`。

### `void release(long trainId)`
放弃列车所持一切（如回到车厂）。调用者：`TrainServer.java:1078`、`Authority.java:194`。

### `boolean canHold(long trainId, List<Move> moves)`
若有别的列车以相反方向持有其中某条轨道则 false。调用者：`FrequencyDeployTest.java:211`。

### `void request(long trainId, List<Move> moves, long plannedMillis)`
申请下一段运行的轨道，在 tick 结束后决定；仍在等待的列车每 tick 重新申请，不再申请即被放弃。调用者：`TrainServer.java:1405`、`ThroatLocks.java:178`、`TickRunner.java:2553`。

### `boolean takeGrant(long trainId)`
申请获批后的下一个 tick 返回一次 true（此时已持有所申请轨道）。调用者：`TrainServer.java:1384`、`ThroatLocks.java:162`。

### `void resolve(long currentMillis)`
结束一个 tick：已离开的列车放弃所持，然后处理本 tick 的申请（`Arbiter.resolve`）。调用者：`RailwayData.java:334`、`TickRunner.java:1056`。

### `Set<Long> getHolders()`
持有轨道的列车（有序）。调用者：`TickRunner.java:3243`、`PlanCheckTest.java:152`。

### `boolean isHeldByOther(long trainId, List<UUID> rails)`
是否有其他列车以任意方向持有其中某条轨道，或持有位于这些轨道端点节点处的道岔（联锁区不计）。调用者：`TrainServer.java:1861`。

### `List<Move> getHeld(long trainId)`
列车持有内容转回 `Move`：双向单线声明展开为两个方向；`TRACK` 声明视为联锁区；`TURNOUT` 为道岔；其他为单线方向。调用者：`TrainServer.java:1094`、`ThroatLocks.java:84`；本类 `isHeldByOther(long, List)`。

### `List<Set<Long>> takeDeadlocks()`
取走自上次以来发现的死锁（各一次）。调用者：`RailwayData.java:619`、`EventSimulator.collectEvents`。

### `static List<Arbiter.Claim<UUID>> claimsOf(List<Move> moves)`
`Move` 转仲裁器声明（公开版 `toClaims`）。调用者：`TickRunner.java:2054`。

### `private static List<Arbiter.Claim<UUID>> toClaims(List<Move> moves)`
联锁区 → `Claim.track`；道岔 → `Claim.turnout(route)`；轨道 → `Claim.singleTrack(方向)`；同一列车对同一轨道申请两个方向（如在该站折返）时合并为一个双向声明（每个资源一列车只有一个声明，否则后者会覆盖前者）。

## 内部类 Move（public static final）
沿一个方向运行的一条轨道，或以某进路占用的站咽喉道岔（L2，`ThroatLocks`），或一个联锁区。forwards 表示从轨道键较小节点到较大节点。
- 常量 `TURNOUT_MARK`、`ZONE_MARK`（private）：道岔/联锁区键低 64 位的标记，轨道键不会取这些值。
- `rail`（UUID）：轨道键，或道岔/联锁区键。
- `forwards`（boolean）、`turnout`（boolean）、`zone`（boolean：联锁区，同一时间只给一列车，不论方向；联锁颜色的信号闭塞区）、`way`（int：道岔的进路，同进路列车共享）。

方法：
- `Move(long fromPos, long toPos)`：从节点对构造轨道方向。调用者：`TrainServer.java:1424`、`TickRunner.java:2563`。
- `Move(UUID rail, boolean forwards)`：直接给定轨道与方向。调用者：`getHeld`。
- 私有 `Move(UUID resource, int way)`（道岔）、`Move(UUID resource)`（联锁区）。
- `static Move zone(long zoneId)`：按 id 构造整个联锁区。调用者：`ThroatLocks.java:193`。
- `private static Move zoneOf(UUID resource)`：由资源键构造联锁区。
- `static Move turnout(long node, long side1, long side2)`：以两侧节点（顺序无关）确定进路的道岔；进路值为两侧节点哈希且最低位置 1。调用者：`ThroatLocks.java:193`、`ThroatLocksTest.java:38`。
- `equals`/`hashCode`：按全部字段（hashCode 不含 turnout/zone 标志）。调用者：`LiveSimulation.java:233` 等比较运行变化。
