# PlanTrack.java

接口：行程计划眼中的"路径上的一段轨道"。宿主的路径条目实现它——游戏侧为 `mtr.path.PathData`（`PathData.java:24`）。`plan` 包里几乎所有路径相关的类（`TripPath`、`PathDistances`、`PathTimes`、`DepotReturn`、`TurnBackPoint`、`AnnouncementStops` 等）都通过此接口读取轨道属性，从而不依赖 Minecraft。

## 接口 `PlanTrack`

### `double getTrackLength()`
轨道长度（单位由宿主约定，游戏中为方块/米，未在此处声明）。

### `int getDwellTime()`
- 返回值：列车在此段轨道末端停车的时长，**单位半秒**；不停车为 0。

### `boolean isPlatformTrack()`
此轨道是否为站台（相对于停车线或普通轨道）。

### `boolean isSidingTrack()`
此轨道是否为车厂的停车线。

### `long getStopId()`
- 返回值：此轨道所属的站台或停车线 ID，不属于任何一个时为 0。

### `default double getStopOffset(double trainLength)`
- 作用（优化第 4 轮"停车点"）：给定车长，列车在此停车时车头距离轨道末端差多少。由站台设置决定（见 `StopPositions`），其它轨道为 0。
- 参数：`trainLength` 列车长度。
- 返回值：默认实现返回 0。
- 主要调用者：`common/src/main/java/mtr/advanced/TrackViews.java:42`、`mtr/data/Depot.java:3883`、`mtr/data/PathTiming.java:60`。

### `int getStopIndex()` / `void setStopIndex(int stopIndex)`
在本路径上此轨道之前已经经过多少个停站。`setStopIndex` 由 `AnnouncementStops.numberDuty` 等写入。

### `boolean isOppositeOf(PlanTrack track)`
- 返回值：另一段轨道是否就是这一段的反方向（列车在此折返）。
- 主要调用者：`transitsim/plan/DepotReturn.java:49`、`transitsim/plan/PathTimes.java:92`、`common/src/main/java/mtr/data/TrainServer.java:1489`。

### `UUID getTrackKey()`
- 返回值：同一段轨道两个方向共用的键。
- 主要调用者：`transitsim/plan/PathTimes.java:93`、`transitsim/plan/TurnBackPoint.java:66`。
