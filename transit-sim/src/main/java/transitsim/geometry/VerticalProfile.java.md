# VerticalProfile.java（0.87.0 新增）

两端有坡度的轨道沿长度的高度（设计文档《MTR-3.x改造记录与寻路方案》9.2 纵曲线）：起点从起点坡度经一段竖曲线过渡到中间的直坡，再经一段竖曲线过渡到终点坡度。竖曲线是抛物线，每格坡度变化 1/半径。轨道太短放不下给定半径时，两端半径按同一比例缩小，直到放得下，`fitsRadius()` 为假。距离按轨道的水平长度计（和 `Rail.getLength` 一致），坡度为升高/水平长度（0.02 即 20‰），所以纵断面怎么变都不影响长度，组件和游戏里用长度的一切都不变。纯 Java，不依赖 Minecraft。被游戏 `mtr.data.Rail`（高度）、`mtr.data.RailGrades`（节点自动坡度）、`mtr.block.BlockNode`（节点设置的范围）、`mtr.screen.NodeAngleScreen`/`NodeGradePreview`（纵向选项卡）使用。测试 `VerticalProfileTest`。

## 类 `VerticalProfile`（final）

### 常量
- `DEFAULT_RADIUS = 500`：竖曲线半径默认值（格）。实际地铁一般 3000 m 以上，在 MC 里过渡太长。
- `MIN_RADIUS = 10`、`MAX_RADIUS = 20000`：半径范围；`MAX_GRADE = 1`：坡度范围 ±1（一格升一格）。

### `public static VerticalProfile of(double length, double yStart, double yEnd, double gradeStart, double gradeEnd, double radiusStart, double radiusEnd)`
- 作用：求中间直坡坡度 G。两段竖曲线长 `a = R1|G − g1|`、`b = R2|G − g2|`，总升高 `G·L − (R1·s(G − g1) + R2·s(G − g2))/2`（`s(x) = x|x|`）。竖曲线放得下（`a + b ≤ L`）的范围里升高随 G 单调增，二分求出。放不下时二分找最大的缩放比例 k（`k = 0` 时就是均匀直坡，总有解）。
- 长度为 0：高度取两端之一。

### `public double getY(double distance)` / `public double getGrade(double distance)`
- 沿轨道某距离的高度 / 坡度，距离夹在 [0, 长度]。

### `getMiddleGrade()`、`getCurveStart()`、`getCurveEnd()`、`getRadiusScale()`、`fitsRadius()`
- 中间直坡坡度；两端竖曲线长度；实际半径 / 给定半径（放得下为 1）。

### `public static double clampRadius(double)` / `public static double clampGrade(double)`
- 夹到范围内；半径非有限值取默认，坡度非有限值取 0。

### `public static double autoNodeGrade(double[] gradesAway, boolean[] forwardSide, boolean anyLevelEnd)`
- 作用：节点没设坡度时由相连轨道算出的坡度（沿节点朝向）：两侧各取轨道平均坡度（升高/长度）的均值，再取两侧均值；只有一侧有轨道时取那一侧。背侧的轨道离开节点是逆着节点朝向，坡度取负。有旧式轨道（两端水平）时为 0，以便衔接。
- 参数：`gradesAway` 每条轨道离开节点方向的平均坡度；`forwardSide` 是否顺着节点朝向离开；`anyLevelEnd` 是否有旧式轨道。
