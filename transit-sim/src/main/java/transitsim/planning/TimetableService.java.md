# TimetableService.java

规划器输入：一条路线的一个时刻表班次。各站偏移从首站计划到达算起。另有 `Stop`（班次的一站）和 `Through`（由多个班次组成的直通行程）。由 `DutyGenerator` 从路线时刻表构造（`DutyGenerator.java:599`、`:627`），交给 `DutyPlanner.plan`（`DutyPlanner.java:69`）展开为行程。

## 类 `TimetableService`（final）

### 字段
- `id`：班次 ID。
- `routeId`：路线。
- `daysMask`：运行的星期位掩码（最低位周一，见 `OperationTime`）。
- `firstArrivalMillis`：首站计划到达（周内或一天内的毫秒，取决于调用方；`DutyGenerator` 传入的是一天中的时刻 `startMillis`，未确定是否始终如此）。
- `blank`：是否为空白班次（注释：直通行程可以包含为此保留的空白班次）。
- `stops`：不可修改的停站列表。
- `startDepots`：运行图上设置的、必须新从中出库的车厂（停车线）集合；null 表示任意列车（已在外的或最近车厂的）。由 `DutyGenerator.java:264` 设置。
- `endsDuty`：运行图上设置该班之后列车回库，而不是继续跑。
- `returnDepots`：对于之后回库的班次，运行图上设置的可回去的车厂停车线；null 表示其池中任意；空集合表示该车厂在此没有规划停车线，因此没有车跑它。

### `public TimetableService(long id, long routeId, int daysMask, long firstArrivalMillis, boolean blank, List<Stop> stops)`
- 作用：赋值并复制停站为不可修改列表。
- 副作用：`stops` 为空时抛 `IllegalArgumentException("A service needs at least one stop")`。
- 主要调用者：`transitsim/planning/DutyGenerator.java:627`。

### `public boolean runsOn(int day)`
`daysMask` 中该星期位是否为 1（`day` 0 = 周一）。

## 内部类 `Stop`（public static final）
- `platformId`：站台。
- `arrivalOffsetMillis`、`departureOffsetMillis`：相对首站计划到达的毫秒。
- `pass`：通过不停。
- 构造 `Stop(platformId, arrivalOffsetMillis, departureOffsetMillis, pass)`。主要调用者：`transitsim/planning/DutyGenerator.java:599`。

## 内部类 `Through`（public static final）
由几个班次组成的直通行程，其中可以有为此保留的空白班次。
- `id`、`daysMask`、`serviceIds`（不可修改）。
- 构造 `Through(long id, int daysMask, List<Long> serviceIds)`：少于两个班次时抛 `IllegalArgumentException("A through trip needs at least two services")`。
- 使用者：`transitsim/planning/DutyPlanner.java:69`、`:116`（main 代码中未 grep 到构造调用，未确定是否仅测试构造）。
