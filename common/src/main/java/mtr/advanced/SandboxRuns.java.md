# SandboxRuns.java

为高级编辑器模拟的一天（S5）：把世界数据加上玩家的草稿，由组件 `transit-sim` 在它自己的"世界"中、在后台线程上运行，因此不会碰到游戏中的列车。每个玩家同时只有一次运行，新请求会停止前一次。服务端代码；包 `PACKET_SIMULATE` 由 `MTR.java:517` 注册到 `receiveSimulateC2S`；结果包 `PACKET_SIMULATE_RESULT` 由客户端 `EditorSimulation.receive` 处理。开关为 `SecurityConfig.editorSimulation`（调试用，默认关闭）。组件侧：`SimDataset`（数据集）、`SimScope`（裁剪到相关车厂）、`DayRun`（运行一天并产出报告）。

## final class SandboxRuns implements IPacket

### 常量与静态状态
- 状态码：`STATUS_RESULT = 0`、`STATUS_BUSY = 1`（排队过多或运行超时）、`STATUS_NO_PERMISSION = 2`、`STATUS_ERROR = 3`、`STATUS_DISABLED = 4`（服务器未开启编辑器模拟）。
- `MAX_DRAFTS = 256`、`MAX_REPORT_ROUTES = 256`（public）：草稿与报告线路上限。
- `MAX_WAITING = 8`：同时排队/运行的上限。
- `TIME_LIMIT_NANOS = 60 s`：单次运行时限。
- `PART_BYTES = 512 KiB`：结果分片大小。
- `HOUR`：1 小时毫秒数。
- `EXECUTOR`：单线程执行器，守护线程 "MTR-Editor-Simulation"，最低优先级。
- `RUNS`：玩家 UUID → 当前运行的停止标志。
- `WAITING`：排队/运行中的数量。

### `private SandboxRuns()`
工具类，禁止实例化。

### `public static void receiveSimulateC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet)`
- 作用：读请求包（网络线程），经 `PacketGuard.execute` 在服务器线程上 `start`。
- 包字段顺序：`int requestId, int weekday, int n + long×n 报告线路, int m + byteArray×m 线路草稿（SimDataset.RouteData.toBytes）, int k + (long depotId, byteArray 运用 DepotDuties.toBytes)×k, boolean fromNow`。各数量截断到上限（注意：截断后剩余数据不会被跳过）。
- 调用者：`MTR.java:517`（注册）。

### `private static void start(MinecraftServer minecraftServer, ServerPlayer player, int requestId, int weekday, Set<Long> reportRoutes, List<byte[]> draftRoutes, Map<Long, byte[]> draftDuties, boolean fromNow)`
- 作用：检查开关、权限、铁路数据、排队数（失败发对应状态）。在服务器线程上读世界：`SimDataset.fromBytes(SimExport.build(railwayData))`，`putDrafts` 放入草稿；按星期求日起点；`fromNow` 且列车记录时间在该运营日内时从当前列车状态开始（G0），否则从日起点（所有列车在车厂）；准备运用草稿的找路输入。停止该玩家之前的运行，排入执行器：在时限内 `findDutyPaths`、`SimScope.of(dataset, reportRoutes)`（只保留线路相关部分：其车厂及列车经过同一区域的车厂）、`DayRun.run(...)`（以停止标志或超时为取消条件）；未被新请求停止时回到服务器线程发结果（被取消为 `BUSY`）。异常发 `ERROR`。

### `static int putDrafts(SimDataset dataset, RailwayData railwayData, List<byte[]> draftRoutes, Map<Long, byte[]> draftDuties)`
- 作用：线路草稿只有在仍与服务器线路逐站相同时才计入；若线路已找到路径，复制线路、按草稿设置通过站、读草稿时刻表，为每张表用运行该线路的车辆重新计算最快时分（`path.getFastestTimes`，`TimetableTimes.pack`）。然后 `dataset.replaceRoute(draft)`。运用草稿：车厂存在时 `dataset.replaceDuties(depotId, diagramMode ? bytes : 空)`。
- 返回：被忽略的草稿数（不匹配、替换失败或异常）。
- 调用者：本类 `start`。

### `private static List<DutyPathSearch> prepareDutyPaths(RailwayData railwayData, ServerPlayer player, Map<Long, byte[]> draftDuties)`
- 作用（S6）：只按运用发车的列车只运行车厂沿轨道找到路径的运用，而草稿中新的运用形状还没有路径。为每个存在且为运用图模式（`diagramMode`）的草稿准备找路输入（在服务器线程上取车厂的路径图 `depot.getPathGraph` 与是否快速 `usesFastSpeed`）。

### `static int findDutyPaths(SimDataset dataset, List<DutyPathSearch> searches)`
- 作用：像车厂那样找草稿运用的路径（`depot.findDutyPaths`），按键排序后追加到数据集中对应停车线的 `tripPaths`（排在已有路径之后，使同一形状的新路径替代旧的），轨道段由 `SimExport.toTracks` 转换。
- 返回：没有路径的运用形状数（按 `车厂id:键` 去重）。
- 调用者：本类；`Depot.java:1605`；测试 `DutyTurnBackTest.java:70`。

## private static final class DutyPathSearch
- 字段：`depot`、`duties`、`railwayData`、`graph`、`useFastSpeed`、`sidings`（服务器线程上的停车线集合拷贝）、`rails`（轨道拷贝 `DataCache.copyRails`）、`dataCache`（`copyIdMaps`）——拷贝是因为服务器线程会继续修改自己的数据。
- 构造函数在服务器线程上执行拷贝。

### `public static long getDayStart(long millis, int weekday)`
- 返回：该星期几的下一个运营日的开始时刻；今天就是该星期几时为今天的开始。最多向后找 7 天（取日起点 + 1 小时判断星期，避免边界）。
- 调用者：本类；测试 `EditorSimulationTest.java:65`。

### `private static void sendStatus(ServerPlayer player, int requestId, int status, int ignoredDrafts)` / `sendStatus(..., int depots, int allDepots)`
- 包字段顺序（与结果包相同）：`int requestId, int status, int ignoredDrafts, int missingDutyPaths(=0), int depots, int allDepots, int part(=0), int partCount(=0), byteArray(空)`。

### `private static void sendResult(ServerPlayer player, int requestId, int ignoredDrafts, int missingDutyPaths, int depots, int allDepots, byte[] bytes)`
- 作用：把 `DayRun` 报告按 512 KiB 分片，每片一个包（字段同上，status = `RESULT`，part = i，partCount = 片数）。

### `public static List<byte[]> split(byte[] bytes, int partBytes)` / `public static byte[] join(byte[][] parts)`
- 分片与合并；空数组也产生一个空分片。
- 调用者：本类、`EditorSimulation.java:209`（join）。
