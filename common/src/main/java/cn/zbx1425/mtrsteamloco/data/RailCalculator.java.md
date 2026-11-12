# RailCalculator.java（ANTE）

ANTE 的平面轨道几何计算：给定两端节点位置和切线方向，求由"圆弧 + 直线"或"直线 + 圆弧"（或单段圆弧、两段圆弧）组成的轨道，结果用 MTR 的轨道段参数（`h, k, r, tStart, tEnd, reverseT, isStraight`，与 `mtr.data.Rail` 的两段曲线参数一致）表示。纯数学，不依赖游戏状态。当前生产代码中没有调用（`RailMixin` 只 import 了它，未使用；本分支 `52c2a9ea` 起轨道统一使用 MTR 自己的形状），只有测试 `common/src/test/java/cn/zbx1425/mtrsteamloco/data/RailCalculatorTest.java:11`、`:12` 使用。本分支改动：`calculate` 拒绝半径小于 2 格的曲线；节点在对方轴线上、或切线不能向前到达交点时返回 null（交给 MTR 已算好的双圆弧/S 曲线）。

## 类 `RailCalculator`

### 常量
- `PRECISION = 1e-3`：几何比较容差。
- `MIN_TANGENT_LENGTH = 1e-2`：端点到切线交点的最小距离（格），更近视为在对方轴线上。
- `MIN_RADIUS = 2`：可通行的最小曲线半径（格）。

## 内部类 `Vec2`（不可变二维向量，x/z 平面）
- 字段 `x`、`z`。
- `add`、`sub`、`scale`：向量运算。
- `rotateDeg(double)` / `rotateRad(double)`：逆时针（按 x→z 方向）旋转。
- `length()`、`distance(Vec2)`、`radian()`（`atan2(z, x)`）、`degree()`、`sin()`、`cos()`。
- `normalize()`：长度小于 `PRECISION` 时返回零向量。
- `toString()`：`Vec2(x, z)` 两位小数。

## 内部类 `Line`（直线 `A·x + B·z + C = 0`）
- 构造 `Line(Vec2 p1, Vec2 p2)`：过两点。
- `intersection(Line)`：交点；平行（行列式小于容差）返回 null。
- `parallel(Line)`：是否平行。
- `distance(Vec2)`：点到直线距离。
- `perpendicular(Vec2 p)`：过 `p` 的垂线。
- `direction()`：单位方向 `(B, −A)`。
- `equals(Object)`：三组交叉乘积都在容差内即视为同一直线。
- `toString()`。

## 内部类 `Circle`
- 字段 `center`、`radius`。
- `List<Vec2> intersections(Line line)`：与直线的交点（0、1 个切点或 2 个）。

## 内部类 `Section`（一段 MTR 轨道参数）
- 字段 `h, k, r, tStart, tEnd, reverseT, isStraight`：圆弧时 `(h, k)` 为圆心、`r` 半径、`t` 为弧长参数（角度 × 半径）；直线时 `h, k` 为单位方向分量、`r` 为截距参数（两种形式，见 `Segment`）。
- `Section()`：空段（全 0，直线）。
- `getLength()`：`|tEnd − tStart|`。
- `isValid()`：五个数值参数都有限。

## 接口 `Shape`
- `Section toSection()`。

## 内部类 `Arc`（implements `Shape`）
- 构造 `Arc(Vec2 center, Vec2 start, Vec2 end)`：半径取圆心到起点距离。
- `toSection()`：起终点相对圆心的角度差规范到 [−π, π]，`tStart = θ起 × r`，`tEnd = tStart + Δθ × r`，`reverseT = Δθ < 0`。

## 内部类 `Segment`（implements `Shape`）
- 构造 `Segment(Vec2 start, Vec2 end)`。
- `toSection()`：长度小于容差返回 NaN 参数（无效）；单位方向 `(h, k)`；若 `|h|` 与 `|k|` 都 ≥ 0.5 用形式 1（`x = h·T, z = k·T + h·r`），否则形式 2（`x = h·T + k·r, z = k·T + h·r`），据此解出 `r, tStart, tEnd`；`reverseT = tStart > tEnd`。

## 内部类 `Group`
- 字段 `first`、`second`：两段；`EMPTY` 为两个空段。

### `static void main(String[] args)`
- 手工试算示例，打印结果对象。

### `static Group calculate(double startX, double startZ, double endX, double endZ, double startAngle, double endAngle)`
- 作用：调用 `_calculate`；任一段为半径小于 `MIN_RADIUS` 的圆弧则返回 null。
- 参数：两端坐标（格）；`startAngle`/`endAngle` 两端切线方向（弧度，`_calculate` 用 `rotateRad`）。注意：`main` 中传的是度数值，与之不一致。
- 返回：两段参数，或 null（交给 MTR 已算好的曲线）。

### `private static boolean isTooTight(Section section)`
- 圆弧且半径 < 2 时为真。

### `static Group segment(double startX, double startZ, double endX, double endZ)`
- 一段直线加一个空段。

### `private static Group _calculate(double startX, double startZ, double endX, double endZ, double startAngle, double endAngle)`
- 作用：设起点 S、终点 E 及各自切线：
  - 两切线平行：共线则一段直线；否则构造两段圆弧（在 SE 的 1/4 与 3/4 处取垂线与两端法线求两个圆心，在中点 M 相接）；两端法线重合或求不到圆心返回 null；
  - 不平行：求切线交点 M；任一端离 M 太近返回 null；任一端切线不是"向前"指向 M 返回 null（需要 S 曲线）；比较 |ME| 与 |MS|：|ME| 更长则"先弧后直"（在 E 侧切线上取与 S 等距的 F，圆弧 S→F 后直线 F→E），更短则"先直后弧"，相等则一段圆弧。
- 返回：`Group` 或 null。

### `static Double calculateMaxRadiusAngle(double startX, double startZ, double endX, double endZ, double startAngle)`
- 作用：给定起点方向，求终点处使曲线为单段最大半径圆弧的方向角（度）：起点与终点共线时返回起点方向（度）；否则求圆心 D（起点法线与 SE 中垂线的交点），终点方向取 DE 的垂线方向；再用 `_calculate` 验证可行，不可行返回 null。代码中没有调用者。
