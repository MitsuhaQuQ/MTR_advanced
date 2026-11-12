# OperationNetwork.java

列车分配（运用规划）所依赖的那部分轨道网络信息：站台所属车站、站台折返时间、站台间空车连接时间、折返线等待位（混合折返）、等待阈值、车厂（停车线）及其出库/回库时间和车辆池。没有添加的移动视为不可达；全部用有序映射，保证遍历顺序固定（确定性）。由 `DutyGenerator.networkOf`（`DutyGenerator.java:291` 起）构建，`DutyPlanner`、`DutyCheck` 查询。

## 类 `OperationNetwork`（final）

### 常量与字段
- `UNREACHABLE = -1`：不可达。
- `turnBackHolds`：站台 → `{驶入毫秒, 驶回毫秒, 可同时等待列车数, 折返轨标识}`。
- `stations`：站台 → 车站。
- `turnaroundMillis`：站台 → 折返所需毫秒。
- `connections`：起站台 → (终站台 → 毫秒)。
- `depots`：车厂 ID → `Depot`。
- `poolFleets`：池 → 该池一周开始时的列车总数。
- `waitThresholds`：站台 → 等待阈值毫秒。

### `public OperationNetwork setStation(long platformId, long stationId)`
登记站台的车站（用于与同站使用相同轨道折返的站台）。返回自身。主要调用者：`transitsim/planning/DutyGenerator.java:293`。

### `public long stationOf(long platformId)`
站台的车站，未知为 0。

### `public OperationNetwork setTurnBackHold(long platformId, long inMillis, long outMillis)` / `(…, int count)` / `(…, int count, long rail)`
- 作用：登记站台外的折返线，列车可在那里等下一班并让出站台（混合折返）。`inMillis` 驶入、`outMillis` 驶回站台的毫秒；`count` 能同时等待的列车数（运用补完第 4 步：站台外每条折返线、每个停放区段各容一列；按最近者计时），至少 1，默认 1；`rail` 是哪条折返轨，同一折返轨服务的各站台相同（8号线，2026-11-12：同站两个站台在中间同一条线上折返），未知为 0。
- 主要调用者：`transitsim/planning/DutyGenerator.java:355`。

### `public long turnBackRail(long platformId)`
站台外列车等待用的折返轨标识；无或未知为 0。主要调用者：`transitsim/planning/DutyPlanner.java:585`。

### `public List<Long> platformsTurningOn(long rail)`
使用该折返轨的站台（`rail` 为 0 时为空）。主要调用者：`transitsim/planning/DutyPlanner.java:625`。

### `public long[] turnBackHold(long platformId)`
返回 `{驶入, 驶回, 容量, 轨}`，没有为 null。主要调用者：`transitsim/planning/DutyCheck.java:296`、`transitsim/planning/DutyPlanner.java:489`、`:581`。

### `public OperationNetwork setTurnaround(long platformId, long millis)`
登记站台折返时间。主要调用者：`transitsim/planning/DutyGenerator.java:345`。

### `public OperationNetwork setWaitThreshold(long platformId, long millis)` / `public long waitThreshold(long platformId)`
- 作用：站台上等下一班的时间达到多久时改为到站台外折返线等待（运用补完第 2 步：车厂规则，否则车站的，否则线网的）。未设置时为 `transitsim.sim.TrainService.LONG_TURN_BACK_WAIT_MILLIS`。
- 主要调用者：`waitThreshold` 见 `transitsim/planning/DutyPlanner.java:465`；`setWaitThreshold` main 代码中 grep 未见调用（未确定）。

### `public OperationNetwork addConnection(long fromPlatformId, long toPlatformId, long millis)`
登记站台间空车连接时间。主要调用者：`transitsim/planning/DutyGenerator.java:363`、`DutyGenerator.java:368`。

