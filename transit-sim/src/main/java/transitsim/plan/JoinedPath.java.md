# JoinedPath.java

面向追加的路径拼装列表：`addAll` 时只追加对来源片段的引用，而不复制之前所有轨道，用于长运用路径的快速拼接。支持 `set` 覆盖单个位置（记录在 `changed` 中，不改底层片段），`subList` 返回轻量切片，切片再 `addAll` 进另一个 `JoinedPath` 时只引用对应片段的子列表。游戏侧 `mtr.data.Depot`（`Depot.java:3192`、`:3258`）与 `mtr.data.Siding`（`Siding.java:1367`、`:1405`、`:1431`）拼路径时使用；测试见 `JoinedPathTest`、`common/src/test/java/mtr/data/PathPiecesTest.java:90`。

## 类 `JoinedPath<T>`（final，extends `AbstractList<T>`, implements `RandomAccess`）

### 字段
- `parts`：引用的片段列表。
- `ends`：每个片段结束处的累计长度（即 `parts[i]` 覆盖全局下标 `[ends[i-1], ends[i])`）。
- `changed`：被 `set` 覆盖的全局下标 → 新值。
- `count`：总长度。

### `public JoinedPath()` / `public JoinedPath(Collection<? extends T> source)`
空列表；或以 `addAll(source)` 初始化。

### `public int size()`
返回 `count`。

### `public int pieceCount()`
- 返回值：引用的片段数。注释：每次 `addAll` 对来源的每个片段最多加一个。用于测试片段数量不会膨胀。

### `private int part(int index)`
- 作用：二分查找 `ends` 得到下标所在片段。
- 副作用：越界抛 `IndexOutOfBoundsException`。

### `public T get(int index)`
返回位置值；若在 `changed` 中则返回覆盖值。

### `public T set(int index, T value)`
- 作用：把覆盖值记入 `changed`，返回旧值；不修改底层片段（底层可能是不可修改或共享的列表）。

### `public boolean add(T value)`
等同 `addAll(singletonList(value))`，即每次单元素追加都新增一个片段。

### `public boolean addAll(Collection<? extends T> source)`
- 作用：
  - 来源为空：返回 false。
  - 来源是自己：先复制为新的 `JoinedPath` 再加。
  - 来源是 `Slice`：对切片所在 owner 的每个与切片区间相交的片段，追加其 `subList`；并把 owner 中落在切片内的 `changed` 平移后复制过来。
  - 来源是 `JoinedPath`：直接引用其所有片段，并平移复制 `changed`。
  - 其它集合：复制为不可修改的 `ArrayList` 作为一个片段。
- 返回值：有追加时 true。
- 副作用：修改 `parts`/`ends`/`changed`/`count`、`modCount++`；长度溢出时 `Math.addExact` 抛 `ArithmeticException`。注意引用的片段若被外部修改，本列表随之变化（未做防护，未确定调用方是否保证不变）。

### `public List<T> subList(int from, int to)`
- 返回值：`Slice` 视图（读写都转到 owner）。
- 副作用：区间非法时抛 `IndexOutOfBoundsException`。

### `public void clear()`
清空所有字段，`modCount++`。

## 内部类 `Slice<T>`（private static final，extends `AbstractList<T>`, implements `RandomAccess`）
- 字段 `owner`、`from`、`to`：所属 `JoinedPath` 及区间 `[from, to)`。
- `size()`：`to - from`。
- `get(int index)`：越界检查后返回 `owner.get(from + index)`。
- `set(int index, T value)`：检查后调用 `owner.set(from + index, value)`。
