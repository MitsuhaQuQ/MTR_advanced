# Authority.java

列车的行车许可（运行层，解耦文档 5.5）：允许它沿路径运行多远。每个 tick 它持有车身所在和已获批的前方资源，并为前往目标的下一组资源提出申请。仲裁器（`Arbiter`，经 `RunReservations`）在 tick 末对一组资源要么全部批准、要么都不批准，因此许可随前方逐组空出而一组组延伸。车身以外不持有未经检查的东西。

在整体中的位置：组件 `TickRunner.java:2060`（无头列车每 tick `update`）、游戏侧 `TrainServer.java:1468`/`:1495`（手动驾驶列车的许可）。使用 `PathClaims`（把路径区间转成资源组）、`RunReservations`（持有/申请/释放）、`BlockSections`、`Arbiter.Claim`。

## 类 Authority（final）

### 字段
- `holder`（long）：持有者（列车 id）。
- `reservations`（`Supplier<RunReservations>`）：当前的预留表（宿主可能与运行器共享自己的）。
- `grantedEnd`（double）：沿路径已获批到的位置；NaN 表示从头开始。
- `pathKey`（Object）：位置所沿的路径标识；换路径则许可重新开始。
- `pending`（`PathClaims.Group`）：正在等待（未获批）的第一组。
- `asked`（List<Group>）：本 tick 申请的组，按序。
- `restoredEnd`（double）：从记录续跑时记录中的许可终点，否则 NaN。
- `held`（List<Claim<UUID>>）：当前持有的声明。
- `margin`（double）：与其他列车前后保持的间隔（用户 2026-11-12：约一节车厢长）：车尾后也持有这么多，并在未获批分区前这么远处停车，使在轨道交汇处相遇的两车不会顶在接缝上。0 为无。
- `MARGIN`（public static final boolean）：系统属性 `mtr.trainMargin=false` 时为 false，不保留间隔（用于对比旧行为）。

### `void setMargin(double margin)`
设置间隔（方块，车厢长），`MARGIN` 关闭时为 0，负数取 0。调用者：`TickRunner.java:2059`、`AuthorityTest.java:108`。

### 构造函数 `Authority(long holder, RunReservations reservations)` / `Authority(long holder, Supplier<RunReservations> reservations)`
前者包装为常量 Supplier。调用者：`TrainServer.java:1468`、`AuthorityTest.java:67`；`TickRunner`。

### `<T extends RunTrack> double update(PathClaims.Path<T> path, Object pathKey, double tail, double head, double target, BlockSections sections, long plannedMillis)`
无咽喉附加项的便捷形式。调用者：`TrainServer.java:1495`。

### `<T extends RunTrack> double update(PathClaims.Path<T> path, Object pathKey, double bodyTail, double head, double target, BlockSections sections, long plannedMillis, List<PathClaims.Attached> attached)`
- 作用：持有车身与已获批部分，并申请前往 `target` 的后续组。
  1. 实际车尾 = `bodyTail − margin`（不小于 0）。路径变化或首次时为"新开始"：`grantedEnd = head`；若有续跑记录则 `grantedEnd = max(head, restoredEnd)` 且不按新开始处理。
  2. 从预留表取上一 tick 末获批的组数（`takeGrantedGroups`），把 `grantedEnd` 推进到最后获批组的终点；清空申请；目标不超过车头时 `grantedEnd = head`。
  3. 保留组 = 车尾到车头间的组（`PathClaims.between`）+ 跨越车身范围的附加项（咽喉道岔/联锁区）；前方组 = 车头到目标间的组，并附上前方附加项（`PathClaims.attach`）。
  4. 新开始时（重启后或换路径）：立即尽量取得前方能持有（`canHoldClaims`）且畅通（`isClear`）的组，使从记录续跑与持续运行一致。
  5. 逐个前方组：在 `grantedEnd` 内且畅通的继续保留；第一个不满足的组成为 `pending`——若之前已获批但现在被别人占用（如外部占用轨道），许可退回到它的起点（如同信号变红），其后所有组加入申请。
  6. `holdClaims` 持有保留的声明；有申请时 `requestGroups`（按 `plannedMillis` 决定同时申请时的先后），一次可批多组，使许可只取决于当前空闲情况而非申请了几个 tick。
  7. 可达位置：无 pending 时到目标；否则到 `max(grantedEnd, pending.start) − margin`。
- 参数：`path` 路径（轨道及其终点距离）；`pathKey` 路径标识；`bodyTail`/`head` 车尾/车头位置；`target` 想到达的位置（下一停站，或停在站上时的再下一站）；`sections` 涂色信号闭塞区（可 null）；`plannedMillis` 计划发车时刻；`attached` 沿途咽喉道岔与联锁区，列车需要期间持有，并随带它经过的那组一起申请。
- 返回：本 tick 列车可运行到的位置（不超过 max(head, target)）。
- 副作用：修改本对象状态；调用预留表的持有、申请。
- 调用者：`TickRunner.java:2060`、`AuthorityTest.java`。

### `private boolean isClear(PathClaims.Group group)`
组中所有 `TRACK` 种类的声明都没有被其他持有者占用时为真。调用者：`update`。

### `boolean isGrantedBeyond(double head)`
车头之外是否已有获批部分（列车可继续前进）。调用者：`TickRunner.java:2648`、`AuthorityTest.java:69`。

### `boolean isWaiting()`
是否在等待已申请的组。仓库内未找到外部调用者。

### `void release()`
放弃一切（如列车回到停车线或被移除）：释放预留，清空持有、获批、等待与申请、路径。调用者：`TrainServer.java:1078`、`TickRunner.java:554`。

### `double getRecordEnd()`
供存档记录的许可终点：含上 tick 末已获批但尚未取走的组（`peekGrantedGroups`，不消费）；无许可为 NaN。调用者：`TickRunner.java:1811`、`TickRunner.java:1929`。

### `void restore(double recordEnd)`
从记录续跑：下一次 `update` 持有到此位置为止的部分（在届时的路径上），并重置 `grantedEnd`。调用者：`TickRunner.java:632`、`TickRunner.java:1819`。

### `List<Arbiter.Claim<UUID>> getHeld()`
当前持有的声明。调用者：`TrainServer.java:1094`、`TickRunner.java:346`。

### `<T extends RunTrack> double update(..., List<PathClaims.Attached> attached, double wholeUntil)`（0.80.0）
同上，另加 `wholeUntil`：前方终点不超过它的那些组合成一组申请（`whole`，用 `PathClaims.claimsOf` 合并，同一方向资源两个方向都有时成为双向），要么全批、要么都不批；NaN 时不合并（原来的重载都传 NaN）。用于尽头折返线连同出来的进路一起申请（`MTR-待验证.md` 的 A）。调用者：`TickRunner.java` 的 `runLayerTick`。

### `private static List<PathClaims.Group> whole(List<PathClaims.Group> groups, double until)`
把终点不超过 `until` 的前几组合成一组（起点取第一组的、终点取最后一组的），其余照旧；少于两组时原样返回。
