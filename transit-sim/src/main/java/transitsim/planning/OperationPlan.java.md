# OperationPlan.java

规划器 `DutyPlanner` 的输出：一周的计划。计划按周精确重复，所以任何时刻的计划只需看周偏移（见 `OperationTime`）。包含各运用（列车从出库到回库的一串行程）、问题列表和高峰列车数，以及嵌套的数据类：`Issue`/`IssueType`、`TripStop`、`Trip`、`Duty`、`Phase`/`PhaseType`。由 `DutyPlanner.plan` 生成（`DutyPlanner.java:788`），`DutyGenerator` 转成 `DepotDuties`，`DutyCheck` 检查，游戏侧 `mtr.data.Depot` 打印描述。

## 类 `OperationPlan`（final）

### 常量与字段
- `NONE = -1`：没有前/后续运用。
- `duties`：不可修改的运用列表。
- `issues`：不可修改的问题列表。
- `peakTrains`：一周中同时在外的最多列车数（由规划器给出，具体含义以 `DutyPlanner` 为准）。

### `OperationPlan(List<Duty> duties, List<Issue> issues, int peakTrains)`（包内）
包装为不可修改列表。主要调用者：`transitsim/planning/DutyPlanner.java:788`。

### `public boolean hasIssue(IssueType type)`
是否有该类问题。main 代码 grep 未见调用（可能用于测试）。

### `public String describe()`
- 作用：多行文本：每个运用一行 `index depot=… prev=… next=… 各行程 key`，再每个问题一行 `类型 详情`。调试用。
- main 代码中 grep 到的 `describe()` 调用（`common/src/main/java/mtr/data/Depot.java:1213` 等）是 `LineDuties.Plan.describe()`，不是本类；本方法的调用者未找到（可能仅测试或调试使用，未确定）。

## 枚举 `IssueType`
- `NO_WAY_BACK`：列车从最后站台没有任何可回的停车线（路径生成如此判定），只能计划回自己的停车线而它回不去。详情：停车线与站台。
- `NO_TRAIN`：有车的班次没有可用列车，该行程不跑。
- `SHORT_TURNAROUND`：有车在行程出发前到达站台，但来不及折返，行程需要另一列车；详情：站台、可用时间与所需时间（毫秒）。
- `UNUSED_BLANK`：空白班次没有被直通行程使用，不运行；属正常。
- `THROUGH_CONFLICT`：直通行程的班次缺失或间隔太近无法衔接。
- `PLATFORM_CONFLICT`：站台冲突（无注释；由 `DutyPlanner.java:848` 产生，详情含站台和时间）。
- `TURN_BACK_CAPACITY`：列车到达终点站台去另一站台折返的间隔，比一次折返所需时间还短（7号线，2026-11-12：每 30 秒一列，折返一次 1 分钟），折返跟不上，列车越来越晚。详情：站台、最短到达间隔与折返时间（毫秒）。
- `NOT_PERIODIC`：车厂的列车逐周减少，计划无法稳定为重复的一周。
使用者：`transitsim/planning/DutyGenerator.java:231`、`:233`。

## 内部类 `Issue`（public static final）
`type`、`detail`（文本）。包内构造。主要创建者：`transitsim/planning/DutyPlanner.java:96`、`:124`、`:138`。

## 内部类 `TripStop`（public static final）
行程的一个有时刻的停站，时间为周偏移：`serviceId`、`platformId`、`arrivalMillis`、`departureMillis`、`pass`。包内构造（`DutyPlanner.java:156`）。

## 内部类 `Trip`（public static final）
某班次或直通行程在某天的一次运行；环线每一圈是单独的行程。
- `sourceId`：来源班次或直通行程 ID；`stops`：不可修改停站列表（构造时复制）。
- `startMillis()`：首站到达；`endMillis()`：末站出发；`firstPlatform()`、`lastPlatform()`；`key()`：`sourceId@startMillis`。
- 包内构造；主要创建者：`transitsim/planning/DutyPlanner.java:100`（`DutyCheck` 中的 `new Trip` 为其内部类，不是本类）。

## 内部类 `Duty`（public static final）
一列车从离开车厂到返回车厂的一串行程。周末仍在外的运用在周循环后接续到 `nextDuty`。
- `index`：运用序号；`depotId`：车厂（停车线）ID；`trips`：行程（不可修改）。
- `previousDuty`、`nextDuty`：跨周接续的前后运用序号，`NONE` 为无。
- `phases`：分阶段（`Phase`）。
- `returnDepotId`：运行时会让列车回的车厂：池中下一个需要列车的，否则自己的。
- `waits`：除最后一个外每个行程之后在哪里等下一个（`DepotDuties.DayDuty.WAIT_*`）。
- `leaveMillis`：首个行程前多久离开停车线（按计划出库路程）；从前一天接续的为 0。
- 构造：七参数版本（`waits` 空、`leaveMillis` 0）与九参数版本，均复制列表。主要创建者：`transitsim/planning/DutyPlanner.java:784`。

## 枚举 `PhaseType`
`LEAVE_DEPOT`（出库）、`STOP`（停站）、`RUN`（运行）、`LAYOVER`（折返等待）、`CONNECT`（站间空车连接）、`RETURN_TO_DEPOT`（回库）。

## 内部类 `Phase`（public static final）
运用的一部分，结束于 `endMillis`。
- `type`、`startMillis`、`endMillis`、`fromPlatform`、`toPlatform`（静止时只有 `fromPlatform` 有意义）。
- `isAtPlatform()`：`STOP` 或 `LAYOVER` 时为 true。
- 包内构造；主要创建者：`transitsim/planning/DutyPlanner.java:795`、`:802`、`:805`。
