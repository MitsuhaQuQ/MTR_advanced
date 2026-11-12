# NetworkBuilder.java

把 `RailGraph` 压缩成 `Network`，并计算其上的资源（契约文档 4.1）。纯静态工具。调用者：游戏侧 `RailNetworks.compress`（`RailNetworks.java:53`）、`NetworkTest`、`TrainServiceTest`。依赖 `RailGraph`、`PathRegions`、`Boundaries`、`RailAttributes`、`RailKeys`、`Network`、`Resource`、`SectionType`。

## 类 NetworkBuilder（final，不可实例化）

### `static <R> Network build(RailGraph<R> graph, PathRegions regions, Boundaries boundaries, Map<UUID, Long> savedRails, RailAttributes<R> attributes, long version)`
- 作用：
  1. 每条轨道（两个方向合一，键 `RailKeys.of`）收集为 `[较小节点→较大节点的连接, 反向连接]`，并建立节点 → 关联轨道表。
  2. 保留节点：关联轨道数 ≠ 2 的节点、边界节点，以及"特殊轨道"（有站台/停车线或类型非 NORMAL，见 `isSpecial`）的两端。
  3. 从每个保留节点（之后再从所有节点，处理没有保留节点的环线）沿未用轨道走，直到遇到保留节点或特殊轨道为止，构成一个区段；特殊轨道单独成段。区段统一朝向（首节点 < 末节点；闭环时比较第二个与倒数第二个节点），使网络不变时 id 不变。
  4. 为每个区段 `buildSection`，记录轨道 → `{区段 id, 位置, 较小节点在前?}`。
  5. `buildResources` 生成资源，构造 `Network`。
- 参数：`graph` 轨道图；`regions` 寻路区域（null = 无边界）；`boundaries` 寻路边界；`savedRails` 轨道键 → 其上的站台或停车线 id；`attributes` 轨道属性读取器；`version` 发布序号。
- 返回：新 `Network`。副作用：无。
- 主要调用者：`RailNetworks.java:53`、`NetworkTest.java:66`。

### `private static <R> boolean isSpecial(List<Connection<R>> pair, UUID key, Map<UUID, Long> savedRails, RailAttributes<R> attributes)`
轨道上有站台/停车线，或类型不是 `SectionType.NORMAL` 时为真。调用者：`build`（第 41、71、76 行）。

### `private static <R> R getRail(List<Connection<R>> pair)`
取一对连接中非 null 的那个的轨道对象。调用者：`isSpecial`。

### `private static long otherEnd(UUID edge, long node)`
轨道键（高位/低位为两端节点）中 `node` 的另一端。调用者：`build`（第 68 行）。

### `private static <R> Network.Section buildSection(List<Long> nodes, List<UUID> sectionEdges, Map<UUID, List<Connection<R>>> edges, PathRegions regions, Map<UUID, Long> savedRails, RailAttributes<R> attributes)`
- 作用：沿区段每条轨道：`forwards`/`backwards` 为所有轨道在该方向都有且未禁用（`Kind.DISABLED`）；限速：能加速的轨道取其限速，否则 −1（沿用前一条，取决于运行方向）；相邻同限速合并长度。区域取首轨道连接的区域；id = `Network.mix(首节点, 第二节点)`；`savedRailId` 与 `type` 只对单轨道区段有效，否则 0/NORMAL。
- 返回：新区段。调用者：`build`（第 90 行）。

### `private static Map<Long, Resource> buildResources(Map<Long, Network.Section> sections, Map<UUID, long[]> railSections, Map<Long, List<UUID>> nodeEdges)`
- 作用：按契约文档 4.1 生成资源，id 由种类与所在区段/节点生成，同一网络总得到同样资源：
  - 每个区段一个 `TRACK`（容量 1）；类型非 NORMAL 或有保存轨道的区段再有一个 `BERTH`。
  - 关联 ≥ 3 条轨道的节点（道岔，经过的路线可能互相冲突）一个 `TURNOUT`，含交汇的所有区段。
  - 双向区段在无其他轨道的节点（关联正好 2 条）相接时无处会车，用并查集合并为一条单线，每组一个 `SINGLE_TRACK`（以组内最小 id 为键）。
- 返回：`TreeMap` 资源表。调用者：`build`（第 97 行）。

### `private static void add(Map<Long, Resource> resources, Resource.Kind kind, long key, long[] sectionIds, int capacity)`
以 `Network.mix(kind.ordinal()+1, key)` 为 id 新建 `Resource` 放入表。调用者：`buildResources`。

### `private static long find(Map<Long, Long> parents, long id)` / `private static void union(Map<Long, Long> parents, long id1, long id2)`
并查集（路径压缩；较小 id 为根，保证同一网络分组相同）。调用者：`buildResources`。
