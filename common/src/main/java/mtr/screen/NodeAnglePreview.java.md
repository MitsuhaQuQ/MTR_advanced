# NodeAnglePreview.java（本分支新增）

客户端界面组件：节点角度界面（刷子）中的俯视小地图，北在上：灰色为节点现有的轨道，黄色为按输入角度转动后的轨道（不能转时为红色），蓝色为节点的方向线，红色线指向东（角度起点），红色弧从 0° 画到输入角度并带箭头表示转向。在上面拖动可转动节点（由 `NodeAngleScreen` 处理）。使用者：`common/src/main/java/mtr/screen/NodeAngleScreen.java:47`、`:86`。

## 类 `NodeAnglePreview`（implements `IGui`）

### 字段与常量
- `pos`：节点位置；`rails`：`ClientData.RAILS`。
- `x`、`y`、`size`：显示区域（像素，正方形）；`blocksShown`：从中心到边缘显示的方块数。
- `previewedValue`、`rotatedNode`：上次预览的输入文本及其转动结果（`Rail.RotatedNode`，含新轨道与错误键）。
- 颜色：背景半透明黑、边框灰、旧轨道灰、新轨道黄、不能转为红、刻度红、节点白、节点方向线蓝（与黄色轨道区分）。
- `MIN_BLOCKS_SHOWN = 8`、`DIAL_RADIUS_RATIO = 0.18`（弧半径 = 尺寸 × 0.18）。

### 构造函数 `NodeAnglePreview(BlockPos pos)`
- 显示范围 = max(8, 最远相连节点的水平距离) × 1.25（曲线可能凸出端点之外）。

### `void setBounds(int x, int y, int size)` / `int getX()` / `int getY()` / `int getSize()`
- 设置/读取显示区域。

### `boolean isInside(double mouseX, double mouseY)`
- 鼠标是否在区域内。

### `float getAngle(double mouseX, double mouseY, boolean snap)`
- 返回：从中心指向鼠标的角度（度，屏幕坐标系：东为 0、顺时针为正，即与界面中输入的角度相同），`snap` 时取 22.5° 的倍数，否则 0.5°，规整到 −180…180。

### `String render(PoseStack, Font, String typedValue)`
- 作用：画背景与边框；输入可解析为数字时（输入变化才重算）用 `Rail.rotateNode(rails, pos, RailAngle.fromAngle(angle))` 求转动后的轨道；画旧轨道、新轨道、东向线、方向线（双向）、弧与箭头、中心点、"北""东""0°"标签。
- 返回：不能转动的原因翻译键（`rotatedNode.error`），可以或未输入时为 null。

### `private void drawRail(PoseStack, Rail rail, int color, float centerX, float centerY, float scale)`
- 沿轨道约每像素取一点（最多 4096 点）描点。

### `private void drawArc(PoseStack, float centerX, float centerY, float radius, float angle)`
- 从 0° 到 `angle` 画弧；|angle| ≥ 10° 时在末端画箭头。

### `private void drawLine(…)` / `private void plot(PoseStack, float pointX, float pointY, int color)`
- 逐像素画线；只在边框以内描点。
