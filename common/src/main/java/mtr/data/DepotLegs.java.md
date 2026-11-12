# DepotLegs.java

停车线（siding）的列车如何从车厂出到运用的首站、以及从末站回到车厂（解耦文档 5d 第一步）：路径第一次遇到的站台（说明车厂在哪个车站接入线路），以及每个首站/末站与停车线之间的时间。在找路时由运用路径算出，随找路结果发给客户端，用于运行图显示；没有任何运行依据它（但 `outPasses` 被运用编排使用）。不可变（除 `outPasses`/`outPassNodes` 两个可变映射）。由 `Siding`（`:321` 读存档、`:460`/`:644` 读包、`:616`/`:628` 写包、`:1198`/`:1280` 生成）、`Depot`（`:573-602` 存档与包、`:1392` 出库路径、`:1443` 加原因）、`EditorData.java:125`、`PacketTrainDataGuiClient.java:628` 使用。

## final class DepotLegs

### 常量
- `NONE`：空值（入口 0、无出入库时间）。
- 无路原因类型：`REASON_NO_TRACK = 1`（停车线与站台完全不连通）、`REASON_AGAINST = 2`（只找到逆线路方向经过后续站台的路）、`REASON_ENTRANCE_UNSET = 3`（停车线在多个车站接入线路而车厂规则未指定）、`REASON_RULE = 4`（车厂规则给的车站或命名路径走不通）、`REASON_WRONG_WAY = 5`（能到达站台，但方向不对且途中无处折返）。
- `MAX_REASON_PLATFORMS = 16`：原因中附带站台的上限；`MILLIS_PER_TICK = 50`；`MAX_PLATFORMS = 256`：各集合/映射的上限。

### 字段
- `entrancePlatform`：停车线路径最先到达的站台，或 0。
- `outMillis`：首站站台 → 从离开停车线到能离开首站（含在首站的停站）的毫秒数（运用据此提前出库，L05）。
- `backMillis`：末站站台 → 从离开末站到回到停车线的毫秒数。
- `unreachableOut` / `unreachableBack`：最近一次找路时找不到出库/回库路的首站/末站站台（已测得时间的站台被排除，最多 256）。
- `outReasons` / `backReasons`（0.65.26）：无路站台 → `{类型, 相关站台 id...}`（最多 1+16 项），未知时无条目。
- `outPasses`（2026-11-12，6 号线）：首站 → 出库路在折返进入首站之前最后通过的站台，用于运用编排在这种终点站"先到先出"。与路径一起找出，不存档不发包；重启后在重新找路前为空（届时运用会重新编排）。
- `outPassNodes`：同上，但通过的站台轨道上没有站台对象时（出库路不停站通过站台），记录该轨道的一个节点（`BlockPos.asLong`）。

### 构造函数
- `DepotLegs(long entrancePlatform, Map<Long, Long> outMillis, Map<Long, Long> backMillis)`：无无路站台。
- `DepotLegs(..., Set<Long> unreachableOut, Set<Long> unreachableBack)`：无原因。
- 私有全参：拷贝为不可变 `TreeMap`/`TreeSet`；无路集合经 `limit` 过滤；原因经 `limitReasons` 过滤。
- 调用者：`Depot.java:1443`；测试 `DepotLegsTest.java:23`。

### `private static Map<Long, long[]> limitReasons(Map<Long, long[]> reasons, Set<Long> unreachable)`
- 返回：只保留属于无路集合、类型在 1–5 内的原因，长度截断到 17。

### `public DepotLegs withReasons(Map<Long, long[]> out, Map<Long, long[]> back)`
- 返回：附上原因的新对象（保留 `outPasses`/`outPassNodes`）。调用者：`Depot.java:1443`。

### `private static Set<Long> limit(Set<Long> unreachable, Set<Long> measured)`
- 返回：去掉已测得时间的站台，最多 256 个。

### `public DepotLegs withUnreachable(Set<Long> out, Set<Long> back)`
- 返回：附上无路站台的新对象（原因清空；保留通过映射）。调用者：`Siding.java:1198`、`Siding.java:1280`。

### `public static DepotLegs of(Collection<TripPath<PathData>> tripPaths)`
- 作用：由停车线各运用路径（只看 `isDuty()` 且有时间分段的）生成：
  - 入口：每条路径第一个经过（停或不停）的站台计数，取次数最多者（相同取 id 小者）；
  - 首站（第一个停站分段）：`out` 取 `stopTimes.getDepartureLeadMillis()` 的最小值；并用 `passedBeforeTurning` 记录折返前经过的站台/节点；
  - 末站：路径不留在线上（`!staysOnLine`）且末站不是首站时，`back` 取"路径结束时间 − 末站分段结束时间"（tick→毫秒，>0）的最小值。
- 调用者：`Siding.java:1198`、`Siding.java:1280`；测试 `DepotLegsTest.java:34`。

### `static PathData passedBeforeTurning(List<PathData> path, long firstStopId)`
- 作用：在路径中找到首站（该站台且停站时间 > 0 的第一段），再交给下面的私有版本。
- 返回：出库路在进入首站前的最后一次折返之前、到更前一次折返为止通过的最后一个站台轨道段；没有折返或其间没有站台时 `null`。

### `public static PathData passedBeforeTurningInto(List<PathData> way)`
- 作用：同上，用于一条以站台（最后一段）结束的出库路。调用者：`Depot.java:1392`。

### `private static PathData passedBeforeTurning(List<PathData> path, int first)`
- 作用：从 `first` 向前找第一次折返（相邻两段为反向轨道），再从折返前向前找第一个 `RailType.PLATFORM` 段，遇到更早的折返则返回 `null`。

### `public List<Long> toList()` / `public static DepotLegs fromList(List<Long> list)`
- 存档格式（一个 long 列表）：`entrance, n, (platform, millis)×n` 出库, `m, (platform, millis)×m` 回库, `a, platform×a` 无路出库, `b, platform×b` 无路回库, 然后两组原因：`c, (platform, len, value×len)×c`（出库、回库各一组）。原因放在最后，因此旧存档读出相同。
- `fromList`：各数量截断到上限；旧列表缺少的部分为空；格式不对（越界、null）返回 `NONE`。
- 调用者：`Depot.java:573`、`EditorData.java:125`（toList）；`Siding.java:321`（fromList）。

### `public void writePacket(FriendlyByteBuf packet)` / `public static DepotLegs readPacket(FriendlyByteBuf packet)`
- 包字段顺序：`long entrance`，出库映射、回库映射（`int n, (long, long)×n`，写时截断到 256），无路出库、无路回库（`int n, long×n`），出库原因、回库原因（`int n, (long platform, int len, long×len)×n`）。
- 读取：数量 <0 或 >256、原因长度 >17 时抛 `IllegalArgumentException`。不含 `outPasses`。
- 调用者：`Siding.java:616`、`Depot.java:602`（write）；`Siding.java:460`、`PacketTrainDataGuiClient.java:628`（read）。

### 私有包辅助
- `writeReasons`/`readReasons`、`writeSet`/`readSet`、`writeMap`/`readMap`：见上格式。
