# TrackOwnership.java

叠加在轨道区段上的所有权层（优化阶段文档第 2 轮）：每个轨道区段属于哪些运营线路。寻路边界把网络切成区域，区域属于在其中有站台的线路；边界轨道属于两侧的区域。联络线自有部分的区段只属于该联络线。线路只能在本线拥有的、无人拥有的区段或本线站台上运行（硬规则，用户 2026-11-08 决定）；联络线可在任意处运行。

按区段哈希保存，因此边界或线路变化只改所有者真正变化的区段，触及它们的搜索会被重算（`LegCache.changeOwners`）。

玩家可以手工设置区段所有者（网页分层第 2 步，2026-11-09）：按区段内某条轨道的键保存，所以轨道重新切分区段后仍保留，并覆盖自动推算结果。一个区段有多条轨道被手工设置时属于它们所列的全部线路；设为无线路则对所有线路开放。

在整体中的位置：游戏侧 `RoutePaths.java:316`（`compute`、`getOverrides`）、`RoutePaths.java:638`（`mayRun`）、`GraphRecords.java:232`（`changed`）、`GraphRecords.java:422`/`:522`（存取）；组件 `LegRouter.java:498`（`mayRun`）；`LogicalLines` 产出同样形式的结果。

## 类 TrackOwnership（final，不可实例化）

### 常量
- `CONNECTING_PREFIX = "connecting:"`：联络线自有部分区段的所有者名为 `"connecting:" + 联络线键`。

### `static Map<Long, Set<String>> compute(TrackSections.Snapshot graph, Set<Long> boundaryNodes, Set<UUID> boundaryRails, Map<Long, ? extends Collection<String>> platformLines, Map<Long, String> connectingSections)`
无手工覆盖的便捷形式。

### `static Map<Long, Set<String>> compute(TrackSections.Snapshot graph, Set<Long> boundaryNodes, Set<UUID> boundaryRails, Map<Long, ? extends Collection<String>> platformLines, Map<Long, String> connectingSections, Map<UUID, ? extends Collection<String>> overrides)`
- 作用：先 `computeWorkedOut` 推算，再叠加 `getOverrides`：手工设为空集合的区段移除（对所有线路开放），否则替换为手工值。
- 参数：`graph` 区段快照；`boundaryNodes` 寻路边界节点；`boundaryRails` 边界轨道键；`platformLines` 保存轨道 id → 在其上有站台的线路；`connectingSections` 联络线自有区段 → 联络线；`overrides` 轨道键 → 手工所有者。
- 返回：有所有者的区段 → 所有者集合；不在表中的区段任何线路可用。
- 调用者：`RoutePaths.java:316`、`TrackOwnersPacketsTest.java:43`。

### `static Map<Long, Set<String>> getOverrides(TrackSections.Snapshot graph, Map<UUID, ? extends Collection<String>> overrides)`
- 作用：把按轨道键的手工所有者聚合到其所在区段（多条轨道取并集）；空集合表示对所有线路开放。找不到区段的轨道忽略。
- 返回：区段哈希 → 不可修改集合（TreeMap）。调用者：`RoutePaths.java:316`、`RouteLegsTest.java:353`。

### `private static Map<Long, Set<String>> computeWorkedOut(...)`
- 作用：
  1. 只有一条轨道且该轨道为边界轨道的区段视为边界区段。
  2. 在非边界节点处，把相交的非边界区段用并查集合并为区域。
  3. 每个区域的线路 = 其中站台（非边界区段、有保存轨道 id）的线路之并。
  4. 每个区段：属于联络线自有部分 → `{CONNECTING_PREFIX + 键}`；边界区段 → 两端节点处所有非边界区段所在区域线路之并；其他 → 所在区域线路。非空才记录。
- 调用者：`compute`（第 45 行）。

### `static Set<Long> changed(Map<Long, Set<String>> before, Map<Long, Set<String>> after)`
两版本所有权层中所有者不同（含新增与删除）的区段。调用者：`GraphRecords.java:232`、`RoutePaths.java:171`。

### `static boolean mayRun(Set<String> owners, String line)`
线路为 null（联络线）、区段无所有者、或所有者含该线路时为真。调用者：`LegRouter.java:498`、`RoutePaths.java:638`。

### `static byte[] toBytes(Set<String> owners)` / `static Set<String> fromBytes(byte[] data)`
所有者集合与 UTF-8 文本（每行一个）互转；读回时忽略空行，返回不可修改的 TreeSet。调用者：`GraphRecords.java:422`、`GraphRecords.java:522`。

### `private static long find(Map<Long,Long> parents, long id)` / `private static void union(Map<Long,Long> parents, long id1, long id2)`
并查集（路径压缩，较小者为根）。调用者：`computeWorkedOut`。
