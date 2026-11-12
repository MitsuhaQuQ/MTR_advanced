# EditGuard.java（本分支新增）

服务端：多人同时编辑同一份铁路数据时防止互相覆盖（多人编辑冲突）。玩家的修改带着"开始编辑时自己那份数据该部分的版本号"；服务器只在该部分此后未被他人改过时才执行，否则告诉玩家是谁改的，并把当前数据发给他。网页编辑器的修改成批发送，每组要么全做、要么全不做。服务器还会告诉所有人谁正在编辑什么。版本号保存在 `railwayData.editRevisions`（`EditRevisions`）。注册：`common/src/main/java/mtr/MTR.java:491`、`:492`（`register` 各数据更新/删除包）、`:497`（`registerPackets`）；玩家加入时 `revisionsS2C`（`:554`），离开时 `onPlayerQuit`（`:570`）；客户端对应 `common/src/main/java/mtr/client/ClientEditing.java`。

## 包格式
- C2S `PACKET_EDIT`：`String 原包 id`、`int 基准版本`、原包内容（剩余全部字节）。
- C2S `PACKET_EDIT_BATCH`：`int 批号`、`int 修改数(≤4096)`，每个 `String 组名`、`String 原包 id`、`int 基准版本`、`int 长度`、`byte[长度] 原包`；然后 `int 线路数(≤4096)`，每个 `long 线路 id`、`String 页面基于的线路编辑版本（Route.getEditorRevision）`。
- C2S `PACKET_EDIT_FRAGMENT`：分片，见 `EditTransferPackets`，重组后按是否批量交给上面两个处理。
- C2S `PACKET_EDITING`：`String 种类`、`long id`、`boolean 正在编辑`。
- S2C `PACKET_EDIT_RESULT`：`int 批号`、`int 未执行组数`，每组 `String 组名`、`boolean 因被删除`、`String 改动者名（无冲突原因为空）`。
- S2C `PACKET_EDIT_DATA`：`String 种类`、`long id`、`boolean 存在`、（存在时）`data.writePacket`；超过 `MAX_PACKET_BYTES` 不发。
- S2C `PACKET_EDIT_REVISIONS`：`int 条数`，每条 `String 键`（`EditRevisions.getKey(种类, id, 部分)`）、`int 版本`；最后 `UUID 修改者`（全量发送时为 0,0）。
- S2C `PACKET_EDITORS`：`int 人数`，每人 `UUID`、`String 名字`、`String 种类:id`。

## 类 `EditGuard`（final，implements `IPacket`，私有构造）

### 常量与静态字段
- 种类名：`STATION = "station"`、`PLATFORM`、`SIDING`、`ROUTE`、`DEPOT`。
- `MAX_BATCH = 4096`。
- `KINDS`（包 id → 种类）、`DELETES`（删除类包 id）、`HANDLERS`（包 id → 实际处理函数）：静态初始化登记车站/站台/停车轨/线路/车厂的更新与删除包。
- `EDITING`（玩家 → `种类:id`）、`EDITING_NAMES`（玩家 → 名字）。

### `private static void addKind(ResourceLocation packetId, String kind, boolean isDelete)`

### `static String getKind(ResourceLocation packetId)` / `static boolean isDelete(ResourceLocation packetId)`
- 包所改数据的种类（不受检查的包为 null）；是否删除包。

### `static String getKind(NameColorDataBase data)`
- 按对象类型返回种类，其他为 null。调用者：`ClientEditing.java:66`、`:100`。

### `static String getPart(ResourceLocation packetId, FriendlyByteBuf packet)`
- 修改的是对象的哪一部分：删除包为整体（`EditRevisions.WHOLE`）；否则在副本上跳过 id 和交通方式，读出更新键（不移动原包读位置）。

### `static void register(ResourceLocation packetId, NetworkUtilities.PacketCallback callback)`
- 登记处理函数，并把该包作为"未带版本"的修改注册（`EditRevisions.UNCHECKED`）：单独发送、不带版本的修改也不能把服务器运行期间被删除的对象重新建出来。

