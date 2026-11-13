# views/MediaLibrary.tsx

媒体层：为新页面重写的媒体库（以前运行旧的 `media.js`）。"音频"标签：服务器保存的报站音频，左边搜索和筛选，右边是选中文件（播放器、可复制的名字、改声音 ID、删除）；用上传按钮或拖到列表上添加文件；名字不能做声音 ID 的文件在页面内补填（旧页面用 `prompt()`，有的浏览器会拦截），删除也在页面内确认。"图片"标签是 `views/ImagesLibrary.tsx`。端点与以前相同：GET `api/audio`（列表，显示期间每 2 秒读一次）、POST `api/audio`（`{action: upload | delete | soundId}`）、GET `api/audio-file?hash=`（本机已有时取文件播放）。不走旧代码；密钥来自 `api/client.ts`。WAV/MP3 用 `core/audioConversion.ts` 转成 Ogg。由 `App.tsx` 在媒体层显示。

## 常量与工具（内部，除注明外）
- `POLL_MILLIS = 2000`、`MAX_MESSAGES = 8`（最近操作最多条数）、`MAX_SOUND_ID = 64`、`SOUND_ID`（小写字母数字 `_ . -`，可用 `/` 分段）。
- `isSoundId(id)`（导出）：是否合法声音 ID。`src` 中仅本文件使用。
- `soundIdFromName(name)`（导出）：按游戏的规则从文件名生成声音 ID：去 `.ogg`、小写、空格换 `_`；不合法（如中文名）返回空串。`src` 中仅本文件使用。
- `formatSize(bytes)`（导出）：≥1 MB 显示 "x.x MB"，否则 "N KB"（至少 1）。`src` 中仅本文件使用。
- `formatDuration(millis)`：一分钟以上 "m:ss"，否则 "x.x 秒"。`formatClock(seconds)`："m:ss"。`formatTime(millis)`："YYYY-MM-DD HH:MM"。
- `takes(file)`：是否收这个文件（Ogg、WAV、MP3）。
- 类型 `Entry`（音频条目：`key`、`name`、`reference`（引用名）、`sound?`（/playsound 名）、`soundId`、`hash`、`uploaderName`、`size`、`durationMillis`、`uploadedAt`、`cached`（本机已有）、`downloading`、`mine`、`canDelete`）、`Library`（`canUpload`、`maxFileBytes`、`maxTotalBytes`、`totalBytes`、`maxSeconds`、`requests`（服务器端的上传请求状态）、`entries`）、`Message`、`Waiting`（等待填声音 ID 的文件）。
- `url(path)`、`fetchJson(path)`、`sendJson(path, body)`：带密钥的请求，非 2xx 抛 `Error(状态码)`。
- `failed(e)`：413 → "文件太大"，其他 → "发送失败…"。
- `readBase64(file)`：读成 Base64。

## 导出组件
### `MediaLibrary()`
- 顶部"音频 / 图片"切换，分别渲染 `AudioLibrary` / `ImagesLibrary`。
- 调用者：`App.tsx:70`（媒体层）。

## 内部组件
### `AudioLibrary()`
- 状态：`data`、`connection`（与 `api/client.ts` 的 `Connection` 同义：401 key、503 world、其他 offline）、`selected`、`search`、`uploader`、`missingOnly`、`messages`、`waiting`、`drop`（拖放提示）、`converting`（转换进度）；ref：`conversion`（`AbortController`）、`busy`。
- `load()`：GET `api/audio` 更新数据和连接状态；页面可见时每 2 秒轮询。
- `entries`：按上传者、"只看本机没有的"、搜索（名字/引用名/声音）筛选，按上传时间倒序。
- 播放：`play(hash)` 先停当前的；同一个再点即停止；否则 GET `api/audio-file?hash=` 得到 Blob 播放（404 提示"本机还没有这个文件，正在下载"）。`stop()` 释放对象 URL。卸载时停止。
- 上传：
  - `upload(file, soundId)`：POST `{action:"upload", name, soundId, data}`，失败记消息，之后 `load()`。
  - `uploadFiles(files)`：逐个检查格式；WAV/MP3 先 `convertAudio(file, maxSeconds, maxFileBytes, 进度, signal)`（可"取消转换"）；超过大小报错；与自己已有的同名文件（按转换后的 `.ogg` 名）直接覆盖上传（声音 ID 留空沿用），否则加入 `waiting` 等待填写声音 ID（默认 `soundIdFromName`）。
  - `sendWaiting(index)`：校验声音 ID 后上传，不合法时标红。
- 文件操作：`act(name, body)`（POST 后 `load()`）；`copy(text)` 复制到剪贴板；改声音 ID（`{action:"soundId", key, soundId}`）；两步确认删除（`{action:"delete", key}`，正在播放则先停）。
- 界面：顶栏（空间占用条、上传按钮）、连接问题提示、可拖放的列表（表格，键盘上下移动选择）、右侧详情与"最近的操作"（本页消息加服务器 `requests`）、底部统计、隐藏的 `<audio>`。

## 0.74.0 媒体文件夹

- 左边是文件夹树（`views/MediaFolders.tsx` 的 `FolderTree`），`current` 为当前文件夹（null 为全部文件），列表只显示它的文件（搜索时含下面所有层，`filesIn`）。
- 每行可勾选（`checked`），可拖动（`DRAG_TYPE`）：拖勾选的那些或这一行；拖到树上的文件夹发 `{action: "folder", op: "move", …}`。
- 选文件或拖文件进来先 `askUpload`，出现 `UploadTarget` 选传到自己的哪个文件夹（默认当前打开的、是自己的那个），确认后 `uploadFiles(files, folder)`；上传请求带 `folder`，等声音 ID 的文件也记着文件夹。
- 文件夹没了（删除、改名）时回到全部文件。

## 资源 ID 与替换（0.79.0）
- 详情显示资源 ID 和曾用名；「改声音 ID」旁有「替换…」，选一个文件后按 `{action: "replace"}` 上传。
- 选中的文件也按 ID 记住，替换改名后仍选中它。
