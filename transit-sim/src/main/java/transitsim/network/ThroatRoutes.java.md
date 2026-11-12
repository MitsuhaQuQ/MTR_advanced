# ThroatRoutes.java

列车经过其停靠各站咽喉区的进路（解耦文档 5.4，L2）：进入每个站台和离开时经过的道岔，以及经过每个道岔的走向。两列车以不同走向使用同一道岔时不能同时在上面，因此进路冲突；同一走向的列车相互跟驰（由信号闭塞分隔）；同一走向相反方向是对向运行，由单线预留处理。

道岔 = 三条及以上轨道相交的节点（同 `NetworkBuilder`），无节点的平面交叉无法得知。咽喉区在路径连续 `PLAIN_DISTANCE` 方块无道岔处、或在相邻停站处结束。

在整体中的位置：组件 `ThroatLocks.java:61`（运行时咽喉锁）、游戏侧 `RoutePaths.java:1401`、客户端 `ClientThroats.java:57`、`:114`（显示冲突）、编辑器 `EditorData`、时刻表冲突检查 `TimetableConflicts`。

## 类 ThroatRoutes（final，不可实例化）

### 常量
- `PLAIN_DISTANCE = 64`：连续无道岔多少方块即视为咽喉区结束。

### `static List<StopThroat> of(long[] nodes, LongFunction<double[]> position, int[] stopRails, LongToIntFunction rails)`
无联锁区的便捷形式（所有轨道联锁区为 0）。调用者：`ClientThroats.java:57`、测试。

### `static List<StopThroat> of(long[] nodes, LongFunction<double[]> position, int[] stopRails, LongToIntFunction rails, ToLongFunction<UUID> zones)`
- 作用：为每个停站计算进站与出站进路。
  - 停站轨道下标 < 0 或越界：空进路。
  - 进站：从站台首节点向后（不越过上一停站站台之后的节点），遇到相交轨道数 ≥ 3 的节点记为道岔 `Item(节点, 前一节点, 后一节点, 下标)` 并清零无道岔距离；途经轨道若属联锁区则扩展该区覆盖的轨道范围并清零距离；累计无道岔距离超过 `PLAIN_DISTANCE` 停止。之后把每个联锁区作为一个 `Item.zone` 追加。
  - 出站：从站台末节点向前直到下一停站站台之前，规则相同。
- 参数：`nodes` 路径节点（第 i 条轨道从节点 i 到 i+1）；`position` 节点 → {x, z}；`stopRails` 每个停站在路径中的站台轨道下标，未到达为 −1；`rails` 节点处相交的不同轨道数；`zones` 轨道键（`RailKeys`）→ 所在联锁区（联锁颜色的信号闭塞区，进路整体占用），0 为无。
- 返回：与停站一一对应的 `StopThroat` 列表。副作用：无。
- 调用者：`ThroatLocks.java:61`、`RoutePaths.java:1401`。

### `static Item findConflict(List<Item> route1, List<Item> route2)`
- 作用：返回 route1 中第一个与 route2 任一项冲突（`Item.conflicts`）的项，无冲突返回 null。（javadoc 写"是否"，实际返回冲突项。）
- 调用者：`ClientThroats.java:114`、`ThroatRoutesTest.java:57`。

### `private static boolean addZone(Map<Long, int[]> zones, long zone, int rail)`
联锁区非 0 时把该轨道下标并入其 `[首轨道, 末轨道]` 范围并返回 true；为 0 返回 false。调用者：`of`（第 66、86 行）。

### `private static int previousRail(int[] stopRails, int stop)` / `private static int nextRail(int[] stopRails, int stop)`
往前/往后找最近一个已到达停站的站台轨道下标，没有返回 −1。调用者：`of`。

### `private static double distance(LongFunction<double[]> position, long node1, long node2)`
两节点平面距离。调用者：`of`。

## 内部类 Item（public static final）
一个道岔及经过它的走向（以两侧相邻节点表示，与顺序无关），或一个联锁区（无论走向均整体占用）。
- `ZONE_SIDE = Long.MIN_VALUE`（private）：联锁区两侧的标记值（任何节点都不会取此值）。
- `node`（long）：道岔节点，或联锁区 id。
- `side1`、`side2`（long）：两侧节点（较小、较大）；联锁区为 `ZONE_SIDE`。
- `index`（int）：道岔在路径中的节点下标，或 −1；不参与相等比较。联锁区为其在路径上第一条轨道的首节点下标。
- `endIndex`（int）：联锁区在路径上最后一条轨道的下标 + 1（车尾须离开处）；道岔同 `index`。
- `place`（private long）：联锁区显示位置（路径进入它的节点）。

方法：
- `static Item zone(long zone, int firstRail, int lastRail, long firstNode)`：创建联锁区项（`index = firstRail`，`endIndex = lastRail + 1`，`place = firstNode`）。调用者：`of`（第 74、94 行）、`ThroatLocks.java:193`、`TimetableConflictsTest.java:92`。
- `long getPlace()`：显示位置：联锁区为进入节点，道岔为其节点。调用者：`ClientThroats.java:116`、`TimetableConflicts.java:324`。
- 构造函数 `Item(long node, long neighbour1, long neighbour2)`（index = −1）、`Item(node, n1, n2, int index)`（endIndex = index）、私有 `Item(node, n1, n2, index, endIndex)`（两侧排序）。调用者：`of`、测试。
- `boolean isZone()`：是否为联锁区。调用者：`EditorData.java:526`、`TimetableConflicts.java:334`。
- `boolean conflicts(Item other)`：道岔：同节点不同走向则冲突；联锁区：仅与同 id 的联锁区冲突（无论走向）；道岔与联锁区之间不冲突。调用者：`findConflict`、`TimetableConflicts.java:318`。
- `equals`/`hashCode`：按 `node`、`side1`、`side2`。

## 内部类 StopThroat（public static final）
- `entry`（List<Item>）：进站经过的道岔（从站台向后），之后为联锁区。
- `exit`（List<Item>）：出站经过的道岔（从站台向前），之后为联锁区。
- 构造函数按字段赋值（不拷贝）。
