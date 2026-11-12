# views/ImagesLibrary.tsx

媒体层的图片库：列出游戏中保存的图片（给 MTR 刷子右键原版画选用），搜索、按上传者筛选、预览、导入（PNG/JPEG/GIF，必要时在浏览器里压缩缩小）、删除。新页面组件，不走旧代码；直接请求编辑器服务器的 `api/images`、`api/image-file`（带 `X-MTR-Editor-Key` 头，密钥来自 `api/client.ts`）。由 `views/MediaLibrary.tsx` 在"图片"标签下显示。

## 类型与常量（内部）
- `Entry`：一张图片：`hash`、`name`（内部名称）、`width`、`height`、`size`（字节）、`canDelete`、`uploaderName`、`uploadedAt`（毫秒时间戳）。
- `Library`：`api/images` 的回答：`entries`、`canUpload`、`busy`（游戏正在处理上传）、`message`、`maxBytes`、`maxDimension`、`maxTotalBytes?`、`totalBytes?`。
- `headers`：带密钥的请求头。`endpoint(path)`：`/api/<path>` 的绝对 URL。
- `base64(blob)`：读成 data URL 后取逗号后的 Base64。
- `kindOf(file)`：按文件头判断 `"png"`、`"jpeg"` 或 `""`。

## 导出
### `prepareImage(file, maxDimension, maxBytes)`
- 游戏保存不超过 `maxBytes` 的 PNG、JPEG（0.71.0 起）和 GIF。规则：
  - 文件名须为 png/jpg/jpeg/gif 且不超过 16 MB；
  - GIF：不超过 `maxBytes`，校验 `GIF87a/89a` 头并读宽高，不超过 `maxDimension`，原样上传（保留动画）；
  - PNG/JPEG：宽高不超过 `maxDimension`；文件头确认格式且大小合适就原样上传；否则用画布以原格式重存（JPEG 质量 0.9），仍过大则按 `√(maxBytes/大小)×0.95`（至多 0.9）逐次缩小，最多 8 次。
  - 以前一律存 PNG，常比照片的 JPEG 大好几倍，3.6 MB 的照片会被拒为"超过 5 MB"（Q 世界 2026-11-12）。
- 返回 `{ name（基名 + 扩展名）, blob, scaled?（缩小后的 [宽, 高]） }`；不合规则抛出带说明的 `Error`。
- 调用者：本文件 `upload`（`views/ImagesLibrary.tsx:129`）。

### `ImagesLibrary()`（组件）
- 无 props。状态：`data`（`Library`）、`search`、`uploader`（筛选）、`selected`（选中图片名）、`preview`（data URL）、`message`、`sending`；ref：`busy`（防止并发上传）、`alive`（卸载后不再更新）。
- 轮询：每 2 秒 GET `api/images`；失败提示"连不上游戏"。
- 预览：选中图片变化时 GET `api/image-file?hash=…`，转成 data URL；失败每秒重试（服务器可能还在下载）。
- `post(body)`（内部）：POST `api/images`，回答 `ok` 为假时抛出其 `message`。
- `upload(files)`（内部）：可上传、游戏不忙且没有进行中的上传时，逐个文件 `prepareImage` → `post({name, data: base64})`，然后每 0.5 秒轮询直到 `busy` 为假（超过 65 秒报超时），显示游戏给的消息（缩小过时附说明）。一次只传一个文件，因为游戏每个玩家只保留一个上传。
- 界面：标题、搜索框、上传者下拉、隐藏的文件输入与"导入图片"按钮、说明、已用容量；列表（可拖放文件导入）；右侧详情（预览、尺寸与大小、上传者与时间、内部名称、可删除时两步确认"删除"→ `post({action:"delete", name})`）、状态消息。
- 调用者：`views/MediaLibrary.tsx:60`。
