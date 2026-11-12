# Straightener.java

为整条线路的股道示意图，把沿线路路径的轨道"拉直"（设计文档 6.12，D 块）：一个点变为（它离路径最近处沿路径的距离，它偏向侧方的距离——以线路运行方向的左侧为正）。比走廊更远的轨道被略去；轨道在离开走廊处、或最近路径位置跳变处（如折返线路两次经过同一轨道）被切断。

调用者：游戏侧 `RouteLayouts.java:71`（构造）、`:107`、`:110`、`:124`（`project`/`straighten`）；测试 `StraightenerTest`。

## 类 Straightener（final）

### 字段
- `path`（private float[]）：路径点 x,z 交替（拷贝）。
- `distances`（private double[]）：每个路径点沿路径的累计距离。
- `cellSize`（private double）：空间网格边长 = max(halfWidth, 4)。
- `cells`（private Map<Long, List<Integer>>）：网格单元 → 经过它的路径小段下标。
- `length`（public double）：路径总长。

### 构造函数 `Straightener(float[] path, double halfWidth)`
- 作用：拷贝路径，累计距离，把每个小段按包围盒登记到网格。
- 参数：`path` 路径点（x, z, x, z, …）；`halfWidth` 保留轨道的侧向半宽（方块）。
- 调用者：`RouteLayouts.java:71`、`StraightenerTest.java:20`。

### `double[] project(double x, double z, double maxDistance, double hint)`
- 作用：在 `maxDistance` 范围内的网格中找最近的路径小段投影；距离相差 ≤ 0.5 的视为同样近，此时取沿路径距离最接近 `hint` 的（`hint` 为 NaN 时取沿路径距离较小者）。
- 参数：(x, z) 点；`maxDistance` 最大垂直距离；`hint` 同一轨道上前一点的沿路径距离或 NaN（用于路径两次经过同一轨道时选更近的那次）。
- 返回：`{沿路径距离, 侧向偏移（左正）, 到路径的距离}`，超出范围返回 null。副作用：无。
- 调用者：`RouteLayouts.java:107`、`RouteLayouts.java:124`；本类 `straighten`。

### `List<float[]> straighten(float[] line, double halfWidth)`
- 作用：逐点投影一条轨道；点超出走廊，或沿路径位置变化超过 `2 × 两点间距 + halfWidth`（说明到了路径的另一部分）时断开成新的一段。至少两个点的段才保留。
- 参数：`line` 轨道点（x, z, …）；`halfWidth` 侧向保留宽度。
- 返回：各段，每段为 `距离, 偏移, 距离, 偏移, …`。
- 调用者：`RouteLayouts.java:110`、`StraightenerTest.java:23`。

### `private static void addPiece(List<float[]> pieces, List<Float> piece)`
当前段至少 2 个点（4 个数）时转为数组加入结果。调用者：`straighten`。

### `private static boolean isBetter(double[] candidate, double[] best, double hint)`
较近者胜；相差 ≤ 0.5 时比较与 hint 的接近程度，无 hint 时沿路径距离小者胜。调用者：`project`。

### `private double[] projectOnto(int segment, double x, double z)`
点到第 segment 个小段的投影：参数 t 夹到 [0,1]；侧向偏移 = −叉积/段长（x 东 z 南时左侧叉积为负，因此取负使左为正）。返回同 `project`。调用者：`project`。

### `private long cell(double value)` / `private static long key(long x, long z)`
坐标 → 网格编号；两个编号合成 long 键。调用者：构造函数、`project`。
