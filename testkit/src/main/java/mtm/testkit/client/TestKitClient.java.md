# TestKitClient.java

MTM_TestKit 的 Fabric 客户端入口（`ClientModInitializer`，调试专用）。游戏窗口起来后启动客户端驱动 `ClientDriver`，并把收到的聊天/系统消息记下来给测试读取。

## 类 `TestKitClient`（final，implements `ClientModInitializer`）

### `void onInitializeClient()`（@Override）
- 作用：
  - `TestKit.installLogCapture()`（保证客户端进程也收集警告/错误日志）；
  - `ClientLifecycleEvents.CLIENT_STARTED → ClientDriver::start`（开 HTTP 驱动）；
  - `ClientReceiveMessageEvents.GAME → ClientDriver::onChat`（系统消息，含 actionbar 覆盖层标志）；
  - `ClientReceiveMessageEvents.CHAT → ClientDriver.onChat(message, false)`（玩家聊天）。
- 调用者：Fabric Loader。
