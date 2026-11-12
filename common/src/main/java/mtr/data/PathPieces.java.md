# PathPieces.java

以共享"片段"引用的方式保存路径（优化第 5 轮第 2 步）。路径在每个停站之后切成片段；每个片段按内容哈希只在数据库中存一次，路径只保存其各片段的键和每段起始的停站序号。一条停车线约 20 条路径、一个车厂的多条停车线，在站间都走相同的区段，所以大多数片段是共享的。片段内停站从 0 编号，因此同一区段无论出现在路径哪里都是同一片段。谁引用哪个片段保存在单独的表 `path_piece_refs` 中；不再被引用的片段在删除其最后引用的同一事务中清除。

另外提供 `indexDuty`：在内存中把运用路径表示成组件的 `transitsim.plan.IndexedPath`（按分段边界切开、相同几何只保留一份模板）。只有 SQLite 存储支持片段（`RailwayDataStore.storesPathPieces()`）。由 `Siding`（`:369-375` 读、`:512` 写、`:1205/:1287` 预切、`:1549` 索引运用）与 `RailwayDataFileSaveModule`（`:149-188` 读写器设置、`:421/:596/:699` 写入、`:439/:610` 提交、`:456/:626` 回滚）使用。

## final class PathPieces

### 常量与静态状态
- `KEY_PATH_PIECES = "path_pieces"`（public）：存档中代替路径本身写入的键，值为 `[片段键, 起始停站序号, 片段键, 起始停站序号, ...]`。
- 片段编码：msgpack map `{ "v": 1, "rails": [PathData...] }`（`KEY_VERSION`、`KEY_RAILS`、`VERSION = 1`），轨道的 `stopIndex` 减去起始停站序号。
- `MAX_PIECE_RAILS = 256`（public）：一个片段最多轨道段数（长时间不停站的路径也会被切开）。
- `WRITING` / `READING`：当前线程的写入器/读取器（`ThreadLocal`）。
- `PRECUT`：在生成路径的地方（如车厂找路线程）预先切好的路径，等下一次保存取用；`WeakHashMap`（按路径对象身份——`List` 的哈希与相等取决于元素，而元素未覆盖它们，所以实际按对象），路径被释放即遗忘。

### `private PathPieces()`
工具类，禁止实例化。

### `private static String signature(List<PathData> rails)`
- 返回：轨道列表编码（起始序号 0）的 Base64，作为 `IndexedPath` 的签名。

### `private static PathData withStopOffset(PathData rail, int offset)`
- 返回：复制一段轨道并把 `stopIndex` 加上偏移（`IndexedPath` 展开时使用）。

### `public static List<PathData> indexDuty(List<PathData> path, int[] starts)` / `indexDuty(List<PathData> path, int[] starts, int firstServiceRail)`
- 作用：把运用路径按分段起点（`starts`，单个班次与衔接的边界）切开，相同编码的片段共用一个模板，返回 `IndexedPath`。`firstServiceRail >= 0` 时从该轨道起按"进入新的站台轨道"重新给停站编号；否则用原 `stopIndex`。已是 `IndexedPath` 或为空时原样返回。
- 异常：编码失败抛 `IllegalStateException`。
- 调用者：`Siding.java:1549`；测试 `DutyBodyTest.java:62`。

### `public static void writePath(MessagePacker messagePacker, List<PathData> path, String key)`
- 作用：本线程没有写入器时，把路径完整写在 `key` 下（`RailwayData.writeMessagePackDataset`）；否则写 `path_pieces` 引用数组（由写入器切片并收集新片段）。注意两种情况写的键不同，调用方计算 map 长度时都只算一个键。
- 调用者：`Siding.java:512`、`RoutePathCreatorScreen.java:206`。

### `public static List<PathData> readPath(Map<String, Value> map, String key)`
- 返回：没有 `path_pieces` 时读 `key` 下的完整路径；有引用时用本线程读取器拼成 `IndexedPath`（并记下它由哪些引用构成）；未设置读取器或某片段缺失时返回 `null`。
- 调用者：`Siding.java:369`。

### `public static void remember(List<PathData> read, List<PathData> kept)`
- 作用：宿主保留的路径（如行程路径自己的拷贝）若由 `read` 的片段构成，记下其引用，使加载后第一次保存不必重新切片；完整读出的路径或没有读取器时不做事。
- 调用者：`Siding.java:375`。

### `public static void precut(Collection<? extends TripPath<PathData>> tripPaths)`
- 作用：在当前线程（找路线程）把行程路径切好放入 `PRECUT`，供下一次保存使用；异常只打印。
- 调用者：`Siding.java:1205`、`Siding.java:1287`。

