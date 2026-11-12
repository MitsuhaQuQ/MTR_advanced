# WidgetBase.java（modern）

客户端控件接口兼容层（1.20 及以上；构建时由 `build.gradle:166-171` 复制为 `mtr.compat.WidgetBase`）。让实现者只写 PoseStack 版 `render`，默认方法把原版 `GuiGraphics` 版转接过去。被 `common/src/main/java/mtr/screen/WidgetMap.java:45` 实现。

## 接口 `WidgetBase`（extends `WidgetMapper`）

### `void render(PoseStack matrices, int mouseX, int mouseY, float delta)`（抽象）
- 实现者的绘制方法。

### `default void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta)`（@Override）
- 作用：取 `guiGraphics.pose()` 调上面的抽象方法。
