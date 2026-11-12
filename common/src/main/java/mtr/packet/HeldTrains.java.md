# HeldTrains.java（本分支新增）

车厂运用把列车留在线上等待（P1-C）以及"回库指令无法执行"的班次（P1-B）：发给客户端供网页编辑器的同步面板显示；玩家可在那里亲自下令把某列等待中的列车立即收回停车轨。服务端收集与发送：`common/src/main/java/mtr/data/RailwayData.java:868`、`:876`；客户端发召回：`common/src/main/java/mtr/client/editor/EditorActions.java:395`；注册：`common/src/main/java/mtr/MTR.java:471`、`common/src/main/java/mtr/MTRClient.java:336`。

## 包格式
- S2C `PACKET_HELD_TRAINS`：`long 车厂 id`、留线列车列表、回库失败列表；每个列表为 `int 条数(≤64)` + 各 `String(≤256)`。
- C2S `PACKET_RECALL_TRAIN`：`long 车厂 id`、`long 停车轨 id`、`int 槽位`。

## 类 `HeldTrains`（final，implements `IPacket`，私有构造）

### 常量
- `MAX_ENTRIES = 64`、`MAX_LENGTH = 256`。

### `static List<String> collect(Depot depot, Collection<Siding> sidings, DataCache dataCache)`
- 作用：该车厂所属停车轨中、留在线上且没有后续运用的列车，每列一行 `<停车轨> <槽位> <站台> <原因>`（`siding.forEachHeldTrain`），排序后返回。

### `static void sendS2C(Level world, long depotId, List<String> held, List<String> returnMisses)`
- 发给该世界所有玩家。

### `static void receiveS2C(FriendlyByteBuf packet)`
- 客户端：主线程中写入车厂的 `clientHeldTrains`，并在同步块内替换 `returnOrderMisses`。

### `static void sendRecallC2S(long depotId, long sidingId, int slot)`
- 客户端：请求把该停车轨该槽位的等待列车立即收回。

### `static void receiveRecallC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet)`
- 服务器线程中：有铁路数据、有编辑权限、停车轨属于该车厂且 `siding.recallHeldTrain(slot)` 成功时打印日志并置 `depot.runProblemsChanged = true`。

### `private static void writeList(FriendlyByteBuf, List<String>)` / `private static List<String> readList(FriendlyByteBuf)`
- 写最多 64 条（截断到 256 字符）；读时条数越界抛 `IllegalArgumentException`。
