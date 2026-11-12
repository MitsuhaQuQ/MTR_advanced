# TrackSections.java

存档中的轨道区段层（优化文档第 5 节，优化阶段文档第 1 轮）：在"有事发生"的节点处把轨道切成区段，每个区段以其内容哈希命名；轨道变化时逐块增量更新，并逐行保存。

切分节点：轨道分叉或尽头处（相交轨道数 ≠ 2）、被标记处（关键节点或寻路边界节点），以及站台、停车线、折返轨的两端（它们各自成段）。两个切分节点之间的轨道构成一个区段。完全没有切分节点的环从其最小节点开始和结束。

区段哈希只依赖其内容：按序节点、每条轨道的长度、形状、限速标签、类型、可运行方向和保存轨道，以及两端的标记。因此不论轨道加入顺序如何，相同轨道得到相同哈希；轨道变化只改变它所在或与之相接的区段。两个不同区段哈希相同（本不应发生）时按内容区分：共享哈希的区段按内容字节排序，第一个保留哈希，其余用哈希和序号生成新哈希；结果只取决于有哪些区段而不取决于先后，且计入 `getCollisions`。

非线程安全：由一个线程修改；`snapshot()`/`getSections()` 供其他线程读取。

在整体中的位置：游戏侧 `GraphRecords`（`GraphRecords.java:30` 持有，`:117` update、`:126` sync、`:129` restore、`:176` snapshot、`:440` toSectionPath、`:508` 读档、`:559` 构造 Rail）。`Snapshot` 被 `LegRouter`、`LineDepotWays`、`RouteLegs`、`TrackOwnership`、`LogicalLines`、`SignalOverlay` 等使用。

## 类 TrackSections（final）

### 常量
- `MARK_KEY = 1`：节点被标为关键节点。
- `MARK_BOUNDARY = 2`：节点被标为寻路边界。
- `MARK_BOUNDARY_RAIL = 4`：寻路边界轨道的端点（使边界轨道自成一段）。
- `MARK_RELEASED = 8`：被手工从联络线释放的轨道的端点（使其自成一段）。
- `FORMAT = 1`（private）：内容字节格式版本。

### 字段
- `rails`（Map<UUID, Rail>）：轨道键 → 轨道。
- `nodeRails`（Map<Long, List<UUID>>）：节点 → 关联轨道键。
- `marks`（Map<Long, Integer>）：节点 → 标记位。
- `railSections`（Map<UUID, Long>）：轨道键 → 所在区段哈希。
- `sections`（Map<Long, Section>）：哈希 → 区段。
- `groups`（Map<Long, List<Section>>）：内容哈希（baseHash）→ 共享该哈希的区段。
- `collisions`（int）：发生哈希冲突的次数。
- `hasher`（static volatile，包内）：内容哈希函数，默认 `TrackSections::hash`；测试可替换为会冲突的函数。

### `Change sync(Map<UUID, Rail> allRails, Map<Long, Integer> allMarks)`
- 作用：使区段与给定的全部轨道和标记一致，只改有差异的部分：找出新增/变化的轨道、被删除的轨道、标记变化（不在新表或为 0 的视为清除），交给 `update`。
- 参数：`allRails` 全部轨道（键为 `RailKeys.of`）；`allMarks` 有标记的节点。
- 返回：区段增删 `Change`。副作用：修改内部所有表。
- 调用者：`GraphRecords.java:126`、`GraphRecordsTest.java:85`；本类 `restore`。

### `Change update(Collection<Rail> putRails, Collection<UUID> removedRails, Map<Long, Integer> markChanges)`
- 作用：修改部分轨道和标记，只重新切分它们所在或相接的区段：
  1. 收集受影响节点（变化轨道两端、标记变化节点），记下经过这些节点的现有区段（freed）。
  2. 删除/替换轨道，更新标记。
  3. 移除 freed 区段（及其组成员身份），收集其中仍存在的轨道与新轨道作为待切分轨道，`cut` 生成新区段。
  4. 对涉及的每个内容哈希重新 `assign` 最终哈希，比较前后哈希集合得出 `Change`。
- 返回：`Change`（新增与删除的区段哈希）。
- 调用者：`GraphRecords.java:117`、`TrackSectionsTest.java:95`；`sync`、私有 `update`。

### `private Change update(Collection<Rail> putRails, Collection<UUID> removedRails, Map<Long, Integer> markChanges, Set<Long> extraNodes)`
把 `extraNodes` 以其现有标记加入 `markChanges`（标记不变但会被视为受影响节点而重新切分），再调用公有 `update`。调用者：`restore`（第 590 行）。

### `private void removeFromGroup(Section section)`
从其内容哈希组中移除该区段（按哈希），组空则删除。调用者：`update`。

### `private List<Long> assign(long base)`
- 作用：为共享内容哈希 `base` 的区段分配最终哈希：先从 `sections` 中移除它们；多于一个时按内容字节排序、`collisions++` 并打印一行日志（`System.out`）；第 i 个的哈希为 i == 0 ? base : `mix(base, i)`，放回 `sections` 并更新 `railSections`。
- 返回：分配后的哈希列表。调用者：`update`、`restore`。

