# AudioCache.java（本分支新增）

客户端：本客户端拥有的广播音频，存在游戏目录 `mtr_audio_cache` 文件夹下的一个数据库中（`AudioDatabase`，每个文件按哈希存储，所有世界和服务器共用——同一哈希就是同一文件）。服务器发来列表后，在后台请求还没有的文件，只有哈希匹配才保存。同时负责把网页编辑器页面添加、删除、改 sound id 的文件发给服务器（`mtr.audio.AudioServer` 会再次检查）。在游戏线程调用（数据库只在"MTR Audio Cache"线程访问）。接收注册：`common/src/main/java/mtr/MTRClient.java:283`（列表）、`:285`（结果）、`:286`（数据）；页面侧：`common/src/main/java/mtr/client/editor/EditorAudio.java`；播放侧：`AnnouncementPlayer`、`SpeakerPlayback`、`UploadedSounds`。包格式见 `AudioServer.java.md`。

## 类 `AudioCache`（final，implements `IPacket`，私有构造）

### 公开状态（来自服务器列表）
- `canUpload`、`canManage`、`maxFileBytes`（取 `min(5 MB, 服务器值)`）、`maxSeconds`、`maxTotalBytes`、`totalBytes`、`ENTRIES`（`Entry` 列表）。
- `REQUESTS`：最近的上传/删除/改名请求（请求 id → `Request`），最多 20 个，新的在后，供页面显示结果。

### 常量与私有状态
- `CACHE_DIRECTORY = "mtr_audio_cache"`、`MAX_WANTED_PER_PACKET = 256`、`MAX_REQUESTS = 20`、`REQUEST_TIMEOUT_MILLIS = 30000`（无回应判超时，再按文件大小每 64 KB/s 加时 `TIMEOUT_BYTES_PER_SECOND`）。
- `EXECUTOR`（单线程守护）、`DOWNLOADS`（哈希 → 下载中）、`CACHED`（已存哈希，同步集合）、`opened`、`database`（仅在执行器线程使用）、`nextRequestId`。

### `static byte[] read(String hash)`
- 有则在数据库线程同步读出（读不到则从已缓存集合移除）；没有则请求下载并返回 null。调用者：`common/src/main/java/mtr/client/AnnouncementPlayer.java:151`、`common/src/main/java/mtr/client/editor/EditorServer.java:856`。

### `static byte[] readStored(String hash)`
- 供非游戏线程读取已存文件，不发请求；没有返回 null。调用者：`common/src/main/java/mtr/client/UploadedSounds.java:103`。

### `static void want(String hash)`
- 没有就请求（例如播放时才发现缺）。

### `static String getHash(String namespace, String name)`
- 按上传者命名空间（小写）与文件名（不区分大小写）查服务器文件的哈希，没有返回 null。

### `static boolean isCached(String hash)` / `static boolean isDownloading(String hash)`
- 首次调用会打开数据库（`open`）。

### `static int upload(String name, String soundId, byte[] bytes) throws AudioFiles.InvalidAudioException`
- 作用：先在本地检查（无权限、超大小、不是合法 Ogg Vorbis、超时长、sound id 不合法或与自己其他文件重复），不通过则抛异常（消息为编码后的翻译文本）；文件名规范化并补 `.ogg`（同名会被服务器视为更新）；登记请求（超时随大小延长）；按 30 KB 分块发 `PACKET_AUDIO_UPLOAD`（`int 请求 id, int 总长, int 偏移, String 文件名, byte[] 分块, String soundId`，空 sound id 由服务器生成）；保留字节副本，服务器接受后直接存入本地缓存（不用再下载）。
- 返回：请求 id。调用者：`EditorAudio.java:82`。

### `static int delete(Entry entry)`
- 发删除（只删名字，音频仍可被其他名字使用）。返回请求 id。

### `static int setSoundId(Entry entry, String soundId) throws AudioFiles.InvalidAudioException`
- 校验后发改 sound id 请求。

### `static void receiveListS2C(Minecraft, FriendlyByteBuf)`
- 作用：读列表（条目后附 sound id，旧服务器没有则为空），过滤非法条目；主线程中更新权限与容量；找出"名字相同但录音变了且本地有旧录音"的条目立即下载（使广播器和 `/playsound` 第一次就播放更新后的录音）；替换列表；丢弃服务器已没有的下载；若配置"全部下载"（`Config.audioDownloadAll()`），请求所有不超过 `Config.audioPrefetchMaxKilobytes()` 的缺失文件（更大的首次播放时再取）；最后 `UploadedSounds.refresh()`。

### `static void receiveResultS2C(Minecraft, FriendlyByteBuf)`
- 记录请求结果；上传成功时把保留的字节存入缓存。

### `static void receiveDataS2C(Minecraft, FriendlyByteBuf)`
- 只接收请求过的、按顺序、不超过服务器所说大小的分块；收齐后哈希一致才保存，否则丢弃并打印。

### `private static void request(List<String> hashes)`
- 去掉已在下载或服务器列表中没有的，按每包 256 个发 `PACKET_AUDIO_WANTED`。

### `static void expireRequests()`
- 超时未回应的请求标为失败（`gui.mtr.audio_no_response`），页面不会一直显示"发送中"。调用者：`EditorAudio.java:52`。

### `private static int addRequest(String name)`
- 新建请求，超过 20 个时删最旧的。

### `private static void store(String hash, byte[] bytes)`
- 未缓存时在数据库线程写入并标记已缓存。

### `private static void open()`
- 只打开一次：在数据库线程同步打开并读出已有哈希（这样已有文件不会再请求）；打不开则删除重建；没有 SQLite 时什么都不保存。

## 内部类 `Entry`（final）
- `hash`、`namespace`（小写）、`name`、`size`、`durationMillis`、`uploaderName`、`uploadedAt`、`mine`（是否本人上传）、`soundId`（旧服务器为空）。
- `getSoundLocation()`：`mtr_audio:<namespace>/<soundId>` 或 null；`getReference()`：`namespace:name`；`getKey()`：小写名字键。

## 内部类 `Request`（final）
- `id`、`name`、`startedMillis`、`timeoutMillis`、`done`、`ok`、`message`、`bytes`（上传成功前保留的文件）。

## 私有内部类 `Download`
- `bytes`、`received`。

## 0.74.0 媒体文件夹

- `Entry.uploader`、`Entry.folder`（旧服务器没有时为空）；`FOLDERS`。`receiveListS2C` 读列表尾部的上传者、文件夹和文件夹列表。
- `upload(name, soundId, bytes, folder)`：文件夹规整后写在上传包末尾（null 不写）。
- `folderAction(action, owner, path, argument, keys)`：发 `PACKET_AUDIO_FOLDER`，返回请求 id。

## 资源 ID 与替换（0.79.0）
- `Entry` 加 `resourceId`、`oldNames`，从列表包末尾读（旧服务端没有就为空）。
- `find(namespace, name)`，`getHash` 经它按名字或曾用名找，所以报站器按旧名也播放新文件。
- `upload(..., replaceId)`：带 ID 时按替换发送（文件夹位写空串，再写 ID）。
- 列表更新时按 ID 也认出「换了内容」的文件，立即下载新内容。
