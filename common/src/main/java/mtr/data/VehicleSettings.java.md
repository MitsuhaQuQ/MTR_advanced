# VehicleSettings.java

车辆设置（R1）：车厂中一个停车线池（同一种车辆）的列车如何运行，对整个池设置一次，使一列车无论到哪条停车线都一样。未设置时取池中第一条停车线的设置。不可变值对象，由 `Depot`（存储与包读写，`Depot.java:390/473/623`）、`Siding`（运行时取加减速和最高速）、`EditorActions`（网页编辑修改）使用。组件侧对应 `RunSegment` 等读取的加减速参数。

## final class VehicleSettings

### 字段
- `acceleration`（float）：加速度，四舍五入到 3 位小数并限制在 `[Train.MIN_ACCELERATION, Train.MAX_ACCELERATION]`；单位同 MTR 的加速度常数（方块/tick²，未确定）。
- `deceleration`（float）：减速度；0 表示与加速度相同；≤0 或 NaN 存为 0，否则同样取整并限幅。
- `manual`（boolean）：是否允许手动驾驶。
- `maxManualSpeed`（int）：手动驾驶最高速档位（≥0；具体单位见 MTR 原 `Siding` 的同名设置，未确定）。
- `manualToAutomaticTime`（int）：手动转自动的时间（≥0，单位未确定）。
- `maxSpeed`（int）：车辆最高速度 km/h，0 表示不限；限制在 `[0, MAX_SPEED]`。
- `MAX_SPEED = 300`：车辆最高速可设的上限（km/h），与最快的轨道相同。

### `public VehicleSettings(float acceleration, float deceleration, boolean manual, int maxManualSpeed, int manualToAutomaticTime)`
- 等价第二个构造函数且 `maxSpeed = 0`。

### `public VehicleSettings(float acceleration, float deceleration, boolean manual, int maxManualSpeed, int manualToAutomaticTime, int maxSpeed)`
- 作用：构造并规范化各字段（见上）。
- 主要调用者：`EditorActions.java:178`、`Depot.java:390`、`Siding.java:973`。

### `public float getMaxBlocksPerTick()`
- 返回：最高速换算为方块/tick，0 表示不限。
- 主要调用者：未在仓库中找到直接调用，未确定。

### `public static float toBlocksPerTick(int kilometresPerHour)`
- 返回：`kmh > 0 ? kmh / 3.6 / 20 : 0`（方块/tick）。
- 主要调用者：`Siding.java:1721`、`Siding.java:1885`、`Siding.java:2166`。

### `public float getDeceleration()`
- 返回：实际减速度：`deceleration <= 0` 时返回 `acceleration`。
- 主要调用者：`Siding.java:1017`、`SidingScreen.java:140`。

### `public static VehicleSettings read(FriendlyByteBuf packet)` / `public void write(FriendlyByteBuf packet)`
- 包字段顺序：`float acceleration, float deceleration, boolean manual, int maxManualSpeed, int manualToAutomaticTime, int maxSpeed`。
- 主要调用者：`Depot.java:473`、`Depot.java:2477`（read）；`Depot.java:623`（write）。

### `equals(Object)` / `hashCode()`
- 按全部 6 个字段比较/求哈希。用于判断设置是否变化（例如 `EditorData.java:794`、`Depot.java:796`）。
