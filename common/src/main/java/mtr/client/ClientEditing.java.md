# ClientEditing.java（本分支新增）

客户端：防止多人编辑时互相覆盖（多人编辑冲突，服务器侧见 `EditGuard`）。每个修改都带上它所属部分的版本号——取玩家打开该编辑界面时的版本，或客户端当前所知的版本；服务器拒绝来自旧副本的修改，并把数据按服务器现状发回。客户端还会告诉服务器自己在编辑什么，并记录其他人在编辑什么。网页编辑器的多个修改可以收集成一批，按"组"全成或全不成。包接收注册：`common/src/main/java/mtr/MTRClient.java:331`（版本）、`:332`（编辑者）、`:333`（批结果）、`:344`（数据）。

## 类 `ClientEditing`（final，implements `IPacket`，私有构造）

### 静态字段
- `REVISIONS`：`kind:id:part` → 服务器版本号（`EditRevisions.getKey`）。
- `SNAPSHOTS`：每个打开着编辑界面的对象（`kind:id`）→ 打开时各部分版本，随玩家自己的修改递增；`SNAPSHOT_MILLIS`：打开时间。
- `EDITORS`：`kind:id` → 正在编辑它的其他玩家名。
- `batch`：正在收集的网页编辑器修改（每项 `Object[]{packetId, base, bytes, group}`），null 表示不在收集。
- `BATCH_BASES`：本批中页面草稿所基于的各对象各部分版本。
- `batchGroup`：当前收集的修改所属组（服务器按组全成或全不成）。
- `PENDING`：已发送未答复的批（编号 → future）；`nextBatchId`。
- `SNAPSHOT_MILLIS_MAX = 30 分钟`：界面打开超过此时间，其快照不再使用（视为过期，改用客户端当前版本）。

### `static synchronized boolean send(ResourceLocation packetId, FriendlyByteBuf packet)`
- 作用：发送一个铁路数据修改并附上版本号，或加入正在收集的批。`EditGuard.getKind(packetId)` 为 null（不受检查的包）时返回 false，由调用方原样发送。对象 id 取包首个 long（不移动读指针），部分名由 `EditGuard.getPart` 决定。基准版本：删除包为 `EditRevisions.UNCHECKED`；批中有页面给的基准则用之；有未过期快照则用快照（并把快照该部分 +1，使同一界面的下一次修改基于这次）；否则用 `REVISIONS`（默认 0）。不在批中时发送 `PACKET_EDIT`：`String packetId, int base, 原包剩余字节`，超过 `EditTransferAssembler.CHUNK_BYTES` 时分片（`EditTransferPackets.send`）。
- 返回：是否已由本类处理。调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:578`。

### `static synchronized void beginEdit(NameColorDataBase data)`
- 编辑界面打开：以当前已知版本建立快照、记时间，并告诉服务器正在编辑（`sendEditing(…, true)`）。调用者：`common/src/main/java/mtr/screen/EditNameColorScreenBase.java:36`、`common/src/main/java/mtr/screen/SavedRailScreenBase.java:38`。

### `static void endEdit(NameColorDataBase data)`
- 界面关闭：通知服务器不再编辑；快照保留，供关闭时发送的修改使用。调用者：`EditNameColorScreenBase.java:54`、`SavedRailScreenBase.java:118`。

### `static synchronized void startBatch()` / `setBatchGroup(String group)` / `setBatchBases(NameColorDataBase data, Map<String, Integer> parts)`
- 开始收集（清空基准、组为空串）；设置之后修改所属组（如一条线路的修改）；设置某对象在本批中的基准版本（只设第一次）。调用者：`common/src/main/java/mtr/client/editor/EditorActions.java:246`、`:257`、`:261`。

### `static synchronized Map<String, Integer> getRevisions(NameColorDataBase data)`
- 返回：客户端当前所知该对象各部分版本（部分名 → 版本，有序）。调用者：`EditorActions.java:102`、`common/src/main/java/mtr/client/editor/EditorData.java:482`。

### `static synchronized CompletableFuture<BatchResult> sendBatch(Map<Long, String> routeBases)`
- 作用：结束收集并发送 `PACKET_EDIT_BATCH`：`int 批号, int 修改数`，每个 `String 组, String packetId, int base, int 长度, 字节`；然后 `int 线路数`，每个 `long 线路 id, String 基准`（页面所基于的线路编辑器版本 `Route.getEditorRevision`）。必要时分片；发送异常时 future 异常完成。没有修改时立即返回空结果。
- 返回：服务器答复后完成的 future。调用者：`EditorActions.java:280`。

### `static void receiveResultS2C(FriendlyByteBuf packet)`
- 包：`int 批号, int 被拒组数`，每个 `String 组, boolean 是否因删除, String 修改/删除者`。完成对应 future。

### `static synchronized List<String> getEditors(NameColorDataBase data)`
- 返回：正在编辑该对象的其他玩家。调用者：`EditNameColorScreenBase.java:102`、`EditorData.java:479`。

### `static void receiveRevisionsS2C(FriendlyByteBuf packet)`
- 包：`int 数量`，每个 `String 键, int 版本`；`UUID 修改者`（全零表示加入时的全量，先清空版本与快照）。写入 `REVISIONS`；部分为 `EditRevisions.WHOLE`（整个对象变化，如删除）时丢弃该对象快照；本人的修改则把快照对应部分推进到不小于新版本。

### `static void receiveEditorsS2C(FriendlyByteBuf packet)`
- 包：`int 人数`，每个 `UUID, String 名字, String 编辑目标(kind:id)`；排除自己后替换 `EDITORS`。

### `static void receiveDataS2C(Minecraft minecraft, FriendlyByteBuf packet)`
- 修改被拒后服务器发回的对象：`String kind, long id, boolean 存在, [对象]`（车站/站台/停车轨/线路/车厂；未知种类忽略）。主线程替换客户端集合中的对象（不存在则删除），`DATA_CACHE.sync()` 并刷新动态纹理。

### `private static void replace(String kind, long id, NameColorDataBase data)`
- 在对应 `ClientData` 集合中按 id 删除后加入新对象。

### `private static Map<String, Integer> getSnapshot(String objectKey)`
- 返回未超过 30 分钟的快照，否则删除并返回 null。

### `private static void sendEditing(String kind, long id, boolean editing)`
- 发 `PACKET_EDITING`：`String kind, long id, boolean editing`。

## 内部类 `BatchResult`（final）
- `rejected`：未成功的组 → `{"true"/"false"（是否因其中有东西被删除）, 修改或删除者}`。调用者：`EditorActions.java:233`、`:298`。
