# LegCache.java

已找到的区间路径（leg）缓存，保留到其依赖的东西变化为止（优化阶段文档第 2 轮）。每条 leg 按其搜索触及的所有区段（section）和继续搜索出发过的所有节点建立索引：区段被删除、修改或更换所有者时，丢弃触及它的 leg；在某搜索出发过的节点处新增区段（新轨道接在搜索经过处）时也丢弃这些 leg。这样新捷径只让可能受影响的 leg 重算。

leg 在其他线程上基于某个版本的快照计算。若某 leg 所基于的版本早于一个与它相关的变更，它不会被收入，因此缓存内容总是对当前网络正确。线程安全（所有公开方法 synchronized）。

在整体中的位置：游戏侧 `GraphRecords`（`GraphRecords.java:72`）持有一个实例，调用 `get/keep/put/change/changeOwners/clear`；leg 由 `LegRouter` 计算（`LegRouter.Leg`、`LegRouter.Request`）。

## 类 LegCache（final）

### 字段与常量
- `legs`（`Map<Long, LegRouter.Leg>`）：请求键 → leg。
- `bySection`（`Map<Long, Set<Long>>`）：区段 id → 触及它的 leg 键。
- `byNode`（`Map<Long, Set<Long>>`）：节点 id → 从它出发搜索过的 leg 键。
- `changes`（`ArrayDeque<Object[]>`）：最近的变更 `{版本, 区段集合, 节点集合, 仅所有者变更?}`，最多 `MAX_CHANGES = 256` 条，用于检查旧版本算出的 leg 能否收入。
- `version`（long）：当前网络版本，每次变更 +1。
- `modifications`（long）：收入或丢弃 leg 的累计次数；宿主比较它判断自上次保存后是否有变化。

### `LegRouter.Leg get(LegRouter.Request request)`
返回该请求缓存的 leg 或 null。调用者：`GraphRecords.java:272`、`GraphRecords.java:288`。

### `boolean holds(Collection<LegRouter.Leg> used)`
所给的每条 leg 是否仍是缓存中的同一对象（引用相等）；是则由它们推出的结果仍成立。调用者：`GraphRecords.java:296`、`RouteLegsTest.java:421`。

### `long getVersion()`
当前版本（计算新 leg 时使用）。调用者：`GraphRecords.java:188`、`GraphRecords.java:246`。

### `long getModifications()`
累计修改次数。调用者：`GraphRecords.java:394`、`RouteLegsTest.java:287`。

### `LegRouter.Leg keep(LegRouter.Leg leg)`
- 作用：收入 leg，除非已有同一请求且版本 ≥ 它的 leg（如两线程同时算出），或自其搜索以来网络在其触及处变化过（见 `put`）。
- 返回：缓存中对应的 leg：本 leg、已有的 leg，或未收入时 null。
- 副作用：可能改 `legs`、索引、`modifications`。
- 调用者：`GraphRecords.java:278`、`RouteLegsTest.java:75`。

### `boolean put(LegRouter.Leg leg)`
- 作用：收入一条基于某版本算出的 leg。
  - `leg.version < version`：若最老的保留变更都比 `leg.version + 1` 新（无法检查），拒绝；否则对每个比 leg 版本新、且适用（非仅所有者变更，或 leg 限定于某线路 `request.line != null`）的变更，若与 leg 的区段或节点相交则拒绝。
  - `leg.version > version`：拒绝（来自未来版本）。
  - 通过后替换同键旧 leg，更新索引，`modifications++`。
- 返回：是否收入。
- 调用者：`GraphRecords.java:189`、`GraphRecords.java:365`；本类 `keep`。

### `Set<Long> change(Collection<Long> changedSections, Collection<Long> joinedNodes)`
- 作用：网络新版本：丢弃受影响的 leg。
- 参数：`changedSections` 被删除、新增或所有者变化的区段；`joinedNodes` 新增区段的端点节点。
- 返回：被丢弃的 leg 键（有序）。副作用：`version++`，记录变更，修改缓存。
- 调用者：`GraphRecords.java:219`、`RouteLegsTest.java:303`。

### `Set<Long> changeOwners(Collection<Long> changedSections)`
- 作用：仅部分区段所有者变化的新版本：只丢弃限定于某线路轨道（`request.line != null`）且触及这些区段的 leg；联络线（可走任意轨道）的 leg 不受影响。
- 返回：被丢弃的键。调用者：`GraphRecords.java:235`、`RouteLegsTest.java:420`。

### `private Set<Long> change(Collection<Long> changedSections, Collection<Long> joinedNodes, boolean ownersOnly)`
两个公开 change 的共同实现：版本 +1、入队变更（超过 256 条丢最老）、按索引收集受影响 leg、`ownersOnly` 时排除无线路限定的、逐个 `remove`。

### `void clear()`
丢弃全部 leg（如规则变化时），清空索引与变更队列，`version++`、`modifications++`。调用者：`GraphRecords.java:348`。

### `int size()`
缓存 leg 数。调用者：`GraphRecords.java:130`。

### `List<LegRouter.Leg> getLegs()`
返回当前全部 leg 的拷贝列表（供保存）。调用者：`GraphRecords.java:244`、`GraphRecords.java:401`。

### `private void remove(long key)`
移除某键 leg 并从 `bySection`/`byNode` 索引中删掉（空集合一并删除），`modifications++`；键不存在时什么也不做。调用者：`put`（第 103 行）、`change`。

### `private static Set<Long> toSet(long[] values)`
long 数组转 `HashSet<Long>`。调用者：`put`。

### `private static boolean intersects(Set<Long> set, Set<?> other)`
两集合是否有共同元素。调用者：`put`。
