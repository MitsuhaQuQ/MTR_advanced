# AnnouncementPlayer.java（本分支新增）

客户端：播放广播器（`AnnouncementSound`）所指的声音。游戏声音照旧经游戏播放；服务器上添加的文件与网址则直接解码后输出到电脑音频设备（音量 = 游戏主音量 × 方块音量），因为游戏只能播放资源包注册过的声音（若 `UploadedSounds` 已把该文件注册为游戏声音，则仍经游戏播放）。同时只播一个广播，新的打断旧的。0.72.0 起一次广播是多个片段依次播放、片段间有间隔（`transitsim.sim.ClipSequence`）。本地还没有的文件会请求下载，这次跳过。网址只在玩家于 `config/mtr.json` 开启且列出该站点时才获取（否则编辑广播器的人可让附近所有玩家访问任意网站）；只播放 WAV 与 Ogg Vorbis，解码前先检查文件。调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:462`（服务器广播包 → `play`）、`common/src/main/java/mtr/screen/TrainAnnouncerScreen.java:67`（界面试听）、`common/src/main/java/mtr/MTRClient.java:294`（每 tick → `tick`）、`common/src/main/java/mtr/client/UploadedSounds.java:108`（`toSoundBuffer`）。

## 类 `AnnouncementPlayer`（final，私有构造）

### 常量与静态字段
- `TIMEOUT_MILLIS = 10000`（网址连接/读取超时）、`MAX_NETWORK_BYTES = 4 MB`（下载上限）、`MAX_NETWORK_CACHE = 16`（按网址的内存缓存条数，LRU）、`MAX_DECODED_MILLIS = 10 分钟`（解码后最长时长）、`WRITE_FRAMES = 2048`（每次写入音频线的帧数）、`USER_AGENT = "Minecraft-Transit-Railway"`。
- `GENERATION`（AtomicInteger）：每次 `stop` 加一，后台任务据此判断自己是否已被打断。
- `START_TICKS = 2`：经游戏刚开始的声音，在问声音管理器之前先视为正在播放的 tick 数。
- `current`：正在播放的 `Sequence`（游戏线程）。
- `NETWORK_CACHE`：网址 → 字节（同步的访问顺序 LinkedHashMap）。
- `LOADER`（"MTR Announcement Loader"，下载与解码）、`PLAYER`（"MTR Announcement Player"，输出到音频线），均单线程守护。

### `static void play(String soundString, BlockPos pos)`
- 单个声音（`AnnouncementClips.single`）转调下一个重载。游戏线程调用；服务器添加的文件按上传者与文件名在服务器列表中查找，查不到的文本才当作游戏声音。`pos`：游戏声音的播放位置。

### `static void play(List<AnnouncementClips.Clip> clips, BlockPos pos)`
- 作用：`stop()` 打断上一个；世界为空或无片段则返回；新建 `Sequence` 并立即 `tick` 开始第一段（返回 false 则清空）。其余片段在客户端 tick 中开始。

### `static void stop()`
- `GENERATION` 加一并停止当前游戏声音、清空 `current`。

### `static void tick(Minecraft minecraft)`
- 每客户端 tick：世界为空或序列结束时停止并清空。

### `private static SoundInstance start(String soundString, BlockPos pos, int generation, AtomicBoolean done)`
- 作用：开始一个片段。依次：文本清理（`AnnouncementSound.clean`）后为空 → 立即结束；是上传文件（`splitUploaded` + `AudioCache.getHash`）且游戏已认识（`UploadedSounds.getLocation`）→ 经游戏播放；是上传文件但游戏不认识 → `AudioCache.read` 读字节（没有则结束，并已触发下载），交给 `LOADER` 解码直接播放；是游戏声音 id → 经游戏播放；是网址且允许 → `LOADER` 下载并解码播放（失败打印并结束）；否则（不允许的网址打印提示）结束。
- 参数：`generation` 开始时的代数；`done` 直接播放结束（或不能播放）时置 true。
- 返回：经游戏开始的声音实例，否则 null。

### `private static SoundInstance playThroughGame(Minecraft, ResourceLocation id, BlockPos pos)`
- 与原先 `ClientLevel.playLocalSound` 相同的方式（方块声音源，音量 1000000，音调 1）新建 `SimpleSoundInstance` 并播放，保留实例以判断何时结束。

### `static boolean isAllowed(String url)`
- 返回：玩家开启了网络音频（`Config.networkAudioEnabled()`），网址是 http/https、有主机、不含用户信息，且主机等于或是 `Config.networkAudioAllowedHosts()` 中某项的子域名。解析失败返回 false。

### `private static byte[] download(String url) throws Exception`
- 先查 `NETWORK_CACHE`；否则 GET（不跟随重定向，防止跳到未允许的站点；超时 10 秒；设 User-Agent），非 200 或超过 4 MB 抛异常；成功放入缓存。

### `private static void decodeAndPlay(byte[] bytes, float volume, int generation, String name, AtomicBoolean done)`
- 已被打断则结束；按 WAV/Vorbis 解码（失败打印并结束，包括 `LinkageError`）；交给 `PLAYER` 播放，结束后置 `done`。

### `static SoundBuffer toSoundBuffer(byte[] bytes) throws Exception`
- 把文件解码成游戏声音系统用的 `SoundBuffer`（16 位有符号小端 PCM，LWJGL 直接缓冲）。调用者：`UploadedSounds.java:108`。

### `private static boolean isWav(byte[] bytes)`
- 头部为 `RIFF....WAVE`。

### `private static Pcm decodeVorbis(byte[] bytes) throws Exception`
- 先按服务器检查上传的方式检查（`AudioFiles.check`），时长超过 10 分钟抛异常；用 STBVorbis 在自己分配的缓冲中逐块解码（注释：用 `stb_vorbis_decode_memory` 返回的缓冲再用 C 的 free 释放曾破坏堆并使游戏在 Java Sound 播放时崩溃）；只支持单声道/立体声；输出超过 10 分钟的字节数抛异常；`finally` 关闭解码器并释放内存。

### `private static Pcm decodeWav(byte[] bytes) throws Exception`
- 用 Java Sound 读取并转换为 16 位有符号小端 PCM；只支持 1–2 声道；限制 10 分钟。

### `private static void playPcm(Pcm pcm, float volume, int generation)`
- 已被打断或音量 ≤ 0 时不播；打开 `SourceDataLine`，每次写 2048 帧，每个样本乘以 `min(1, volume)`；中途代数变化则停止写入并 `flush`，否则 `drain` 播完。异常打印。

### `private static Thread createThread(Runnable, String name)`
- 守护线程。

## 私有内部类 `Sequence`
- 一次广播的多个片段：字段 `clips`、`pos`（不可变副本）、`generation`、`sequence`（`new ClipSequence(AnnouncementClips.gapTicks(clips), false, 0)`，不循环）、`sound`（经游戏播放的当前声音）、`done`（直接播放的当前片段结束标志）、`age`（当前片段已开始 tick 数）。
- `tick()`：已被打断返回 false；判断当前片段是否仍在播放（经游戏：开始后 2 tick 内或声音管理器仍在播；直接播放：`done` 未置位）；`sequence.tick(playing)` 返回 ≥ 0 时开始该序号的片段；返回序列是否未结束。
- `stopSound()`：停止经游戏播放的当前声音。

## 私有内部类 `Pcm`
- `data`（16 位小端 PCM 字节）、`sampleRate`（Hz）、`channels`。
