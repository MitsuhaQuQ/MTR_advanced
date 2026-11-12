# AudioServer.java（本分支新增）

服务端：本服务器（所有维度共用）保存的广播音频，存在存档 `mtr` 目录下的一个数据库里（`AudioDatabase`，`<世界>/mtr/announce.db`）。玩家给文件起的每个名字（`AnnouncementSound`）指向按哈希只存一份的音频，所以两个玩家可以用各自的名字上传同一段录音。权限足够的玩家分块上传，收齐并校验（`AudioFiles`）后才保存。客户端请求自己没有的文件，服务器每 tick 发几块，避免大列表堵塞连接。全部在服务器线程执行；本类不播放音频，也不与列车关联。

生命周期与包注册在 `mtr.MTR`：`open`（`common/src/main/java/mtr/MTR.java:593`）、`close`（`:598`）、`tick`（`:536`）、玩家加入 `sendList`（`:556`）、离开 `forget`（`:571`）；C2S `PACKET_AUDIO_UPLOAD → receiveUploadC2S`（`:522`）、`PACKET_AUDIO_DELETE → receiveDeleteC2S`（`:524`），以及 sound id、wanted 包（在 `MTR` 中同处注册）。音箱方块用 `getUploadedHash`/`getUploadedDurationTicks`（`common/src/main/java/mtr/block/BlockAudioSpeaker.java:176`、`:188`）。

## 包格式

### S2C `PACKET_AUDIO_LIST`（`sendList`）
`boolean 能上传`、`boolean 能管理`、`int 单文件上限字节`、`int 最长秒数`、`long 总容量上限`、`long 已用`、`int 条数`；每条 `String 哈希, String 命名空间, String 文件名, int 字节数, long 时长毫秒, String 上传者名, long 上传时间, boolean 是否本人上传`；**所有条目之后**再按同顺序各写一个 `String soundId`（旧客户端可按旧格式读，忽略尾部）。

### S2C `PACKET_AUDIO_DATA`（`tick` 下发）
`String 哈希`、`int 总长`、`int 偏移`、`byte[] 分块(≤ 32 KB)`。

### S2C `PACKET_AUDIO_RESULT`（`sendResult`）
`int 请求 id`、`boolean 成功`、`String 消息（TranslatedMessage 编码）`。

### C2S 上传（`receiveUploadC2S`）
`int 请求 id`、`int 总大小`、`int 偏移`、`String 文件名(≤256)`、`byte[] 分块(≤ 32 KB)`、可选 `String soundId(≤256)`（旧客户端没有，由服务器决定）。

### C2S 删除（`receiveDeleteC2S`）
`int 请求 id`、`String 名字键(≤1024)`。

### C2S 设置 sound id（`receiveSoundIdC2S`）
`int 请求 id`、`String 名字键`、`String soundId`。

### C2S 请求文件（`receiveWantedC2S`）
`int 数量(≤256，经 PacketGuard.readCount)`，每个 `String 哈希(64)`。

## 类 `AudioServer`（final，implements `IPacket`，私有构造）

### 常量与静态字段
- `FOLDER = "mtr"`、`MAX_WANTED = 256`、`CHUNKS_PER_TICK = 2`、`HOUR_MILLIS`、`MAX_KEY_LENGTH = 1024`。
- `ENTRIES`（`LinkedHashMap<名字键, Entry>`）、`UPLOADS`（玩家 → 进行中上传）、`UPLOAD_TIMES`（玩家 → 一小时内上传开始时刻）、`SENDING`（玩家 → 待发队列）、`MOD_NAMESPACES`（模组命名空间，用于玩家命名空间避让）、`database`。

### `static void open(MinecraftServer minecraftServer)`
- 作用：`close()`；收集命名空间（`minecraft`、`mtr`、服务器资源中的所有命名空间）；打开数据库并读索引（哈希不合法或命名空间为空的条目跳过并打印）；对没有 sound id 的旧条目按 `getDefaultSoundId` 补算并写回数据库。失败打印并 `close()`（之后无法上传或发送，列表为空）。

### `static void close()`
- 清空所有状态并关闭数据库。

### `static void forget(ServerPlayer player)`
- 清除该玩家的进行中上传和待发队列（上传计数保留）。

### `static void tick(MinecraftServer minecraftServer)`
- 作用：每个有队列的玩家（每次按 UUID 重新查，因为重生会换对象）每 tick 最多发 2 块；首次发送时才从数据库读出字节，读不到就丢弃该项；玩家不在线则丢弃队列。

### `static void sendList(ServerPlayer player)`
- 发该玩家视角的列表（含其权限）。

### `static void receiveUploadC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 作用：服务器线程中处理：无数据库时首块回失败；偏移 0 为新上传：sound id 非空且不合法回 `gui.mtr.audio_bad_sound_id`，否则 `checkUploadStart`，出错回结果；之后的分块必须属于同一请求且偏移连续，否则静默丢弃（被拒或被替换的上传的残块）；分块为空或超长回 `gui.mtr.audio_size_mismatch`；收齐后 `finishUpload`。

### `static void receiveDeleteC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 作用：调 `delete`，回结果（成功消息 `gui.mtr.audio_deleted`）。

