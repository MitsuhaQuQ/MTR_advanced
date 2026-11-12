# PlanCheck.java

用运行内核检验规划器算出的运用（S6）：规划器对折返只用粗略时间来数列车，运用是否真能跑通要靠用它们模拟一天来看。提供从 `SimDataset`（世界数据）生成运用的方法（单车厂或按运营线路组成的规划单元），以及用 `transitsim.sim.DayRun` 模拟一天并统计问题。依赖 `DutyGenerator`、`SimDataset`、`DayRun`、`ThroatLocks.position`。main 代码中没有调用者，只在测试中使用：`transit-sim/src/test/java/transitsim/sim/PlanCheckTest.java`、`WholeDayTest.java`、`DutyPlacementTest.java` 等。

## 类 `PlanCheck`（final）

### 字段
- `dayRun`：模拟一天的结果。
- `notRun`：没有运行的班次问题数（`DayRun.Problem` 类型 `NOT_RUN`）。
- `late`：晚点问题数（`LATE`）。
- `deadlocks`：死锁问题数（`DEADLOCK`）。
- `held`：其它问题数：等站台、线上停住、停站超过计划。
- `MILLIS_PER_BLOCK = 50`（私有常量）：选最近停车线时，列车跑一个方块到首站所需毫秒（与游戏一致）。

### `private PlanCheck(DayRun dayRun)`
按问题类型计数。

### `public boolean passes()`
所有班次都跑了、准点、无互锁时为 true（`held` 不计入）。

### `public static DutyGenerator.Result generate(SimDataset dataset, long depotId, int dayStartMillis)`
- 作用：像编辑器那样从世界数据算出一个车厂的运用：其路线的班次（时间取自停车线路径的最快时间），其停车线（`maxTrains ≥ UNLIMITED_TRAINS` 时视为不限，传 -1）。数据中没有车站，所以列车只能在到达的站台原地折返（`stationOf` 为空）。最长停留按车厂 `cycleRoutes` 取 `MAX_STAY_MILLIS` 或 `MAX_LAYOVER_MILLIS`。
- 参数：`dayStartMillis` 运营日开始（一天中 UTC 毫秒）。
- 返回值：`DutyGenerator.Result`；车厂不存在时为空结果。

### `public static PlanCheck check(SimDataset dataset, long depotId, Map<Long, List<DepotDuties.Slot>> slots, long dayStart)`
- 作用：在数据副本上（`toBytes`/`fromBytes` 深拷贝）把该车厂的运用替换为 `slots`（`diagramMode = true`），然后对该车厂路线模拟一天（`DayRun.run`），返回统计。数据需要包含运用形状对应的路径（游戏的车厂会沿轨道找到它们）。
- 参数：`slots` 各停车线的槽位（`DutyGenerator.Result.sidings` 的格式）；`dayStart` 运营日开始的绝对毫秒。
- 副作用：不修改原 `dataset`。

### `public static DutyGenerator.Result generateByLine(SimDataset dataset, long depotId, int dayStartMillis)`
- 作用：像游戏的 `LineDuties` 那样按运营线路为车厂所在的规划单元算运用：运行同一线路（以及通过直通班次相连的线路）的车厂一起规划。每条停车线的出库时间按"停车线路径起点节点到本车厂各路线首站节点的直线距离 × `MILLIS_PER_BLOCK`"估计（越近越早）。与游戏不同：数据中没有车辆服务类型，所以单元内所有停车线都可跑所有路线，也没有需要排除的手工运用。任一车厂 `cycleRoutes` 则按留在线上规划。
- 返回值：单元内所有停车线的运用；单元为空时为空结果。

### `public static PlanCheck checkByLine(SimDataset dataset, long depotId, Map<Long, List<DepotDuties.Slot>> slots, long dayStart)`
- 作用：在数据副本上把单元内每个车厂的停车线运用替换为 `slots` 中对应部分，对单元所有路线模拟一天并返回统计。

### `private static void addPlatformNode(Map<Long, Long> platformNodes, SimDataset.DataTrack track)`
站台轨道（有 stop ID）时记录站台 → 轨道起点节点（首次出现为准）。

### `private static Unit unitOf(SimDataset dataset, long depotId)`
- 作用：求与该车厂一起规划的车厂和路线：从车厂出发，广度优先地经由"车厂路线所属线路 → 该线路的路线 → 运行这些路线的其它车厂"以及"直通班次相连的线路"扩展。没有线路 key 的路线自成一条线路（`"route:" + id`）。
- 返回值：`Unit`（车厂列表按数据中的顺序；路线 ID 有序）；车厂不存在时为空。

### `private static SimDataset.DepotData findDepot(SimDataset dataset, long depotId)`
按 ID 查找车厂，没有为 null。

## 内部类 `Unit`（private static final）
`depots`（`SimDataset.DepotData` 列表）、`routeIds`（`TreeSet`）。
