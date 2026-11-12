# IndexedPath.java

虚拟的轨道列表：由共享的片段模板（segment template）加上每次出现的停站序号偏移组成。同一段运行（例如同一路线跑多趟）只存一份模板，读取时按需把模板轨道套上 `stopOffset`（通过宿主给的 `offset` 函数生成副本），并只缓存最近 `CACHED_SEGMENTS` 个片段的展开结果，从而节省长运用路径的内存。游戏侧 `mtr.data.PathPieces`（`PathPieces.java:96`、`:140`）和组件 `transitsim.sim.SimDataset:730` 创建；`TripPath` 用 `copyOf`/`rebuild` 保持索引形式；`PathDistances` 按片段计算距离；`mtr.advanced.TrackViews`、`transitsim.sim.RunTrack` 用 `compiled` 缓存派生视图。

## 类 `IndexedPath<T extends PlanTrack>`（final，extends `AbstractList<T>`, implements `RandomAccess`）

### 常量
- `CACHED_SEGMENTS = 8`：展开缓存最多保留的片段数（LRU）。

### 字段
- `segments`：不可修改的片段列表。
- `ends`：每个片段结束处的累计长度。
- `offset`：`(轨道, 偏移) → 套上停站序号偏移后的轨道`，由宿主提供。
- `signature`：`模板 → 字符串签名`（宿主的精确编码），可为 null；用于 `rebuild` 时识别相同模板。
- `cache`：访问序 `LinkedHashMap<片段号, 展开后的轨道>`，超过 8 个淘汰最旧。
- `replacements`：被 `set` 替换的全局下标 → 轨道。
- `compiled`：访问序 LRU（最多 16 项），按任意 key 缓存从本路径派生的对象。

### 内部类 `Segment<T>`（public static final）
- 字段 `template`（不可修改的模板轨道列表，停站序号以 0 为基准）、`stopOffset`（此次出现的停站序号偏移）。
- 构造 `Segment(List<T> template, int stopOffset)`：模板为空时抛 `IllegalArgumentException("Empty segment")`。
- 主要创建者：`common/src/main/java/mtr/data/PathPieces.java:94`。

### `public synchronized <V> V compiled(Object key, Supplier<V> create)`
- 作用：按 key 取缓存的派生对象，没有则调用 `create` 生成并缓存。`set`/`updateTemplates` 会清空。
- 主要调用者：`common/src/main/java/mtr/advanced/TrackViews.java:41`、`transitsim/sim/RunTrack.java:75`。

### `public IndexedPath(List<Segment<T>> segments, BiFunction<T, Integer, T> offset)` / `public IndexedPath(…, Function<List<T>, String> signature)`
- 作用：复制片段列表，计算 `ends`。总长溢出时 `Math.addExact` 抛 `ArithmeticException`。
- 主要调用者：`common/src/main/java/mtr/data/PathPieces.java:96`、`PathPieces.java:140`、`transitsim/sim/SimDataset.java:730`。

### `public synchronized List<Segment<T>> segments()`
- 作用：返回片段列表；若有 `replacements`，把受影响的片段复制一份新模板，把替换轨道减去该片段偏移（`offset.apply(rail, -stopOffset)`）写回模板，返回新的不可修改列表（不改变自身）。
- 主要调用者：`transitsim/plan/PathDistances.java:17`。

### `public int segmentStart(int segment)` / `public int segmentAt(int index)`
片段起始全局下标；全局下标所在片段（二分，越界抛 `IndexOutOfBoundsException`）。

### `public int size()`
总轨道数。

### `public synchronized T get(int index)`
优先返回 `replacements`；否则从缓存或现场展开该片段（对每条模板轨道 `offset.apply(rail, stopOffset)`）取值。

### `public synchronized T set(int index, T value)`
- 作用：记录替换，清空 `compiled`，返回旧值。模板与缓存不变。

### `public synchronized void updateTemplates(Consumer<T> change)`
- 作用：对每个不同模板中的每条轨道以及所有替换轨道执行 `change`（就地修改），然后清空 `cache` 和 `compiled`。
- 主要调用者：`transitsim/sim/SimDataset.java:294`。

### `public synchronized <U extends PlanTrack> IndexedPath<U> map(Function<T, U> mapper, BiFunction<U, Integer, U> shift)` / `map(…, Function<List<U>, String> key)`
- 作用：把每个不同模板映射为新类型的模板（同一模板只映射一次，保持共享），用 `shift` 作为新路径的偏移函数、`key` 作为签名，并映射 `replacements`。
- 返回值：新的 `IndexedPath<U>`。
- 主要调用者：`common/src/main/java/mtr/advanced/SimExport.java:110`。

### `public List<T> rebuild(List<T> source, int[] boundaries)`
- 作用：把一个临时编辑过的普通视图 `source` 重新切成索引路径：在 0、`source.size()` 以及 `boundaries` 中合法的下标处切开；每一块以块首轨道的 `getStopIndex()` 为基准减去偏移得到局部模板，按 `signature` 查找已有模板，相同则复用，否则用新模板。
- 返回值：新的 `IndexedPath`；若本路径没有 `signature`，退化为 `copyOf(source)`。
- 主要调用者：`transitsim/plan/TripPath.java:261`、`TripPath.java:376`。

### `public synchronized int materializedSegments()`
当前展开缓存中的片段数（测试用，`IndexedPathTest`、`SimDatasetTest`）。

### `public static <T extends PlanTrack> List<T> copyOf(List<T> source)`
`source` 是 `IndexedPath` 时原样返回（不复制），否则复制为 `ArrayList`。主要调用者：`transitsim/plan/TripPath.java:180`、`:208`、`:232`。
