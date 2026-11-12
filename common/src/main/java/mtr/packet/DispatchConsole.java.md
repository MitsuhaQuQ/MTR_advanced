# DispatchConsole.java（本分支新增）

调度台（设计文档 D6）的网络包：网页编辑器打开调度台时每隔几秒向服务器要一次快照（运用中/在线列车的状态与被占用的信号闭塞），以及调度员对列车下达的命令。客户端：`common/src/main/java/mtr/client/editor/EditorConsole.java:30`（请求）、`:32`（读快照）、`:171`（命令）；注册：`common/src/main/java/mtr/MTR.java:476`、`:478`（C2S），`common/src/main/java/mtr/MTRClient.java:342`（S2C）；测试 `common/src/test/java/mtr/packet/DispatchConsoleTest.java`。

## 包格式
- C2S `PACKET_CONSOLE`（请求）：空包。
- S2C `PACKET_CONSOLE`（快照）：`long 服务器毫秒`；`VarInt 占用闭塞数(≤20000)`、各 `long 闭塞 id`；`VarInt 列车数(≤4000)`，每列：`long 列车 id`、`long 停车轨 id`、`long 当前趟线路 id`、`VarInt 运用槽`、`long+long 车头所在轨道的 UUID（无为 0,0）`、`long 下一停靠站台`、`long 晚点起始毫秒`、`long 等待起始毫秒`、`VarInt 标志`（1 正在越站、2 停运、4 不在运用中）、`VarInt 折返状态`、`VarInt 交接状态`、`VarInt 调度状态`、`long 上一停靠站台`、`VarInt 停站状态`（见 `TrainServer.getStopState`）。
- C2S `PACKET_DISPATCH`（命令）：`long 列车 id`、`VarInt 命令`、`long 值`、`VarInt 站台数(服务端最多读 64)`、各 `long 站台 id`。

## 类 `DispatchConsole`（final，implements `IPacket`，私有构造）

### 常量与静态字段
- `MAX_TRAINS = 4000`、`MAX_BLOCKS = 20000`：快照上限。
- `clientSnapshot`（volatile）：客户端最近收到的快照，没有为 null。
- `lastRequestMillis`：客户端上次请求时间。

### `static void sendRequestC2S()`
- 客户端：距上次不足 1 秒则不发。

### `static void receiveRequestC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 服务器线程中把 `write(railwayData)` 发给请求者（不检查编辑权限，只读）。

### `static FriendlyByteBuf write(RailwayData railwayData)`（包级）
- 作用：收集被占用的信号闭塞（`signalBlock.isOccupied()`），按停车轨 id 顺序收集所有正在线上（`isOnRouteNow()`）的列车——无论是否在运用中（2026-11-09：曾有一列停在终点站、已脱离运用的列车占着闭塞，但调度台什么都不显示），按上述格式写出。

### `static void receiveS2C(FriendlyByteBuf packet)`
- 客户端：`clientSnapshot = read(packet)`。

### `static Snapshot read(FriendlyByteBuf packet)`（包级）
- 读快照（数量按上限截断）；`clockOffset = 客户端当前毫秒 − 服务器毫秒`。

### `static void sendCommandC2S(long trainId, int command, long value, Collection<Long> platformIds)`
- 客户端发调度命令（含义见 `TrainService.dispatch`）。

### `static void receiveCommandC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 服务器线程中：须有编辑权限且命令在 `TrainService.COMMAND_HOLD`…`COMMAND_CANCEL` 范围内；`railwayData.dispatch(trainId, command, value, platformIds)` 成功则打印日志。

## 内部类 `Snapshot`（final）
- `serverMillis`、`clockOffset`（客户端时钟减服务器时钟，用来按服务器时刻计算晚点多久）、`occupiedBlocks`、`trains`。

## 内部类 `TrainState`（final）
- 字段与快照中每列车的字段一一对应：`trainId`、`sidingId`、`routeId`、`slot`、`headRail`、`nextStopId`、`lateSince`、`waitingSince`、`flags`（1 越站、2 停运、4 不在运用）、`turnState`、`takeoverState`、`dispatchState`、`lastStopId`、`stopState`。
