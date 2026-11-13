# EditorAudio.java

高级编辑器的"报站音频"页面：服务器的音频列表加上本客户端已缓存的情况、上传文件、修改声音 id、删除文件，底层是客户端的 `AudioCache`。在游戏线程上执行；由 `EditorServer` 的音频 servlet 调用（`EditorServer.java:801`、`:834`）。

## final class EditorAudio

### `private EditorAudio()`
工具类，禁止实例化。

### `public static JsonObject build()`
- 作用：生成音频页 JSON。
- 返回字段：`canUpload`、`canManage`、`maxFileBytes`、`maxSeconds`、`maxTotalBytes`、`totalBytes`、`language`（`EditorData.getLanguage()`）；`entries[]` 每项 `key, reference, hash, name, soundId, sound (资源位置或 ""), size, durationMillis, uploaderName, uploadedAt, canDelete (canManage 或自己上传), mine, cached, downloading`；`requests[]` 每项 `id, name, done, ok, message (已翻译)`。
- 副作用：调用 `AudioCache.expireRequests()` 清理过期请求。
- 主要调用者：`EditorServer.java:801`。

### `public static JsonObject upload(String name, String soundId, String data)`
- 参数：`name` 文件名；`soundId` 要给的声音 id，空串表示由服务器按文件名生成；`data` 文件的 Base64。
- 返回：成功 `{id: 请求id, ok: true}`；失败 `{ok: false, message}`（Base64 无效时为 `gui.mtr.audio_read_failed`，其余为 `AudioFiles.InvalidAudioException` 的消息，经 `TranslatedMessage.decode`）。
- 副作用：`AudioCache.upload(...)` 发起上传。
- 主要调用者：`EditorServer.java:834`。

### `public static JsonObject setSoundId(String key, String soundId)`
- 参数：`key` 文件的名字键（`AudioCache.Entry#getKey`）；`soundId` 新声音 id。
- 返回：成功 `{id, ok: true}`；找不到条目或校验失败 `{ok: false, message}`（`gui.mtr.audio_missing` 等）。
- 主要调用者：`EditorServer.java:834`。

### `public static JsonObject delete(String key)`
- 参数：`key` 要删除条目的名字键。
- 返回：找不到 `{ok: false, message: audio_missing 的翻译}`；否则 `{id: AudioCache.delete(entry), ok: true}`。
- 主要调用者：`EditorServer.java:834`。

## 0.74.0 媒体文件夹

- 列表每条加 `uploader`、`folder`，另有 `folders`（`owner`、`ownerName`、`path`）和 `self`（本玩家 UUID）。
- `upload(name, soundId, data, folder)`；`folder(request)`：`op` 为 create、rename、delete、move，`owner`、`path`，新建和改名用 `name`，移动用 `folder` 和 `keys`；返回请求 id。工具方法 `folderAction`、`string`、`folders`、`self` 也给 `EditorImages` 用。

## 资源 ID 与替换（0.79.0）
- 列表每项加 `resourceId`、`oldNames`。
- `replace(resourceId, name, soundId, data)`：`POST api/audio {action: "replace", resourceId, name, soundId?, data}` 调它。
