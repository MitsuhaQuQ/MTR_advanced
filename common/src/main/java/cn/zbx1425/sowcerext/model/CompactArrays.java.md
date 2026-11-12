# CompactArrays.java（ANTE sowcerext）

客户端模型数据去重：让几何相同的网格共用同一个数组（同一列车的多种涂装大多如此）。数组放进来后永不修改；重载结束时只清空查找表，数组本身仍由使用它的网格持有。由 `RawMesh` 压缩网格时调用 `share`（`common/src/main/java/cn/zbx1425/sowcerext/model/RawMesh.java:462`、`:463`），`CustomResourcesMixin.reloadTail` 调 `clear` 并记录节省的内存（`common/src/main/java/cn/zbx1425/mtrsteamloco/mixin/CustomResourcesMixin.java:66`）；测试见 `common/src/test/java/cn/zbx1425/sowcerext/model/RawMeshTest.java:183`。

## 类 `CompactArrays`（final，私有构造）

### 静态字段
- `FLOATS`、`INTS`（`ConcurrentHashMap<Key, 数组>`）：按内容查找已有数组；可被多个加载线程并发使用。
- `SAVED_BYTES`：自上次 `clear` 以来因共享节省的字节数。

### `static float[] share(float[] array)` / `static int[] share(int[] array)`
- 作用：内容相同的数组已存在则返回已有那个并累加节省字节（长度 × 4），否则登记并返回传入的数组。
- 注意：调用后不得再修改传入或返回的数组。

### `static long clear()`
- 作用：清空两个查找表，返回并清零节省字节数。

## 私有内部类 `Key`
- 字段 `array`（`float[]` 或 `int[]`）、`hash`（预先算好的 `Arrays.hashCode`）。
- `equals`：同类型数组且 `Arrays.equals`。`hashCode`：返回 `hash`。
