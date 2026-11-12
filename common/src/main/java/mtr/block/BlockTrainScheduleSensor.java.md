# BlockTrainScheduleSensor.java

时刻表传感器方块（服务端逻辑）：列车按时刻表将要到达附近站台时输出红石信号。原版 MTR 行为（这里称 `LEGACY`）：找到 4 格内的站台，取最早一班（经线路过滤）预计到达时间，剩余秒数 ≤ 设定值时触发。本分支新增：可选的车站触发方式（`StationCall.Trigger`，如到站前若干秒，由 `RailwayData.getStationCalls(pos)` 提供的停站调用判断）；按列车种类过滤（所有、只停站、只通过，2026-11-12）；同一列车同一到达只触发一次（预计到达时间会随运行变动）。注册于 `common/src/main/java/mtr/Blocks.java:209`；测试线网 `Lines.placeSensors` 放置并监视它（`testkit/src/main/java/mtm/testkit/serverdriver/Lines.java:344`）；设置界面 `TrainScheduleSensorScreen`。

## 类 `BlockTrainScheduleSensor`（extends `BlockTrainPoweredSensorBase`）

### 构造函数 / `createBlockEntity` / `getType`
- 方块实体 `TileEntityTrainScheduleSensor`，类型 `TRAIN_SCHEDULE_SENSOR_TILE_ENTITY`。

### `<T> void tick(Level world, BlockPos pos, T blockEntity)`
- 转调 `TileEntityTrainScheduleSensor.tick`。

### `void createBlockStateDefinition(...)`
- 只有 `POWERED`（来自父类，整数，激活时 > 1）。

## 内部类 `TileEntityTrainScheduleSensor`（extends `TileEntityTrainSensorBase`，implements `TickableMapper`）

### 字段与常量
- `seconds`（默认 10）：提前秒数。
- `trigger`（`StationCall.Trigger`，默认 `LEGACY`）。
- `lastTrainId`、`lastArrivalMillis`：上次触发的列车与到达时间。
- `SAME_ARRIVAL_MILLIS = 30000`：同一列车到达时间相差 30 秒内视为同一次到达。
- `trainKind`：`KIND_ALL = 0`、`KIND_STOPPING = 1`、`KIND_PASSING = 2`；旧存档没有该键，即全部列车。

### NBT 键
- `seconds`、`train_kind`（读时夹到 0–2）、`station_trigger`（触发方式枚举名，`StationCall.Trigger.read` 解析）。

### `void tick()`
- `TickableMapper` 入口，转调静态 `tick`。

### `void readCompoundTag(CompoundTag)` / `void writeCompoundTag(CompoundTag)`
- 读写上述键及父类数据。

### `void setData(Set<Long> filterRouteIds, boolean stoppedOnly, boolean movingOnly, int number, String... strings)`
- `number` 为秒数；`strings[1]` 触发方式名；`strings[2]` 列车种类数字（非数字忽略）；再设置父类过滤。调用者：传感器设置包处理；测试 `Lines.java:344`。

### `private boolean isNewTrain(ScheduleEntry scheduleEntry)`
- 列车 id 相同且到达时间相差 < 30 秒时返回 false；否则记下并返回 true。

### `int getSeconds()` / `StationCall.Trigger getTrigger()` / `int getTrainKind()`

### `boolean matchesKind(boolean passing)` / `static boolean matchesKind(int trainKind, boolean passing)`
- 全部种类总是匹配；否则"只通过"匹配通过列车、"只停站"匹配停站列车。

### `static <T> void tick(Level world, BlockPos pos, T blockEntity)`
- 作用（服务端）：已激活且已安排方块刻（信号还在持续）时不处理；非 `LEGACY` 触发：在该位置的停站调用中，只要有一个满足线路过滤、种类匹配且 `call.trigger(pos, trigger, seconds, now)` 为真就 `power`；`LEGACY`：找 4 格内站台（`getClosePlatformId`），取该站台全部时刻条目中满足过滤与种类的最早一条，剩余毫秒 ≥ 0 且剩余秒 ≤ `seconds` 且是新列车时 `power`。
