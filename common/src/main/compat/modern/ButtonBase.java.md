# ButtonBase.java（modern）

客户端 GUI 兼容层：1.20 及以上版本的按钮基类。构建时 `build.gradle:166-171` 按版本把 `compat/legacy` 或 `compat/modern` 复制成 `common/src/main/java/mtr/compat`，本文件在 1.20.1 构建中生效。1.20 起原版 `render` 参数从 `PoseStack` 换成 `GuiGraphics`，本类在两种签名之间转接，使 MTR 旧的 PoseStack 绘制代码不用改。被 `WidgetColorSelector` 继承（`common/src/main/java/mtr/screen/WidgetColorSelector.java:25`）。

## 类 `ButtonBase`（abstract，extends `ButtonMapper`）

### 构造函数 `ButtonBase(int x, int y, int width, int height, Component component, OnPress onPress)`
- 作用：原样转给 `ButtonMapper`。参数：GUI 像素坐标、像素尺寸、按钮文字、点击回调。

### `render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta)`（@Override）
- 作用：原版入口；取 `guiGraphics.pose()` 转调下面的 PoseStack 版本，使子类只需覆盖 PoseStack 版。
- 参数：鼠标 GUI 坐标；`delta` 为帧间插值（partial tick）。

### `render(PoseStack matrices, int mouseX, int mouseY, float delta)`
- 作用：子类可覆盖的 PoseStack 版本；默认实现用 `GuiCompat.graphics(matrices)` 现造一个 `GuiGraphics` 再调用父类原版绘制。
- 副作用：每次调用都新建一个 `GuiGraphics`（见 `GuiCompat.graphics`）。
- 调用者：上面的 GuiGraphics 版本；子类的 `super.render(...)`。
