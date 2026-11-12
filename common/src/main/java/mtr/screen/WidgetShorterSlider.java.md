# WidgetShorterSlider.java（上游文件，本分支有改动）

客户端界面控件：整数滑块，比原版滑块矮（高度可设），文字显示在滑块右边，可选刻度与 Shift 点击动作。使用者：`common/src/main/java/mtr/screen/ConfigScreen.java:106`–`:108` 等。

本分支改动：改继承 `mtr.compat.SliderBase`，绘制改经 `GuiCompat`（跨版本兼容），无逻辑变化。

## 类 `WidgetShorterSlider`（extends `SliderBase`，implements `IGui`）

### 字段与常量
- `maxValue`：最大整数值（最小为 0）。
- `markerFrequency`：每隔多少值画一个刻度（0 不画）；`markerDisplayedRatio`：刻度数字 = 值 / 此比例。
- `setMessage`：值 → 显示文字；`shiftClickAction`：按住 Shift 点击/拖动时的回调（可为 null）。
- `SLIDER_WIDTH = 6`（滑块把手宽度）、`TICK_HEIGHT = SQUARE_SIZE / 2`。

### 构造函数
- `WidgetShorterSlider(int x, int width, int maxValue, int markerFrequency, int markerDisplayedRatio, Function<Integer, String> setMessage, Consumer<Integer> shiftClickAction)`。
- `WidgetShorterSlider(int x, int width, int maxValue, Function<Integer, String> setMessage, Consumer<Integer> shiftClickAction)`：无刻度。

### `void onClick(double, double)` / `protected void onDrag(…)`
- 原有行为后检查 Shift 动作。

### `void setWidth(int width)`
- 宽度上限 380。

### `protected void updateMessage()`
- 文字 = `setMessage(getIntValue())`。

### `protected void applyValue()`
- 空（由调用方读取 `getIntValue`）。

### `void renderWidget(…)` / `void renderButton(…)`
- 两个版本的绘制入口，均调用私有 `render`。

### `void setValue(int valueInt)` / `void setHeight(int height)` / `int getIntValue()`
- 设置整数值（转换为 0–1）；设置高度；取四舍五入后的整数值。

### `private void render(PoseStack matrices)`
- 用原版控件纹理拼出背景与把手（悬停时高亮），右侧绘制文字；有刻度时在下方画刻度线和数字。

### `private void checkShiftClick()`
- 按住 Shift 且有回调时以当前值调用。
