# SpeedLimits.java

一个限速标签代表的两种速度（优化文档 5.6、5.15）：列车绝不能超过的物理限速，以及低于它、用于编制时刻表的计划速度，使晚点列车有余地赶点。

标签 ≥ 60 km/h：标签值作为计划速度，物理限速再加 5 km/h；标签 < 60 km/h：标签值作为物理限速，计划速度低 5 km/h。同一规则适用于轨道、线路限速和车辆。

调用者：`TrackSections.java:149`、`:156`（区段速度）、`RunTrack.java:97`、`TickRunner.java:1487`、`SignalOverlay`，游戏侧 `SpeedRules.java:61`、`:85`、`:92`。

## 类 SpeedLimits（final，不可实例化）

### 常量
- `BOUNDARY_KMH = 60`：从此值起标签即计划速度。
- `MARGIN_KMH = 5`：物理限速与计划速度之差。

### `static int physicalKmh(int labelKmh)`
标签 ≥ 60 返回标签 + 5，否则返回标签（km/h）。调用者：`TrackSections.java:149`、本类 `physicalBlocksPerTick`。

### `static int planKmh(int labelKmh)`
标签 ≥ 60 返回标签，否则 max(1, 标签 − 5)（km/h，至少 1）。调用者：`TrackSections.java:156`、本类 `planBlocksPerTick`。

### `static float physicalBlocksPerTick(float blocksPerTick)`
- 作用：以方块/tick 给出的标签（轨道类型和设置中的保存形式）→ 物理限速（方块/tick）。≤ 0 表示无，原样返回。先 `toKmh` 取整到 km/h 再换算。
- 调用者：`SpeedRules.java:85`、`RunTrack.java:97`、`TickRunner.java:1487`。

### `static float planBlocksPerTick(float blocksPerTick)`
同上，返回计划速度。调用者：`SpeedRules.java:92`、`VehicleMaxSpeedTest.java:69`。

### `static int toKmh(float blocksPerTick)`
方块/tick → km/h（×72，四舍五入）；≤ 0 返回 0。调用者：本类。

### `static float toBlocksPerTick(int kmh)`
km/h → 方块/tick（÷3.6÷20）。调用者：`SpeedRules.java:61`、`SignalOverlay.java:141`。
