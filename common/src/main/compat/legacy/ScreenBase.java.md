# ScreenBase.java（legacy）

客户端界面基类兼容层（1.20 以前；构建时由 `build.gradle:166-171` 按版本复制为 `mtr.compat.ScreenBase`），仅继承 `ScreenMapper`。MTR 大部分界面都 `extends ScreenBase`，例如 `common/src/main/java/mtr/screen/DashboardScreen.java:28`、`common/src/main/java/mtr/screen/ConfigScreen.java:20`、`common/src/main/java/mtr/screen/AudioSpeakerScreen.java:43`。

## 类 `ScreenBase`（abstract，extends `ScreenMapper`）

### 构造函数 `protected ScreenBase(Component title)`
- 作用：转给 `ScreenMapper(title)`；`title` 为界面标题。
