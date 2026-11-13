# EditorServer.java

高级编辑器：本客户端为自己在系统浏览器中提供的网页（Jetty 嵌入式服务器）。它与服务器为所有人提供的地图网站是分开的：只监听本机 `127.0.0.1`、端口在启动时随机分配，只响应带本次会话密钥打开的页面。它读取本客户端已知的数据，所以单人和多人行为相同；修改仍走游戏的数据包，由服务器检查。由 `DashboardScreen`（`:100`、`:135`）调用 `open()`；`MTRClient`（`:379`、`:416`）在离开世界等时调用 `stop()`。静态页面资源在 `assets/mtr/editor/`（`editor-web` 构建出的 `index.html`、`assets/`、`legacy.css`、`audio/`，加上普通脚本 `i18n.js`、`batch.js`、`terminal.js`；旧页面 `editor.js` 等已在退役第 9 步删除），API 处理委托给本包的其它类（`EditorData`、`EditorActions`、`EditorTimes`、`EditorSimulation`、`EditorDuties`、`EditorLayout`、`EditorNetwork`、`EditorConsole`、`EditorAudio`、`EditorImages`）和 `mtr.packet` 中的预览请求。

## final class EditorServer

### 静态状态与常量
- `server`、`connector`、`key`（会话密钥，24 字节随机数的十六进制）。
- `LOOPBACK = "127.0.0.1"`、`KEY_HEADER = "X-MTR-Editor-Key"`（API 请求携带密钥的请求头）。
- `DATA_TIMEOUT_SECONDS = 10`：在游戏线程上执行的等待上限。
- `MAX_BODY_BYTES = 4 MiB`：请求体上限（音频/图片为 8 MiB）。
- `MAX_LAYOUT_TRACKS = 64`。
- `DRAFTS_FOLDER = "mtr_editor_drafts"`：草稿保存目录（在游戏 `config/` 下）。
- `CONTENT_SECURITY_POLICY`：仅允许自身来源的脚本/样式/连接，`data:` 图片、`blob:` 媒体，禁止对象、表单、被嵌入。

### `public static synchronized String open()`
- 作用：服务器未运行则启动。
- 返回：`http://127.0.0.1:<端口>/#key=<密钥>`（密钥在 URL 片段中，不随请求发送）；失败时打印、`stop()` 并返回 `null`。

### `public static synchronized void stop()`
- 作用：停止服务器并清空密钥（如离开世界），使仍打开的页面失效。异常只打印。

### `private static void start()`
- 作用：生成密钥；建立守护线程池（8 线程，名 `mtr-editor`，不会阻止游戏进程退出）；连接器绑定 127.0.0.1、端口 0；静态资源来自 jar 中 `/assets/mtr/editor/`（禁止目录列表、`no-cache`）；挂 `EditorFilter`；`/next/*`（新页面原来的地址，UI 自动测试仍打开它）用 `RedirectServlet("/")`、`/audio.html`（旧报站音频页的地址）用 `RedirectServlet("/#/media")` 重定向；注册 API servlet：
  - `/api/data`、`/api/revision`（`DataServlet`）
  - `/api/apply`（`ApplyServlet`）、`/api/draft`（`DraftServlet`）
  - `/api/plan-duties`、`/api/place-trains`、`/api/replan-duties`
  - `/api/times`、`/api/simulate`
  - `/api/layout`、`/api/route-layout`、`/api/console`、`/api/network`
  - `/api/route-preview`、`/api/depot-way-preview`
  - `/api/audio`、`/api/images`、`/api/image-file`、`/api/audio-file`
- 异常：启动失败抛出（由 `open` 处理）。

### `private static boolean matchesKey(String actual)`
- 常量时间比较请求头中的密钥。

### `private static boolean isOwnOrigin(String origin)`
- 返回：`Origin` 为 `http://127.0.0.1:<端口>` 或 `http://localhost:<端口>`（其它网站无法伪造）。

### `private static <T> T onGameThread(Supplier<T> supplier)`
- 作用：在游戏（客户端主）线程上执行并等待结果，最多 10 秒；超时/异常抛出。

### `private static void sendJson(HttpServletResponse response, String content)`
- 200、`application/json; UTF-8`、`Cache-Control: no-store`。

### `private static String readBody(HttpServletRequest request)` / `readBody(HttpServletRequest, int maximumBytes)`
- 读请求体为 UTF-8 文本；超过上限返回 `null`。

### `private static Path getDraftPath()`
- 返回：草稿文件路径：`config/mtr_editor_drafts/<world-存档名 | server-地址 | unknown 清洗后>-<哈希>.json`，每个世界/服务器一份，关闭页面或重启游戏后仍在。

