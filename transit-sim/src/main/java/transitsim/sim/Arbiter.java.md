# Arbiter.java

决定哪列车可以使用哪些资源（契约文档第 4 节，S4b）。闭塞分区、带方向的单线、道岔、泊位都是资源，按同样规则批准，没有哪一类单独决定。

三者分开保存（4.2）：列车实际所在（宿主根据车头车尾给出）；已批准的（只有仲裁器给出）；预测会用的（只在仿真分叉中计算，永不作为批准依据）。

同一事件边界内的请求在其结束后一起按固定顺序处理，结果与到达顺序无关：等待 ≥ `AGING_MILLIS` 的请求优先（等待越久越先），其余按计划发车时刻，再按持有者 id。请求要么得到全部资源，要么一个都得不到。等待过久的请求不能批准时，它所要的资源不再批给尚未持有其中任何资源的持有者，让已在其上的先离开，避免源源不断的其他列车让它永远等待。

不会因为列车停下或续期中断而自动放走资源：持有者自己声明仍持有的（只放弃车尾已离开的），或被移除。每 tick 续期（`hold`）的持有者若某 tick 未声明，在该 tick 结束时视为移除——游戏服务器列车就是这样使用的。

持有者相互等待形成环即为死锁（4.5）：只报告一次，不再把环内资源批给新持有者，也不会自行释放任何东西，只能由宿主或玩家解开。

在整体中的位置：`RunReservations` 包装一个 `Arbiter<UUID>` 供运行层使用（`hold`、`request`、`requestGroups`、`resolve`、`takeGrantedGroups`、`takeDeadlocks` 等）；`PathClaims`、`RunReservations`、`ThroatLocks` 用 `Claim` 的工厂方法；`Authority` 通过 `RunReservations` 间接使用。泛型 `K` 为资源键类型。

## 类 Arbiter<K extends Comparable<K>>（final）

### 常量
- `AGING_MILLIS = 60000`：请求等待超过此时间（毫秒）即优先于计划更早发车的请求，并参与死锁判断。

### 字段
- `holdings`：持有者 → 已批准的声明列表。
- `holders`：资源 → (持有者 → 声明)。
- `occupied` / `occupants`：占用者 → 实际所在资源；资源 → 占用者。
- `predicted`：持有者 → 预测使用的资源（仅分叉仿真用）。
- `heldThisTick`：本 tick 已续期的持有者。
- `requests`：持有者 → 本边界的请求。
- `firstRequestMillis`：持有者 → 本轮开始等待的时刻。
- `grants`：上次 resolve 中获批的持有者。
- `blocking`：上次 resolve 中阻碍了别人请求的持有者。
- `grantedGroups`：持有者 → 上次分组请求获批的组数。
- `frozen`：死锁中的资源 → 该死锁的持有者集合；其他持有者在死锁解除前不能获批。
- `knownDeadlocks` / `newDeadlocks`：已知死锁；新发现且尚未取走的死锁。

### `void hold(long holder, List<Claim<K>> claims)`
- 作用：设置持有者已批准的声明，替换原有（每 tick 续期的持有者给出从车尾起仍需的部分）。标记本 tick 已续期；与原持有完全相同时不做改动；空列表即放弃全部。
- 副作用：修改 `holdings`、`holders`、`heldThisTick`。
- 调用者：`RunReservations.java:50`、`:58`；本类 `cancel`、`resolve`。

### `void release(long holder)`
- 作用：收回持有者已批准的一切（如列车回到车厂），并取消本 tick 续期标记；其请求保留（停车线中的列车在上线前就申请出库路径）。
- 调用者：`RunReservations.java:111`；`remove`。

### `void remove(long holder)`
收回持有者的一切：批准、请求、实际占用、预测、等待计时、获批标记（如宿主移除列车）。调用者：测试 `ArbiterTest.java:194`。

### `boolean cancel(long holder, boolean stopped, boolean headBeforeGrant, List<Claim<K>> keep)`
- 作用：收回已批准但尚未进入的部分（如列车在站内改路径，4.4）。只有列车静止且车头在批准起点之前才安全，否则返回 false 且不做任何事；安全时持有改为 `keep`，并保持原本是否续期的状态。
- 调用者：`ArbiterTest.java:137`。

