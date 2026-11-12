# LogCapture.java

MTM_TestKit 的 Log4j2 追加器（appender）：挂在根 logger 上，保存本游戏进程最近的警告与错误（带递增序号），测试可以查询"自某序号以来"的日志，出现新异常就判失败。服务端与客户端各进程只装一次（`TestKit.installLogCapture`）。查询接口为两端驱动的 `/log`（`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:113`、`testkit/src/main/java/mtm/testkit/serverdriver/ServerDriver.java:103`）。

## 类 `LogCapture`（final，extends `AbstractAppender`）

### 常量与静态字段
- `KEEP = 500`：最多保留条数，超出丢最旧的。
- `EVENTS`（`Deque<JsonObject>`）：保存的日志条目；以自身为锁。
- `sequence`：已记录的最大序号（从 1 开始递增）。

### 私有构造函数 `LogCapture()`
- 名称 `MTM-TestKit`，无过滤器、无布局，`ignoreExceptions = true`。

### `static void install()`
- 作用：新建并启动 appender，加到根 logger。
- 副作用：改变全局日志配置。调用者：`testkit/src/main/java/mtm/testkit/TestKit.java:30`。

### `void append(LogEvent event)`（@Override）
- 作用：只处理 WARN 及更严重级别；生成 `{"level","logger","thread","message","thrown"?, "seq"}`，加锁追加并裁剪到 `KEEP` 条。

### `static JsonObject since(long since)`
- 作用：返回 `{"last": 当前最大序号, "events": [序号 > since 的条目]}`。
- 参数：`since` 上次看到的序号，0 表示全部。