### `static void receiveSoundIdC2S(MinecraftServer, ServerPlayer, FriendlyByteBuf)`
- 作用：改某文件的 sound id（`/playsound` 和广播器可用它称呼）：文件须存在（`audio_missing`）、操作者是上传者或管理者（`audio_no_delete_permission`）、sound id 合法（`audio_bad_sound_id`）且在该玩家文件中不重复（`audio_same_sound_id`）；写库成功后打印日志并向所有人重发列表，回 `audio_sound_id_set`；写库失败回 `audio_save_failed`。

### `private static boolean isSoundIdTaken(String namespace, String soundId, String exceptKey)`
- 同命名空间中除 `exceptKey` 外是否已有该 sound id。

### `static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher)`
- 注册 `/mtr deleteaudio <命名空间:文件名>`：补全列出可删的名字（管理者全部，否则自己的）；规则与网页编辑器删除相同，控制台/命令方块按其权限等级判断管理权限；结果发给玩家或打印到控制台。返回 1 成功、0 失败。

### `private static String delete(MinecraftServer, String deleterName, boolean canManage, String deleterId, String nameKey)`
- 作用：任何人都能删自己上传的（即使已失去上传权限），只有管理者能删别人的（`MediaPermissions.canDelete`）；删库、移除条目；若该哈希已无名字引用，从所有待发队列移除；打印日志并广播列表。
- 返回：失败原因（编码消息）或 null。

### `private static String getPlayerNamespace(ServerPlayer)` / `private static ServerPlayer getPlayer(CommandSourceStack)`
- 玩家命名空间；命令源若是玩家则返回之。

### `private static boolean canManage(CommandSourceStack source, ServerPlayer player)`
- 非玩家命令源按 `SecurityConfig.audioManagePermissionLevel` 判断。

### `static boolean canUpload(ServerPlayer player)`
- 单文件上限 > 0 且（权限 ≥ `audioUploadPermissionLevel` 或世界主人）。也被 `ImageServer.tick` 用来算权限位。

### `static boolean canManage(ServerPlayer player)`
- 权限 ≥ `audioManagePermissionLevel` 或世界主人。

### `private static boolean isOwner(ServerPlayer player)`
- 在自己电脑上玩该世界的主人（非专用服务器），即使没开作弊也总能上传。

### `private static String checkUploadStart(ServerPlayer player, int size)`
- 无权限（`audio_no_permission`）、大小不在 1–单文件上限（`audio_file_too_big_kb`）、总量会超（`audio_server_full`）、一小时内次数达到 `SecurityConfig.audioUploadsPerHour` 且非主人（`audio_too_frequent`）时返回原因；否则记录本次尝试（失败的上传也计数）并返回 null。

### `private static void finishUpload(MinecraftServer, ServerPlayer, Upload)`
- 包一层，运行时异常回 `audio_save_failed`，保证网页不会一直等。

### `private static void finishUploadChecked(MinecraftServer, ServerPlayer, Upload)`
- 作用：`AudioFiles.check` 失败回其消息；时长超过 `SecurityConfig.audioMaxSeconds` 回 `audio_too_long_seconds`；文件名为空时用哈希前 8 位，补 `.ogg`；按玩家命名空间求名字键；同名同内容回"已上传过"；同名但属于他人回 `audio_same_name`；指定的 sound id 被占用回 `audio_same_sound_id`；sound id 取指定值，否则沿用旧条目的（同名即更新，旧 sound id 保留，引用它的广播器播放新录音），否则自动生成；`database.replace`（失败回 `audio_save_failed`）；旧音频若已无引用则从待发队列移除；打印日志，回 `audio_uploaded`/`audio_updated`，广播列表。

### `private static void sendListToAll(MinecraftServer)` / `private static void sendResult(ServerPlayer, int requestId, boolean ok, String message)`

### `private static byte[] readFile(String hash)`
- 从数据库读音频，出错打印并返回 null。

### `private static int getMaxFileBytes()` / `private static long getMaxTotalBytes()` / `private static long getTotalBytes()`
- 单文件上限 `min(5 MB, SecurityConfig.audioMaxFileKilobytes × 1024)`；总上限 `audioMaxTotalMegabytes` MB；已用为按哈希去重后的大小和。

### `static String getUploadedHash(String reference)`
- 按 `命名空间:文件名` 查哈希；不存在或 sound id 无效返回 null。

### `static long getUploadedDurationTicks(String reference)`
- 时长换算为 tick（向上取整），不存在为 0。

### `private static boolean hasHash(String hash)`
- 是否仍有名字引用该哈希。

## 私有内部类 `Entry`
- 字段：`hash`、`namespace`、`name`、`size`、`durationMillis`、`uploader`（UUID 字符串）、`uploaderName`、`uploadedAt`、`soundId`（可变，同一上传者内唯一）。
- `getSoundLocation()`：`mtr_audio:<namespace>/<soundId>`；`getKey()`：名字键；`getReference()`：`namespace:name`。

## 私有内部类 `Upload`
- `requestId`、`name`、`soundId`、`bytes`（预分配）、`received`。

## 私有内部类 `Sending`
- `hash`、`bytes`（首次发送时读入）、`offset`。
