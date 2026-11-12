# Http.java

MTM_TestKit 的小型 HTTP 服务器，只监听 `127.0.0.1` 的随机端口。客户端驱动和服务端驱动各起一个（side 为 `client` / `server`），供外部测试脚本（`autotest/`、`C:/gtmp/fast/ui-test.sh`）远程操作游戏。安全约束：每个请求必须带启动时写在 `<游戏目录>/testkit/endpoint-<side>.json` 里的令牌；带 `Origin` 头的请求（来自浏览器网页）一律拒绝。处理函数返回 `JsonElement`、`byte[]`（作为 PNG 发送）或 null。调用者：`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:126`、`testkit/src/main/java/mtm/testkit/serverdriver/ServerDriver.java:109`。

## 接口 `Http.Handler`
- `Object handle(JsonObject body, Map<String, String> query) throws Exception`：`body` 为请求体 JSON（空体为 `{}`），`query` 为 URL 查询参数（已 URL 解码）。

## 类 `Http`（final）

### 常量
- `TOKEN_HEADER = "X-TestKit-Token"`：令牌请求头名。
- `GSON`：共用 Gson。

### 字段
- `server`（JDK `HttpServer`）、`executor`（线程池）、`endpointFile`（端点文件路径，停止时删除）。

### 私有构造函数 `Http(HttpServer, ExecutorService, Path)`

### `static Http start(String side, Path gameDirectory, Map<String, Handler> routes) throws IOException`
- 作用：用 `SecureRandom` 生成 24 字节（48 位十六进制）令牌；在回环地址端口 0（系统分配）上建服务器，积压 16；线程池为缓存线程池，守护线程名 `MTM-TestKit-<side>`；为每个路径注册处理器；启动后写端点文件 `{"port":…, "token":…, "pid":…}`，并打 info 日志。
- 参数：`side` 端名；`gameDirectory` 游戏目录；`routes` 路径 → 处理器。
- 返回：新的 `Http` 实例。
- 副作用：开端口、建目录 `testkit/`、写文件、写日志。
- 异常：端口或文件出错抛 `IOException`。

### `void stop()`
- 作用：立即停止服务器、关闭线程池、删除端点文件（删除失败忽略）。

### `static <T> T onThread(BlockableEventLoop<?> loop, Supplier<T> supplier) throws Exception`
- 作用：在游戏线程（客户端渲染线程或服务器主线程）执行 `supplier` 并等待结果，让处理函数在 tick/帧之间读改游戏状态。已在该线程则直接执行。
- 异常：等待超过 20 秒抛 `TimeoutException`；任务异常包装为 `ExecutionException`。
- 调用者：`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:74` 等几乎所有路由。

### `private static void serve(HttpExchange exchange, String token, Handler handler)`
- 作用：校验令牌且无 `Origin` 头，否则回 403 `{"ok":false,"error":"forbidden"}`；读取请求体解析为 JSON；调用处理器；结果是 `byte[]` 则以 `image/png` 回 200，否则回 `{"ok":true,"result":…}`。任何异常回 500，`error` 为异常文字（`ExecutionException` 取其 cause），`stack` 为堆栈文本。最后关闭 exchange。

### `private static JsonObject error(String message)`
- 返回 `{"ok":false,"error":message}`。

### `private static void send(HttpExchange exchange, int status, JsonObject response)`
- 以 UTF-8 JSON 写响应。

### `private static byte[] readAll(InputStream inputStream)`
- 以 8 KB 缓冲读完输入流。

### `private static Map<String, String> parseQuery(String rawQuery)`
- 按 `&`、`=` 拆分并 URL 解码；没有 `=` 的键值为空串；解码失败的项跳过。