### `startWriting(Writer)` / `stopWriting()` / `startReading(Reader)` / `stopReading()`
- 设置/清除本线程的写入器、读取器。调用者：`RailwayDataFileSaveModule.java:659/664/167/176`。

### `static List<Integer> cuts(List<PathData> path)`
- 返回：每个片段的首轨道下标，末尾附路径长度：每个停站（`dwellTime > 0`）之后切，或片段达到 256 段时切。

### `static byte[] encode(List<PathData> path, int from, int to, int stopBase)`
- 作用：按上述片段格式编码 `[from, to)`。

### `static List<PathData> decode(byte[] bytes)`
- 返回：解码的片段；版本不符返回 `null`。

### `static long keyOf(byte[] bytes)`
- 返回：内容 SHA-256 的前 8 字节作为片段键。调用者：本类 `Cut`。（组件 `DutyLayers`、`PlanVersions` 有各自的同名方法。）

## public static final class Reader
加载数据时的片段读取器，每个片段只解码一次。
- 字段：`stored`（键 → 字节）、`decoded`（缓存）、`read`（拼出的路径 → 引用，按对象身份）、`remembered`（保留的路径 → 引用）。
- `Reader(Map<Long, byte[]> stored)`：调用者 `RailwayDataFileSaveModule.java:166`。
- `seed(Writer writer)`：把记住的路径及其引用交给写入器（作为已切好），然后清空。调用者：`RailwayDataFileSaveModule.java:178`。
- `private List<PathData> get(long key)`：取并缓存解码结果；缺失或解码失败为 `null`。

## public static final class Writer
把写入的路径切片，记住每条路径的切法（每次保存都会再写同一路径，只切一次），收集尚未存储的片段及每个所有者引用的片段。
- 字段：`cutPaths`（上一轮写过的路径 → 切法，按身份）、`cutPathsNow`（本轮）、`ownerPieces`、`newPieces`、`stored`（数据库已有的键，即 `Store.stored`）。
- `Writer(Set<Long> stored)`：调用者 `RailwayDataFileSaveModule.java:149`。
- `startOwner()`：开始收集一个所有者（停车线等）的引用。调用者：`RailwayDataFileSaveModule.java:658`。
- `endOwner()`：返回 `Owner(引用的片段, 其中尚未存储的)`。调用者：`:666`。
- `endRound()`：遗忘本轮未写到的路径，不再持有它们的片段。调用者：`:299`。
- `private long[] add(List<PathData> path)`：取切法（本轮 → 上轮 → 预切 → 新切；长度不符时新切）；记录所有者引用；未存储的片段加入新片段（若字节已被丢弃——曾存储后被别的所有者清除——则重新切）；已存储的片段丢弃内存中的字节。返回引用数组。

## public static final class Owner
- `pieces`：所有者引用的片段键；`newPieces`：其中尚未存储的键 → 字节。

## private static final class Cut
- 字段：`size`（路径长度）、`keys`、`bytes`（可为 null：已在数据库中）、`references`（`[键, 起始序号]...`）。
- `Cut(int size, long[] references)`：由读取的引用构造（字节在数据库中）。
- `Cut(List<PathData> path)`：`IndexedPath` 按其段与模板编码（同一模板只编码一次）；普通路径按 `cuts` 切并编码。

## public static final class Store
数据库中的状态：已存储片段的键和每个所有者引用的片段。变化按事务计算，提交后才生效。
- `stored`（public）、`owners`、`ownersPending`、`addedPending`、`sweptPending`。
- `load(RailwayDataStore dataStore)`：读全部片段与引用，返回片段字节（给 `Reader`）。调用者：`RailwayDataFileSaveModule.java:166`、`:206`。
- `write(RailwayDataStore dataStore, Map<Long, Owner> written, Collection<Long> deleted, Set<Long> kept)`：在进行中的事务里写入新片段、写变化了的所有者引用（被删所有者写空集），然后清除没有任何所有者（未变的、刚写的）和 `kept`（已计算但尚未写出的保存所需）引用的片段（包括本次新增但无人引用的）。`written` 的值为 `null` 表示该所有者以完整路径写出（无引用）。
- `committed()`：把待定变化应用到内存状态。调用者：`RailwayDataFileSaveModule.java:439`、`:610`。
- `rolledBack()`：丢弃待定变化。调用者：`:456`、`:626`。
- `getStoredCount()`：已存储片段数（测试用）。
