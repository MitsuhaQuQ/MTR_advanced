# SliderBase.java（legacy）

客户端滑块控件基类兼容层（1.20 以前；构建时由 `build.gradle:166-171` 按版本复制为 `mtr.compat.SliderBase`），继承原版 `AbstractSliderButton`。被 `common/src/main/java/mtr/screen/WidgetShorterSlider.java:15` 继承。

## 类 `SliderBase`（abstract，extends `AbstractSliderButton`）

### 构造函数 `SliderBase(int x, int y, int width, int height, Component message, double value)`
- 作用：转给父类。`x`/`y`/`width`/`height` 为 GUI 像素；`message` 显示文字；`value` 为 0–1 的滑块位置。
