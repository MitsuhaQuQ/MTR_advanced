# BinaryWriter.java

组件写出要保存或发送的数据的接口。编码沿用 Minecraft 数据包缓冲区（`FriendlyByteBuf`）的格式，因此宿主（游戏）以前保存的数据仍能按同样方式读回：数字大端、布尔 1 字节、varint 每 7 位一组、低位在前。实现：`ByteArrayBinary.Writer` 和游戏侧 `mtr.advanced.PacketBinary`。使用者：`BinaryKeyed`、`SimDataset`、各计划类的 `write(...)`、游戏侧 `Depot`/`Siding`/`SandboxRuns` 等。

## 接口 BinaryWriter
各方法副作用都是把数据追加到目标；返回值均为 void。

- `void writeByte(int value)`：写低 8 位。
- `void writeBoolean(boolean value)`：写 1 或 0。
- `void writeInt(int value)`：写 4 字节大端。
- `void writeLong(long value)`：写 8 字节大端。
- `void writeVarInt(int value)`：写 varint（负数按无符号处理，`ByteArrayBinary` 实现中占 5 字节）。
