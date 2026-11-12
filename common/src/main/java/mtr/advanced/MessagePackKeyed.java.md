# MessagePackKeyed.java

组件 `transit-sim` 的"命名值"读写接口（`KeyedReader` / `KeyedWriter`）在游戏 msgpack 存档映射上的实现。组件的数据类（例如 `TurnBacks`、`TripPath`，以及 `Siding`、`TrainServer` 中由组件定义的状态）通过它把字段直接写进宿主的 msgpack 映射，存档键与原来一致。

## final class MessagePackKeyed

### `private MessagePackKeyed()`
工具类，禁止实例化。

## public static class Reader implements KeyedReader
包装 `MessagePackHelper`（对已解码的 msgpack 映射按键取值）。

- `Reader(MessagePackHelper messagePackHelper)`：主要调用者 `Siding.java:335`、`Siding.java:373`、`TrainServer.java:227`。
- `getString(key, defaultValue)` / `getInt` / `getLong` / `getBoolean`：委托给 `MessagePackHelper` 同名方法，键不存在时返回默认值。
- `long[] getLongs(String key)`：读一个整数数组；键不存在时得到空数组（取决于 `iterateArrayValue` 行为，未确定）。脚本未找到直接调用者。
- `List<long[]> getLongArrays(String key)`：读"数组的数组"。主要调用者：`TurnBacks.java:72`。

## public static class Writer implements KeyedWriter
包装 `MessagePacker`，每个方法先写键字符串再写值（调用方需事先写好 map 头并计算长度）。

- `Writer(MessagePacker messagePacker)`：主要调用者 `Siding.java:511`、`Siding.java:514`、`TrainServer.java:276`。
- `writeString` / `writeInt` / `writeLong` / `writeBoolean`：键 + 单值。
- `writeInts(String key, int[] values)`：键 + 数组头 + 各 int。主要调用者：`TripPath.java:979`。
- `writeLongs(String key, long[] values)`：键 + 数组头 + 各 long。
- `writeLongArrays(String key, List<long[]> values)`：键 + 外层数组头 + 每个内层数组（头 + long）。主要调用者：`TurnBacks.java:87`。
- 异常：均可抛 `IOException`。
