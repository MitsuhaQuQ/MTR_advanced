# FareNetwork.java

票价计算所用的网络（设计文档 6.13）：车站为节点，每条线路相邻两停站之间的轨道为一条"连接"（Join），连接标记所属路线组（公司）。计价只看进站和出站的车站，不看实际乘坐的列车；连接双向可走。票价路径的选择规则：先使跨越路线组的次数最少，次数相同再取最短；多个组共用的轨道按"行程当前所在的组"计，避免不必要的拆段。

在整体中的位置：游戏侧 `FareData`（`FareData.java:136`）在线路变化时用所有 `Leg` 重新构造本对象并设置参考线长度（`FareData.java:138`）；`FareTrip.charge` 调 `find` 和 `getLongestBlocks` 计价。不依赖其他 transitsim 类。

## 类 FareNetwork（final）

### 常量与字段
- `CROSSING_COST = 1E12`（private static）：每跨越一次组附加的代价，远大于任何距离，使"跨组次数"成为第一排序键。
- `joins`（`Map<Long, List<Join>>`）：车站 id → 从该站出发的连接列表（每条无向连接在两端各存一次）。
- `longest`（`Map<String, Long>`）：组 → 组内最长票价距离的缓存（或手工设定值）。
- `cache`（`LinkedHashMap<CacheKey, Path>`，访问顺序）：已找到的票价路径 LRU 缓存（2026-11-12 加入），上限 `MAX_CACHED_PATHS = 8192`；未连通结果用哨兵 `NO_PATH` 存。网络在线路变化时整体重建，所以缓存无需失效逻辑。
- `NO_PATH`：空 parts、crossings = -1 的哨兵 `Path`，仅表示缓存中的"无路径"。

### 匿名子类方法 `removeEldestEntry(Map.Entry)`
- 作用：缓存超过 `MAX_CACHED_PATHS` 时淘汰最久未使用项。由 `LinkedHashMap` 在 put 后自动调用。

### 构造函数 `FareNetwork(Collection<Leg> legs)`
- 作用：由线路区段建图。跳过起终站相同或 `blocks < 0` 的区段；对同一（组、无序站对）只保留最短长度；然后在两端站各加一条 `Join`。
- 参数：`legs` —— 每条为一条线路两相邻停站间的轨道：组名、两站 id、长度（方块）。
- 副作用：填充 `joins`。
- 主要调用者：`FareData.java:136`、`FarePathEquivalenceTest.java:32`。

### `Set<String> getGroups(long station)`
- 作用：返回在该站有连接的所有组。
- 参数：`station` 车站 id。返回：新的 `HashSet`（无连接则为空）。副作用：无。
- 主要调用者：`ReferenceFareNetwork.java:55`（测试参照实现）；本类 `getLongestBlocks`（第 90 行）。

### `synchronized void setLongestBlocks(String group, long blocks)`
- 作用：直接设定某组的最长距离（0.73.0 加入）：票价规则的"参考线"长度，其档位生成到此长度为止。`blocks <= 0` 时不做任何事，保持自动计算。
- 参数：`group` 组名；`blocks` 长度（方块）。
- 副作用：写 `longest`。
- 主要调用者：`FareData.java:138`、`FareTest.java:70`。
- 注：源码在此方法前有一段孤立的 javadoc（"组内两站沿组自身轨道的最长票价距离"），实际描述的是下面的 `getLongestBlocks`。

### `synchronized long getLongestBlocks(String group)`
- 作用：组内任意两站沿该组自身连接的最长最短路距离。首次计算后缓存在 `longest`；计算方式为对该组每个车站调用 `farthest` 取最大值（O(站数 × Dijkstra)）。
- 参数：`group` 组名。返回：距离（方块），组不存在时 0。
- 副作用：可能写 `longest`。
- 主要调用者：`FareTrip.java:101`、`FarePackets.java:50`、`FarePathEquivalenceTest.java:51`。

### `Path find(long fromStation, long toStation, Set<String> startGroups, Set<String> endGroups)`
- 作用：单个终点组集合的便捷形式，委托给数组版 `find`。
- 参数：起终站 id；`startGroups` 行程可起始的组（空=任意）；`endGroups` 行程应结束的组，结束在其他组算多跨一次（空=任意）。
- 返回：票价路径，不连通时 null。
- 主要调用者：`FarePathEquivalenceTest.java:40`。

