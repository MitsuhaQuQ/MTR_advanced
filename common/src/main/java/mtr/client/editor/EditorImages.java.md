# EditorImages.java

浏览器编辑器"媒体库"中的图片类别，在游戏线程上执行。`EditorServer` 的 `ImagesServlet`（`EditorServer.java:770-779`）通过 `onGameThread` 调用这里，把 `ImportedImages`（已导入图片列表与上传/删除逻辑）和 `ImageFiles`（大小、尺寸限制）包装成 JSON 返回给网页。

## final class EditorImages

### `private EditorImages()`
工具类，禁止实例化。

### `public static JsonObject build()`
- 作用：生成媒体库图片列表 JSON。
- 返回：字段 `canUpload`、`busy`、`message`（已翻译文本，空则 `""`）、`maxBytes`、`maxDimension`、`maxTotalBytes`、`totalBytes`（按 hash 去重后的总字节）、`entries`（数组，每项 `hash, name, width, height, size, uploaderName, uploadedAt, canDelete`；`canDelete` 表示 name 在 `ImportedImages.DELETABLE` 中）。
- 副作用：先调用 `ImportedImages.ensureConnection()`（具体效果见该类，此处未确定）。
- 主要调用者：`EditorServer.java:772`。

### `public static JsonObject upload(String name, String data)`
- 作用：上传一张图片。
- 参数：`name` 文件名；`data` Base64 编码的图片字节。
- 返回：成功 `{ok:true}`；失败 `{ok:false, message}`，message 为 `gui.mtr.image_*` 异常键的翻译，其它异常统一为 `gui.mtr.image_invalid`。
- 副作用：调用 `ImportedImages.upload(name, bytes)`；Base64 字符串长度超过 `MAX_BYTES*4/3+8` 时直接判失败。
- 主要调用者：`EditorServer.java:778`。

### `public static JsonObject delete(String reference)`
- 作用：删除一张图片。
- 参数：`reference` 图片引用名。
- 返回：若不是合法引用（`ImageFiles.isReference`）或不在可删集合中，返回 `{ok:false, message: gui.mtr.image_no_permission}`；否则调用 `ImportedImages.delete` 并返回 `{ok:true}`。
- 主要调用者：`EditorServer.java:778`。

## 0.74.0 媒体文件夹

- 列表每条加 `uploader`、`folder`，另有 `folders`、`self`、`canManage`。
- `upload(name, data, folder)`；`folder(request)` 同音频，结果由游戏以消息回（列表的 `message`）。
