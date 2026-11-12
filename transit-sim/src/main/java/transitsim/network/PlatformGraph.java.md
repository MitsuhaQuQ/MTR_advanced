# PlatformGraph.java

列车在站台之间不折返能怎样运行——选线路站台和新建服务类型时以此为依据（设计文档 6.12，D 块）。

"网络"指物理上连在一起的轨道，即 `RailGraph` 的一个连通分量。没有轨道相连的站台属于不同网络，永远不会互相推荐，不论它们属于哪个车站；寻路边界不影响它（边界只缩小搜索范围）。

每个站台有两个状态，一个方向一个：0 = 从第一端到第二端，1 = 反向。从某状态出发不折返地往前开，每条分支在遇到的第一个站台处结束，形成一条到该站台对应方向状态的边。经过多个车站的列车沿多条边连续运行。在站台折返不是边：宿主可以允许，但给出警告。不看路线的途经点。

在整体中的位置：`RegionComputer.compute`（`RegionComputer.java:22`）计算并放入 `RegionData`；游戏侧 `RegionDataPackets.java:91` 收发；编辑器 `EditorData`、`EditorActions`、`EditorContingencies`、客户端 `ClientStationTracks`、组件 `ContingencySystems` 查询。

## 类 PlatformGraph（final，不可变）

### 字段与常量
- `platformIds`（long[]）：站台 id，按 id 升序。
- `networks`（long[]）：每个站台所在网络编号（网络内最小节点 id；无轨道为 0）。
- `usable`（boolean[2n]）：每个状态（站台下标×2 + 方向）列车能否按该方向运行通过站台（连接存在且未禁用）。
- `targets`（int[2n][]）：每个状态的边所指向的状态（升序）。
- `costs`（double[2n][]）：对应边的代价（沿途连接代价之和）。
- `indices`（Map<Long,Integer>）：站台 id → 下标。
- `networkCount`（int）：不同网络个数。
- `EMPTY`：空图。
- `MAX_SEARCH_CONNECTIONS = 50000`：单个状态搜索最多查看的连接数，防止超大网络拖慢其他计算。

### 构造函数 `PlatformGraph(long[] platformIds, long[] networks, boolean[] usable, int[][] targets, double[][] costs)`
- 作用：保存数组（不拷贝），建索引，统计网络数。数组长度不一致时抛 `IllegalArgumentException`。
- 调用者：`compute`（第 93 行）、`RegionDataPackets.java:91`（从包读入）、`ContingencySystemsTest.java:33`。

### `static <R> PlatformGraph compute(RailGraph<R> graph, PathRegions regions, List<RegionComputer.SavedRail> platforms)`
- 作用：站台按 id 排序；求每个节点所属网络（`findNetworks`）；每个站台两个方向分别找连接（`graph.find(pos1,pos2)` / 反向），可用则记录状态；对每个可用状态 `search` 得到边。
- 参数：`regions` 寻路区域或 null——不影响站台间的路，只有轨道决定；`platforms` 站台（id 与两端节点）。
- 返回：新图。副作用：无。
- 调用者：`RegionComputer.java:22`。

### `private static <R> Map<Integer, Double> search(RailGraph<R> graph, PathRegions regions, List<SavedRail> platforms, Connection<?>[] stateConnections, Map<Integer,Integer> connectionStates, int startState)`
- 作用：从状态对应的连接出发做 Dijkstra（按距离、再按连接下标排序以保持确定性），只沿 `continuesFrom` 允许（不折返、未禁用）且不直接掉头的连接前进；遇到另一个站台状态的连接时记录（取第一次即最低代价）并不再从它继续。最多处理 `MAX_SEARCH_CONNECTIONS` 个连接。
- 返回：到达的状态 → 最低代价（TreeMap）。调用者：`compute`（第 89 行）。

### `private static <R> boolean isAllowed(...)`
- 作用：总是返回 true。注释说明：轨道给出的每条路都算数，寻路边界只缩小搜索，有路的线路无论边界如何都能找到（用户决定）；每条分支本就在第一个站台结束，不需要区域来限制搜索规模。参数均未使用。调用者：`search`（第 123 行）。

### `private static <R> Map<Long, Long> findNetworks(RailGraph<R> graph)`
用并查集把每条连接两端合并，返回节点 → 所在网络最小节点 id（同样的轨道总得同样编号）。调用者：`compute`（第 69 行）。

### `private static long find(Map<Long,Long> parents, long node)` / `private static void union(Map<Long,Long> parents, long node1, long node2)`
并查集（首次见到节点时自成一集；路径压缩）。调用者：`findNetworks`。

### 查询方法（均无副作用）
- `int size()`：站台数。调用者：`RegionDataPackets.java:47`、`EditorData.java`。
- `long getPlatformId(int index)`：下标 → 站台 id。调用者：`RegionDataPackets.java:49`、`ContingencySystems.java:85`。
- `int indexOf(long platformId)`：站台 id → 下标，不在图中为 −1。调用者：`ClientStationTracks.java:31`、`EditorContingencies.java:55`。
- `long getNetwork(int index)`：下标 → 网络。调用者：`RegionDataPackets.java:50`、`ContingencySystems.java:86`。
- `long getNetworkOf(long platformId)`：站台 id → 网络，无轨道站台为 0。调用者：`EditorActions.java:835`、`EditorData.java:509`。
- `int getNetworkCount()`：网络数。调用者：`EditorData.java:761`。
- `boolean isUsable(int state)`：状态是否可用。调用者：`RegionDataPackets.java:55`、`EditorData.java:715`。
- `int[] getTargets(int state)` / `double[] getCosts(int state)`：边目标/代价的拷贝。调用者：`RegionDataPackets.java:53-54`、`EditorData.java:716-717`。

### `boolean canReach(long fromPlatformId, long toPlatformId)`
- 作用：一站台的列车能否到达另一站台（可经过其他站台）。可以任一方向离开（即在起始站台折返算数），但之后不得再折返。两站台不在图中或不在同一网络时为 false；否则从起点两个可用状态做 BFS。
- 调用者：`EditorActions.java:840`、`PlatformGraphTest.java:117`。
