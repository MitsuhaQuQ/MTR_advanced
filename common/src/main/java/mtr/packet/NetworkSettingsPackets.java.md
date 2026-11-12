# NetworkSettingsPackets.java（本分支新增）

线网全局设置（优化第 4 轮，`mtr.data.NetworkSettings`：最短换端时间、早到阈值、各线路行车方向等）的网络包：玩家加入时和设置变化时发给玩家；玩家修改后发给服务器，服务器检查编辑权限。服务端发送：`common/src/main/java/mtr/data/RailwayData.java:611`、`:904`；客户端发送：`common/src/main/java/mtr/client/editor/EditorActions.java:544`；注册：`common/src/main/java/mtr/MTR.java:473`、`common/src/main/java/mtr/MTRClient.java:339`。

## 包格式（通道 `PACKET_NETWORK_SETTINGS`，双向相同）
- `NetworkSettings.writePacket(packet)` 的内容（字段顺序见该类）。

## 类 `NetworkSettingsPackets`（final，implements `IPacket`，私有构造）

### `static void sendAllS2C(Level world, RailwayData railwayData)` / `static void sendS2C(ServerPlayer player, RailwayData railwayData)`
- 把 `railwayData.dataCache.networkSettings` 发给世界所有玩家 / 一个玩家。

### `static void receiveS2C(FriendlyByteBuf packet)`
- 客户端：读入后在主线程 `ClientData.DATA_CACHE.networkSettings.copyFrom(read)`。

### `static void sendC2S(NetworkSettings settings)`
- 客户端发送修改后的设置。

### `static void receiveC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet)`
- 作用：服务器线程中：无铁路数据、无编辑权限或与现值相同则忽略；否则复制进 `dataCache.networkSettings`，打印日志（含最短换端毫秒、早到阈值等），置 `networkSettingsChanged = true`（之后保存并广播）。