### `Path[] find(long fromStation, long toStation, Set<String> startGroups, List<Set<String>> endGroupSets)`
- 作用：同时为多组"终点组集合"求票价路径（2026-11-12）。先查缓存，缺失的那些合并为一次 `search`（只有"结束在某组"的代价不同，一次搜索即可服务全部），结果写回缓存。每个结果与单集合版 `find` 给出的一致。
- 参数：同上；`endGroupSets` 每个想要的路径对应一个终点组集合。
- 返回：与 `endGroupSets` 等长的数组，不连通处为 null。
- 副作用：读写 `cache`（在 `synchronized (cache)` 内）。
- 主要调用者：`FareTrip.java:90`；本类单集合版 `find`（第 105 行）。

### `private Path[] search(long fromStation, long toStation, Set<String> startGroups, List<Set<String>> endGroupSets)`
- 作用：在状态（车站, 最后所在组）上做 Dijkstra。代价 = 跨组次数 × `CROSSING_COST` + 距离；起点状态为每个起始组一个（`startGroups` 空则组为 null 的单一起点，从 null 组出发不算跨越）。到达终点站时，对每个集合计算结束代价（不在期望组内再加一次跨越），记录各集合最优；当出队代价 ≥ 所有集合最优中的最大值时停止。`best` 中存 [总代价, 纯距离]。
- 返回：每个集合的 `Path`；未到达时若起终站相同返回空路径（0 跨越），否则 null。
- 副作用：无（局部数据结构）。
- 调用者：本类 `find`（第 136 行）；`FarePathEquivalenceTest.java:19` 引用名称。

### `private static Path makePath(State end, Map<State,State> previous, Map<State,Long> previousBlocks, Set<String> startGroups, Set<String> endGroups)`
- 作用：从终点状态沿 `previous` 回溯，按组合并相邻同组连接为 `Part`；跨越数 = parts 数 − 1，若第一段组不在 `startGroups`（非空时）或最后一段不在 `endGroups`（非空时）各再加 1。
- 返回：新 `Path`。调用者：`search`（第 221 行）。

### `private long farthest(long fromStation, String onlyGroup)`
- 作用：只沿 `onlyGroup` 的连接做 Dijkstra，返回从该站到达的最远最短路距离（方块）。
- 调用者：`getLongestBlocks`（第 93 行）。

## 内部类 Leg（public static final）
输入数据：一条线路两相邻停站间的轨道。字段 `group`（组名）、`fromStation`、`toStation`（车站 id）、`blocks`（长度，方块；<0 被构造函数忽略）。构造函数按字段赋值。调用者：`FareData.java:204`。

## 内部类 Path（public static final）
一条票价路径。
- `parts`（不可修改 `List<Part>`）：按组分段，顺序从起点到终点。
- `crossings`（int）：跨组次数（含起止不在期望组时的额外计数）。
- 构造函数 `Path(List<Part>, int)`（包内可见）：包装为不可修改列表。调用者：`makePath`、`search`、`ReferenceFareNetwork.java:143`。
- `long getBlocks()`：各段距离之和（方块）。调用者：`FareTrip.java:104`、`FareTest.java:95`。

## 内部类 Part（public static final）
路径的一段：`group`（组名）、`blocks`（该组内连续距离，方块）。构造函数按字段赋值；调用者 `makePath`（第 236、238 行）。

## 内部类 Join（private static final）
一条连接的一端视角：`station`（对端车站 id）、`group`、`blocks`（长度）。构造函数调用者：构造函数第 59、60 行。

## 内部类 CacheKey（private static final）
缓存键：起终站 + 起始组集合拷贝 + 终点组集合拷贝；`equals`/`hashCode` 按这四项值比较。构造调用者：`find`（第 123、141 行）。

## 内部类 State（private static final）
Dijkstra 状态：`station` + `group`（最后所在组，可为 null）；`equals`/`hashCode` 按值。调用者：`search`。
