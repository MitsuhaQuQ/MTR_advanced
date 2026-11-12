# AudioSpeakerPackets.java（本分支新增）

音箱方块的网络包：服务端让客户端打开设置界面、接收客户端提交的设置、以及向指定玩家下达播放/停止。服务端发送方是 `BlockAudioSpeaker`（`common/src/main/java/mtr/block/BlockAudioSpeaker.java:59`、`:193`、`:209`、`:220`）；C2S `PACKET_UPDATE_AUDIO_SPEAKER → edit` 在 `common/src/main/java/mtr/MTR.java:505` 经 `PacketGuard` 注册；客户端接收在 `MTRClient`（打开界面 → `AudioSpeakerScreen`，播放 → `SpeakerPlayback`）。

## 包格式
- S2C `PACKET_OPEN_AUDIO_SPEAKER`：`BlockPos`、`SpeakerSettings`（`settings.write(packet)` 的字段顺序）。
- C2S `PACKET_UPDATE_AUDIO_SPEAKER`：`BlockPos`、`SpeakerSettings`。
- S2C `PACKET_AUDIO_SPEAKER_PLAYBACK`：`ResourceLocation 维度`、`BlockPos`、`Action` 枚举；`STOP` 到此为止；`ONCE`/`LOOP` 之后接 `VarInt 片段数`，每段 `String 声音文本`、`String 文件哈希(≤64)`、`VarInt 间隔(0.1 秒)`，再 `VarInt 半径`、`byte 听众方向掩码`、`boolean 播完再触发`、`VarInt 间隔(0.1 秒)`（0.70.0）。

## 类 `AudioSpeakerPackets`（final，implements `IPacket`，私有构造）

### `static void open(ServerPlayer player, BlockPos pos, SpeakerSettings settings)`
- 发 S2C 打开界面包。

### `static void edit(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet)`
- 作用：网络线程读位置与设置（不合法时 `SpeakerSettings.read` 抛异常，由 `PacketGuard` 断开连接）；服务器线程中检查：有编辑权限、手持刷子、区块已加载、距离 ≤ 8 格、该处是音箱方块实体；任一片段未上传、外观方块不可用、或区域模式的触发中心超出世界范围时，在动作栏提示 `gui.mtr.audio_speaker_invalid` 并放弃；否则 `applySettings`。

### `static void play(ServerPlayer player, ServerLevel world, BlockPos pos, SpeakerSettings settings, List<String> hashes, Action action)`
- 发播放包（格式见上）；`hashes` 与片段一一对应。

### `static void stop(ServerPlayer player, ServerLevel world, BlockPos pos)`
- 发 `STOP` 包。

### `private static FriendlyByteBuf playbackPacket(ServerLevel world, BlockPos pos, Action action)`
- 写包头：维度、位置、动作。

## 枚举 `Action`
- `STOP`、`ONCE`（播放一遍）、`LOOP`（循环）。
