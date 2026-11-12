# CheckboxBase.java（modern）

客户端 GUI 兼容层：1.20 及以上版本的复选框基类（构建时由 `build.gradle:166-171` 复制为 `mtr.compat.CheckboxBase`）。与 modern `ButtonBase` 一样在 `GuiGraphics` 和 `PoseStack` 两种 `render` 签名之间转接。被 `WidgetBetterCheckbox` 继承（`common/src/main/java/mtr/screen/WidgetBetterCheckbox.java:11`）。

## 类 `CheckboxBase`（abstract，extends `Checkbox`）

### 构造函数 `CheckboxBase(int x, int y, int width, int height, Component text, boolean checked, boolean showMessage)`
- 作用：原样转给 `Checkbox`。参数：GUI 像素坐标/尺寸、标签、初始勾选、是否显示标签。

### `render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta)`（@Override）
- 作用：取 `guiGraphics.pose()` 转调 PoseStack 版本。

### `render(PoseStack matrices, int mouseX, int mouseY, float delta)`
- 作用：默认用 `GuiCompat.graphics(matrices)` 新建 `GuiGraphics` 后调父类绘制；子类可覆盖。
- 副作用：每次新建一个 `GuiGraphics`。
