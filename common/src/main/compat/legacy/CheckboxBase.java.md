# CheckboxBase.java（legacy）

客户端 GUI 兼容层：1.20 以前版本的复选框基类，直接继承原版 `Checkbox`。构建时 `build.gradle:166-171` 按版本把 `compat/legacy` 或 `compat/modern` 复制成 `mtr.compat` 包。被 `WidgetBetterCheckbox` 继承（`common/src/main/java/mtr/screen/WidgetBetterCheckbox.java:11`）。

## 类 `CheckboxBase`（abstract，extends `Checkbox`）

### 构造函数 `CheckboxBase(int x, int y, int width, int height, Component text, boolean checked, boolean showMessage)`
- 作用：原样转给 `Checkbox`。
- 参数：GUI 像素坐标与尺寸；`text` 标签；`checked` 初始是否勾选；`showMessage` 是否显示标签文字。
- 调用者：`WidgetBetterCheckbox` 构造函数。
