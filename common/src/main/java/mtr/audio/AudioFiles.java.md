# AudioFiles.java（本分支新增）

广播音频文件的校验与命名工具（服务端与客户端用同一套规则）。文件以字节的 SHA-256 标识，同一段录音只存、只发一次。只接受 Ogg Vorbis：单一逻辑流、每页完整且 CRC 正确、开头有三个 Vorbis 头包；不解码音频本身。被 `AudioServer`（`common/src/main/java/mtr/audio/AudioServer.java:443` 等）、`client/AudioCache`、`client/AnnouncementPlayer`、`image/ImageFiles` 使用；测试 `common/src/test/java/mtr/audio/AudioFilesTest.java`。

## 类 `AudioFiles`（final，私有构造）

### 常量
- `MAX_FILE_BYTES = 5 MB`：任何服务器配置下单文件上限（保证文件能在网页编辑器一次请求内传完）。
- `CHUNK_BYTES = 32 KB`：服务端→客户端下发分块大小。
- `UPLOAD_CHUNK_BYTES = 30 KB`：客户端→服务端上传分块大小（客户端包最大 32767 字节，还要带请求、大小、文件名、sound id；与 ANTE-advanced 2f3921c8 同样的修复）。
- `HASH_LENGTH = 64`、`MAX_NAME_LENGTH = 64`（文件名码点数上限）。
- 私有：`MAX_CHANNELS = 2`、`MIN_SAMPLE_RATE = 8000`、`MAX_SAMPLE_RATE = 192000`、`PAGE_HEADER_BYTES = 27`、`FLAG_CONTINUED = 0x01`、`FLAG_FIRST = 0x02`、`CRC_TABLE`（Ogg CRC32 表，多项式 0x04C11DB7，静态初始化）。

### `static String hash(byte[] bytes)`
- SHA-256 小写十六进制（64 字符）。

### `static boolean isHash(String text)`
- 只有 64 位小写十六进制才算（哈希来自对方，且会用作磁盘文件名）。

### `static String cleanName(String name)`
- 作用：去首尾空白、去控制字符、`/` 和 `\` 换成 `_`；超过 64 个码点时截断主体、保留扩展名（≤ 8 字符）。空名保持为空，由调用者替换。

### `static String withExtension(String name)`
- 没有扩展名时加 `.ogg`。

### `static Info check(byte[] bytes) throws InvalidAudioException`
- 作用：逐页检查：空文件、超大、不是 `OggS`、版本不为 0、页不完整、CRC 不符、首页无"首页"标志、多于一个流、页序号不连续、首三个包跨页续接标志不一致、头包不是 Vorbis（Opus 有专门提示）、标识头长度/版本/帧标志不对、声道数不在 1–2、采样率不在 8000–192000，分别抛带翻译键（经 `TranslatedMessage.encode`）的异常，如 `gui.mtr.audio_empty`、`audio_too_big`、`audio_not_ogg`、`audio_trailing_data`、`audio_ogg_version`、`audio_incomplete`、`audio_bad_checksum`、`audio_one_stream`、`audio_bad_page_order`、`audio_corrupt`、`audio_opus`、`audio_vorbis_only`、`audio_vorbis_header_incomplete`、`audio_vorbis_header_invalid`、`audio_channels`、`audio_sample_rate`。
- 返回：声道数、采样率、时长毫秒（最后一个有效 granule 位置 ×1000 / 采样率）。

### `private static void checkHeader(byte[] header, int index) throws InvalidAudioException`
- 校验第 `index` 个 Vorbis 头包（类型 1、3、5，后接 `vorbis`），标识头额外检查长度 30、版本 0、帧标志、声道与采样率。

### `private static int crc(byte[] bytes, int start, int length)`
- Ogg 页 CRC（校验字段本身按 0 计）。

### `private static int readInt(byte[], int)` / `private static long readLong(byte[], int)`
- 小端读整数。

## 内部类 `Info`（final）
- `channels`、`sampleRate`（Hz）、`durationMillis`。

## 内部类 `InvalidAudioException`（extends `Exception`）
- 消息为编码后的翻译文本，可直接显示给玩家。
