# DepotWayPreview.java（本分支新增）

车厂规则的出入库路径预览（运用补完第 2 步）：从停车轨出到某站台、或从站台回停车轨的路径，按规则可以指定出入库经过的车站，或经过一条命名回送路径的节点；服务端用与路径生成相同的方法求出，供网页编辑器在全网地图上随选择实时绘制。回答中还包含该停车轨接入正线的车站。客户端用 `request` 发问，服务端只回答发问的玩家。调用者：`common/src/main/java/mtr/client/editor/EditorServer.java:674`；注册：`common/src/main/java/mtr/MTR.java:470`（C2S）、`common/src/main/java/mtr/MTRClient.java:335`（S2C）。

## 包格式（通道 `PACKET_DEPOT_WAY_PREVIEW`）
- C2S：`int 请求 id`、`long 车厂 id`、`long 停车轨 id`、`long 站台 id（0 表示只要接入车站）`、`boolean 出库`、`long 经过的车站（0 表示无）`、`int 途经节点数(≤ DepotRules.MAX_WAYPOINTS)`、各 `long 节点`。
- S2C：`int 请求 id`、`int 接入车站数(≤64)`、各 `long`、`int 节点数(≤8192)`、各 `long 节点`、`long 用时毫秒（无路径为 −1）`。

## 类 `DepotWayPreview`（final，implements `IPacket`，私有构造）

### 常量与静态字段
- `MAX_WAYPOINTS = transitsim.plan.DepotRules.MAX_WAYPOINTS`、`MAX_NODES = 8192`、`MAX_ENTRANCES = 64`。
- `NEXT_ID`（请求 id 计数）、`PENDING`（请求 id → 等待中的 Future，以自身为锁）。

## 内部类 `Answer`（final）
- `entrances`：停车轨接入正线的车站 id；`nodes`：路径节点（无路径为空）；`millis`：用时（毫秒，无路径为 −1）。

### `static CompletableFuture<Answer> request(long depotId, long sidingId, long platformId, boolean out, long entrance, List<Long> waypoints)`
- 客户端：登记 Future 并发包；途经点超过上限时截断。

### `static void receiveC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet)`
- 服务端：途经点数越界则不回答；服务器线程中求解并回发。

### `private static Answer find(RailwayData railwayData, long depotId, long sidingId, long platformId, boolean out, long entrance, long[] waypoints)`
- 作用：车厂或停车轨不存在返回空回答；用车厂线路与规则建 `RoutePaths.DepotLine` 并 `withWay(entrance, waypoints)`；`routePaths.findEntrances` 求接入车站；站台不存在只返回接入车站；`routePaths.previewDepotWay(起, 终, rails, out, depotLine)` 求路径，失败只返回接入车站；成功时节点为首段起点加各段终点（最多 8192），用时为 `RoutePaths.timeDepotWay(way, siding.getTimingVehicle(), out)`。

### `private static FriendlyByteBuf answer(int id, Answer answer)`
- 组回答包。

### `static void receiveS2C(FriendlyByteBuf packet)`
- 客户端：数量越界则丢弃；否则取出对应 Future 并完成。
