# TimeGenerator.java

接口：描述"列车以最快可能速度沿路径行驶时的时间"如何计算。因为加速度、最高速度等列车设置在宿主（游戏侧 `mtr.data`）中，组件只定义接口，由宿主实现。调用方见游戏侧 `mtr.data.PathTiming`、`RoutePaths`、`Siding`、`Depot`。

## 接口 `TimeGenerator<T extends PlanTrack>`

### `StopTimes calculateStopTimes(List<T> path)`
- 作用：计算整条路径的停站时间信息。
- 参数：`path` 轨道序列。
- 返回值：`StopTimes`（见 `StopTimes.java.md`）。
- 主要调用者：`common/src/main/java/mtr/data/PathTiming.java:26`、`mtr/data/RoutePaths.java:1160`、`mtr/data/Siding.java:241`。

### `void generateTimeSegments(List<T> path, List<TimeSegment> timeSegments, StopTimes stopTimes, int joinIndex, int lapEndIndex)`
- 作用：沿路径生成时间段（`TimeSegment`），写入 `timeSegments`。
- 参数：
  - `path` 轨道序列；`timeSegments` 输出列表；`stopTimes` 上一步得到的停站时间；
  - `joinIndex`：列车在环线中途加入时的第一段轨道下标，否则 0；
  - `lapEndIndex`：一圈结束处的轨道下标，否则 0。
- 返回值：无；副作用为修改 `timeSegments`（先清空还是追加取决于实现，未确定）。
- 主要调用者：`common/src/main/java/mtr/data/PathTiming.java:28`、`mtr/data/RoutePaths.java:1164`、`mtr/data/Depot.java:3873`。