### `boolean isHeldByOther(long holder, K resource)`
是否有其他持有者持有或有其他占用者站在该资源上。调用者：`RunReservations.java:104`、`:156`。
（源码中其上方有一段孤立 javadoc "everybody granted something"，属于 `getHolders`。）

### `boolean isBlocking(long holder)`
该持有者所持有或所在之处是否在上次 resolve 中阻碍了别人的请求。调用者：`RunReservations.java:47`。

### `Set<Long> getHolders()`
有批准的持有者（有序拷贝）。调用者：`RunReservations.java:149`。

### `List<Claim<K>> getHoldings(long holder)`
持有者已批准的声明（不可修改视图），无则空列表。调用者：`RunReservations.java:181`；`resolve`。

### `boolean canHold(long holder, List<Claim<K>> claims)`
在他人批准与实际占用的情况下，这些声明现在能否批给该持有者（每个声明都没有阻碍者）。调用者：`RunReservations.java:87`、`:118`；`resolve`。

### `void setOccupied(long occupant, Collection<K> resources)`
设置某列车（或宿主知道的其他东西）当前从车尾到车头实际所在的资源，替换原值。调用者：`RunReservations.java:72`；`remove`。

### `boolean isOccupied(K resource)`
资源上是否有占用者。仓库内未找到外部调用者。

### `void setPredicted(long holder, Collection<K> resources)` / `Set<K> getPredicted(long holder)`
设置/读取预测使用的资源（仅用于分叉冲突检查，永不作为占用或批准，4.2 规则 3）。调用者：`ArbiterTest.java:125`、`:129`。

### `void request(long holder, List<Claim<K>> claims, long plannedMillis)`
- 作用：申请资源（单组），在事件边界结束时决定；仍在等待的持有者须在下一边界再次申请，不再申请即被丢弃。
- 参数：`plannedMillis` 计划发车时刻（排序用）。
- 调用者：`RunReservations.java:65`、`:128`。

### `void requestGroups(long holder, List<List<Claim<K>>> groups, long plannedMillis)`
- 作用：按到达顺序申请多组声明：tick 末尽可能多地按序批准前面的组（每组完整），追加到已持有的部分（运行层 5.5）。空列表不做任何事。（源码中其上方另有一段孤立 javadoc，描述 `takeGrant`。）
- 调用者：`Authority.java:154`（经 `RunReservations.java:79`）。

### `int peekGrantedGroups(long holder)`
上个 tick 末该持有者分组请求获批的组数（不取走）。调用者：`RunReservations.java:90`。

### `int takeGrantedGroups(long holder)`
同上但取走（并清除获批标记），只返回一次。调用者：`RunReservations.java:97`。

### `boolean takeGrant(long holder)`
持有者的请求获批后只返回一次 true。调用者：`RunReservations.java:135`、`ArbiterTest.java:28`。

### `void resolve(long currentMillis)`
- 作用：结束一个事件边界：
  1. 本 tick 未续期的持有者放弃所持。
  2. 清空上次的获批、分组计数、阻碍者；丢弃已不再申请者的等待计时；持有者已不在申请中的死锁视为结束，清理对应冻结资源。
  3. 记录新请求的开始等待时刻，按"是否超龄 → (超龄按开始等待时刻 / 否则按计划发车时刻) → 持有者 id"排序。
  4. 依次处理：分组请求在首组可持有、未被冻结、且（已持有被超龄请求等待的资源 或 与超龄请求不冲突）时，逐组批准直到某组不可持有、与超龄请求冲突或碰到别的死锁冻结资源；单组请求同理整体批准；批准即并入原持有并记入 `grants`。否则记为未批准，记录阻碍者；若已超龄，把它要的资源加入 `waitedFor`（后续新持有者不得逆向进入）。
  5. `findDeadlocks`，清空请求与续期标记（每 tick 续期的持有者下一 tick 须重新声明全部，包括刚获批的）。
- 参数：`currentMillis` 当前时刻（毫秒）。
- 调用者：`RunReservations.java:142`、`ArbiterTest.java:27`。

### `List<Set<Long>> takeDeadlocks()`
取走自上次调用以来新发现的死锁（每个只报告一次，顺序固定）。调用者：`RunReservations.java:195`。

