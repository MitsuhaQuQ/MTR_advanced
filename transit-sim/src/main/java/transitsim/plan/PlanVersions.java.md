# PlanVersions.java

路线在其时刻表某一修订版本（revision）时的固定副本：时刻表、例外或路线停站被编辑时，正在跑某班的列车仍按开始时的计划跑完，到下一班再切换（S3d，修复 R04，编号见 `MTR-设计与架构审查评估-2026-11-03.md`）。跑同一修订的列车共享一份副本；没有列车再持有的副本会被回收（`WeakHashMap`）。由 `transitsim.sim.TrainService:2396`–`2397` 调用 `fix`，`StopTimes:302` 用 `keyOf` 判断缓存是否失效。

## 类 `PlanVersions`（final，全部静态）

### 静态字段
- `ROUTES`：`WeakHashMap<宿主路线对象, FixedRoute>`，每条路线最新的副本。
- `TIMETABLES`：`WeakHashMap<时刻表, 副本>`。

### `private PlanVersions()`
禁止实例化。

### `public static synchronized PlanRoute fix(PlanRoute route)`
- 作用：返回路线当前修订的副本；已是 `FixedRoute` 时原样返回；已缓存且修订号相同、`getTimingBaseline()` 为同一对象时复用；否则新建并缓存。
- 主要调用者：本类 `fix(List)`。

### `public static synchronized RouteTimetable fix(RouteTimetable timetable)`
- 作用：返回时刻表当前修订的副本（`timetable.copy()`），修订号未变时复用。
- 主要调用者：`transitsim/sim/TrainService.java:2397`。

### `public static List<PlanRoute> fix(List<? extends PlanRoute> routes)`
- 作用：逐个 `fix`，返回带修订 key 的不可修改列表 `FixedRoutes`。
- 主要调用者：`transitsim/sim/TrainService.java:2396`。

### `public static long keyOf(List<? extends PlanRoute> routes)`
- 作用：一串路线的修订 key。`FixedRoutes` 直接返回预先算好的 key（免得每次停站查询扫描整条运用）；否则按 `key = key*31 + routeId + revision; key = key*31 + identityHashCode(timingBaseline)` 累积。
- 主要调用者：`transitsim/plan/StopTimes.java:302`。

## 内部类 `FixedRoutes`（private static final，extends `AbstractList<PlanRoute>`, implements `RandomAccess`）
- 字段 `routes`（不可修改）、`key`（构造时 `keyOf(routes)`）。
- `size()`、`get(int)` 委托 `routes`。

## 内部类 `FixedRoute`（private static，implements `PlanRoute`）
构造时复制宿主路线的一切：`timingBaseline` 深拷贝（同时记录源对象 `sourceTimingBaseline` 用于判断是否需要重新固定）、`terminalPlatforms`、`startPlatforms`、`routeId`、`timetable.copy()` 及其 `revision`、每个停站（`FixedStop`，按表数复制）、`isLoop`、`transportModeId`。各 getter 返回这些副本（`getStops()` 返回不可修改视图）。

## 内部类 `FixedStop`（private static，implements `PlanStop`）
- 构造 `FixedStop(PlanStop stop, int tableCount)`：复制站台 ID、是否通过，以及每个表的到达、出发、待避设置。
- `getArrival(table)`/`getDeparture(table)`：表号越界返回 -1；`getOvertake(table)` 越界返回 false。
