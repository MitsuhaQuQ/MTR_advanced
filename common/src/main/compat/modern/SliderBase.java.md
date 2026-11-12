# SliderBase.java（modern）

客户端滑块控件基类兼容层（1.20 及以上；构建时由 `build.gradle:166-171` 复制为 `mtr.compat.SliderBase`）。把 `renderWidget(GuiGraphics, ...)` 转接成抽象的 PoseStack 版本。被 `common/src/main/java/mtr/screen/WidgetShorterSlider.java:15` 继承。

## 类 `SliderBase`（abstract，extends `AbstractSliderButton`）

### 构造函数 `SliderBase(int x, int y, int width, int height, Component message, double value)`
- 转给父类；`value` 0–1。

### `renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta)`（@Override）
- 作用：取 `pose()` 转调抽象版本。

### `abstract void renderWidget(PoseStack matrices, int mouseX, int mouseY, float delta)`
- 子类必须实现的绘制方法（由 `WidgetShorterSlider` 实现）。
