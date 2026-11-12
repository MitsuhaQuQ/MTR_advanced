# media.js

旧页面高级编辑器的媒体库，即第二个工作区（`shell.js` 第一次打开媒体工作区时动态加载）：左边按种类列出服务器保存的文件，中间是该种类的文件列表（搜索、筛选），右边是选中文件及可做的操作。用上传按钮或拖到列表上添加文件。每个种类是 `KINDS` 的一项（API、接受的文件、列表列和详情）；目前只有报站音频（原来的 `audio.html` 现在打开这里）：游戏保存的 Ogg Vorbis 文件，报站器用"上传者名小写:文件名"引用，同时也是游戏声音 `mtr_audio:<上传者>/<声音 ID>`，可用 `/playsound` 播放。游戏把文件发到服务器、稍后才回答，所以显示期间每隔几秒重读列表。文件只有本机已有时才能在这里播放；缺的由游戏自己向服务器要。新页面不运行本文件，而用重写的 `editor-web/src/views/MediaLibrary.tsx`（同样的端点）。整个文件是一个 IIFE，没有导出；依赖全局 `MTREditor`（`shell.js`）、`tr`/`I18N`（`i18n.js`）。

## 常量与工具
- `POLL_MILLIS = 2000`、`MAX_MESSAGES = 8`。
- `$(id)`：`document.getElementById`。`escapeHtml(text)`：HTML 转义。`key = MTREditor.key`。
- `fetchJson(path)` / `sendJson(path, body)`：带 `X-MTR-Editor-Key` 的 GET / POST JSON，非 2xx 抛 `Error(状态码)`。
- `formatSize(bytes)`、`formatDuration(millis)`、`formatClock(seconds)`、`formatTime(millis)`：同新页面 `MediaLibrary.tsx` 的同名函数。
- `failed(e)`：413 → "文件太大"，否则"发送失败…"。

## 报站音频
- `SOUND_ID`、`MAX_SOUND_ID = 64`、`isSoundId(id)`、`soundIdFromName(name)`：声音 ID 规则（小写字母数字 `_ . -` 可用 `/` 分段；从文件名生成时去 `.ogg`、小写、空格换 `_`，中文名不行返回空）。
- `askSoundId(text, suggested)`：用 `prompt()` 循环询问合法声音 ID，非法时 `alert` 后重问；取消返回 null。
- `audio`（种类对象）：`id: "audio"`、`name()`、`api: "api/audio"`、`accept`、`takes(file)`（.ogg）、`wrongType()`、`uploadLabel()`、`noUpload()`、`uploadTitle()`、`limits(data)`（大小和时长上限说明）、`empty()`、`matches(entry, text)`（名字/引用名/声音包含搜索词）、`row(entry)`（列表行 HTML：名字与引用名、时长、大小、上传者、本机、试听/停止按钮 `data-play`）、`detail(entry)`（详情 HTML：播放器、属性、引用名与 /playsound 名的复制按钮 `data-copy`、可删除时的改声音 ID `data-sound-id` 和删除 `data-delete`）、`status(data)`（底部统计）。
  - `upload(file)`：自己已有同名文件时直接覆盖（不问声音 ID），否则 `askSoundId`；POST `{action:"upload", name, soundId, data: Base64}`；失败记消息。
  - `remove(entry)`：`confirm()` 后 POST `{action:"delete", key}`。
  - `changeSoundId(entry)`：问新 ID，POST `{action:"soundId", key, soundId}`。
- `KINDS = [audio]`。

## 状态
- `stores`：每种类 `{ data, connection }`。`view`：`{ kind, selected, search, uploader, missingOnly }`。`messages`：本页消息（最新在前）。`playing`、`playingUrl`、`pollTimer`、`sideOpen`。
- `kindOf(part)`、`store()`、`active()`（当前在媒体工作区）、`localText(entry)`（有/下载中/没有）。
- `setHtml(element, html)`：内容变化时才重写（保持焦点和悬停）。
- `note(name, message, ok)`：记一条消息（最多 8 条）。
- `visibleEntries()`：按上传者、只看本机没有的、搜索筛选，按上传时间倒序。

## 读取与绘制
- `load()`：GET 当前种类的 API，`I18N.setLanguage(data.language)`，记连接状态（401 key、503 world、其他 offline），`render()`。
- `poll()`：在媒体工作区且页面可见时 `load()`，每 2 秒一次。
- `render()`：不在媒体工作区时不画。更新连接问题横幅、上传按钮（文字、可用性、提示、`accept`）、空间占用条（≥90% 琥珀、满红）、限制说明，然后 `renderKinds`、`renderUploaders`、`renderMessages`、`renderEntries`、`renderDetail`、底部状态。
- `renderKinds()`：种类列表（多于一种时加"全部"）及数量。
- `renderUploaders()`：上传者下拉，名字或语言变化时才重建。
- `renderMessages()`：最近操作（本页消息加服务器的 `requests`）。
- `renderEntries()`：空列表提示、"只看本机没有的"按钮状态、列表行（选中行高亮）。
- `renderDetail()`：选中文件的详情或提示，然后 `showProgress()`。
- `showProgress()`：更新播放进度条和时间。

## 操作
- `uploadFiles(files)`：逐个检查类型和大小，`kind.upload(file)`，每个后 `render()`，最后 `load()`。
- `readBase64(file)`：读成 Base64。
- `stop()`：停止播放并释放对象 URL。
- `play(hash)`：同一个再点停止；否则 GET `api/audio-file?hash=` 播放（404 提示正在下载）。
- `act(action, entryKey)`：对条目执行操作（删除、改 ID），失败记消息，然后 `load()`。
- `copy(text)`：复制到剪贴板，失败时用 `prompt` 显示让用户手动复制。
- `select(entryKey)`：选中，窄屏时把详情滚动到可见。
- `portrait`、`setSideOpen(open)`：种类栏折叠（`body.media-side-closed`）及 `#btn-tree` 的 `aria-expanded`。

## 事件（加载时注册）
- 上传按钮与文件输入；种类点击（`MTREditor.open("media", 种类)`）；搜索、上传者、只看本机没有的；列表点击（试听或选中）与键盘（Enter/空格选中，上下方向键移动）；详情按钮（试听、复制、删除、改声音 ID）；播放器 `timeupdate`/`ended`；列表拖放（拖入时提示是否接受，放下时上传）；`#btn-tree`、遮罩、屏幕方向变化；`mtr:workspace` 事件（离开媒体工作区时停止播放；进入时按地址切换种类、`render()`、`poll()`）。
- 最后：按 `MTREditor.part` 选种类，恢复搜索框内容，设置种类栏，`render()`、`poll()`。
