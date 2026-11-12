# BinaryReader.java

组件读取二进制数据的接口，读回 `BinaryWriter` 写的内容。编码与 Minecraft 数据包缓冲区一致（大端数字、1 字节布尔、7 位一组低位在前的 varint）。实现：`ByteArrayBinary.Reader`（组件自用字节数组）和游戏侧 `mtr.advanced.PacketBinary`（包装 `FriendlyByteBuf`）。使用者：`BinaryKeyed.Reader`、`TimetableBinary`、`DepotDuties`、`Contingencies` 等各种 `read(...)` 方法。

## 接口 BinaryReader
各方法均推进读取位置；越界时的行为由实现决定（`ByteArrayBinary.Reader` 抛 `IllegalStateException`）。

- `byte readByte()`：读 1 字节（有符号）。
- `int readUnsignedByte()`：读 1 字节，返回 0–255。
- `boolean readBoolean()`：读 1 字节，非 0 为 true。
- `int readInt()`：读 4 字节大端 int。
- `long readLong()`：读 8 字节大端 long。
- `int readVarInt()`：读 varint（最多 5 字节）。
- `default boolean hasMore()`：是否还有未读数据（就读取者所知）；无法判断时返回 false（默认实现）。用于读旧数据时判断后面是否追加了新字段。调用者：`TimetableBinary.java:111`、`TimetableBinary.java:117`；`ByteArrayBinary.java:68` 覆盖实现。
