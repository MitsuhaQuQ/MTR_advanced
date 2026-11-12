# WebSecurityFilter.java（本分支新增）

内置网页服务器（线路图网站与 `/data` 等接口）的安全过滤器，挂在所有路径上（`common/src/main/java/mtr/servlet/Webserver.java:61`）。依次：加安全响应头；只允许 GET/HEAD；检查 Host 头防 DNS 重绑定；可选令牌认证；按客户端 IP 限流。相关配置在 `SecurityConfig`（`config/mtr_security.json`）：`webserverAllowedHosts`、`webserverToken`、`webserverBindHost`。

## 类 `WebSecurityFilter`（implements `javax.servlet.Filter`）

### 常量与字段
- `requestCounts`（`ConcurrentHashMap<IP, long[3]>`）：`{窗口开始毫秒, 本窗口总请求数, 本窗口 /route 请求数}`。
- `MAX_REQUESTS_PER_MINUTE = 1200`：普通请求每 IP 每分钟上限。
- `MAX_ROUTE_REQUESTS_PER_MINUTE = 60`：`/route`（路径规划，较重）每 IP 每分钟上限。
- `MAX_TRACKED_CLIENTS = 4096`：记录的 IP 超过此数就全部清空（防内存膨胀）。
- `MILLIS_PER_MINUTE = 60000`。
- `TOKEN_PARAMETER = "token"`、`TOKEN_COOKIE = "mtr_token"`、`ROUTE_PATH = "/route"`。
- `CONTENT_SECURITY_POLICY`：只允许本站脚本，样式允许内联和 Google Fonts，字体允许 Google Fonts 等（完整值见源码）。

### `void init(FilterConfig)` / `void destroy()`
- 空实现。

### `void doFilter(ServletRequest, ServletResponse, FilterChain)`
- 作用：设置 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`、CSP；然后：非 GET/HEAD 回 405；Host 不允许回 403；未认证回 401；超限回 429；否则放行。

### `private static boolean isAllowedHost(String host)`
- `localhost`、`127.0.0.1`、`[::1]`、`::1` 总是允许；配置了允许主机列表时只允许列表内的（小写比较）；未配置时，若服务器只绑定回环地址则拒绝其他主机名（防 DNS 重绑定），否则允许任意。

### `private static boolean isAuthorized(HttpServletRequest request, HttpServletResponse response)`
- 未配置令牌时总是通过；否则依次接受 `Authorization: Bearer <令牌>`、Cookie `mtr_token`、查询参数 `?token=`（此时写 `Set-Cookie: mtr_token=…; Path=/; HttpOnly; SameSite=Strict`，浏览器之后用 Cookie 访问）。

### `private static boolean matches(String expected, String actual)`
- 常量时间比较（`MessageDigest.isEqual`），防计时攻击。

### `private boolean withinRateLimit(HttpServletRequest request)`
- 以 `getRemoteAddr()` 为键、按 1 分钟固定窗口计数：`/route` 请求计入两个计数并按 60 判断，其他按 1200 判断。
