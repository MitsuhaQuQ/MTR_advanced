# TrackView.java

单列车眼中的线路：其路径上的轨道按顺序排列，每条带有运动所依赖的属性（长度、限速、是否停站、是否折返、停车偏移）。宿主用自己的路径构造，运动规则只读。同时缓存基于本视图算出的 `SpeedCurves`。构造者：游戏侧 `TrackViews.java:65`、组件 `RunTrack.java:97`、测试 `MotionFixture.java:480`。读者：`SpeedCurves`。

## 类 TrackView（final）

### 字段
- `curves`（private LRU `LinkedHashMap`，最多 16 项）：键 (起点, 车长, 加速度, 减速度) → `SpeedCurves`。
- `lengths`（double[]）：每条轨道长度（方块）。
- `limits`（double[]）：每条轨道限速（方块/tick），−1 表示保持驶入时的速度（不能加速的轨道）。
- `stops`（boolean[]）：该轨道末端是否停站。
- `reversals`（boolean[]）：该轨道之后列车是否折返。
- `stopOffsets`（double[]）：停站轨道上车头距轨道末端的后退量（优化第 4 轮），0 = 停在末端。
- `minSpeed`（public double）：最低巡航速度，也作为第一条设定限速的轨道之前的限速。

### `synchronized SpeedCurves curves(double start, double length, double acceleration, double deceleration, Supplier<SpeedCurves> create)`（包内可见）
- 作用：按四个参数查缓存，缺失时调用 `create` 生成并存入。
- 副作用：写 `curves`。调用者：`SpeedCurves.of`（`SpeedCurves.java:38`）。

### 构造函数 `TrackView(double[] lengths, double[] limits, boolean[] stops, boolean[] reversals, double minSpeed)`
停车偏移全为 0 的便捷形式。调用者：`SpeedCurvesTest.java:22`、`MotionFixture.java:480`。

### 构造函数 `TrackView(double[] lengths, double[] limits, boolean[] stops, boolean[] reversals, double[] stopOffsets, double minSpeed)`
- 作用：拷贝所有数组保存。
- 副作用：任一数组长度与 `lengths` 不同则抛 `IllegalArgumentException`。
- 调用者：`TrackViews.java:65`、`RunTrack.java:97`。

### 访问方法（均无副作用）
- `int size()`：轨道条数。调用者：`SpeedCurves.java:43`、`TrackViews.java:50`。
- `double getLength(int rail)`：长度。调用者：`SpeedCurves.java:49`。
- `double getLimit(int rail)`：限速，−1 表示沿用前一条。调用者：`SpeedCurves.java:51`。
- `boolean isStop(int rail)`：末端是否停站。调用者：`SpeedCurves.java:60`。
- `double getStopOffset(int rail)`：停车偏移。调用者：`SpeedCurves.java:63`、`RunTrack.java:94`。
- `boolean reversesAfter(int rail)`：之后是否折返。调用者：`SpeedCurves.java:66`。
