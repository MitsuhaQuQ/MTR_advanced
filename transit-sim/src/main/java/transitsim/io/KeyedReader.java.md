# KeyedReader.java

按名字读回保存值的接口，读的是 `KeyedWriter` 写的内容；名字不存在时返回默认值或空数组。实现：`BinaryKeyed.Reader`（组件字节格式）和游戏侧 `mtr.advanced.MessagePackKeyed.Reader`（msgpack 存档）。使用者：带 `read(KeyedReader)` 的运行状态类（如 `TrainService`、`DutyLedger`）及游戏侧 `Siding`/`TrainServer` 读档。

## 接口 KeyedReader
组件实现中所有方法均为只读查表，无副作用。

- `String getString(String key, String defaultValue)`：取字符串，缺失返回 `defaultValue`。
- `int getInt(String key, int defaultValue)`：取 int。
- `long getLong(String key, long defaultValue)`：取 long。
- `boolean getBoolean(String key, boolean defaultValue)`：取布尔。
- `long[] getLongs(String key)`：取 long 数组，缺失返回空数组。
- `List<long[]> getLongArrays(String key)`：取多个 long 数组，缺失返回空列表。
