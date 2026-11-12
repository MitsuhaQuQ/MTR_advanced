# PacketBinary.java

把 Minecraft 的 `FriendlyByteBuf` 包装成 `transit-sim` 组件 `io` 包的 `BinaryWriter` 与 `BinaryReader`，让组件自己的二进制格式（例如应急预案 `Contingencies` 的 plans/state）能直接写进网络包和存档，字节与之前完全相同。

## final class PacketBinary implements BinaryWriter, BinaryReader

### 字段
- `private final FriendlyByteBuf packet`：被包装的缓冲。

### `private PacketBinary(FriendlyByteBuf packet)` / `public static PacketBinary of(FriendlyByteBuf packet)`
- 作用：包装一个缓冲（不拷贝）。
- 主要调用者：`ContingencySystems.java:139`、`ContingencySystems.java:140`、`ContingencySystems.java:163`；`MTRClient.java:299`。

### 写方法（均直接委托给 `FriendlyByteBuf` 同名方法）
- `writeByte(int)`、`writeBoolean(boolean)`、`writeInt(int)`（4 字节大端）、`writeLong(long)`（8 字节）、`writeVarInt(int)`（Minecraft 变长整数）。

### 读方法（均直接委托）
- `readByte()`、`readUnsignedByte()`、`readBoolean()`、`readInt()`、`readLong()`、`readVarInt()`。

以上读写方法由组件代码经接口调用（如 `Contingencies.writePlans/readPlans`），测试见 `PacketBinaryTest.java:29`。
