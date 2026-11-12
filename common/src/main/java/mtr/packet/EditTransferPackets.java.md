# EditTransferPackets.java（本分支新增）

编辑包（`PACKET_EDIT` / `PACKET_EDIT_BATCH`）的分片传输：超过单包大小的编辑（例如一次修改很多线路）在客户端切成分片发送，服务端按玩家重组后交回原来的编辑处理（`EditGuard`），复用其权限、版本号和原子组校验。客户端发送：`common/src/main/java/mtr/client/ClientEditing.java:91`、`:204`；服务端接收：`common/src/main/java/mtr/packet/EditGuard.java:124`；玩家离开时 `forget`（`common/src/main/java/mtr/MTR.java:569`）。重组逻辑在 `EditTransferAssembler`（不在本文件）。

## 分片包格式（`PACKET_EDIT_FRAGMENT`）
`long 传输 id`、`boolean 是否批量编辑`、`VarInt 总字节数`、`VarInt 偏移`、`byte[] 分片(≤ EditTransferAssembler.CHUNK_BYTES)`；之后不得有多余字节。

## 类 `EditTransferPackets`（final，implements `IPacket`，私有构造）

### 静态字段
- `NEXT`：客户端传输 id 计数。
- `RECEIVING`（玩家 → 重组器）、`DIMENSIONS`（玩家 → 开始传输时所在维度）。

### `static void send(ResourceLocation target, FriendlyByteBuf packet, BiConsumer<ResourceLocation, FriendlyByteBuf> sender)`
- 作用：只允许 `PACKET_EDIT`/`PACKET_EDIT_BATCH`，否则抛 `IllegalArgumentException`；超过 `EditTransferAssembler.MAX_BYTES`（1 MiB）抛 `IllegalArgumentException("Edit exceeds 1 MiB; apply fewer routes at once")`；不超过单片大小直接原样发送；否则切片按上述格式发送。不改变 `packet` 的读位置。

### `static void receive(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet, BiConsumer<Boolean, FriendlyByteBuf> complete)`
- 作用：网络线程读分片并校验（有多余字节、总长 ≤ 0 或 > 1 MiB 抛异常）；服务器线程中：清理超时的重组器；玩家须仍在线（同一对象）且有编辑权限；同时重组的玩家最多 64 个；偏移 0 时记下维度，之后维度变化则丢弃该传输；交给重组器，完整后取出、以 `(是否批量, 数据)` 调 `complete`，最后释放缓冲。

### `static void forget(ServerPlayer player)`
- 丢弃该玩家进行中的重组。
