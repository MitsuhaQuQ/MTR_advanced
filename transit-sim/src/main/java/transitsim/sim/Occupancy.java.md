# Occupancy.java

哪些轨道被占用、被什么占用：由运动规则移动的列车、手动驾驶的列车、列车预订的信号闭塞区与折返轨。宿主边运行边写入（解耦文档 3.7）；本类不读取世界。轨道是宿主的轨道键，占用者是宿主的列车 id。

保留两个 tick：上一个和当前的，所以不论另一列车本 tick 是否已移动，列车都能看到被占的轨道。

在整体中的位置：游戏侧 `RailwayData`（`RailwayData.java:275` 持有、`:570`/`:578` `nextTick`、`:696` `isOccupiedNow`、`:277` `getTicks`）、`TrainServer.java:918`（`occupy`）、`TrainServer.java:591`/`:625`/`:1844`（静态 `isTakenByOther`）；组件 `TickRunner.java:1251` 采用相同语义。

## 类 Occupancy（final）

### 字段
- `ticks`（List<Map<UUID, Long>>，长度 2）：[上一 tick, 当前 tick]，各为轨道 → 占用者。

### 构造函数 `Occupancy()`
建两个空表。调用者：`RailwayData.java:275`。

### `void nextTick()`
开始新 tick：当前变为上一个，新的当前 tick 为空。调用者：`RailwayData.java:570`。

### `void occupy(UUID rail, long occupant)`
本 tick 占用轨道，若已被别的占用则不覆盖（先到者保留）。调用者：`TrainServer.java:918`。

### `boolean isOccupiedNow(UUID rail)`
本 tick 是否有任何东西占用该轨道。调用者：`RailwayData.java:696`。

### `boolean isTakenByOther(UUID rail, long occupant)`
本 tick 或上一 tick 是否被该占用者以外的东西占用。调用者：测试。

### `List<Map<UUID, Long>> getTicks()`
按宿主一直以来传递的形式返回两个 tick（上一个、当前）；修改会直接作用于本对象。调用者：`RailwayData.java:277`。

### `static boolean isTakenByOther(List<Map<UUID, Long>> ticks, UUID rail, long occupant)`
只有两个 tick 表的宿主代码使用的静态版本。调用者：`TrainServer.java:591`、`TrainServer.java:625`、`TrainServer.java:1844`。
