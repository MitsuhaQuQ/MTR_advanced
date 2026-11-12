# GuiCompat.java（legacy）

客户端 GUI 绘制的跨版本静态工具（1.20 以前版本；构建时由 `build.gradle:166-171` 按版本复制为 `mtr.compat.GuiCompat`）。legacy 版全部直接转调原版 `Font`/`GuiComponent` 的 PoseStack 接口。主要调用者：`common/src/main/java/mtr/render/RenderDrivingOverlay.java:29`、`:54`、`:62`。

## 接口 `GuiCompat`（只有静态方法）

### `static PoseStack getPose(Object context)`
- 作用：旧版本渲染上下文本身就是 `PoseStack`，直接强转返回。调用者：`common/src/main/java/mtr/render/RenderDrivingOverlay.java:29`。

### `draw(PoseStack, Font, String|Component|FormattedCharSequence, float x, float y, int color)`
- 作用：无阴影画字（`font.draw`）。`x`/`y` 为 GUI 像素坐标，`color` 为 ARGB。

### `drawShadow(PoseStack, Font, String, float x, float y, int color)`
- 作用：带阴影画字。调用者：`common/src/main/java/mtr/render/RenderDrivingOverlay.java:62`。

### `drawString(PoseStack, Font, String|Component, int x, int y, int color)`
- 作用：`GuiComponent.drawString`（带阴影）。

### `drawCenteredString(PoseStack, Font, String|Component, int x, int y, int color)`
- 作用：以 `x` 为水平中心画带阴影文字。

### `fill(PoseStack, int x1, int y1, int x2, int y2, int color)`
- 作用：填充矩形，左上/右下像素坐标，ARGB 颜色。

### `vLine(PoseStack, int x, int y1, int y2, int color)`
- 作用：1 像素宽竖线，填充 `(x, y1+1)–(x+1, y2)`。

### `blit(PoseStack, ResourceLocation texture, int x, int y, int u, int v, int width, int height)`
- 作用：按 256×256 纹理尺寸贴图，转调下一个重载。调用者：`common/src/main/java/mtr/render/RenderDrivingOverlay.java:54`。

### `blit(PoseStack, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight)`
- 作用：先 `UtilitiesClient.beginDrawingTexture(texture)` 绑定纹理，再 `GuiComponent.blit`。
- 副作用：改变当前绑定的着色器纹理。
