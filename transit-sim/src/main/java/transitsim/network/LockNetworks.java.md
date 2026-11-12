# LockNetworks.java

把新运行逻辑与旧逻辑隔开（解耦文档 5.4，L2）：按运用（duty）运行的列车在站咽喉区加锁，按频率发车的列车不加锁；两者不能共用轨道，因为锁只有在轨道上所有列车都参与时才有效。"轨道"指各车厂列车运行经过的部分：其停车线路径和运用路径，在共用节点处连成一片；路径边界把路径分开，因此也切分网络。若某个网络上有旧逻辑车厂，该网络上按运用运行的车厂关闭咽喉锁。

两个宿主用各自的路径以同样方式计算。使用者：游戏侧 `RailwayData`（`RailwayData.java:131-137`）、`Siding.java:1560`、编辑器 `EditorData.java:140`；组件 `SimDataset.java:479`、`SimDataset.java:397`。实现为节点上的并查集。

## 类 LockNetworks（final）

### 字段
- `parent`（`Map<Long,Long>`）：并查集父指针，节点 id → 父节点。
- `depotNodes`（`TreeMap<Long,Long>`）：每个车厂路径中的一个代表节点（第一次加入路径的首节点）。
- `newLogic`（`TreeMap<Long,Boolean>`）：车厂是否按新逻辑（运用）运行。

### `void addDepot(long depotId, boolean runsByNewLogic)`
- 作用：登记车厂；同一车厂多次登记时取逻辑与（只要一次为旧逻辑即视为旧）。
- 副作用：写 `newLogic`。
- 调用者：`RailwayData.java:131`、`EditorData.java:140`、`SimDataset.java:479`。

### `void addPath(long depotId, long[] nodes)`
- 作用：加入车厂某列车路径经过的节点序列：把首节点与车厂代表节点合并（无代表则设为代表），再把相邻节点两两合并。空数组忽略。
- 副作用：写 `depotNodes`、`parent`。
- 调用者：`RailwayData.java:132`、`RailwayData.java:1070`、`Siding.java:1560`。

### `Result result()`
- 作用：按并查集根把车厂分到各网络；未登记逻辑的车厂默认按新逻辑。新旧车厂并存的网络记入 `mixed`，其新逻辑车厂记入 `offDepots`（只有它们会加锁，所以只关它们）。`networks` 为网络总数。
- 返回：新的 `Result`。副作用：并查集路径压缩。
- 调用者：`RailwayData.java:135`、`EditorData.java:145`、`SimDataset.java:397`。

### `private long find(long node)`
并查集查找（首次见到的节点自成一集），带路径压缩。调用者：`addPath`、`result`、`union`。

### `private void union(long node1, long node2)`
合并两集，较大的根指向较小的根（结果与插入顺序无关）。调用者：`addPath`。

## 内部类 Result（public static final）
- `offDepots`（`TreeSet<Long>`）：位于含旧逻辑车厂网络上的按运用运行车厂，其列车不锁咽喉。
- `mixed`（`List<Network>`）：新旧车厂并存的网络。
- `networks`（int）：网络总数。
- `String describe()`：把各混合网络的旧/新车厂集合拼成字符串，用于判断结果是否变化。调用者：`RailwayData.java:137`。

## 内部类 Network（public static final）
一个网络中的车厂：`oldDepots`（旧逻辑）、`newDepots`（新逻辑），均为 `TreeSet<Long>`。
