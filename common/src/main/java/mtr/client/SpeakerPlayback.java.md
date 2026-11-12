# SpeakerPlayback.java（本分支新增）

客户端：管理服务器指向本玩家的音箱所播放的游戏声音实例。0.70.0 起音箱按顺序播放多个片段，片段后有设定的间隔（由仿真组件的 `transitsim.sim.ClipSequence` 控制顺序与间隔）；只有一段且无间隔的循环仍用游戏自带的循环声音，完全无缝。注册：`common/src/main/java/mtr/MTRClient.java:291`（打开界面包 → `open`）、`:292`（播放包 → `receive`）、`:293`（每 tick → `tick`）。包格式见 `AudioSpeakerPackets`。

## 类 `SpeakerPlayback`（final，私有构造）

### 常量与静态字段
- `LOOP_LEASE_TICKS = 80`：循环的"租期"——服务器每 20 tick 刷新一次听众，80 tick 没收到就停止。
- `DOWNLOAD_TIMEOUT_TICKS = 600`：单次播放等待文件下载的最长时间。
- `MAX_SPEAKERS = 32`：同时最多音箱数。
- `START_TICKS = 2`：刚开始的片段在问声音管理器之前先视为正在播放的 tick 数。
- `PLAYING`（方块位置 → `Playback`）、`world`（当前世界，变化时全部停止）。

### `static void open(FriendlyByteBuf packet)`
- 读位置与 `SpeakerSettings`，主线程中打开 `AudioSpeakerScreen`。

### `static void receive(FriendlyByteBuf packet)`
- 作用：读维度、位置、动作；非 STOP 时读片段（1–16 个：声音文本、哈希、间隔）、半径、方向掩码、是否播完再触发、间隔。主线程中：维度不符忽略；STOP 停止并移除；参数不合法忽略；单次播放、"播完再触发"且之前同一组片段仍在单次播放时忽略；同样参数的循环只续租；否则（已有或腾出了位置）停止旧的并新建 `Playback`。

### `private static boolean makeRoom(Minecraft client, BlockPos pos, int radius, int audienceMask)`
- 未满 32 个直接允许；已满时只有在新音箱对玩家可听时，停掉最远且不可听的一个腾位，找不到则拒绝。

### `static void tick(Minecraft client)`
- 每 tick（未暂停）推进每个播放；区块已加载但方块不再是音箱、玩家不存在、或 `Playback.tick` 返回 false 时停止并移除。

### `private static void checkWorld(Minecraft client)`
- 世界对象变化时停止全部播放。

## 私有内部类 `Clip`
- `reference`（上传声音文本）、`hash`、`gapTenths`；`valid()`：引用是上传文件形式、哈希合法、间隔合法；`equals/hashCode` 按三者。

## 私有内部类 `Playback`
- 字段：`clips`、`radius`、`loop`、`seamless`（循环 + 单段 + 无间隔）、`audienceMask`、`intervalTenths`、`sequence`（`ClipSequence(各段间隔 tick, 是否循环, 循环间隔 tick)`）、`lease`、`waited`、`pollDelay`、`soundAge`、`cooldown`、`cooldownTicks`（单次播放且"播完再触发"时，播完后本地再等的间隔；否则 0，间隔由服务器控制）、`ready`（所有片段文件都在本地）、`sound`。
- `tick(SoundManager, BlockPos)`：冷却中倒计时；循环租期到期返回 false；未就绪时每 10 tick 检查一次每段：本地记录的哈希须与服务器给的一致、游戏中已有对应声音（`UploadedSounds.getLocation`），文件未缓存则 `AudioCache.want` 请求下载；单次播放等待超过 600 tick 放弃。就绪后：无缝循环只需启动一次；否则判断当前片段是否仍在播放，`sequence.tick(playing)` 给出应开始的片段序号则启动；序列结束时进入冷却。返回 false 表示应移除。
- `start(SoundManager, BlockPos, int index)`：停止当前声音，按该段的游戏声音 id 新建 `SpeakerSound` 播放。
- `stop(SoundManager)`：结束并停止当前声音。

## 私有内部类 `SpeakerSound`（extends `TickableSoundInstanceMapper`）
- 方块声音源，位置为方块中心，音调 1，无距离衰减（`Attenuation.NONE`，音量由自身计算），`looping` 按是否无缝循环；允许静音启动（`canStartSilent`）。
- `tick()`：音量 = `SpeakerSettings.volumeAt(位置, 半径, 掩码, 玩家位置)`（听不到为 0，否则线性衰减），离开范围只是静音，不打断播放。
- `finish()`：`stop()`。
