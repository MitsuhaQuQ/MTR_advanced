# TestKit.java

调试专用测试模组 MTM_TestKit 的 Fabric 通用入口（`ModInitializer`，在 `testkit/src/main/resources/fabric.mod.json` 中登记）。在服务端（专用服务器或单人内置服务器）启动后开启服务端驱动 `ServerDriver`，停止时关闭，并每个服务器 tick 末尾调用 `ServerDriver.tickSensors`。设计见仓库根目录 `MTR-自动化游戏内测试设计.md`。加载时打一条警告日志：此模组只用于自动化测试，不能装在有人游玩的服务器上。

## 类 `TestKit`（final，implements `ModInitializer`）

### 常量 `LOGGER`
- 名为 `MTM_TestKit` 的 SLF4J logger。被 `testkit/src/main/java/mtm/testkit/Http.java:81`、`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:128` 使用。

### 静态字段 `logInstalled`
- 是否已安装 `LogCapture`，保证每进程只装一次（客户端进程里通用入口和客户端入口都会调用）。

### `void onInitialize()`（@Override）
- 作用：`installLogCapture()`；写警告日志；注册 `ServerLifecycleEvents.SERVER_STARTED → ServerDriver::start`、`SERVER_STOPPING → ServerDriver.stop()`、`ServerTickEvents.END_SERVER_TICK → ServerDriver::tickSensors`。
- 调用者：Fabric Loader 加载模组时。

### `static synchronized void installLogCapture()`
- 作用：首次调用时 `LogCapture.install()`。调用者：本类 `onInitialize`、`testkit/src/main/java/mtm/testkit/client/TestKitClient.java:15`。
