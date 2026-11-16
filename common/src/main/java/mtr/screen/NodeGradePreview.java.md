# NodeGradePreview.java（0.87.0 新增）

刷子节点界面纵向选项卡右侧的侧视图：节点朝向（蓝线方向）朝右，横向为沿轨道的距离，竖向为高度并按需要放大（`getStretch`，使高差占满图的一部分，最少 1 倍）。灰线为轨道现状，黄线为按输入的节点坡度和半径重算后的纵断面（半径放不下时为红色），蓝线为过节点的坡度线，深灰水平线为节点高度。在图上拖动设坡度。被 `NodeAngleScreen` 使用。

## 类 `NodeGradePreview`

- 构造 `(BlockPos pos, RailAngle facing)`：`facing` 为节点方块的朝向；每条相连轨道记为一个 `Side`（离开方向与朝向点积 ≥ 0 为前侧，画在右边）；显示范围取最长轨道和最大高差。
- `setBounds`、`getX`、`getY`、`getSize`、`isInside`：位置与命中。
- `getGradePerMille(mouseX, mouseY)`：节点到鼠标连线的坡度（‰，0.5 一档）。
- `render(matrices, font, grade, radius)`：画图；返回第一条放不下给定半径的轨道（无则 null），图尺寸为 0 时也计算。
- 内部类 `Side`：`posEnd`；`getProfile(grade, radius)`：把节点这一端换成输入的坡度和半径、另一端保持轨道现有的坡度和半径后的 `VerticalProfile`（旧式和缆车轨道为 null）；`getFittingRadius`：实际放得下的半径。
