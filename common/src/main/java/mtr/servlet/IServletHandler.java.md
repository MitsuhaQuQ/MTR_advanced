# IServletHandler.java

内置网页服务器（Jetty）各数据接口的共用工具：异步写 JSON 响应、开启带超时的异步请求、生成线路类型键。被 `ArrivalsServletHandler`、`DataServletHandler` 等使用（`common/src/main/java/mtr/servlet/ArrivalsServletHandler.java:28`、`:75`，`DataServletHandler.java:20`、`:150`）。本分支改动：跨域头不再固定为 `*`，只有在 `SecurityConfig.webserverCorsOrigin` 非空时才写该值；异步请求设 30 秒超时（`startAsync`）；写响应时也捕获 `IllegalStateException`（请求已超时完成时）。

## 接口 `IServletHandler`

### 常量 `REQUEST_TIMEOUT_MILLIS = 30000`
- 异步请求超时（毫秒）。

### `static void sendResponse(HttpServletResponse response, AsyncContext asyncContext, String content)`
- 作用：可选写 `Access-Control-Allow-Origin`，写 `Content-Type: application/json`；用非阻塞 `WriteListener` 逐字节写出 UTF-8 内容，写完设 200 并 `asyncContext.complete()`；出错也 complete。
- 异常：`IOException`/`IllegalStateException` 只打印堆栈。

### `static AsyncContext startAsync(HttpServletRequest request)`
- 开启异步并设超时 30 秒。

### `static String createRouteKey(TransportMode transportMode, RouteType routeType)`
- 返回小写的 `交通方式_线路类型`，如 `train_normal`。调用者：`common/src/main/java/mtr/servlet/DataServletHandler.java:45`。
