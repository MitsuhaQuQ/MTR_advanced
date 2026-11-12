# RenderStationAreas.java（本分支新增）

客户端：玩家手持车站区域工具时，在世界中画出附近车站的区域矩形：限定了高度的矩形画成长方体线框，覆盖所有高度的矩形在玩家脚下高度画一圈轮廓，颜色为车站颜色。由 `common/src/main/java/mtr/render/RenderTrains.java:265` 每帧调用。

## 类 `RenderStationAreas`（final，私有构造）

### 常量 `MAX_DISTANCE = 128`
- 只画矩形外扩 128 格范围内能看到的（按 x/z 判断）。

### `static void render(PoseStack matrices, MultiBufferSource vertexConsumers, Player player)`
- 作用：没拿工具则返回；对 `ClientData.STATIONS` 中每个车站的每个矩形（`station.getRectangles()`，方块坐标、终点 +1 包含整格）：玩家远离则跳过；`station.getHeight(rectangle)` 为 null（不限高度）时在 `floor(玩家 y) + 0.05` 画轮廓；否则在 `y1 = 下限`、`y2 = 上限 + 1` 各画轮廓并连四条竖线。

### `private static void outline(PoseStack, MultiBufferSource, double x1, double z1, double x2, double z2, double y, int r, int g, int b)`
- 在高度 `y` 画矩形四边（`IDrawing.drawLineWorld`）。
