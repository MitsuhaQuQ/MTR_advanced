# AnnouncementSound.java（本分支新增）

广播器/音箱播放内容的统一文本表示及其解析（服务端与客户端共用同一套规则）。一段文本可以是：
- 玩家上传到服务器的音频文件：`上传者命名空间:文件名`，例如 `steve:下一站.ogg`；若玩家名与某个模组命名空间相同则为 `mtr_player:下一站.ogg`（见 `getNamespace`）；
- 游戏或资源包里的声音：`mtr:ticket_barrier`；
- 网址 `http(s)://...`：客户端只在玩家允许该站点时播放。

上传文件和游戏声音写法一样，客户端先找上传文件。上传文件在游戏中还会注册为声音 `mtr_audio:<命名空间>/<sound id>`，以便 `/playsound` 和资源名形式播放。主要调用者：`AudioServer`、`SpeakerSettings`、`AnnouncementClips`、`client/AnnouncementPlayer`、`client/AudioCache`、`client/UploadedSounds`、`block/BlockTrainAnnouncer`、`image/ImageServer`。测试：`common/src/test/java/mtr/audio/AnnouncementSoundTest.java`。

## 类 `AnnouncementSound`（final，私有构造）

### 常量
- `MAX_LENGTH = 1024`：声音文本最大长度。
- `SOUND_NAMESPACE = "mtr_audio"`：上传文件在游戏声音注册表中的命名空间。
- `MAX_SOUND_ID_LENGTH = 64`：sound id 最大长度。
- `SOUND_ID`：sound id 正则 `[a-z0-9_.-]+(/[a-z0-9_.-]+)*`。
- `HASH_SOUND_ID_LENGTH = 12`：用哈希做 sound id 时取前 12 位。
- `PLAYER_SUFFIX = "_player"`。

### `static String clean(String soundString)`
- 去首尾空白；null 视为空；超过 1024 字符返回空串。调用者：`common/src/main/java/mtr/audio/SpeakerSettings.java:266`、`AnnouncementClips.java:32`。

### `static String getUploadedString(String namespace, String fileName)`
- `namespace:fileName`。

### `static String getNameKey(String namespace, String fileName)`
- 名字比较不区分大小写，键为小写的 `namespace:fileName`。调用者：`common/src/main/java/mtr/audio/AudioServer.java:310`、`:456`。

### `static String getNamespace(String playerName, Set<String> modNamespaces)`
- 玩家名小写；若与模组命名空间重名则加 `_player`，避免遮住模组声音。该命名空间随文件保存，玩家改名或模组变动后不变。调用者：`AudioServer.java:353`、`image/ImageServer.java:47`。

### `static String[] splitUploaded(String soundString)`
- 可能是上传文件时返回 `{小写命名空间, 文件名}`，否则（没有冒号、冒号在首尾、是网址）返回 null。

### `static boolean isValidSoundId(String soundId)`
- 非空、≤ 64、符合 `SOUND_ID`。

### `static String getSoundIdFromFileName(String fileName)`
- 去掉 `.ogg`、转小写、空格换 `_`；合法则返回，否则（例如中文名）返回空串。

### `static String getDefaultSoundId(String fileName, String hash, Predicate<String> taken)`
- 作用：未指定 sound id 时生成：优先用文件名派生，否则用哈希前 12 位；若同一上传者已有同名，则依次尝试 `基础_哈希前6位`、`_前12位`…（必要时截短基础部分保持 ≤ 64），都被占用则用哈希前 64 位。
- 调用者：`AudioServer.java:92`、`:473`。

### `static ResourceLocation getSoundLocation(String namespace, String soundId)`
- 返回 `mtr_audio:<namespace>/<soundId>`；任一不合法返回 null。调用者：`AudioServer.java:575`、`client/AudioCache.java:470`。

### `static boolean isNetwork(String soundString)`
- 以 `http://` 或 `https://` 开头（不区分大小写）。

### `static ResourceLocation getSoundId(String soundString)`
- 作为游戏声音解析为 `ResourceLocation`；空、网址或非法返回 null。调用者：`common/src/main/java/mtr/block/BlockTrainAnnouncer.java:79`、`client/AnnouncementPlayer.java:160`。
