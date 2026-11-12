# PacketGuard.java（本分支新增）

服务端：注册玩家发给服务器的所有 MTR 包，在读取前拒绝过大或过于频繁的包，处理时出错（格式错误）就断开该玩家。所有 C2S 包都经它注册（`common/src/main/java/mtr/MTR.java:468` 起）；处理函数里改游戏状态的部分用 `execute` 放到服务器线程。

## 类 `PacketGuard`（final，私有构造）

### 常量与静态字段
- `MAX_PACKET_BYTES = 1 << 20`（1 MiB）：单包可读字节上限。
- `MAX_PACKETS_PER_SECOND = 400`：每玩家每种包每秒上限。
- `MILLIS_PER_SECOND = 1000`。
- `PACKET_COUNTS`：`玩家 UUID → (包 id → {窗口开始毫秒, 计数})`。

### `static void register(ResourceLocation id, NetworkUtilities.PacketCallback packetCallback)`
- 作用：注册接收器：超过 1 MiB → `reject("packet too large")`；超过速率 → 静默丢弃；否则调用回调，回调抛任何异常 → 打印并 `reject("malformed packet")`。

### `static void execute(MinecraftServer minecraftServer, Runnable runnable)`
- 交给服务器线程执行，异常只打印（不断开）。调用者：几乎所有 C2S 处理，如 `common/src/main/java/mtr/audio/AudioServer.java:194`。

### `static int readCount(FriendlyByteBuf packet, int minimumElementBytes, int maximum)`
- 作用：读一个 int 数量，若为负、超过 `maximum`、或 `数量 × 每元素最少字节` 超过剩余字节，抛 `IllegalArgumentException`（防止巨大数量导致分配）。调用者：`AudioServer.java:368`、`common/src/main/java/mtr/client/ImportedImages.java:49`。

### `static void forget(ServerPlayer player)`
- 清除该玩家的计数。调用者：`common/src/main/java/mtr/MTR.java:568`。

### `private static boolean withinRateLimit(ServerPlayer player, ResourceLocation id)`
- 1 秒固定窗口计数，≤ 400 为真。

### `private static void reject(MinecraftServer minecraftServer, ServerPlayer player, ResourceLocation id, String reason)`
- 打印 `Rejected packet <id> from <玩家>: <原因>`，在服务器线程断开该玩家（消息 `Invalid Minecraft Transit Railway packet`）。
