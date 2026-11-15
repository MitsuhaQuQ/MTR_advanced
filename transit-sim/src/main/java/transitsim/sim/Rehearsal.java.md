# Rehearsal.java

运行图预演（0.83.0，用户 2026-11-15：编排时预演运行图能否跑通并提示，不强行准点）：用 `DayRun` 仿真这些线路的一天，按线路和种类汇总成警告，每条带最主要的原因。计划不变。纯 Java；游戏侧 `mtr.advanced.Rehearsals` 在车厂运用或时刻表改动并稳定后在后台调用，离线工具 `SimulateDay --rehearse` 也用它。

## 类 Rehearsal（final）

- `MAX_WARNINGS = 40`：最多保留的警告数。
- 枚举 `Kind`：`LATE`（晚点超过 `DayRun.REPORT_MILLIS`）、`HELD`（途中或站台前被挡超过它）、`DEADLOCK`（互相卡死）、`NOT_RUN`（没车跑）。
- `static List<Warning> run(SimDataset dataset, long dayStart, Set<Long> routes, BooleanSupplier cancelled)`：`SimScope.of` 裁出这些线路那部分世界，`DayRun.run` 一天，停止了返回 null，否则 `summarize`。
- `static List<Warning> summarize(DayRun dayRun)`：`LATE`、`WAITING_FOR_PLATFORM`/`HELD_ON_LINE`、`NOT_RUN` 按线路分组，每组一条：次数、最坏的那次（时长、时刻、班次、站序、站台）、累计挡得最久的原因（挡的车的线路、种类、站台；它的班次只在它就是最坏那次的原因时给出，否则 -1）；每个死锁一条（detail 为相关列车）。排序：死锁、没车跑、再按最多时长从大到小，截到 `MAX_WARNINGS`。

## 类 Warning（public static final）

字段 `kind`、`routeId`（死锁为 0）、`count`、`maxMillis`、`timeOfDay`（运营日开始后的毫秒）、`serviceOfDay`（同上，或 -1）、`stopIndex`、`platformId`、`causeKnown`、`causeRouteId`（0 为不在载客的车，如出库空车）、`causeServiceOfDay`、`causeByTrain`（是不是别的车挡的）、`causeKind`（`transitsim.network.Resource.Kind` 名，没车挡时为空）、`causePlatformId`、`detail`。
- `String encode()`：一行，`|` 分隔，detail 里的 `|` 换成空格。
- `static Warning decode(String line)`：读回，不是这种行时返回 null。

- 0.83.4 `run` 在 `SimLog.quietly` 里跑 `DayRun.run`：模拟列车的互换运用等不打进游戏日志。
