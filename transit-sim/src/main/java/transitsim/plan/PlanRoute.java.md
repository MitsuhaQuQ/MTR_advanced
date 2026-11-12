# PlanRoute.java

接口：时刻表规则眼中的"一条路线"。宿主的路线实现它——游戏侧 `mtr.data.Route`（`Route.java:35`）；组件内 `PlanVersions` 的固定快照也实现它（`PlanVersions.java:136` 附近）。时刻表、运用生成与冲突检查通过它读取路线的站序、时刻表和折返/起点偏好。

## 接口 `PlanRoute`

### `long getRouteId()`
路线 ID。

### `RouteTimetable getTimetable()`
路线的时刻表（见 `RouteTimetable.java.md`）。

### `default long[][] getTimingBaseline()`
- 作用：只与路线本身有关的各表计时对（到达/出发），与网页编辑器共享。
- 返回值：默认 null 表示不可用。格式同 `TimetableTimes.pack` 的输出。
- 主要调用者：`transitsim/plan/PlanVersions.java:33`、`PlanVersions.java:66`；实现见 `common/src/main/java/mtr/data/Route.java:78`。

### `List<? extends PlanStop> getStops()`
按顺序排列的路线停站。

### `boolean isLoop()`
最后一站是否回到第一站（列车绕圈运行的环线）。

### `int getTransportModeId()`
运行该路线的交通工具类型；只在路线之间比较是否相同。

### `default List<Long> getTerminalPlatforms()`
- 作用：末站所在车站中，本路线列车还可以折返的其他站台（站前折返），使下一条路线从那里出发；按尝试顺序排列，不包含本路线自己的末站站台。
- 返回值：默认空列表。
- 主要调用者：`transitsim/plan/PlanVersions.java:96`；实现 `common/src/main/java/mtr/data/Route.java:825`。

### `default List<Long> getStartPlatforms()`
- 作用：首站所在车站中，本路线更希望从哪些站台发车，最优在前（种别优先）；空表示无偏好。
- 主要调用者：`transitsim/plan/PlanVersions.java:97`；实现 `common/src/main/java/mtr/data/Route.java:830`。

### `default long getLastPlatformId()`
- 返回值：最后一站的站台 ID；没有停站时为 0。
- 主要调用者：`common/src/main/java/mtr/client/ClientCache.java:228`、`cn/zbx1425/mtrsteamloco/scripting/train/TrainWrapper.java:91`。
