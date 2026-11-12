# RoutePreview.java（本分支新增）

新建线路的路径预览：给出一串站台（每段可带途经节点），服务端按路径生成同样的方法逐段寻路，返回每段经过的节点或找不到的原因，供网页编辑器在全网地图上绘制新线。客户端用 `request` 发问，服务端只回答发问的玩家。调用者：`common/src/main/java/mtr/client/editor/EditorServer.java:717`、`:735`；注册：`common/src/main/java/mtr/MTR.java:469`、`common/src/main/java/mtr/MTRClient.java:334`。

## 包格式（通道 `PACKET_ROUTE_PREVIEW`）
- C2S：`int 请求 id`、`int 站台数(≤32)`，每个 `long 站台 id`、`int 该段途经点数(≤16)`、各 `long 节点`。
- S2C：`int 请求 id`、`int 段数`，每段 `int 节点数(≤8192)`、各 `long 节点`、`PathFailure`（`writePacket` 格式）。

## 类 `RoutePreview`（final，implements `IPacket`，私有构造）

### 常量与静态字段
- `MAX_STOPS = 32`（public，编辑器也用）、`MAX_WAYPOINTS = 16`、`MAX_NODES = 8192`。
- `NEXT_ID`、`PENDING`（请求 id → Future，以自身为锁）。

## 内部类 `Leg`（final）
- `nodes`：该段经过的节点（失败为空）；`failure`：失败原因（成功为 `PathFailure.NONE`）。

### `static CompletableFuture<List<Leg>> request(List<Long> platformIds, List<List<Long>> waypoints)`
- 客户端：登记 Future 并发包；超出上限的站台/途经点被截断。

### `static void receiveC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 服务端：数量越界则不回答；服务器线程中 `find` 后回发。

### `private static List<Leg> find(RailwayData railwayData, List<Long> platformIds, List<List<BlockPos>> waypoints)`
- 作用：用 `railwayData.getRails()` 和预览图（`getPreviewGraph()`，含寻路边界）逐段 `PathFinder.findPath`（巡航高度 256、非快速），站台不存在或失败时给出原因（有图时 `PathFailure.diagnose`，否则 `NO_ROUTE`）；成功时节点为首段起点加各段终点（最多 8192）。

### `private static FriendlyByteBuf answer(int id, List<Leg> legs)`
- 组回答包。

### `static void receiveS2C(FriendlyByteBuf packet)`
- 客户端：节点数越界则丢弃整个回答；否则取出 Future 并完成。
