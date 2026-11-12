# Webserver.java

MTR 内置网页服务器（Jetty）：提供线路图网站静态文件（jar 内 `/assets/mtr/website/`）以及 `/data`、`/info`、`/arrivals`、`/delays`、`/route` 接口；请求通过 `callback` 交给服务器线程处理。服务端在 `common/src/main/java/mtr/MTR.java:576`–`:597` 初始化、设置回调、启动、停止；单人游戏客户端在 `common/src/main/java/mtr/MTRClient.java:402`–`:416` 同样处理。本分支改动：线程池为守护线程（名 `mtr-webserver`，服务器停止后不会拖住进程）；加 `WebSecurityFilter`；禁止目录列表；`runSafely`；`canExposePlayers`；`start` 先停旧实例、受 `SecurityConfig.webserverEnabled` 控制、绑定 `SecurityConfig.webserverBindHost`，启动失败（端口占用等）时打印一行并把已启动的部分停掉。

## 类 `Webserver`（abstract，只有静态成员）

### 静态字段
- `callback`（`Consumer<Runnable>`）：把任务交到服务器线程执行（默认就地执行）；由 `MTR`/`MTRClient` 设置为 `server.execute`。
- `getWorlds`（`Supplier<List<Level>>`）：取所有世界（维度），接口用 `worldIndex` 选择。
- `getRoutes`（`Function<RailwayData, Set<Route>>`）、`getDataCache`（`Function<RailwayData, DataCache>`）：取某维度的线路与数据缓存。
- `webServer`、`serverConnector`：Jetty 服务器与连接器。

### `static void init()`
- 作用：建守护线程池（最大 100、最小 10、空闲 120 秒），建连接器与 `ServletContextHandler`；静态资源根为 jar 内网站目录；挂 `WebSecurityFilter`；默认 servlet 不允许目录列表、`cacheControl: max-age=0,public`；注册 5 个接口 servlet。

### `static void runSafely(Runnable runnable)`
- 经 `callback` 执行，异常只打印。调用者：`common/src/main/java/mtr/servlet/ArrivalsServletHandler.java:32`、`DataServletHandler.java:22`。

### `static boolean canExposePlayers()`
- 只绑定回环地址或配置了令牌时为 true，此时接口才返回玩家相关信息（`InfoServletHandler.java:27`、`RouteFinderServletHandler.java:124`）。

### `static void start(Path path)`
- 作用：先 `stop()`；未启用则返回；端口从 `path` 文件读（去掉非数字，夹到 1025–65535），读不到就写入默认 8888；设置绑定地址与端口并启动；失败打印 `Minecraft Transit Railway: the web server couldn't start on port …` 并 `stop()`。
- 参数：端口配置文件路径（由调用方给出，通常在 config 目录）。

### `static void stop()`
- 未处于停止状态就停止（包括启动失败后的半启动状态）；异常打印。