### `private static int compareBytes(byte[] bytes1, byte[] bytes2)`
无符号字节字典序比较。调用者：`assign`。

### `Change restore(Collection<Section> saved, Map<UUID, Rail> allRails, Map<Long, Integer> allMarks)`
- 作用：读档：清空后逐个放回保存的区段（与已放入轨道重叠或哈希重复的视为损坏，记入删除）；按组重新分配哈希；再 `sync` 到当前轨道与标记；不符合当前切分规则（`fitsRules`）的区段从其起点重新切分；合并所有变化。
- 返回：需要改变的部分，存档完全一致时为空。
- 调用者：`GraphRecords.java:129`、`TrackSectionsTest.java:195`。

### `private static void merge(Change into, Change from)`
合并两次变化：先删后加或先加后删的哈希相互抵消。调用者：`restore`。

### `private boolean fitsRules(Section section)`
区段是否按当前规则切分：非环时两端都是切分节点；无切分点的环从最小节点开始；中间节点都不是切分节点；两端标记与当前一致。调用者：`restore`。

### `void clear()`
清空所有表（不重置 `collisions`）。调用者：`restore`；游戏侧。

### `Map<Long, Section> getSections()`
区段表的不可修改视图（非拷贝，注释称可供其他线程读取时应注意此点）。调用者：`GraphRecords.java:146`、`GraphRecords.java:156`。

### `Section getSection(long hash)`
按哈希取区段。调用者：`GraphRecords.java:363`。

### `Long getSectionOf(UUID railKey)`
轨道所在区段哈希或 null。调用者：游戏侧、测试。

### `int getRailCount()`
轨道数。

### `Snapshot snapshot()`
拷贝区段表与轨道 → 区段表，生成不可变快照（附指纹），供其他线程搜索。调用者：`GraphRecords.java:176`、`DepotBehindTerminusTest.java:109`。

### `long getFingerprint()`
整个区段层的指纹：区段数 + Σ`mix(hash, 0x51ED2701)`，与顺序无关。调用者：`GraphRecords.java:379`、`GraphRecords.java:436`；`snapshot`。

### `Network.SectionPath toSectionPath(List<long[]> moves)`
- 作用：与 `Network.toSectionPath` 相同，但使用区段哈希：每个 move `{起节点, 终节点, 停站时间, 站台或 0}`；按 move 在区段节点序列中的先后判定方向；同区段换方向记折返；查不到的轨道计入 `missingRails`。
- 调用者：`GraphRecords.java:440`。

### `int getCollisions()`
哈希冲突次数。调用者：`TrackSectionsTest.java:97`。

### `boolean isCut(long node)`
- 作用：节点是否切分轨道：无轨道、关联轨道数 ≠ 2、有标记；或两条轨道以相同朝向离开该节点（列车无法通过的尖点）；或任一关联轨道是特殊轨道（`Rail.isSpecial`）。
- 调用者：`fitsRules`、`cut`、`walk`。

### `private void collectSections(Set<Long> nodes, Set<Long> into)`
把经过/止于这些节点的轨道所在区段哈希加入 `into`。调用者：`update`。

### `private void addRail(Rail rail)` / `private void addIncident(long node, UUID key)` / `private void removeRail(UUID key)` / `private void removeIncident(long node, UUID key)`
维护 `rails` 与 `nodeRails`（自环轨道只登记一次；空列表删除）。调用者：`update`、`restore`。

### `private List<Section> cut(Set<UUID> freeRails)`
- 作用：把待切分轨道切成区段：先从所有切分节点出发 `walkAll`，再从其余节点出发（剩下的是无切分节点的环，从最小节点开始，因节点有序）。待切分轨道必须构成完整区段，行走不会离开它们。
- 调用者：`update`。

### `private void walkAll(long node, Set<UUID> freeRails, Set<UUID> used, List<Section> made)`
按轨道键排序后，从该节点沿每条未用的待切分轨道 `walk`。

### `private Section walk(long start, UUID first, Set<UUID> freeRails, Set<UUID> used)`
- 作用：从 start 沿 first 走，直到回到起点或遇到切分节点；下一条轨道若不在待切分集合中抛 `IllegalStateException("A section walk left the rails being cut at …")`。统一朝向（首节点 < 末节点；环时比较第二与倒数第二节点），计算端点标记和内容哈希，生成 `Section(base, base, …)`。
- 调用者：`walkAll`。

### `private static byte[] contentBytes(long[] nodes, Rail[] rails, int startMark, int endMark)`
- 作用：区段内容字节（哈希与保存所用）：`FORMAT`、轨道数、各节点；每条轨道：长度、形状、限速 km/h、标志字节（bit0 可加速、bit1 沿区段方向可运行、bit2 逆区段方向可运行）、类型名、保存轨道 id；最后两端标记。IO 异常包成 `UncheckedIOException`。
- 调用者：`Section.toBytes`、`Section.fromBytes`、`walk`。

### `static long hash(byte[] data)`（包内）
64 位 FNV-1a，最后用 SplitMix64 的混合步骤（`mix(hash, 长度)`）。调用者：`hasher` 默认值、`TrackSectionsTest.java:166`。

