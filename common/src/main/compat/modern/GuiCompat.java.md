# GuiCompat.java（modern）

客户端 GUI 绘制的跨版本静态工具（1.20 及以上；构建时由 `build.gradle:166-171` 复制为 `mtr.compat.GuiCompat`）。1.20 起原版绘制都要 `GuiGraphics`，而 MTR 的绘制代码一直传 `PoseStack`，本接口在需要时临时造一个 `GuiGraphics`，或直接用 `Font.drawInBatch` 画字。被 modern 版 `ButtonBase`/`CheckboxBase`/`ScreenBase`（`common/src/main/compat/modern/ScreenBase.java:20`）和 `common/src/main/java/mtr/render/RenderDrivingOverlay.java:29` 等使用。

## 接口 `GuiCompat`

### 常量 `FULL_BRIGHT = 0xF000F0`
- 打包光照值（天空光、方块光都满），画字时用，GUI 文字不受光照影响。

### `static PoseStack getPose(Object context)`
- 作用：`context` 是 `GuiGraphics` 则取其 `pose()`，否则当 `PoseStack` 强转。调用者：`common/src/main/java/mtr/render/RenderDrivingOverlay.java:29`。

### `static GuiGraphics graphics(PoseStack matrices)`
- 作用：用 `Minecraft.renderBuffers().bufferSource()` 新建 `GuiGraphics`，并把 `matrices` 当前矩阵乘进它的 pose。
- 返回：新的 `GuiGraphics`。
- 副作用：每次分配一个对象；绘制提交到共享 bufferSource。
- 调用者：`common/src/main/compat/modern/ButtonBase.java:20`、`common/src/main/compat/modern/ScreenBase.java:20`、本类 `fill`/`blit`。

### `draw(PoseStack, Font, String|Component|FormattedCharSequence, float x, float y, int color)`
- 作用：无阴影画字（Component 先转 `getVisualOrderText()`），走私有 `drawText(..., shadow=false)`。

### `drawShadow(PoseStack, Font, String, float x, float y, int color)`
- 作用：带阴影画字。调用者：`common/src/main/java/mtr/render/RenderDrivingOverlay.java:62`。

### `drawString(PoseStack, Font, String|Component, int x, int y, int color)`
- 作用：带阴影画字（与原版 `drawString` 行为一致）。

### `drawCenteredString(PoseStack, Font, String|Component, int x, int y, int color)`
- 作用：在 `x - font.width(text)/2` 处带阴影画字，即以 `x` 为中心。

### `fill(PoseStack, int x1, int y1, int x2, int y2, int color)`
- 作用：`graphics(matrices).fill(...)`，ARGB 填充矩形。

### `vLine(PoseStack, int x, int y1, int y2, int color)`
- 作用：1 像素竖线，填充 `(x, y1+1)–(x+1, y2)`。

### `blit(PoseStack, ResourceLocation, int x, int y, int u, int v, int width, int height)`
- 作用：`graphics(matrices).blit(...)`（纹理按原版默认 256×256）。调用者：`common/src/main/java/mtr/render/RenderDrivingOverlay.java:54`。

### `blit(PoseStack, ResourceLocation, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight)`
- 作用：指定纹理总尺寸的贴图。

### `private static void drawText(PoseStack, Font, String|FormattedCharSequence, float x, float y, int color, boolean shadow)`（两个重载）
- 作用：新建 `MultiBufferSource.immediate(Tesselator...)`，`font.drawInBatch(..., DisplayMode.NORMAL, 0, FULL_BRIGHT)`，然后立刻 `endBatch()`。
- 副作用：立即提交一批绘制，不与其他批合并。
