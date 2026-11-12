# RunTrack.java

仿真列车所运行路径上的一条轨道：计划所需（继承 `PlanTrack`），外加允许速度和所连接的节点，用于无世界运行列车。实现：`SimDataset.DataTrack`（`SimDataset.java:1064`）、测试的 `ScenarioLine`。使用者：`TickRunner`、`PathClaims`、`RunInvariants`、`EventSimulator`、`run.Authority`（泛型上界）；静态方法被 `SimDataset.java:617`、`TickRunner.java:2625`、`:2851`、`:2857` 调用。

## 接口 RunTrack（继承 `PlanTrack`）

### 抽象方法
- `float getMaxSpeed()`：限速标签（方块/tick）。
- `boolean canAccelerate()`：false 表示站台等保持进入速度的轨道（只用于不切分闭塞分区，见 `buildTrackView` 注释）。
- `boolean hasSignal()`：其上的列车是否需要相互隔开（除停车线自身轨道外都是）。
- `boolean isTwoWay()`：列车是否也能反向运行，因此预订时需要方向。
- `long getStartNode()` / `long getEndNode()`：沿路径方向的起止节点。

### 常量（运行方向约定）
- `CONVENTION_WITH = 1`：按线路行车侧规定的方向运行双向轨道（复线，`RunningDirections`）。
- `CONVENTION_AGAINST = -1`：逆该方向运行（反向运行，如经渡线到另一线）。
- `CONVENTION_NONE = 0`：没有自身方向的轨道（单线、折返轨、停车线或未知）。

### `default int getConvention()`
路径在此轨道上与行车侧约定的关系：顺向时同向列车逐分区跟驰；逆向或无方向时一列车一次占用整段（如同单线）。默认 `CONVENTION_NONE`。调用者：`PathClaims.java:206`、`SimDataset.java:1078`（实现）、`RunningSides.java:73`。

### `static TrackView toTrackView(List<? extends RunTrack> path, double minSpeed)`
无车辆最高速度、停在轨道末端的便捷形式。调用者：`ScenarioLine.java:99`。

### `static TrackView toTrackView(List<? extends RunTrack> path, double minSpeed, double vehicleMaxSpeed)`
无车长（停在每条轨道末端）的形式。

### `static TrackView toTrackView(List<? extends RunTrack> path, double minSpeed, double vehicleMaxSpeed, double trainLength)`
- 作用：速度曲线所见的路径，构建方式与游戏用自身路径构建相同。路径为 `IndexedPath` 时按 `("physical-track", minSpeed, vehicleMaxSpeed, trainLength)` 缓存编译结果，否则直接 `buildTrackView`。
- 参数：`minSpeed` 该类车辆最低巡航速度；`vehicleMaxSpeed` 车辆最高速度（方块/tick），0 为无；`trainLength` 车长（站台停车位置依赖它，第 4 轮），NaN 表示停在每条轨道末端。
- 调用者：`SimDataset.java:617`、`TickRunner.java:2625`。

### `static TrackView buildTrackView(List<? extends RunTrack> path, double minSpeed, double vehicleMaxSpeed, double trainLength)`
- 作用：每条轨道：长度；限速 = `runSpeed`（第 3b 轮起站台、停车线、折返轨也按自身限速）；有停站时间即为停站；与下一条反向则折返；停站且车长非 NaN 时停车偏移 = `getStopOffset(trainLength)`。最低速度 = min(minSpeed, 车辆物理最高速度)。
- 调用者：`toTrackView`。

### `static float runSpeed(RunTrack track, double vehicleMaxSpeed)`
列车可在该轨道上运行的速度：轨道标签与车辆最高速度各自的物理限速（`SpeedLimits.physicalBlocksPerTick`，优化文档 5.6）中较低者。调用者：`TickRunner.java:2857`；`buildTrackView`。
（源码中其上方有一段孤立 javadoc "车辆最高速度下的轨道限速，或 0"，与本方法紧邻。）

### `static float limit(float railSpeed, double vehicleMaxSpeed)`
车辆最高速度 > 0 时取两者较小，否则轨道速度。调用者：`TickRunner.java:2851`、`TickRunner.java:2904`。
