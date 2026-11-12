# BinaryKeyed.java

组件自有数据集的"按名字存值"二进制格式（S4e）：每个值先写名字，再写数值；数值统一存成 long 或 long 数组，这已足够满足各计划（plan）要写的命名值。它是 `KeyedWriter`/`KeyedReader` 的字节实现，底层写到任意 `BinaryWriter`（通常是 `ByteArrayBinary.Writer`），读自任意 `BinaryReader`。游戏侧 `Siding`（`Siding.java:1866`、`Siding.java:2010`）和 `TrainServer`（`TrainServer.java:522`、`TrainServer.java:532`）用它保存/读取运行状态；`SimDataset` 用其静态 `writeString`/`readString` 写字符串。

格式：`varint 条目数`，然后按键名字典序（TreeMap）每条 `字符串 键名` + `1 字节类型` + 值：类型 0 = 字符串；1 = 单个 long（8 字节大端）；2 = `varint 数组个数`，每个数组 `varint 长度` + 若干 long。

## 类 BinaryKeyed（final，不可实例化）
- 私有构造函数：仅作命名空间。

### `static void writeString(BinaryWriter writer, String text)`
- 作用：以 UTF-8 写字符串：先 varint 字节长度，再逐字节 `writeByte`。
- 参数：`writer` 目标；`text` 非 null 字符串（null 会抛 NPE）。
- 副作用：向 `writer` 写入数据。
- 主要调用者：`SimDataset.java:118`、`SimDataset.java:120`；本类 `Writer.writeTo`（第 84、87 行）。

### `static String readString(BinaryReader reader)`
- 作用：读回 `writeString` 写的字符串。
- 返回：解码后的字符串。副作用：推进 `reader` 位置；数据不足时由 reader 抛异常（`ByteArrayBinary.Reader` 抛 `IllegalStateException`）。
- 主要调用者：`SimDataset.java:206`、`SimDataset.java:216`；本类 `Reader` 构造函数（第 116、119 行）。

## 内部类 Writer（public static final，实现 `KeyedWriter`）
先在内存中收集命名值，最后用 `writeTo` 一次写出。

- 字段 `values`（`TreeMap<String,Object>`）：键 → 值，值的类型为 `String`、`long[]`（长度 1，表示单个数）或 `long[][]`（数组）。同名后写覆盖先写。
- `writeString(String key, String value)`：存字符串。
- `writeInt(String key, int value)` / `writeLong(String key, long value)`：存为单元素 `long[]`。
- `writeBoolean(String key, boolean value)`：存为 1/0。
- `writeInts(String key, int[] ints)`：转成 long 后存为只含一个数组的 `long[][]`。
- `writeLongs(String key, long[] longs)`：拷贝后存为只含一个数组的 `long[][]`。
- `writeLongArrays(String key, List<long[]> arrays)`：存为 `long[][]`（各数组本身不拷贝）。
- 以上方法副作用均为写 `values`；不抛异常（接口声明的 `IOException` 未使用）。调用者：`Siding.java:621`（writeInt）、`Siding.java:677`（writeLong）、`SimDataset.java:114`（writeLongs）等。
- `void writeTo(BinaryWriter writer)`：按上面的格式把所有值写出。副作用：写 `writer`。调用者：`Siding.java:1871`、`TrainServer.java:528`。

## 内部类 Reader（public static final，实现 `KeyedReader`）
- 字段 `values`：同上，但单个数存为 `Long`。
- 构造函数 `Reader(BinaryReader reader)`：一次性读完全部条目（类型 0、1 以外都当作 2 处理）。副作用：推进 `reader`。调用者：`TrainServer.java:532`。
- `getString(key, default)`：值为字符串时返回，否则返回默认。
- `getInt(key, default)`：值为单个数时截断为 int，否则默认。
- `getLong(key, default)`：值为单个数时返回，否则默认。
- `getBoolean(key, default)`：单个数非 0 为 true，否则默认。
- `getLongs(key)`：值为数组组且非空时返回第一个数组的拷贝，否则空数组。
- `getLongArrays(key)`：返回所有数组的拷贝列表；不是数组类型时返回不可修改的空列表。
- 以上 getter 无副作用；类型不匹配时静默返回默认值。调用者：`Siding.java:308`（getString）、`Siding.java:310`（getInt）、`Siding.java:342`（getLong）、`DutyLedger.java:73`。
