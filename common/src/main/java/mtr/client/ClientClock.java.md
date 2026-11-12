# ClientClock.java（本分支新增）

客户端看到的服务器时钟（G2）：即使两台电脑时钟不一致，客户端也按服务器时间计算列车位置和显示时刻。服务器会不时发送当前时间；消息只会迟到不会早到，所以最近几次"服务器时间 − 本地时间"差值中的最大者最接近真实偏差。小的变化缓慢追上，显示的时间永不倒退；大的变化（刚加入或服务器改了时钟）立即采用。服务器时间由 `PacketTrainDataGuiClient.receiveServerTimeS2C` 送入（`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:99`）；使用者：`common/src/main/java/mtr/data/Train.java:746`、`RenderPIDS` 等。测试：`common/src/test/java/mtr/client/ClientClockTest.java`。

## 类 `ClientClock`（final）

### 字段与常量
- `INSTANCE`：客户端唯一实例。
- `differences`：最近的差值（最多 `SAMPLES = 6` 个）。
- `known`：是否已有服务器时间。
- `target`：目标偏差（毫秒，差值最大者）。
- `offset`：当前使用的偏差。
- `lastLocalMillis`、`lastServerMillis`：上次计算时的本地时间与给出的服务器时间（保证单调）。
- `JUMP_MILLIS = 5000`：目标与当前偏差相差超过 5 秒就立即跳变。
- `SLEW_DIVISOR = 10`：小的变化每经过 10 毫秒追 1 毫秒。

### `void onServerTime(long serverMillis)` / `synchronized void onServerTime(long serverMillis, long localMillis)`
- 作用：记录差值并取最近 6 个中的最大者为目标；尚未知道或偏差变化超过 5 秒时立即采用，并重置单调基准。
- 参数：服务器毫秒、本地收到时的毫秒。

### `long serverMillis()` / `synchronized long serverMillis(long localMillis)`
- 作用：先按经过的本地时间把偏差向目标逼近（`currentOffset`），返回 `max(本地 + 偏差, 上次返回值)`。
- 返回：服务器当前毫秒；除非服务器时钟跳变，否则不会倒退。

### `synchronized void reset()`
- 离开服务器时清空。

### `private long currentOffset(long localMillis)`
- 偏差已等于目标则返回；否则按 `(本地 − 上次本地) / 10` 的步长向目标移动，不越过目标。