### `static void registerPackets()`
- 注册 `PACKET_EDIT_FRAGMENT`、`PACKET_EDIT`、`PACKET_EDIT_BATCH`、`PACKET_EDITING`。

### `private static void receiveEditC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 解出原包 id、基准版本、原包，属于受检查的包则 `handle`。

### `private static void handle(MinecraftServer, ServerPlayer, ResourceLocation packetId, int base, FriendlyByteBuf packet)`
- 服务器线程中（逐个按顺序检查）：无数据或无编辑权限则忽略；`editRevisions.check(种类, id, 部分, 基准, 是否删除)` 有冲突则 `reject`；否则作为单个修改的组 `applyGroup`。

### `private static boolean applyGroup(MinecraftServer, ServerPlayer, RailwayData, List<Object[]> changes)`
- 作用：先把组内每个对象的当前状态存为字节（`RailwayDataFileSaveModule.toBytes`，不存在为 null）；记下每个修改的种类/id/部分；依次执行处理函数，每个之前清 `PacketTrainDataBase.FAILED`，出现异常或 `FAILED` 即停止。失败时把组内所有对象恢复原样（`restore`），`dataCache.sync()`，把这些对象重新发给世界内所有人（可能已收到部分修改），提示 `gui.mtr.edit_failed`，返回 false，不计版本。成功时为每个修改记录新版本（`editRevisions.record`，记修改者名），向世界内所有人发版本更新，`railwayData.markEdited`（在 tick 末保存），返回 true。
- 参数：`changes` 每项 `{包 id, 基准版本, 包}`。

### `private static void restore(RailwayData, String kind, long id, byte[] bytes)`
- 从集合中删除该对象；有保存的字节则按种类用 msgpack map 重建（`new Station(map)` 等）放回。

### `private static void addTo(Set<?>, NameColorDataBase)` / `private static Set<?> getSet(RailwayData, String kind)` / `private static NameColorDataBase getData(RailwayData, String kind, long id)`
- 按种类取集合/对象的辅助函数。

### `private static void receiveBatchC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 作用：读批（未知包 id 的修改丢弃），按组归类；服务器线程中：无权限则所有组记为未执行并回结果；否则每组先全部检查（第一个冲突为组冲突）；对 `route:<id>` 组，若页面给出的线路编辑版本与现在不同（页面加载后被改过，覆盖时刻表与停站），以"不可能的基准版本"检查 `timetable` 部分以得到最后修改者作为冲突；有冲突则记录并 `reject`（不影响其他组）；否则 `applyGroup`，失败也记为未执行。最后 `sendResult` 告诉页面哪些组没执行（页面把它们留在草稿中）。

### `private static Long parseLong(String text)`
- 解析失败返回 null。

### `private static void sendResult(ServerPlayer, int batchId, Map<String, EditRevisions.Conflict> rejected)`
- 发 `PACKET_EDIT_RESULT`。

### `private static void reject(ServerPlayer, RailwayData, List<long[]> objects, List<String> kinds, EditRevisions.Conflict conflict)`
- 提示 `gui.mtr.edit_conflict_deleted` 或 `gui.mtr.edit_conflict_changed`（带改动者名，未知为 `?`），并把涉及的对象（去重）当前状态发给该玩家，让其副本恢复正确。

### `private static void sendData(ServerPlayer, RailwayData, String kind, long id)`
- 发 `PACKET_EDIT_DATA`。

### `private static void sendRevision(Level world, String kind, long id, String part, int revision, UUID editor)`
- 向世界内所有人发一条版本更新。

### `static void revisionsS2C(ServerPlayer player)`
- 玩家加入时发全部版本（修改者 UUID 为 0,0）和当前编辑者列表。

### `private static void writeRevision(FriendlyByteBuf, String key, int revision)`

### `private static void receiveEditingC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 玩家开始编辑则登记（覆盖之前的）；结束编辑且与登记的一致则移除；之后向所有在线玩家发编辑者列表。

### `static void onPlayerQuit(MinecraftServer, ServerPlayer)`
- 玩家离开时移除其编辑登记，若有则通知其他玩家。

### `private static void sendEditors(ServerPlayer player)`
- 发 `PACKET_EDITORS`。
