# AnimatedGif.java（本分支新增）

导入图片功能中的 GIF 动图解码：把 GIF 的每一帧按"逻辑屏幕"坐标合成为完整画面（处理帧偏移、透明、`restoreToPrevious`/`restoreToBackgroundColor` 处置方式），并记录每帧结束时刻和循环次数。服务端上传时用 `check` 只校验不合成（`ImageFiles.check` → `common/src/main/java/mtr/image/ImageFiles.java:23`），客户端用 `decode` 解码后做动态纹理（`common/src/main/java/mtr/client/ImportedImages.java:116`）。测试：`common/src/test/java/mtr/image/AnimatedGifTest.java:24`。

## 类 `AnimatedGif`（final）

### 常量
- `MAX_FRAMES = 256`：最多帧数。
- `MAX_PIXELS = 16 * 1024 * 1024`：宽 × 高 × 帧数的上限（客户端也用它限制纹理，`ImportedImages.java:136`）。

### 字段
- `width`、`height`（public final）：逻辑屏幕尺寸（像素）。
- `frames`：每帧合成后的 ARGB 像素（行优先，长度 `width*height`）；`check` 模式下为 null。
- `frameEnds`：每帧结束的累计毫秒数。
- `repeats`：循环次数：−1 无限，0 不重复（只播一遍），正数为额外重复次数（取自 NETSCAPE2.0/ANIMEXTS1.0 扩展）。

### 私有构造函数 `AnimatedGif(int width, int height, int[][] frames, long[] frameEnds, int repeats)`

### `int pixelCount()`
- `width × height × 帧数`。

### `int[] pixels(int frame)`
- 返回该帧像素数组（不复制）。

### `int frameAt(long elapsedMillis)`
- 作用：按已播放毫秒数求当前帧：超过循环次数后停在最后一帧；否则对总时长取模后找第一个 `结束时刻 > 位置` 的帧。

### `static AnimatedGif decode(byte[] bytes) throws IOException`
- 完整解码合成。调用者：`common/src/main/java/mtr/client/ImportedImages.java:116`。

### `static int[] check(byte[] bytes) throws IOException`（包级）
- 只校验（读每帧尺寸、不合成），返回 `{width, height}`。

### `private static AnimatedGif read(byte[] bytes, boolean compose) throws IOException`
- 作用：前置检查：长度 ≥ 14、≤ `ImageFiles.MAX_BYTES`、GIF 头、以 0x3B 结尾，否则抛 `IllegalArgumentException("gui.mtr.image_invalid")`；读逻辑屏幕尺寸并 `ImageFiles.checkDimensions`；帧数 < 1 抛 invalid；帧数或总像素超限抛 `"gui.mtr.image_animation_limit"`；每帧位置/尺寸越界抛 invalid。合成时：第一帧前用背景色（有透明标志时为透明）填满画布并读循环次数；`restoreToPrevious` 先保存该帧区域；绘制后拷贝整幅画布为该帧；延时 < 2（1/100 秒）按 100 毫秒，否则 `延时 × 10` 毫秒；最后按处置方式恢复区域。
- 异常：消息为翻译键的 `IllegalArgumentException`；IO 错误为 `IOException`。

### `private static int repeats(Node metadata)`
- 从应用扩展读循环次数，`loops == 0` 返回 −1（无限）；没有扩展返回 0。

### `private static int backgroundColor(Node stream)`
- 全局调色板中背景色索引对应的不透明 ARGB；没有调色板返回 0（透明）。

### `private static void fill(Graphics2D graphics, int color, int x, int y, int width, int height)`
- 以 `Src` 合成模式填充矩形（直接覆盖，含 alpha）。

### `private static Node child(Node parent, String name)` / `private static String attribute(Node node, String name)` / `private static int number(Node node, String name)`
- 读 ImageIO 元数据树的子节点、属性（缺失为空串）、整数属性（缺失为 0）。
