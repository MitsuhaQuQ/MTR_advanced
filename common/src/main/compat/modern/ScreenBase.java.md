# ScreenBase.java（modern）

客户端界面基类兼容层（1.20 及以上；构建时由 `build.gradle:166-171` 复制为 `mtr.compat.ScreenBase`）。把原版 `render(GuiGraphics, ...)` 固定（final）转接到 PoseStack 版本，让 MTR 各界面继续覆盖 `render(PoseStack, ...)`。子类如 `common/src/main/java/mtr/screen/DashboardScreen.java:28`、`common/src/main/java/mtr/screen/AudioSpeakerScreen.java:43`。

## 类 `ScreenBase`（abstract，extends `ScreenMapper`）

### 构造函数 `protected ScreenBase(Component title)`
- 转给 `ScreenMapper`。

### `final void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta)`（@Override）
- 作用：取 `guiGraphics.pose()` 转调 PoseStack 版本。final：子类不能覆盖这一版。

### `void render(PoseStack matrices, int mouseX, int mouseY, float delta)`
- 作用：默认用 `GuiCompat.graphics(matrices)` 新建 `GuiGraphics` 后调父类绘制（控件、子元素）。子类通常覆盖后再 `super.render`。

### `void renderBackground(PoseStack matrices)`
- 作用：PoseStack 版背景绘制，转调父类 `renderBackground(GuiCompat.graphics(matrices))`。