### `boolean isFrozen(K resource)`
资源是否因死锁冻结。调用者：`ArbiterTest.java:162`。

### `private void findDeadlocks(List<Request<K>> notGranted, long currentMillis)`
- 作用：只把等待 ≥ `AGING_MILLIS` 的未批准请求视为"在等"（沿环线互相跟驰、仍在已批区间运行的列车不算死锁）；建立"等待谁"的图（只连向同样在等的持有者），用 DFS 找环；重建 `frozen`：每个死锁的请求资源与持有资源都冻结；新出现的死锁加入 `newDeadlocks`。
- 调用者：`resolve`。

### `private static void findCircle(long holder, Map<Long, Set<Long>> waitsFor, List<Long> trail, Set<Long> onTrail, Set<Long> done, Set<Set<Long>> deadlocks)`
DFS 找环，找到时把环上持有者集合加入 `deadlocks`。

### `private Set<Long> blockers(long holder, Claim<K> claim)`
- 作用：当前阻止该声明批给 holder 的其他持有者：持有同一资源且 `conflicts` 的；泊位已满（其他持有者数 ≥ 容量）时其他全部持有者；对 TRACK/TURNOUT，还包括站在其上却没有批准的占用者（如手动驾驶列车）。
- 调用者：`canHold`、`resolve`、`findDeadlocks`。

### `private static <K> boolean conflicts(Claim<K> claim, Claim<K> other)`
同一资源两个声明能否共存：SINGLE_TRACK 方向不同或本声明为双向（way = 0）则冲突；TURNOUT 进路不同则冲突（同进路的列车跟驰，由闭塞分区分隔）；BERTH 不冲突（另按容量）；其他（TRACK）总冲突。

### `private boolean isFrozenFor(Request<K> request)`
请求的资源是否被一个不包含该持有者的死锁冻结（且持有者尚未持有该资源）。死锁中的持有者自己若能前进仍可继续。

### `private boolean holdsResource(long holder, K resource)`
持有者是否持有该资源。

### `private boolean holdsAny(long holder, Map<K, Claim<K>> waitedFor)`
持有者是否已持有被超龄请求等待的某资源（这样的持有者属于"应让其先离开"的一方，可以继续获批）。

### `private static <K> boolean isAgainst(List<Claim<K>> claims, Map<K, Claim<K>> waitedFor)`
声明中是否有与超龄请求冲突（或同为泊位）的资源。

### `private static <K> boolean isSame(List<Claim<K>> held, List<Claim<K>> claims)`
两个声明列表是否逐项等价（同对象或种类、方向、容量、资源都相同）。调用者：`hold`。

### `private void removeHoldings(long holder)`
移除持有者全部批准并更新 `holders` 反向索引。

## 内部类 Claim<K>（public static final）
申请或已批准的资源及其用法。
- `resource`（K）、`kind`（`Resource.Kind`）。
- `way`（int）：单线上的方向（1 / −1，0 = 双向），或经过道岔的进路；同资源同 way 的声明可同时批准。
- `capacity`（int）：泊位容量。
- 私有构造函数。
- `static Claim<K> track(K)`：一次一列车的分区。调用者：`PathClaims.java:194`、`RunReservations.java:213`。
- `static Claim<K> singleTrack(K, boolean forwards)`：带方向的单线，同向列车共享。调用者：`PathClaims.java:208`、`RunReservations.java:220`。
- `static Claim<K> singleTrackBothWays(K)`：一列车双向使用（如折返站，掉头车优先），期间其他列车两个方向都不可用。调用者：`PathClaims.java:219`、`PathClaims.java:271`。
- `static Claim<K> turnout(K, int route)`：道岔，同进路共享。调用者：`RunReservations.java:215`、`RunReservations.java:302`。
- `static Claim<K> berth(K, int capacity)`：泊位，容量至少 1。调用者：`ArbiterTest.java:60`。

## 内部类 Request<K>（private static final）
- `holder`；`claims`（首组声明，未获批时持有者等待的就是它）；`plannedMillis`；`groups`（按序尽量批准的各组，单组请求为 null）。
- 两个私有构造函数。调用者：`request`、`requestGroups`。
