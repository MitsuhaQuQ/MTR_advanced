# ByteArrayBinary.java

`BinaryWriter` 写入字节数组、`BinaryReader` 从字节数组读取的实现，供组件把数据存成字节（不依赖 netty）。编码与 Minecraft 包缓冲区相同。使用者很多：游戏侧 `Depot`、`Siding`（`Siding.java:1865`）、`TrainServer`（`TrainServer.java:527`）、`SandboxRuns`；组件内 `DepotDuties`（`DepotDuties.java:295`）、`Contingencies`（`Contingencies.java:92`）、`LineDuties` 等。

## 类 ByteArrayBinary（final，不可实例化）
- 常量 `VAR_INT_MAX_BYTES = 5`：varint 最多字节数。
- 私有构造函数：仅作命名空间。

## 内部类 Writer（public static final，实现 `BinaryWriter`）
- 字段 `stream`（`ByteArrayOutputStream`）：累积写出的字节。
- `writeByte(int)`：写低 8 位。
- `writeBoolean(boolean)`：写 1/0。
- `writeInt(int)`：按 24、16、8、0 位移写 4 字节（大端）。
- `writeLong(long)`：8 字节大端。
- `writeVarInt(int)`：每次写低 7 位，还有更高位时置最高位 0x80；用无符号右移，负数写 5 字节。
- `byte[] toBytes()`：返回目前所写内容的拷贝。调用者：`SandboxRuns.java:148`、`Depot.java:556`。
- 各 write 方法副作用：追加到 `stream`。调用者如 `SandboxRuns.java:296`（writeInt）、`Depot.java:701`（writeVarInt）、`LineDuties.java:1298`（writeByte）。

## 内部类 Reader（public static final，实现 `BinaryReader`）
- 字段 `bytes`（不拷贝的源数组）、`position`（下一读取位置）。
- 构造函数 `Reader(byte[] bytes)`：从位置 0 开始读。调用者：`Contingencies.java:109`、`DepotDuties.java:302`、`TrainServer.java:532`。
- `hasMore()`：`position < bytes.length`。
- `readByte()`：读一字节并前移；到末尾时抛 `IllegalStateException("Read past the end of the data")`。
- `readUnsignedByte()`：`readByte() & 0xFF`。
- `readBoolean()`：非 0 为 true。
- `readInt()` / `readLong()`：按大端拼 4/8 字节。
- `readVarInt()`：最多读 5 字节；第 5 字节仍有续位时抛 `IllegalStateException("Var-int too long")`。
- 调用者：`SandboxRuns.java:89`（readInt）、`Depot.java:452`（readLong）、`DepotDuties.java:230`（readUnsignedByte）。
