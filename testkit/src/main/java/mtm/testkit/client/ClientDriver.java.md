# ClientDriver.java

MTM_TestKit 的客户端驱动：在客户端开 `Http` 服务（side = `client`），提供一组 HTTP 路由，让外部测试脚本读取当前界面、点击/输入、对方块使用物品、发聊天和命令、截图、读客户端那份铁路数据。除 `/suggest`、`/chat-log`、`/editor-url`、`/log`、`/quit` 外，所有动作都通过 `Http.onThread` 在渲染线程执行。由 `TestKitClient` 在 `CLIENT_STARTED` 时启动（`testkit/src/main/java/mtm/testkit/client/TestKitClient.java:16`），聊天消息由其注册的事件送入 `onChat`。

## 类 `ClientDriver`（final，私有构造）

### 常量与静态字段
- `KEEP_CHAT = 300`：保留的聊天条数。
- `CHAT`（`Deque<JsonObject>`，自身为锁）、`chatSequence`（递增序号）。
- `KEYS`：按键名 → GLFW 键码，含 `ENTER ESCAPE TAB BACKSPACE DELETE LEFT RIGHT UP DOWN HOME END A C V`。
- `http`：运行中的服务器实例。

### `static synchronized void start(Minecraft minecraft)`
- 作用：注册路由并 `Http.start("client", 游戏目录, routes)`；启动失败只写错误日志。
- 路由一览（请求体 JSON 字段）：
  - `/ping` → `ping`；`/state` → `state`；
  - `/click`（`id`|`text`|`x,y`，`button`，`force`）→ `click`；
  - `/move`（`x,y`）→ `screen.mouseMoved`；`/scroll`（`x,y,amount`）→ `screen.mouseScrolled`；
  - `/type`（`id`?，`text`，`clear`?；或 `chars`）→ `type`；`/key`（`key`，`ctrl/shift/alt`）→ `key`；
  - `/close` → 当前界面 `onClose()`；`/slot`（`slot`）→ 切换快捷栏格；
  - `/use`（`x,y,z`，`face`?，`slot`?）→ `use`；`/use-entity`（`type`，默认 `painting`，2026-11-13）→ 对 6 格内最近的该类实体 `gameMode.interact`（主手），返回 `{"result":…}`，没有时报错；
  - `/compare`（`setting`：`bake`|`optimization`，`settleMs`?，`tolerance`?，`name`?，2026-11-13）→ `RenderCompare.compare`；`/use-item` → 主手 `gameMode.useItem`，返回 `{"result":…}`；
  - `/chat`（`text`）→ `chat`；`/suggest`（`text`）→ `suggest`；`/chat-log?since=` → `chatLog`；
  - `/screenshot?id=` → `screenshot`（PNG）；
  - `/frames`（`optimizeTrainRendering`?、`maxFps`?（同时关垂直同步）、`x,y,z,yaw,pitch`?、`settleMs`?、`ms`，默认 10000）→ 先在渲染线程设好，再在请求线程等待并 `FrameStats.measure(ms)`（帧率测量，0.73.4）；
  - `/packs`（`select`?：资源包 ID 列表）→ 重新扫描资源包；有 `select` 时保留内置包、选上这些并 `reloadResourcePacks`，等重载完成（最多 300 s）；返回 `available`（ID 和兼容性）与 `selected`；
  - `/client-data` → `DataJson.snapshot(ClientData.STATIONS, PLATFORMS, SIDINGS, ROUTES, DEPOTS)`；
  - `/editor-url` → `{"url": EditorServer.open()}`（打开/取得网页编辑器地址）；
  - `/log?since=` → `LogCapture.since`；
  - `/join`（`address`）→ 仿多人游戏"直接连接"调用 `ConnectScreen.startConnecting`，服务器名 `MTM UI tests`；
  - `/quit` → `minecraft.stop()`。
- 副作用：开本地端口、写端点文件。

### `static void onChat(Component message, boolean overlay)`
- 作用：记录 `{"text","overlay","seq"}`，最多保留 300 条。
- 调用者：`testkit/src/main/java/mtm/testkit/client/TestKitClient.java:17`、`:18`。

### `private static JsonObject chatLog(long since)`
- 返回 `{"last": 最大序号, "messages": [序号 > since 的条目]}`。

### `private static Screen screen(Minecraft)` / `private static LocalPlayer player(Minecraft)`
- 取当前界面/本地玩家，没有则抛 `IllegalStateException`（"no screen is open" / "not in a world"），HTTP 回 500。

### `private static JsonObject ping(Minecraft)`
- 返回 `side="client"`、`inWorld`、`player`（名字）、`server`（IP）、`screen`（类名）、`titleScreen`、`fps`。

### `private static JsonObject state(Minecraft)`
- 返回 GUI 缩放 `guiScale/guiWidth/guiHeight`、`texts`（`ScreenText.lastFrame()`）、`screen`/`screenName`/`title`、`widgets`（`WidgetTree.describe` 列表）、`lists`（`WidgetTree.lists`）。没有界面时 `screen` 为 null。

### `private static WidgetTree.Found find(Screen screen, JsonObject body)`
- 按 `id` 精确匹配，或按 `text` 匹配第一个可见且文字相同的控件；都没给返回 null；找不到抛 `IllegalArgumentException`。

### `private static JsonObject click(Minecraft, JsonObject body)`
- 作用：点控件中心或给定坐标；控件隐藏或不可用时除非 `force` 否则抛 `IllegalStateException`（玩家也点不到）。依次 `mouseMoved`、`mouseClicked`，若界面没被关闭/替换再 `mouseReleased`。
- 返回：`{"id","x","y","handled"}`。

### `private static JsonElement type(Minecraft, JsonObject body)`
- 作用：有 `chars` 时不找控件，直接对当前界面逐字符 `charTyped`（如刷子选图片列表的搜索，2026-11-13）；否则有目标控件则先设焦点（`EditBox` 且 `clear` 时清空），再逐字符 `charTyped`。返回 null。

### `private static JsonElement key(Minecraft, JsonObject body)`
- 作用：按键名或数字键码，组合 Ctrl/Shift/Alt 修饰，`keyPressed`，界面未变时再 `keyReleased`。
- 异常：未知键名且不是数字时 `NumberFormatException`。

### `private static boolean flag(JsonObject body, String name)`
- 字段存在且为 true。

### `private static JsonObject use(Minecraft, JsonObject body)`
- 作用：可选切换快捷栏；构造命中 `(x,y,z)` 方块 `face` 面中心的 `BlockHitResult`（默认 UP），调 `gameMode.useItemOn(MAIN_HAND)`，等同右键。服务器会检查距离。
- 返回：`{"result": InteractionResult}`。

### `private static JsonElement chat(Minecraft, String text)`
- 作用：以 `/` 开头则 `sendCommand`，否则 `sendChat`。未连接抛 `IllegalStateException`。

### `private static JsonObject suggest(Minecraft, String text) throws Exception`
- 作用：在渲染线程用客户端命令树解析，取补全建议（需要时会问服务器），在 HTTP 线程等最多 10 秒。
- 返回：`{"suggestions": [文本…]}`。

### `private static byte[] screenshot(Minecraft, String id)`
- 作用：截主渲染目标为 PNG；给 `id` 时按 GUI 缩放把该控件矩形裁出来。
- 异常：编码失败包装为 `IllegalStateException`。
