# DelayAlarms.java（本分支新增）

车厂按运用运行的列车中，晚点或卡住而没有应急预案处理的告警（设计文档 D2）：在服务端由仿真组件的 `transitsim` `DelayRules` 计算，原因变化时写日志，并发给客户端供网页编辑器显示（调度员可在那里启用预案或指定对该列车的处置）。同一遍还算出需要通知列车的越站（D3）、提前折返（D4）、交给备车（D5）变化，放进 `Lookups` 由调用方下达。调用者：`common/src/main/java/mtr/data/RailwayData.java:832`–`:866`；客户端接收注册于 `common/src/main/java/mtr/MTRClient.java:337`，结果存入 `Depot.clientAlarms`。

## 包格式（S2C `PACKET_DELAY_ALARMS`）
`long 车厂 id`、`long 服务器当前毫秒`（客户端据此求时钟偏差，按服务器时间计算晚点多久）、`int 条数(≤64)`、每条 `String(≤256，超长截断)`。

## 类 `DelayAlarms`（final，implements `IPacket`，私有构造）

### 常量
- `MAX_ENTRIES = 64`、`MAX_LENGTH = 256`。

## 内部类 `Lookups`（final）：一遍扫描中按线路缓存的查询结果和待下达的变化
- `anchorsByRoute`：线路 → 其轨道系统锚点列表（`systems.anchorsForRoute`）。
- `anchorByRoute`：线路 → 单个锚点（无预案系统时用）。
- `stationPlatforms`：车站 → 站台 id 列表（首次需要时由 `dataCache.platformIdToStation` 建立）。
- `skips`（public）：列车 → 应越过的站台集合（D3）；空集合表示取消。
- `turns`（public）：列车 → `{模式, 之后, 站台…}`（D4 提前折返）。
- `handOvers`（public）：列车 → 是否把运用交给备车（D5）。
- `private Collection<Long> getStationPlatforms(DataCache, long stationId)`。

### `static List<String> collect(Collection<Siding> depotSidings, DataCache dataCache, Set<Long> deadlocked, ContingencySystems systems, PlatformGraph platformGraph, Lookups lookups)`
- 作用：对车厂每列车：
  - 不在运用中：若之前被告知越站/折返/交接，放入 `lookups` 取消；
  - 在运用中：取晚点起始时刻、等待起始时刻、晚点毫秒、等待毫秒；取其线路所在所有轨道系统的预案（锚点升序，任一系统都不丢）；是否在互相等待的死锁环中（且正在等）；`DelayRules.evaluate` 得结果；再分别求越站站台、提前折返、交接，与当前不同时放入 `lookups`；
  - 有告警（`outcome.alarm`）或已被预案/调度员处理（`outcome.contingency`）时生成一条文本。
- 返回：告警行按字典序排序，然后一个 `"|"`，然后"已处理"行排序。告警行格式：`<列车> <停车轨> <运用槽> <线路> <原因> <晚点起始> <等待起始> <等待调度的预案或0> <系统锚点> <是否正在越站0/1> <折返状态0–3> <交接状态> <调度状态>`（最后一项为 `service.getDispatchState()`）；折返状态 0 未要求、1 前往中、2 已折返、3 被告知的车站无法折返；交接状态见 `TrainService.getTakeoverState`。"已处理"行同样格式，原因为空时写 `-`，预案位置为所用预案 id，若是调度员对该列车的手动设置（`adhoc`）则为 0。晚点/等待起始时刻在情况不变时保持不变，所以列表只有真正变化时才需要重发。
- 参数：`deadlocked` 互相等待成环的列车 id。

### `static void log(Depot depot, List<String> alarms, Map<Long, String> before)`
- 作用：解析每行得出"列车 → 原因"（已处理的加 `plan <预案> ` 前缀），与上次比较，变化的打印 `depot … train … reported, …` 或 `falls under plan …`；最后用本次结果替换 `before`。

### `static void sendS2C(Level world, long depotId, List<String> alarms)`
- 发给该世界所有玩家（最多 64 条，每条截断到 256 字符）。

### `static void receiveS2C(FriendlyByteBuf packet)`
- 客户端：条数越界抛 `IllegalArgumentException`；在主线程把列表和时钟偏差写入对应车厂的 `clientAlarms`、`clientAlarmClockOffset`。

### `static Contingency.Reason parseReason(String text)`
- 把行中的原因文本解析为枚举，不认识返回 null。
