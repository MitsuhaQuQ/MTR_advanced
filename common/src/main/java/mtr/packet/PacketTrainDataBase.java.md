# PacketTrainDataBase.java

MTR 数据包处理的基类（服务端与客户端共用），提供"按 id 更新或新建一条数据"和"按 id 删除"的通用逻辑；`PacketTrainDataGuiClient` 和 `PacketTrainDataGuiServer` 继承它。本分支改动：新增 `FAILED`（线程局部标志），更新或删除时出现异常就置位，使多人同时编辑冲突时一组一起提交的修改可以整体撤销（`EditGuard` 检查，`common/src/main/java/mtr/packet/EditGuard.java:195`、`:200`）；新建数据有数量上限 `MAX_DATA_COUNT`；处理异常不再抛出到网络线程。

## 类 `PacketTrainDataBase`（abstract，implements `IPacket`）

### 常量
- `MAX_DATA_COUNT = 100000`：一个数据集合最多条数，超过不再新建。
- `FAILED`（`ThreadLocal<Boolean>`，初值 false）：本线程上某次修改失败。

### 包格式（更新包，`updateData` 读取）
`long id`、`String 交通方式名`、`String 字段键`，之后是该字段的值（由 `data.update(key, packet)` 读取，格式随字段而定）。删除包只有 `long id`。

### `protected static <T extends NameColorDataBase, U extends ReentrantBlockableEventLoop<?>> void updateData(Set<T> dataSet, Map<Long, T> cacheMap, U minecraft, FriendlyByteBuf packet, PacketCallback packetCallback, BiConsumer<T, List<String>> dataCallback, BiFunction<Long, TransportMode, T> createDataWithId)`
- 作用：复制完整包（用于转发给其他玩家），读 id、交通方式（不认识用 TRAIN）、键，复制剩余部分；在 `minecraft` 线程（服务器或客户端主线程）中：找到数据则（若有 `dataCallback` 先取旧数据快照 `RailwayDataLoggingModule.getData`）`update(key, 剩余包)` 并回调；找不到且允许新建（`createDataWithId` 非 null 且未达上限）则新建、加入集合、更新并回调（旧数据为空列表）；最后 `packetCallback(更新部分, 完整包)`（服务端一般用来广播）。任何异常打印并置 `FAILED = true`。
- 参数：`dataSet`/`cacheMap` 数据集合与 id 索引；`dataCallback` 修改后回调（用于日志、同步等，可为 null）。

### `protected static <T, U> void deleteData(Set<T> dataSet, Map<Long, T> cacheMap, U minecraft, FriendlyByteBuf packet, PacketCallback packetCallback, Consumer<T> dataCallback)`
- 作用：读 id；在主线程中找到则回调后从集合删除；之后 `packetCallback(null, 完整包)`；异常打印并置 `FAILED`。调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:326`。

## 函数式接口 `PacketCallback`（protected）
- `void packetCallback(FriendlyByteBuf updatePacket, FriendlyByteBuf fullPacket)`：`updatePacket` 为去掉 id/方式/键后的部分（删除时为 null），`fullPacket` 为原始完整包副本。
