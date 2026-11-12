# TrackOwnersPackets.java（本分支新增）

服务器算出的轨道区段归属（网页分层第 2 步，2026-11-09），供网页编辑器的全网地图显示：每个有归属或手动设置了归属的区段，发送其轨道、所属线路名、是否手动设置以及所属逻辑线。未发送的区段表示所有线路都可运行。归属重新计算后发给所有玩家，玩家打开地图时也会单独发；大线网放不进一个包，所以分片发送。服务端：`common/src/main/java/mtr/data/RailwayData.java:660`（`sendAllIfChanged`）、`:1004`（`send`）；客户端读取：`common/src/main/java/mtr/client/editor/EditorNetwork.java:58`、`:84`、`:126`；注册 `common/src/main/java/mtr/MTRClient.java:341`；测试 `common/src/test/java/mtr/packet/TrackOwnersPacketsTest.java`。

## 包格式（S2C `PACKET_TRACK_OWNERS`，可多片）
- `int 发送版本`、`boolean 首片`、`boolean 末片`；
- 仅首片：`VarInt 名称数`、各 `String 线路名(≤256)`；
- `VarInt 本片区段数`；每个区段：`VarInt 归属数`、各 `VarInt 名称下标`、`boolean 手动`、`long 逻辑线 id(0 为无)`、`VarInt 轨道数(≤65536)`、各 `long 节点1`、`long 节点2`。

## 类 `TrackOwnersPackets`（final，implements `IPacket`，私有构造）

### 客户端公开状态（volatile，收齐后整体替换，编辑器线程读取）
- `railOwners`：轨道键（`RailKeys.of(节点1, 节点2)`）→ `Owners`。
- `ownerNames`：线路名列表（`Owners.owners` 是其下标）。
- `version`：每完整收到一套归属加 1。

### 常量
- `PART_BYTES = 768 KB`（每片区段数据大约上限）、`MAX_NAMES = 65536`、`MAX_SECTIONS = 1 << 20`、`MAX_RAILS = 1 << 16`、`MAX_NAME_LENGTH = 256`。

### 接收中的临时状态
- `RECEIVING`、`RECEIVING_NAMES`、`receivingVersion`（−1 表示无效/未在接收）；以 `RECEIVING` 为锁。

## 内部类 `Owners`（final）
- `owners`（名称下标数组）、`manual`（是否手动设置）、`logicalLine`（逻辑线 id 或 0）。

### `static void sendAllIfChanged(Level world, RailwayData railwayData)`
- 归属版本（`routePaths.getOwnersVersion()`）与上次发送的不同时，记下版本并发给世界内所有玩家（每个玩家用包的副本）。

### `static void send(ServerPlayer player, RailwayData railwayData)`
- 发给一个玩家。

### `private static List<FriendlyByteBuf> write(RailwayData railwayData)`
- 图记录的线路段（`graphRecords.getLegs()`）或归属尚未算好时返回 null（不发）；否则调用下一个重载。

### `static List<FriendlyByteBuf> write(TrackSections.Snapshot snapshot, Map<Long, Set<String>> sectionOwners, Set<Long> manual, Map<Long, Long> logical, int sendVersion, int partBytes)`（包级）
- 作用：要发送的区段 = 有归属 ∪ 手动 ∪ 有逻辑线（按哈希排序）；名称按首次出现编号；逐区段写入，区段数据超过 `partBytes` 就切出一片；最后一片标记末片。快照中不存在的区段跳过。

### `private static FriendlyByteBuf part(int sendVersion, boolean first, boolean last, Map<String, Integer> nameIndices, int count, FriendlyByteBuf sections)`
- 组装一片（首片带名称表）。

### `static void receiveS2C(FriendlyByteBuf packet)`
- 客户端：首片时清空并读名称表；非首片且版本不符（漏了首片或旧的一套）则丢弃；任何数量或下标越界时把 `receivingVersion` 置 −1（本套作废）；末片且版本一致时整体替换 `railOwners`、`ownerNames`，`version++`。
