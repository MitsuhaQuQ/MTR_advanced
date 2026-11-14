# OpposingMoves.java

车厂运用路径里的冲突进路（0.80.0，`MTR-待验证.md` 的 A，2026-11-14）。六线图原 8 号线：B 线车在三站经旁边一条尽头折返线换端，掉头三次，和 A 线车经同一条折返线掉头出来的进路方向相反。列车进尽头线前会连同出来的进路一起申请（`TickRunner.DEAD_END_EXITS`），所以不会死锁，但会互相等；车厂的提示把这些列出来，并建议更好的配线（折返线末端加道岔改单边站前折返，或各线各用一边站台站前折返、进站保持正线方向、出站才过渡线）。

纯计算，宿主（游戏 `Siding`/`Depot`）给路径、拿结果。

## 类 OpposingMoves（final）

### 内部类 `Move<T extends PlanTrack>`
两站之间、途中至少掉头一次的走法：`fromStop`/`toStop`（停站的站台 id，`getStopId`）、`turns`（换端次数）、`tracks`（两站之间的轨道，不含两端站台）。

### 内部类 `Finding`
找到的一处：`move` 掉头两次以上（`other` 为 null），或 `move` 和 `other` 对向。`toString()` 是发给客户端的格式：`turns 从 到 次数`，或 `against 从 到 次数 从 到 次数`。

### `static <T extends PlanTrack> List<Move<T>> movesOf(List<T> path)`
路径里相邻两个停站（站台、有站台 id、停站时间 > 0）之间掉头过的走法。经过存车线（`isSidingTrack`）就重新开始，所以出入库不算。在站台上原地掉头（终到后反向出发）没有中间轨道，不会和别的走法对向。调用者：`Siding` 的两个 `generate…Route`。

### `static <T extends PlanTrack> List<Finding> find(Collection<Move<T>> moves)`
先按（起止站台、轨道序列、各处是否反向）去重（多条存车线、多个运用里同一走法只算一次），列出掉头两次以上的，再对共用轨道的走法两两比较：某段轨道一方只按相反方向走过（`runsAgainst`，两个方向都走过的折返线本身不算）就记一对，每对一次。调用者：`Depot.findConflicts`。测试：`OpposingMovesTest`。
