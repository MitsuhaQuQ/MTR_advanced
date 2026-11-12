# api/client.ts

新页面访问游戏内编辑器服务器（`EditorServer`，本机 HTTP）的客户端。负责取编辑器密钥、发 GET/POST/PUT 请求、把错误转成连接状态。端点在服务器根下 `/api/…`，页面本身在 `/next/`。被 `state/store.tsx`（读数据、存草稿、应用）、`legacy/engine.ts`（只取 `key`）、`views/TrackDiagram.tsx`（`post`）、媒体/图片库（`key`）使用。

## 模块加载时
- `KEY_STORAGE = "mtr-editor-key"`：会话存储键，与旧页面 `shell.js` 相同，两页互相打开时都能找到密钥。
- `key`：立即求值。游戏以 `…#key=<十六进制>` 打开编辑器；地址里有就存进 `sessionStorage` 并使用，否则读 `sessionStorage`，都没有为空串；存储不可用时只用地址里的。
- 若地址含 `key=`，立刻 `history.replaceState` 去掉 hash，使密钥不留在历史记录中、不显示。

## 导出
- `key: string`：编辑器密钥，作为请求头 `X-MTR-Editor-Key`。使用者：`legacy/engine.ts:11`、`views/ImagesLibrary.tsx:2`、`views/MediaLibrary.tsx:11`。
- `class ApiError extends Error`：端点返回错误。`status`：HTTP 状态码；0 表示网络失败；401 密钥错误；503 没有加载世界。
- `post<T>(path, body)`：对 `/api/<path>` 发 POST JSON，返回解析后的 JSON。调用者：`views/TrackDiagram.tsx:56`（`route-layout`）、`views/TrackDiagram.tsx:63`（`network`）。
- `api`：常用端点：
  - `data()`：GET `api/data`，返回 `EditorData`（游戏数据）。调用者：`state/store.tsx:72`。
  - `revision()`：GET `api/revision`，返回 `{ revision }`，用于判断数据是否变了。调用者：`state/store.tsx:70`。
  - `draft()`：GET `api/draft`，返回服务器保存的草稿。调用者：`state/store.tsx:113`。
  - `saveDraft(draft)`：PUT `api/draft`。调用者：`state/store.tsx:99`。
  - `apply(operations)`：POST `api/apply`，body `{ operations }`（EditorActions 操作列表），返回 `{ ok, errors?: [{index, message}], created?: [{id}] }`。调用者：`state/store.tsx:165`、`state/store.tsx:181`。
- `type Connection = "connecting" | "ok" | "key" | "world" | "offline"`：连接状态。
- `connectionOf(error)`：`ApiError` 401→`"key"`，503→`"world"`，其他及非 `ApiError`→`"offline"`。调用者：`state/store.tsx:83`、`state/store.tsx:173`。

## 内部函数
- `url(path)`：拼出 `location.origin + "/api/" + path`。
- `getJson<T>(path)`：带密钥头、`no-store` 的 GET；`fetch` 抛错转为 `ApiError(0)`，非 2xx 转为 `ApiError(状态)`；返回 JSON。
- `sendJson<T>(path, method, body)`：同上，`POST` 或 `PUT`，`Content-Type: application/json`。
