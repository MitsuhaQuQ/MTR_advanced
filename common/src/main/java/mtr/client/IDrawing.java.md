# IDrawing.java（上游文件，本分支有改动）

客户端：绘图工具接口（只有静态方法）：在世界或界面中绘制文字、线、矩形、贴图四边形，设置控件位置，以及报站朗读/聊天显示。几乎所有方块实体渲染器、列车模型和界面都使用它；ANTE 脚本也把它暴露给 JS（`cn/zbx1425/mtrsteamloco/scripting/ScriptHolderClient.java:75`）。

本分支新增：`drawLineWorld`、`drawTextureWorld`、`translateWorldOrigin`——以相机为原点、先用 double 做减法再转 float，避免大坐标在矩阵中以 float 表示时的精度问题。

## 接口 `IDrawing`

### `static void drawStringWithFont(PoseStack, Font, MultiBufferSource.BufferSource immediate, String text, float x, float y, int light)`
- 居中、不限尺寸、缩放 1、白色、带阴影。

### `drawStringWithFont(…, HorizontalAlignment, VerticalAlignment, float x, float y, float maxWidth, float maxHeight, float scale, int textColor, boolean shadow, int light, DrawingCallback)`
- `xAlignment` 与 `horizontalAlignment` 相同。

### `drawStringWithFont(…, HorizontalAlignment, VerticalAlignment, HorizontalAlignment xAlignment, x, y, maxWidth, maxHeight, scale, textColor, shadow, light, DrawingCallback)`
- 中文颜色同 `textColor`，中文字号比例 2。

### `drawStringWithFont(…, xAlignment, x, y, maxWidth, maxHeight, scale, int textColor, int textColorCjk, float fontSizeRatio, boolean shadow, int light, DrawingCallback drawingCallback)`
- 作用：多行文字绘制。`Config.useMTRFont()` 时用 `mtr:mtr` 字体；把 `||` 合并为 `|` 后按 `|` 分行；中文行按 `fontSizeRatio` 放大；总高超过 `maxHeight`（≥0 时）则调大 `scale`；总宽超过 `maxWidth * scale` 则横向压缩；按对齐方式逐行绘制。颜色按光照变暗（`light == MAX_LIGHT_GLOWING` 不变暗，否则乘 `min(方块光/16*0.1+0.7, 1)`）。`immediate` 为 null 时只计算不绘制。最后若有回调，回调文字所占矩形 `(x1, y1, x2, y2)`。
- 参数：`maxWidth`/`maxHeight` 为 −1 表示不限；`scale` 为缩小倍数（矩阵按 `1/scale` 缩放）；颜色为 ARGB。
- 调用者：`common/src/main/java/mtr/client/DynamicTrainModel.java:303`、`common/src/main/java/mtr/model/ModelSimpleTrainBase.java:76`。

### `static void drawLine(PoseStack, MultiBufferSource, float x1, y1, z1, x2, y2, z2, int r, int g, int b)`
- 用 `RenderType.lines()` 画一条线（当前矩阵坐标）。调用者：`cn/zbx1425/mtrsteamloco/mixin/RenderTrainsMixin.java:142`、`cn/zbx1425/mtrsteamloco/render/RailPicker.java:104`。

### `static void drawLineWorld(PoseStack, MultiBufferSource, double x1, y1, z1, double x2, y2, z2, int r, int g, int b)`（本分支新增）
- 世界坐标的线：平移到起点（相对相机），终点用 double 相减后的差值。调用者：`common/src/main/java/mtr/render/RenderRailInspect.java:65`、`common/src/main/java/mtr/render/RenderStationAreas.java:42`。

### `static void drawRectangle(VertexConsumer, double x1, y1, x2, y2, int color)`
- 界面中画纯色矩形（4 个顶点，z=0）；透明度为 0 不画。调用者：`common/src/main/java/mtr/screen/DashboardList.java:226`。

### `drawTexture` 重载
- `(matrices, vc, x1, y1, z1, x2, y2, z2, facing, color, light)`：UV 0–1。
- `(matrices, vc, x, y, width, height, facing, light)`：z=0 平面，白色。
- `(matrices, vc, x, y, width, height, u1, v1, u2, v2, facing, color, light)`。
- `(matrices, vc, x1, y1, z1, x2, y2, z2, u1, v1, u2, v2, facing, color, light)`：由对角两点生成四个角。
- `(matrices, vc, 四个角 x/y/z, u1, v1, u2, v2, Direction facing, int color, int light)`：写四个顶点（颜色、UV、无覆盖层、光照、按 `facing` 的法线）；透明度为 0 不画。调用者：`common/src/main/java/mtr/client/RouteMapGenerator.java:666`、`cn/zbx1425/mtrsteamloco/scripting/train/TrainDrawCalls.java:103`。

### `static void drawTextureWorld(PoseStack, VertexConsumer, 四个角 double x/y/z, float u1, v1, u2, v2, Direction facing, int color, int light)`（本分支新增）
- 四个角用 double 减去相机位置后转 float，再调用 `drawTexture`；不对每个四边形做矩阵压栈/复制/平移。调用者：`common/src/main/java/mtr/render/JonModelTrainRenderer.java:393`、`common/src/main/java/mtr/render/RenderTrains.java:483`。

### `static void translateWorldOrigin(PoseStack, double x, double y, double z)`（本分支新增）
- 平移到 `(x, y, z) - 相机位置`：渲染矩阵以相机为原点，大世界坐标不会以 float 进入矩阵。调用者：`common/src/main/java/mtr/render/RenderPSDAPGDoor.java:68`、`common/src/main/java/mtr/render/RenderRailwaySign.java:77`。

### `static void setPositionAndWidth(AbstractWidget widget, int x, int y, int widgetWidth)`
- 设置控件位置与宽度（宽度限制在 0…380，文本框再减 `TEXT_FIELD_PADDING`）。调用者：众多界面，如 `cn/zbx1425/mtrsteamloco/gui/BrushEditRailScreen.java:537`。

### `static void narrateOrAnnounce(String message)`
- 格式化站名（`IGui.formatStationName`，双空格换单空格）后：开启 TTS（`Config.useTTSAnnouncements`）则朗读；开启报站文字（`Config.showAnnouncementMessages`）则显示在聊天栏。调用者：`common/src/main/java/mtr/MTRClient.java:422`、`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:459`。

## 函数式接口 `DrawingCallback`
- `void drawingCallback(float x1, float y1, float x2, float y2)`：文字所占矩形。
