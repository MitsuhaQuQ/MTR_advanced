# TurnBacks.java

一条停车线的列车在路线末站之后可以选多条折返轨的所有位置（折返点）。在每个折返点，列车离开停站时选第一条空闲的折返轨。常规路径不走的那些走法的路径与服务路径一起保存，key 见 `TripPath.getTurnBackKey`。本类可序列化（`KeyedReader`/`KeyedWriter`），由游戏侧 `mtr.data.Siding` 生成/保存（`Siding.java:335`、`:514`、`:1173`、`:1203`），组件 `transitsim.sim.SimDataset` 读取，`transitsim.sim.TrainService` 在运行时查询（`TrainService.java:1408` 起）。

## 类 `TurnBacks`

### 常量
- `KEY_POINTS = "turn_back_points"`、`KEY_STOPS = "turn_back_stops"`：序列化键名。
- `NONE`：没有折返点的空实例。

### 字段
- `points`（private final）：折返点列表。
- `depotStops`（private final）：常规路径上每个折返点之前停站的轨道下标；后加的（运用专有）折返点为 -1。

### `private TurnBacks(List<TurnBackPoint> points, int[] depotStops)`
原样赋值。

### `public static TurnBacks fromSections(List<? extends TurnBackSection<?>> turnBackSections, int[] depotStops)`
- 作用：每个 `TurnBackSection` 生成一个 `TurnBackPoint`。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:1173`。

### `public TurnBacks with(List<TurnBackPoint> more)`
- 作用：追加常规路径不经过的折返点（如运用中的），编号排在已有之后，其 `depotStops` 填 -1。
- 返回值：`more` 为空时返回自身，否则新实例。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:1203`、`Siding.java:1285`。

### `public int size()`
折返点数量。

### `public static TurnBacks of(int[] depotStops, TurnBackPoint... points)`
- 作用：用别处算好的折返点构造，供无世界运行服务时使用。
- 主要调用者：main 代码中 grep 未见调用（可能用于测试，未确定）。

### `public static TurnBacks read(KeyedReader reader)`
- 作用：读 `KEY_POINTS`（每个点一个 long 数组，见 `TurnBackPoint.read`）与 `KEY_STOPS`。
- 返回值：点数与停站数相等且非空时新实例，否则 `NONE`。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:335`、`transitsim/sim/SimDataset.java:459`。

### `public void write(KeyedWriter writer) throws IOException`
写出上述两个键。主要调用者：`common/src/main/java/mtr/data/Siding.java:514`、`Siding.java:1868`。

### `public int getPoint(int[] stops, int stopIndex)`
- 作用：找停站下标等于 `stopIndex` 的第一个折返点。
- 参数：`stops` 列车所在路径各折返点的停站下标（`TripPath.turnBackStops`），null 表示常规路径（用 `depotStops`）；`stopIndex` 停站下标（轨道下标）。
- 返回值：折返点编号；没有时 -1。
- 主要调用者：`transitsim/sim/TrainService.java:1408`、`TrainService.java:1568`。

### `public int getStop(int[] stops, int point)`
- 返回值：该路径上此折返点之前停站的轨道下标；路径不经过（越界）时 -1。
- 主要调用者：`transitsim/sim/TrainService.java:1444`、`TrainService.java:1467`。

### `public TurnBackPoint get(int point)`
返回第 `point` 个折返点。

### `public List<UUID> getCandidates(UUID rail)`
- 作用：若某折返点的默认走法在 `rail` 上折返，返回该点每种（非空）走法的第一条折返轨；否则只返回 `rail` 本身。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:2047`。
