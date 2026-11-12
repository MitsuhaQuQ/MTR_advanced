# ContingencyPackets.java（本分支新增）

轨道系统应急预案（设计文档 D2）的网络包：玩家加入时和预案变化时，把所有系统的预案发给玩家；玩家修改某系统的预案、或启用哪些预案及单列车的临时设置时发给服务器，服务器检查编辑权限。服务端发送：`common/src/main/java/mtr/data/RailwayData.java:606`（变化后广播）、`:903`（玩家加入）；客户端发送：网页编辑器 `common/src/main/java/mtr/client/editor/EditorContingencies.java:205`、`:249`；注册：`common/src/main/java/mtr/MTR.java:472`（C2S）、`common/src/main/java/mtr/MTRClient.java:338`（S2C）。

## 包格式（同一通道 `PACKET_CONTINGENCIES`）
- S2C：`railwayData.contingencySystems.write(packet)` 的内容（见 `mtr.data.ContingencySystems`）。
- C2S：`long anchor`（系统锚点：系统中某站台 id）、`VarInt kind`（0 = 预案 `KIND_PLANS`，1 = 状态 `KIND_STATE`），之后经 `PacketBinary` 写 `Contingencies.writePlans` 或 `writeState` 的内容。

## 类 `ContingencyPackets`（final，implements `IPacket`，私有构造）

### 常量
- `KIND_PLANS = 0`、`KIND_STATE = 1`。

### `static void sendAllS2C(Level world, RailwayData railwayData)`
- 发给该世界所有玩家。

### `static void sendS2C(ServerPlayer player, RailwayData railwayData)`
- 发给一个玩家。

### `static void receiveS2C(FriendlyByteBuf packet)`
- 客户端：读入后在主线程替换 `ClientData.CONTINGENCY_SYSTEMS.systems`。

### `static void sendPlansC2S(long anchor, Contingencies contingencies)` / `static void sendStateC2S(long anchor, Contingencies contingencies)`
- 客户端：发送某系统的全部预案（替换）/ 启用状态与单列车设置。

### `private static void send(long anchor, int kind, Contingencies contingencies)`
- 组包并 `RegistryClient.sendToServer`。

### `static void receiveC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet)`
- 作用：网络线程读入；服务器线程中：无铁路数据、无编辑权限或锚点站台不存在则忽略；新系统且已达 `ContingencySystems.MAX_SYSTEMS` 则忽略；预案：替换全部预案，启用集合只保留仍存在的预案，打印日志；状态：启用集合只接受存在的预案，替换单列车临时设置（`adhoc`），打印日志；系统变空则删除；置 `contingenciesChanged = true`（之后保存并广播）。
