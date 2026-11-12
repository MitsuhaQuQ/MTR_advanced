# KeyedWriter.java

按名字写值的接口，供 `KeyedReader` 读回；具体如何存储由宿主决定。实现：`BinaryKeyed.Writer`（组件字节格式）和游戏侧 `mtr.advanced.MessagePackKeyed.Writer`（写入 msgpack 存档）。使用者：带 `write(KeyedWriter)` 的运行状态类（如 `TrainService`）及游戏侧 `Siding`/`TrainServer` 存档。

## 接口 KeyedWriter
每个方法把 `key` 对应的值写出；都声明 `throws IOException`（组件 `BinaryKeyed.Writer` 不会抛，msgpack 实现可能抛）。

- `void writeString(String key, String value)`
- `void writeInt(String key, int value)`
- `void writeLong(String key, long value)`
- `void writeBoolean(String key, boolean value)`
- `void writeInts(String key, int[] values)`
- `void writeLongs(String key, long[] values)`
- `void writeLongArrays(String key, List<long[]> values)`
