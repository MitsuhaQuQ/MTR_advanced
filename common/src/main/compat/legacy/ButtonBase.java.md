# ButtonBase.java（legacy）

客户端 GUI 兼容层：1.20 以前版本使用的按钮基类。构建时 `build.gradle:166-171` 先清空 `common/src/main/java/mtr/compat`，再按版本把 `common/src/main/compat/legacy`（1.20 以前）或 `common/src/main/compat/modern`（1.20 及以上）整目录复制过去，所以代码里写 `mtr.compat.ButtonBase`，实际编译的是两个目录之一。legacy 版只是把 `ButtonMapper`（`mtr.mappings`）包一层，让上层代码在新旧版本都能写 `extends ButtonBase`。被 `WidgetColorSelector` 继承（`common/src/main/java/mtr/screen/WidgetColorSelector.java:25`）。

## 类 `ButtonBase`（abstract，extends `ButtonMapper`）

### 构造函数 `ButtonBase(int x, int y, int width, int height, Component component, OnPress onPress)`
- 作用：原样转给 `ButtonMapper` 构造函数。
- 参数：`x`/`y` 屏幕 GUI 像素坐标（左上角）；`width`/`height` 像素尺寸；`component` 按钮文字；`onPress` 点击回调。
- 返回/副作用：无。
- 调用者：子类 `WidgetColorSelector`（`common/src/main/java/mtr/screen/WidgetColorSelector.java:25`）。
