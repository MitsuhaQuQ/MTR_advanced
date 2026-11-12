# BlockTrainAnnouncer.java

列车广播器方块：与原版 MTR 一样，向驶过它的列车上的乘客广播——显示文字消息，并依次播放一组声音（每段之后有间隔，0.72.0）。0.72.0 起只做这件事：本分支曾加的车站触发（到站前、停稳后、发车前）和向站台广播已移除（用户 2026-11-12）；旧存档里的这些设置原样保留写回但不再使用，所以这类广播器重新变为向驶过的乘客广播。列车检测在 `TrainServer`（`common/src/main/java/mtr/data/TrainServer.java:427`–`:429`）；注册于 `common/src/main/java/mtr/Blocks.java:204`。本分支改动：声音从单个 `ResourceLocation` 改为声音文本（游戏声音、服务器上传文件或网址，见 `AnnouncementSound`）并支持片段列表（`AnnouncementClips`）；新增 NBT 键并保持旧键兼容。

## 类 `BlockTrainAnnouncer`（extends `BlockTrainSensorBase`）

### `BlockEntityMapper createBlockEntity(BlockPos, BlockState)`
- 新建 `TileEntityTrainAnnouncer`。

## 内部类 `TileEntityTrainAnnouncer`（extends `TileEntityTrainSensorBase`）

### 字段
- `message`：文字消息。
- `soundString`：第一段声音（`AnnouncementSound` 文本）。
- `clips`：依次播放的片段（`AnnouncementClips.Clip`，含间隔），第一段即 `soundString`。
- `retiredSettings`：0.6x 存档中车站触发设置的原样拷贝（不使用，写回时保留）。
- `lastAnnouncedMillis`：每个玩家上次被广播的时间。
- `ANNOUNCE_COOL_DOWN_MILLIS = 20000`：同一玩家 20 秒内只广播一次。

### NBT 键
- `message`；`sound_id`（旧键：只有游戏声音的 id，网址/上传文件时为空串，供不认识新键的版本继续播放游戏声音）；`sound_string`（与 MTR 1.20.1-3.5.0 相同的键，保留网址）；`sound_clips`（`AnnouncementClips.toText` 文本，0.72.0）；退役键 `station_trigger`、`station_seconds`、`station_to_riders`（原样保留）。

### 构造函数 `TileEntityTrainAnnouncer(BlockPos pos, BlockState state)`

### `void readCompoundTag(CompoundTag)`
- 读消息；声音优先 `sound_string`，否则 `sound_id`；有 `sound_clips` 则解析为片段，否则把单个声音当一段；拷贝退役键；再读父类（线路过滤等）。

### `void writeCompoundTag(CompoundTag)`
- 写上述所有键（退役设置原样写回），再写父类。

### `void setData(Set<Long> filterRouteIds, boolean stoppedOnly, boolean movingOnly, int number, String... strings)`
- 界面保存：`strings[0]` 消息、`strings[1]` 第一段声音、`strings[2]`（可选，0.72.0）片段文本；再设置父类的线路过滤与停/行条件。调用者：`PacketTrainDataGuiServer` 的传感器设置包处理。

### `String getMessage()` / `String getSoundIdString()` / `List<AnnouncementClips.Clip> getClips()`

### `private void setClips(List<AnnouncementClips.Clip> newClips)`
- 设置片段并同步 `soundString` 为第一段（无片段为空串）。

### `void announce(Player player)`
- 作用：对服务端玩家，若 20 秒内未广播过，记录时间并 `PacketTrainDataGuiServer.announceS2C(player, message, clips)`。调用者：`common/src/main/java/mtr/data/TrainServer.java:429`。