### `public OperationNetwork addDepot(long depotId, int trainCount)` / `addDepot(long depotId, int trainCount, String poolKey)`
- 作用：登记车厂（实际为停车线）及其列车数；`poolKey` 为互相跑对方运用、回到下一个需要列车者的车厂组（如同一池的停车线），null 时为自己单独一池（`"#" + id`）。
- 主要调用者：`transitsim/planning/DutyGenerator.java:295`。

### `public OperationNetwork setPoolFleet(String poolKey, int trains)`
- 作用：让一个池的车厂在一周开始时共有这么多列车（P2-C、R3），而不是各自满员；`poolKey` 为 null 或 `trains < 0` 时忽略。
- 主要调用者：`transitsim/planning/DutyGenerator.java:296`。

### `public Map<Long, Integer> getStartingTrains()`
- 返回值：每个车厂一周开始时的列车数：未设池总数时为其容量；设了时按车厂 ID 顺序，每个取 `min(容量, 池剩余)`。
- 主要调用者：`transitsim/planning/DutyPlanner.java:188`。

### `public OperationNetwork addDepotLink(long depotId, long platformId, long millis)`
登记车厂出库到站台的时间。车厂不存在时 `NullPointerException`。主要调用者：`transitsim/planning/DutyGenerator.java:314`、`:318`、`:336`。

### `public OperationNetwork addDepotPassing(long depotId, long platformId, long passedPlatformId)`
登记车厂到 `platformId` 的出库路在折返进入它之前经过 `passedPlatformId`（如经由终点站折返线出库）。主要调用者：`transitsim/planning/DutyGenerator.java:321`。

### `public OperationNetwork addDepotBackLink(long depotId, long platformId, long millis)`
登记从站台回车厂的实测时间（`UNREACHABLE` 表示路径生成发现回不去）。主要调用者：`transitsim/planning/DutyGenerator.java:341`。

### `public long turnaroundMillis(long platformId)`
站台折返时间，未登记为 0。主要调用者：`transitsim/planning/DutyCheck.java:280`、`transitsim/planning/DutyPlanner.java:817`、`:896`。

### `public long connectionMillis(long fromPlatformId, long toPlatformId)`
同一站台为 0；否则登记值，未登记为 `UNREACHABLE`。主要调用者：`transitsim/planning/DutyCheck.java:275`。

### `public List<Depot> depots()` / `public Depot depot(long depotId)`
全部车厂（新的不可修改列表）/ 按 ID 查找（无则 null）。主要调用者：`transitsim/planning/DutyPlanner.java:190`、`:266`、`:364`、`transitsim/planning/DutyCheck.java:335`。

## 内部类 `Depot`（public static final）
- `id`、`trainCount`、`poolKey`。
- `links`（私有）：站台 → 出库毫秒；`backLinks`（私有）：站台 → 回库毫秒；`passes`（私有）：首站台 → 出库路折返进入它之前经过的站台（6号线，2026-11-12：出库经过该终点站的折返线，排在在那里折返的列车之后）。
- `linkMillis(long platformId)`：出库时间，未登记为 `UNREACHABLE`。主要调用者：`transitsim/planning/DutyCheck.java:162`、`transitsim/planning/DutyPlanner.java:439`、`:519`。
- `passesBefore(long platformId)`：经过的站台，无为 0。主要调用者：`transitsim/planning/DutyPlanner.java:442`。
- `canGoBack(long platformId)`：没有被判定回不去（回库值不是 `UNREACHABLE`）即为 true。主要调用者：`transitsim/planning/DutyCheck.java:332`、`:335`。
- `backMillis(long platformId)`：回库时间：实测值；否则该站台的出库时间；都没有时用本车厂最长的出库时间（而不是 0，免得像列车一到站就已回库）。注意：若实测值为 `UNREACHABLE`（-1）会原样返回 -1，调用方 `DutyPlanner.java:364` 用 `Math.max(…, 0)` 处理。主要调用者：`transitsim/planning/DutyPlanner.java:364`、`:528`。
- 源码中 `canGoBack` 前有一段描述 `backMillis` 的孤立 Javadoc（两段注释相邻），属于注释位置错放，不影响行为。
