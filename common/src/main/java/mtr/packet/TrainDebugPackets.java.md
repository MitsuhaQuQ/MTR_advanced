# TrainDebugPackets.java（本分支新增）

列车调试显示（0.65.31）：玩家手持刷子看向某列车时，客户端约每秒请求一次该列车的调试信息，服务器回答该列车的若干行（`TrainServer.getDebugLines`），由 `RenderTrainDebug` 显示在屏幕右上。调用者：`common/src/main/java/mtr/render/RenderTrainDebug.java:78`、`:79`、`:85`；注册：`common/src/main/java/mtr/MTR.java:477`、`common/src/main/java/mtr/MTRClient.java:343`；测试 `common/src/test/java/mtr/packet/TrainDebugPacketsTest.java`。

## 包格式（通道 `PACKET_TRAIN_DEBUG`）
- C2S：`long 列车 id`。
- S2C：`long 列车 id`、`VarInt 行数(≤32)`，每行 `String 标签(≤256)`、`String 值(≤256)`。

## 类 `TrainDebugPackets`（final，implements `IPacket`，私有构造）

### 常量与静态字段
- `MAX_LINES = 32`、`MAX_TEXT = 256`。
- 客户端：`lastRequestMillis`、`lastRequestedTrain`；最近回答 `clientTrainId`、`clientLines`（`{标签, 值}` 列表）、`clientReceivedMillis`（均 volatile）。

### `static void sendRequestC2S(long trainId)`
- 换了列车立即请求，同一列车最多每秒一次。

### `static void receiveRequestC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 服务器线程中在所有停车轨中找该列车，回答其调试行（找不到回空列表）。不检查权限。

### `static FriendlyByteBuf write(long trainId, List<String[]> lines)`（包级）
- 最多 32 行，每项截断到 256 字符。

### `static void receiveS2C(FriendlyByteBuf packet)`
- 客户端：更新三个 `client*` 字段。

### `static List<String[]> read(FriendlyByteBuf packet)`（包级）
- 行数越界抛 `IllegalArgumentException`。

### `private static String limit(String text)`
- null 为空串，超长截断。