## private static class EditorFilter implements Filter
- `init`/`destroy`：空。
- `doFilter`：设置安全响应头（`nosniff`、`X-Frame-Options: DENY`、`no-referrer`、CSP）。拒绝规则：非 GET/HEAD 且不是 API 的 POST/PUT → 405；主机名不是 127.0.0.1/localhost → 403（防 DNS 重绑定）；API 请求密钥不符 → 401；写请求的 `Origin` 不是本页面或 Content-Type 不是 `application/json` → 403（防止别的网站通过浏览器发修改）。

## `RedirectServlet`（私有静态内部类，2026-11-13）
- 构造参数 `target`：要去的地址。
- `doGet`：回 302、`Location: target`、`Cache-Control: no-store`。`target` 不带 `#…` 时浏览器保留请求地址里的 `#…`，所以 `/next/#/timetable` 打开的是 `/#/timetable`；密钥在会话存储里（`editor-web/src/api/client.ts`），重定向后仍能找到。HEAD 由 `HttpServlet` 默认交给 `doGet`。

## API servlet（均为私有静态内部类；处理异常时一般返回 503，请求解析失败 400，过大 413）
- `DataServlet(boolean revisionOnly)`：GET。游戏线程上 `EditorData.build()`；`revisionOnly` 时只返回 `{"revision": <JSON 文本哈希>}`，供页面轮询变化。
- `ApplyServlet`：POST。在游戏线程上 `EditorActions.applyAndSend(request)` 生成并发送修改，然后在 HTTP 线程等待服务器答复（`.await()`），游戏期间不阻塞。
- `SimulateServlet`：GET 返回 `EditorSimulation.getStatus()`；POST `EditorSimulation.start(request)`。
- `TimesServlet`：POST。`EditorTimes.fromDraftAsync(request)`，最多等 35 秒；任何异常返回 400。不改任何数据。
- `PlaceTrainsServlet`：POST `{depot}`。有权限且车厂存在时 `placeTrainsC2S(depotId)`（结果在游戏聊天栏告知），返回 `{"ok": true/false}`。
- `ReplanDutiesServlet`：POST `{depot, group?}`（第 4 轮第 4 步：重新编排而非保留；`group` 为单个运用组，空为全部）。`replanDutiesC2S`，返回 `{"ok": ...}`。
- `PlanDutiesServlet`：POST `{depot}`。游戏线程上 `EditorDuties.prepareGeneration` 复制数据，在 HTTP 线程计算初稿（大时刻表不卡游戏）；车厂不存在 404。不改数据。
- `NetworkServlet`：POST `{mode, fresh, version}` → `EditorNetwork.build`。
- `ConsoleServlet`：GET → `EditorConsole.build()`（D6）。
- `RouteLayoutServlet`：POST `{route, halfWidth?, fresh?}` → `EditorLayout.buildRoute`。
- `LayoutServlet`：POST `{station, margin?, tracks[]? (≤64)}` → `EditorLayout.build`；无车站 404。
- `DepotWayPreviewServlet`：POST `{depot, siding, platform?, out?, entrance?, waypoints[]?}`。由服务器按候选规则找出入库路径（`DepotWayPreview.request`，"运用补完"第 2 步），最多等 8 秒。返回 `{entrances[], nodes [[x+.5, z+.5, id]], found, millis}`；超时 `{error: "no answer"}`。
- `RoutePreviewServlet`：POST `{platforms[] (≤RoutePreview.MAX_STOPS), waypoints[][]?}`。服务器为全网地图上选的停站找新线路路径（`RoutePreview.request`），最多 8 秒；返回 `{legs: [{nodes, found, failure (PathFailureText 或 "?")}]}`；超时 `error`。
- `ImagesServlet`：GET → `EditorImages.build()`；POST `{name, action?: "delete", data?}` → 删除或上传（请求体上限 8 MiB：5 MiB 图片经 Base64 约 7 MiB）。
- `ImageFileServlet`：GET `?hash=`。`ImportedImages.read(hash)` 的字节，按内容判断 Content-Type；无则 404。
- `AudioServlet`：GET → `EditorAudio.build()`；POST `{action: upload|delete|soundId, name?, data|key, soundId?}`（上传/改声音 id/删除，游戏稍后答复，页面重新读列表看到）。
- `AudioFileServlet`：GET `?hash=`（须为合法哈希）。本客户端已有的音频（`AudioCache.read`），`audio/ogg`；没有 404（会被请求下载，页面稍后重试）。
- `DraftServlet`：GET 返回保存的草稿（无则 `{}`）；PUT 保存页面发来的草稿（必须是 JSON 对象，坏草稿不会妨碍打开编辑器），返回 `{"ok": true}`。

调用者说明：servlet 方法由 Jetty 按路径调用，脚本找不到直接的 Java 调用者；页面端调用在 `assets/mtr/editor/*.js`（由另一会话记录）。

## 0.74.0 媒体文件夹

- `api/audio` 和 `api/images` 的 POST 多了 `{action: "folder", …}`（交给 `EditorAudio.folder` / `EditorImages.folder`），上传可带 `folder`。
