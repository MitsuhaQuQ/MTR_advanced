# ImportedImages.java（本分支新增）

客户端：导入图片的本服务器目录（由服务器列表包维护）加上按内容寻址的磁盘缓存（`<游戏目录>/mtr_image_cache/pictures.db`，`ImageDatabase`，所有服务器共用），以及最多 16 张的纹理 LRU 缓存（GIF 动画逐帧更新）。服务端对应 `mtr.image.ImageServer`；S2C 接收在 `common/src/main/java/mtr/MTRClient.java:284` 注册；使用者：画框渲染 `ImportedPaintingRenderer`（`texture`）、`ImportedPaintingScreen`、网页编辑器 `EditorImages`/`EditorServer`。

## 类 `ImportedImages`（final，implements `IPacket`，私有构造）

### 公开状态
- `ENTRIES`：服务器图片列表（`ImageDatabase.Entry`）。
- `DELETABLE`：本玩家可删除的图片名。
- `canUpload`、`maxTotalBytes`（服务器总容量上限，默认 256 MB，限制在 0–4 GB）、`message`（最近一条结果的翻译键）、`ok`（最近结果是否成功）。

### 私有状态
- `RECEIVING`/`OFFSETS`（下载中的字节与已收偏移）、`WANTED`（哈希 → 请求时间）、`TEXTURES`（访问顺序 LinkedHashMap，LRU）、`connection`（当前连接对象，变化即清空）、`session`、`uploading`（上传开始毫秒，0 为无）、`cache`（磁盘缓存库，懒加载）。

### `static void ensureConnection()`
- 连接对象变了（换服务器/重连）就 `clear()`。

### `private static void clear()`
- 释放所有纹理并清空全部状态。

### `private static ImageDatabase cache() throws Exception`
- 懒打开磁盘缓存。

### `static void receive(FriendlyByteBuf packet)`
- 作用：按首字节：`0` 列表（会话 id、能否上传、条目（逐条校验哈希/引用名/尺寸/大小，非法跳过；可删的记入集合）、可选总容量）——主线程中会话变了先清空，再替换列表与权限；`1` 下载分块 → 主线程 `data(...)`；`2` 结果（成功、是否传输、翻译键）→ 传输结果清除上传状态，记录并在聊天栏显示。包格式见 `ImageServer`。

### `private static void data(String hash, int size, int offset, byte[] bytes)`
- 只接收自己请求过且与列表大小一致的哈希；偏移 0 开始新缓冲；偏移不连续或超长则放弃该下载；收齐后校验哈希与格式，写入磁盘缓存；无论成败都清除下载状态。

### `static boolean busy()`
- 是否正在上传；超过 60 秒无结果视为超时（`gui.mtr.image_timeout`）。

### `static void upload(String name, byte[] bytes) throws Exception`
- 作用：无权限或忙时抛 `IllegalArgumentException`（翻译键）；`ImageFiles.check`；按 30 KB 分块发 `PACKET_IMAGE_ACTION` 动作 0。调用者：`common/src/main/java/mtr/client/editor/EditorImages.java:25`。

### `static byte[] read(String hash)`
- 作用：哈希须在列表中；先读磁盘缓存并校验（坏的删除）；没有则清理 30 秒前的旧请求，若同时请求不足 8 个就发动作 1 请求下载，返回 null（之后再来取）。调用者：`common/src/main/java/mtr/client/editor/EditorServer.java:786`。

### `static ResourceLocation texture(String reference)`
- 作用：按名字（不区分大小写）找到哈希（找不到就把参数当哈希）；已有纹理则（GIF 更新帧后）返回；否则 `read` 取字节（没有返回 null），解码：JPEG 用 Java 解码逐像素转 ABGR、GIF 用 `AnimatedGif.decode` 写第一帧、PNG 用 `NativeImage.read`；登记为动态纹理；超过 16 张或动画像素总数超过 `2 × AnimatedGif.MAX_PIXELS` 时淘汰最久未用的。
- 返回：纹理 id，或 null（未就绪/出错）。调用者：`common/src/main/java/mtr/render/ImportedPaintingRenderer.java:29`、`common/src/main/java/mtr/screen/ImportedPaintingScreen.java:187`。

### `static void apply(int entityId, PaintingSettings settings)`
- 发动作 2：设置某幅画的图片设置。调用者：`ImportedPaintingScreen.java:81`。

### `static void delete(String reference)`
- 发动作 3：删除图片。

## 私有内部类 `PictureTexture`
- 字段：`texture`（`DynamicTexture`）、`gif`（可为 null）、`id`（注册到纹理管理器，前缀 `mtr_image`）、`started`（纳秒）、`frame`。
- `update()`：GIF 时按已播放毫秒求帧（`frameAt`），帧变化则写入像素并上传；返回 id。
- `animatedPixels()`：GIF 像素总数，否则 0。
- `release()`：从纹理管理器释放。
- `static writeFrame(NativeImage, AnimatedGif, int frame)`：ARGB → ABGR 写入一帧。

## 0.74.0 媒体文件夹

- `FOLDERS`、`canManage`；列表读每行文件夹（按服务器发的行号对应，跳过的行不影响）、文件夹列表和"能否整理所有人的"。
- `upload(name, bytes, folder)`；`folderAction(action, owner, path, argument, references)`（动作 4）。
