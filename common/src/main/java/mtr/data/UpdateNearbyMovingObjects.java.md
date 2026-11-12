# UpdateNearbyMovingObjects.java

为每个玩家维护其附近移动对象（列车、升降机）的客户端副本。每个 tick：新进入范围的对象发送完整数据；已在范围内的只发变化（对列车而言是"状态包"，G2）；离开范围或被移除的在客户端删除。`RailwayData` 持有两个实例（`RailwayData.java:344-345`：列车带状态包、升降机不带），每 tick `startTick`（`:569`、`:635`）→ 由 `Siding.simulateTrain` / `Lift.tickServer` 填充 `newDataSetInPlayerRange` 与 `dataSetToSync` → `tick`（`:662-663`）。玩家离开时 `forget`（`:1350`），客户端请求完整快照时 `requestFullSnapshot`（`:1359`）。

## class UpdateNearbyMovingObjects<T extends NameColorDataBase> implements IPacket

### 字段
- `newDataSetInPlayerRange`（public）：本 tick 每个玩家范围内的对象，由外部填充。
- `dataSetToSync`（public）：本 tick 需要重新同步的对象（数据有变化）。
- `dataSetInPlayerRange`：上一 tick 每个玩家范围内的对象。
- `deletePacketId`、`updatePacketId`：删除包、完整更新包的 id。
- `statePacketId`：仅列车，只含运行中会变化部分的状态包 id，或 `null`。
- `sentKeys`：每个玩家 → 每列车上次完整发送时的同步键（`Train.getSyncKey()`，路径未变则相同）。
- `TOLD_TOO_BIG`（静态）：已报告过"太大无法发送"的对象 id，只报一次。
- `requestedSnapshots`：本 tick 请求完整快照的玩家。
- `snapshotTimes`（`WeakHashMap`）：每个玩家上次请求快照的时间，用于限流。
- `sender`：发送函数（默认 `Registry::sendToPlayer`，测试可替换）。
- `interface Sender { void send(ServerPlayer, ResourceLocation, FriendlyByteBuf); }`

### 构造函数
- `(deletePacketId, updatePacketId)`：无状态包。
- `(deletePacketId, updatePacketId, statePacketId)`：默认发送器。
- 包内 `(…, Sender sender)`：测试用。
- 主要调用者：`RailwayData.java:344`、`:345`。

### `public void requestFullSnapshot(Player player)` / `boolean requestFullSnapshot(Player player, long now)`
- 作用：请求下次 `tick` 给该玩家发完整快照；同一玩家 2 秒内重复请求被忽略。
- 参数：`now` 毫秒（public 版用 `System.nanoTime()/1e6`）。
- 返回（包内版）：是否接受请求。
- 主要调用者：`RailwayData.java:1359`；测试 `TrainSnapshotRecoveryTest.java:24`。

### `public void forget(Player player)`
- 作用：清除该玩家所有记录。
- 主要调用者：`RailwayData.java:1350-1351`、`MTR.java:568`。

### `public void startTick()`
- 作用：清空 `newDataSetInPlayerRange` 与 `dataSetToSync`。

### `public void tick()`
- 作用：
  1. 对请求快照的玩家：发一个删除包，内容为"保留列表"（`int n, long id×n`，本 tick 可见对象），客户端据此删除其余；然后清除该玩家的范围与同步键记录，使下面把可见对象当作新进入、发完整数据。
  2. 对上一 tick 有、本 tick 消失的对象：给该玩家发一个删除包（同样是保留列表，玩家不在新集合中时为 0 个）；每个玩家最多发一次。
  3. 对本 tick 每个玩家的每个对象：若需同步或新进入：列车且非新进入且同步键未变 → 写状态包；否则写完整包（< `MAX_PACKET_BYTES` 时加入，列车记下同步键；过大则首次打印 "... is too big to send to players"，玩家看不到它）。清理不在范围内的同步键。状态包合并发送；完整包按 `MAX_PACKET_BYTES`（1 MiB）尽量合并后发送。
  4. 用本 tick 集合替换 `dataSetInPlayerRange`。
- 副作用：发包、日志。
- 注意：删除包超过 `MAX_PACKET_BYTES` 时直接不发。

### `private void send(ServerPlayer player, ResourceLocation packetId, List<FriendlyByteBuf> packets)`
- 作用：把多个包按大小上限尽量拼接成少量包发送（消费 `packets`）。
