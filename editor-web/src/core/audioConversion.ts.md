# core/audioConversion.ts

在浏览器本地把 WAV / MP3 报站音频转成 Ogg Vorbis，只把转换结果经已有的上传端点发出。先读元数据检查时长（避免先分配解码后的大缓冲），用 `OfflineAudioContext` 解码并重采样为 24 kHz 单声道，再交给 Web Worker `audio/convert-worker.js`（相对页面 `BASE_URL`）编码。被 `views/MediaLibrary.tsx` 使用。

## 常量
- `MAX_SOURCE_BYTES = 128 MB`：源文件大小上限。
- `SAMPLE_RATE = 24000`：输出采样率（Hz）。

## 导出
- `isWav(file)`：按扩展名 `.wav` 或 MIME（`audio/wav`、`audio/x-wav`、`audio/wave`）判断。调用者：`views/MediaLibrary.tsx:26`、`views/MediaLibrary.tsx:166`。
- `isMp3(file)`：按 `.mp3` 或 MIME（`audio/mpeg`、`audio/mp3`、`audio/x-mp3`）判断。调用者同上。
- `oggName(name)`：去掉 `.wav`/`.mp3` 后缀并加 `.ogg`。调用者：本文件 `convertAudio`。
- `wavDuration(bytes)`：解析 RIFF/WAVE 头，返回时长（秒）。要求 `fmt ` 块格式为 PCM(1)/浮点(3)/扩展(0xFFFE)、1–2 声道、8–192 kHz，`data` 大小为块对齐的整数倍；否则抛"无法读取 WAV…"。调用者：本文件 `convertAudio`。
- `convertAudio(file, maxSeconds, maxBytes, progress, signal?)`：转换主流程：
  - 参数：`file` 源文件；`maxSeconds` 服务器允许的最长时长（秒）；`maxBytes` 服务器允许的最大文件字节；`progress(percent)` 进度回调；`signal` 可取消。
  - 检查源大小；MP3 先读元数据时长（`mp3Duration`），WAV 用 `wavDuration`；超过 `maxSeconds` 或 600 秒（网页转换上限）报错。
  - 解码并离线渲染为单声道 24 kHz；解码失败按格式给出可读错误。
  - 启动 Worker，发送采样（转移缓冲区）；Worker 回 `progress` 时回调，回 `blob` 时检查大小并以 `oggName` 生成 `File`（`audio/ogg`）；`error` 或 `onerror` 报"音频转换失败"；180 秒超时；取消时抛 `AbortError`。
  - 返回 `Promise<File>`。调用者：`views/MediaLibrary.tsx:169`。

## 内部
- `mp3Duration(file, signal?)`：用一个 `<audio preload="metadata">` 读 MP3 时长（秒），20 秒超时、可取消；结束后释放对象 URL。调用者：`convertAudio`。
