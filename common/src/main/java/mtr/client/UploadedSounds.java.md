# UploadedSounds.java（本分支新增）

客户端：把服务器的广播音频注册成游戏声音，命名为 `mtr_audio:<上传者命名空间>/<sound id>`，这样广播器通过游戏的声音系统播放（跟随游戏音量与输出设备），`/playsound` 也能播放（其补全来自客户端已知声音）。每当服务器列表或游戏声音重新加载时把这些声音加入游戏的声音表；游戏请求音频时与其他声音一样，从本客户端的文件（`AudioCache`）解码，并保留到下次重载。尚未下载的文件会被请求，下次再播。除解码外都在游戏线程调用。挂钩：`SoundManagerMixin.afterApply → refresh`（`fabric/src/main/java/mtr/mixin/SoundManagerMixin.java:35`），`SoundBufferLibraryMixin → isUploadedPath/getBuffer`（`fabric/src/main/java/mtr/mixin/SoundBufferLibraryMixin.java:30`、`:31`）；`AudioCache` 列表更新后也调 `refresh`（`common/src/main/java/mtr/client/AudioCache.java:287`）。

## 类 `UploadedSounds`（final，私有构造）

### 静态字段与常量
- `BY_PATH`：游戏请求音频时使用的路径（`Sound.getPath()`，内容为 `mtr_audio:<哈希>` 对应的路径）→ 条目。
- `BY_LOCATION`：声音事件 id → 条目。
- `ATTENUATION_DISTANCE = 16`：声音衰减距离（格）。

### `static void refresh()`
- 作用：清空两个表；没有 mixin（声音管理器未实现 `SoundManagerAccess`）时不改游戏列表（广播器自行播放文件）；否则移除注册表中所有 `mtr_audio` 命名空间的声音，再为 `AudioCache.ENTRIES` 中每个有有效声音位置的条目注册一个声音（音频按录音哈希命名，所以同名替换后播放的是新录音而不是游戏缓存的旧音频）。

### `static ResourceLocation getLocation(String namespace, String fileName)`
- 按上传者命名空间与文件名（不区分大小写）找声音 id，没有返回 null。调用者：`common/src/main/java/mtr/client/AnnouncementPlayer.java:146`、`common/src/main/java/mtr/client/SpeakerPlayback.java:209`。

### `static boolean isUploadedPath(ResourceLocation path)`
- 命名空间是 `mtr_audio`。

### `static CompletableFuture<SoundBuffer> getBuffer(Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache, ResourceLocation path)`
- 作用：替代游戏从资源包读声音文件：已缓存直接返回；未知路径或文件尚未缓存（同时 `AudioCache.want` 请求下载）返回失败的 Future；否则在后台线程读文件并 `AnnouncementPlayer.toSoundBuffer` 解码，放入游戏缓存；失败时打印并从缓存移除（下次重试）。
- 参数：`cache` 为游戏的音频缓冲缓存（保留到下次重载）。

### `private static CompletableFuture<SoundBuffer> failed(String message)`
- 以 `IllegalStateException` 异常完成的 Future。
