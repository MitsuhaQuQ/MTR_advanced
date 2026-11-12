# ImageFiles.java（本分支新增）

导入图片的格式校验与工具（服务端与客户端共用）：有界地校验 PNG、JPEG（0.71.0 起）和 GIF，给出扩展名、内容类型、哈希与引用名规则。被 `ImageDatabase`、`ImageServer`、客户端 `ImportedImages`、网页编辑器 `EditorImages`/`EditorServer` 使用。测试：`common/src/test/java/mtr/image/ImagesTest.java`。

## 类 `ImageFiles`（final，私有构造）

### 常量
- `MAX_BYTES = 5 MB`：单张图片最大字节数。
- `MAX_DIMENSION = 2048`：宽高上限（像素）。
- `CHUNK = 30 KB`：上传/下载分块大小（包内字节数组上限）。

### `static int[] check(byte[] bytes) throws Exception`
- 作用：超过 `MAX_BYTES` 抛 invalid；GIF 交给 `AnimatedGif.check`；JPEG 交给 `checkJpeg`；否则必须是 PNG：8 字节签名、IHDR 块长 13、类型 `IHDR`，读宽高并 `checkDimensions`，再整体解码确认尺寸一致。
- 返回：`{width, height}`。
- 异常：`IllegalArgumentException`，消息为 `gui.mtr.image_invalid` 或 `gui.mtr.image_dimensions` 等翻译键。
- 调用者：`ImageDatabase.add`、`common/src/main/java/mtr/client/ImportedImages.java:78`。

### `private static int[] checkJpeg(byte[] bytes) throws Exception`
- 先从文件头读尺寸（过大的在解码前就拒绝），再完整解码确认可读；解码错误转为 invalid。

### `static BufferedImage decodeJpeg(byte[] bytes) throws Exception`
- 校验后用 Java 解码 JPEG，供客户端做纹理（不依赖游戏自带解码器，后续版本游戏只读 PNG）。调用者：`common/src/main/java/mtr/client/ImportedImages.java:120`。

### `static void checkDimensions(int width, int height)`（包级）
- 宽高须在 1–2048，否则抛 `gui.mtr.image_dimensions`。

### `static boolean isGif(byte[])` / `static boolean isJpeg(byte[])`
- 按文件头（`GIF87a`/`GIF89a`；`FF D8 FF`）判断。

### `static String extensionOf(byte[] bytes)`
- `.gif` / `.jpg` / `.png`。

### `static String contentTypeOf(byte[] bytes)`
- `image/gif` / `image/jpeg` / `image/png`。调用者：`common/src/main/java/mtr/client/editor/EditorServer.java:788`。

### `static String hash(byte[])` / `static boolean isHash(String)`
- 转调 `AudioFiles.hash`（SHA-256 十六进制）和 `AudioFiles.isHash`。

### `static boolean isReference(String reference)`
- 引用名格式：长度 ≤ 129，`命名空间:文件名.(png|gif|jpg)`，命名空间为 `[a-z0-9_.-]+`，文件名不含 `:`、`/`、`\` 和控制字符。