### `private static long mix(long value1, long value2)`
SplitMix64 混合。调用者：`assign`、`getFingerprint`、`hash`。

## 内部类 Rail（public static final）
宿主眼中的一条轨道（两方向合一）。
- `node1`、`node2`：较小、较大节点。
- `length`（double）：长度（方块）。
- `shape`（long）：形状，高 32 位为离开较小节点的朝向、低 32 位为离开较大节点的朝向；两条轨道以同一朝向离开某节点时在该处不相通（同 `RailGraph.Connection.continuesFrom`）。
- `speedKmh`（int）：轨道上的限速标签（km/h）。
- `canAccelerate`（boolean）：false 表示站台、停车线等保持前一轨道速度的轨道。
- `type`（SectionType）、`savedRailId`（站台/停车线 id 或 0）。
- `forwards` / `backwards`：能否从较小节点驶向较大节点 / 反向。

方法：
- 构造函数 `Rail(long nodeA, long nodeB, double length, long shape, int speedKmh, boolean canAccelerate, SectionType type, boolean fromAToB, boolean fromBToA, long savedRailId)`：把 A/B 规范为较小/较大节点并相应换算方向标志。调用者：`GraphRecords.java:559`、`Section.fromBytes`。
- `UUID getKey()`：`RailKeys.of(node1, node2)`。
- `int getFacing(long node)`：离开某端的朝向。调用者：`Section.getEndFacing`、`isCut`。
- `double getCost()`：长度 / (限速换算为方块/tick)，与 `RailGraph` 代价一致。调用者：`Section.getCost`、`LegRouter.getMaxChordPerCost`。
- `boolean isSpecial()`：类型非 NORMAL 或有保存轨道，自成一段。
- `int getPhysicalKmh()` / `int getPlanKmh()`：按 `SpeedLimits` 的物理限速/计划速度。调用者：`GraphRecordsTest.java:49`、`TrackSectionsTest.java:332`。
- `equals`/`hashCode`：所有字段（长度按位比较）。`toString()`：`"node1-node2"`。

## 内部类 Section（public static final）
两个切分节点之间按序的轨道。
- `hash`（long）：区段哈希（冲突时可能不等于内容哈希）。
- `baseHash`（private long）：内容哈希。
- `nodes`（private long[]）、`rails`（private Rail[]）。
- `startMark`、`endMark`（int）：两端节点标记。

方法：
- 私有构造函数；`withHash(long)`（私有）：换哈希得到新对象（相同则返回自身）。
- `long[] getNodes()`（拷贝）、`long getStart()`、`long getEnd()`、`int getRailCount()`、`Rail getRail(int index)`（节点 index 与 index+1 之间的轨道）。调用者：`LegRouter`、`RoutePaths`、`TrackOwnership` 等。
- `boolean isAlong(int index)`：区段在该轨道上是否从其较小节点走向较大节点。
- `boolean canRun(boolean along)`：能否沿区段顺序（从起点）或逆序（从终点）运行。调用者：`LegRouter.java:494`。
- `int getEndFacing(boolean atStart)`：区段离开其起点（true）或终点的朝向。调用者：`LegRouter.java:458`、`:467`。
- `double getCost()`：各轨道代价之和。
- `SectionType getType()` / `long getSavedRailId()`：仅单轨道区段有效，否则 NORMAL / 0。
- `double getLength()`：总长。
- `boolean isForwards()` / `boolean isBackwards()`：整段能否顺序/逆序运行。
- `byte[] toBytes()`：内容字节（即哈希来源）。调用者：`GraphRecords`（保存）、`assign`。
- `boolean sameContent(Section other)`：不论哈希，内容是否相同。调用者：`TrackSectionsTest.java:313`。
- `static Section fromBytes(long hash, byte[] data) throws IOException`：读回区段；格式号不符抛 `IOException("Unknown track section format")`，轨道数不在 [1, 2²⁰] 抛 `IOException`；重新计算内容哈希作为 baseHash。调用者：`GraphRecords.java:508`。
- `toString()`：十六进制哈希 + 节点数组。

## 内部类 Change（public static final）
一次更新对区段的影响：`added`、`removed`（TreeSet 区段哈希）；`isEmpty()`；`toString()` 为 `"+N -M"`。

## 内部类 Snapshot（public static final）
某一时刻的区段及每个节点的区段，任何线程可读。
- `sections`（不可修改 Map）、`railSections`（private）、`nodeSections`（private，节点 → 在该节点开始或结束的区段哈希，有序数组）、`fingerprint`。
- 私有构造函数：建立 `nodeSections`。调用者：`snapshot()`。
- `long[] getSectionsAt(long node)`：在该节点开始或结束的区段哈希（有序），无则空数组。调用者：`LegRouter.java:253`、`TrackOwnership.java:94`。
- `Section getSectionOf(long node1, long node2)`：两节点间轨道所在区段，无则 null。调用者：`RouteLegs.railWaypoints`、`LogicalLines`、`TrackOwnership.getOverrides`、`RoutePaths.java:295`。
