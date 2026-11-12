# ContingencySystems.java

每个轨道系统的应急预案（D2）：对一个相互连通的轨道网络中的所有线路（不论由哪个车厂的列车运行）有一套预案及其状态——按用户要求，以网络而不是车厂为单位。由于每次轨道变化网络都会重算，一个系统以其网络中的一个站台（锚点 anchor）标识；线路属于锚点与其首个站台在同一网络中的那些系统。

两系统之间铺设轨道会把它们连起来，但两者都不会丢失：都继续保存并生效（锚点较小者优先），网页编辑器显示由两者组成的网络，直到玩家把它们合并为一个。锚点站台被删除时，系统迁移到它事先记下的同网络另一个站台（`backups`）。预案本体是组件 `transit-sim` 的 `Contingencies`。由 `RailwayData`（`:818` 保持锚点、`:1159` 发包）、`RailwayDataFileSaveModule`（存档）、`DelayAlarms`（晚点告警，`:101`、`:131`）、`ContingencyPackets`、`EditorContingencies` 使用。

## final class ContingencySystems

### 字段与常量
- `MAX_SYSTEMS = 64`（public）：读包时系统数上限。
- `systems`（`TreeMap`）：锚点站台 → 预案与状态。
- `backups`（`TreeMap`）：锚点 → 同网络其它站台（升序，最多 `MAX_BACKUPS = 8` 个）。

### `public List<Long> anchorsForRoute(Route route, PlatformGraph platformGraph)`
- 返回：线路所属系统的锚点（升序；轨道连通了多个系统时多于一个）。线路为空、无停站或无系统时空表。按线路首站台所在网络查找。
- 调用者：`DelayAlarms.java:101`。

### `public List<Long> anchorsOf(long network, PlatformGraph platformGraph)`
- 返回：位于该网络中的锚点（升序）；`network == 0` 时空表。
- 调用者：`EditorContingencies.java:152`。

### `public List<Contingencies> forRoute(Route route, PlatformGraph platformGraph)`
- 返回：线路所属系统的预案（锚点升序）。
- 调用者：测试 `ContingencySystemsTest.java:63`（游戏代码调用未找到，未确定）。

### `public Map<Long, Long> keepAnchors(PlatformGraph platformGraph, LongPredicate platformExists)`
- 作用：锚点站台仍存在时，记下其网络中其它站台（升序前 8 个）作为备选；锚点不存在时，迁移到第一个仍存在且本身不是锚点的备选（剩余备选随之迁移）。最后删除不再对应系统的备选表。
- 返回：迁移记录 旧锚点 → 新锚点。
- 副作用：修改 `systems`、`backups`。
- 调用者：`RailwayData.java:818`。

### `public long anchorOf(long network, PlatformGraph platformGraph)`
- 返回：网络中已有系统的锚点（第一个）；没有则返回该网络中 id 最小的站台；网络无站台返回 0。
- 调用者：`DelayAlarms.java:131`。

### `public Contingencies getOrCreate(long anchor)`
- 返回：锚点的预案，不存在则新建并存入。调用者：`ContingencyPackets.java:91`。

### `public void write(FriendlyByteBuf packet)` / `public void read(FriendlyByteBuf packet)`
- 包字段顺序：`varint 系统数`；每个系统 `long anchor, varint 备选数, long×备选数`，然后组件的 `writePlans`、`writeState`（经 `PacketBinary`）。
- `read`：系统数须在 `[0, 64]`、备选数在 `[0, 8]`，否则抛 `IllegalArgumentException`；读完后整体替换 `systems` 与 `backups`。
- 调用者：`RailwayData.java:1159`（write）、`ContingencyPackets.java:41`（read）。

## public static final class Entry extends SerializedDataBase
存档中的一个系统。

### 字段与存档键
- `anchor`（键 `anchor`，long）。
- `bytes`（键 `contingencies`，msgpack 二进制）：`Contingencies.toBytes()`。
- `backups`（键 `backups`，long 数组；读取时最多 8 个）。

### 构造函数
- `Entry(long anchor, Contingencies contingencies, List<Long> backups)`：从运行时对象创建（`backups` 可为 `null`）。
- `Entry(Map<String, Value> map)`：读存档；`contingencies` 不是二进制值时为空数组。
- 调用者：`RailwayDataFileSaveModule.java:309`。

### `public Contingencies toContingencies()`
- 返回：由 `bytes` 解码的新 `Contingencies`。调用者：`RailwayDataFileSaveModule.java:191`。

### `toMessagePack` / `messagePackLength`（= 3） / `writePacket`（空实现，系统通过 `ContingencySystems.write` 发包）。
