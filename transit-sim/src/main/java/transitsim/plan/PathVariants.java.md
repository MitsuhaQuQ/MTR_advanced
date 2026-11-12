# PathVariants.java

接口：宿主提供的"按需生成路径变体"能力，例如通过部分站不停（D3）、临时加停（D5）、站内折返（D4）、从车厂出到中途站（D5）。组件的 `TripPath`（如 `withSkips`、出库/折返变体）通过它请宿主复制轨道并计时。游戏侧实现在 `mtr.data.Siding.java:195`（匿名类）和 `Depot.java:1749`；组件内 `transitsim.sim.SimDataset`（约 `SimDataset.java:439`、`:967`）也有实现；`transitsim.sim.TrainService` 调用。

## 接口 `PathVariants<T extends PlanTrack>`

### `T pass(T track)`
- 返回值：一段列车通过不停车的轨道副本，其余属性相同。
- 主要调用者：`transitsim/plan/TripPath.java:281`、`TripPath.java:685`。

### `TimeGenerator<T> getTimeGenerator()`
返回宿主为路径计时的方法（见 `TimeGenerator.java.md`）。

### `default T withStop(T track, int dwellTime, long platformId)`
- 作用（D5）：返回一段在末端停车 `dwellTime` 的轨道副本，作为在 `platformId` 站台的停站，其余相同。
- 参数：`dwellTime` 单位半秒；`platformId` 站台 ID。
- 返回值：默认 null，表示宿主不能生成。
- 主要调用者：`transitsim/plan/TripPath.java:504`、`TripPath.java:513`。

### `default Collection<Long> getTurnTargets(long platformId)`
- 作用（D4）：同一车站中，在此站台的列车可以折返到达的其他站台（路径生成时找到过路的）。
- 返回值：默认空。
- 主要调用者：`transitsim/sim/TrainService.java:806`。

### `default List<T> getTurnLink(long fromPlatformId, long toPlatformId)`
- 作用（D4）：从一个站台绕到同站另一站台的路径，从前者轨道到后者轨道。
- 返回值：默认 null（没有）。

### `default List<T> getTurnReturn(long platformId)`
- 作用（D4）：在该站台折返后空车回停车线的路径。
- 返回值：默认 null。
- 主要调用者：`transitsim/plan/TripPath.java:568`。

### `default List<T> getWayOut(long platformId)`
- 作用（D5）：从停车线出到某站台的路径，该站台是运用中某一段的起点；用于接手运用余下部分或晚发车的列车。
- 返回值：默认 null。
- 主要调用者：`transitsim/plan/TripPath.java:494`。
