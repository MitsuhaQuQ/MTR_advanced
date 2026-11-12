# DutyShapes.java

运用的"形状"（设计文档 6.12，E2 块）：跑哪些路线、在哪里加入/离开每条路线、每班在哪些站停或通过、从哪里出发、是否在线上结束。形状相同的运用共享同一条路径；因为通过/停车是形状的一部分，单班的通过或加停才能真正生效。本文件还包含运用槽位过夜接续、具名回送路径标记，以及运营日时间换算。依赖 `DepotDuties`、`PlanRoute`、`RouteTimetable`、`ServiceExceptions`、`OperatingDay`。被游戏侧 `mtr.data.Depot`、`mtr.data.PidsSchedules`、`mtr.data.Siding`、`mtr.advanced.LiveSimulation`/`SandboxRuns`，以及组件 `transitsim.sim.DepotDispatcher`、`TrainService`、`DutyLedger`、`TripPath` 使用。

## 类 `DutyShapes`（final，工具类）

### 常量
- `KEY_PREFIX = "duty|"`：运用路径 key 的前缀；`TripPath.java:744` 据此判断路径是否为运用路径。
- `PATH_MARK = "!"`（私有）：key 中具名回送路径标记，后接 `o`（出库）或 `b`（回库）及路径 ID。

### `private DutyShapes()`
禁止实例化。

### `public static String getKey(List<DepotDuties.Segment> segments, LongFunction<? extends PlanRoute> routes, int weekday, long startPlatform, boolean staysOnLine)`
- 作用：生成运用路径 key：`duty|<startPlatform>|`，然后每段 `<routeId>@<首站>-<末站>:<每站通过标志 0/1>>`，最后 `stay` 或 `depot`。
- 参数：`segments` 运用各段；`routes` 路线查找；`weekday` 星期（0=周一），决定用哪张表的例外；`startPlatform` 过夜留在线上的列车出发站台，0 表示从停车线出发；`staysOnLine` 是否在线上过夜。
- 返回值：key；段为空、或任一路线不存在/站数 < 2 时返回 null。
- 主要调用者：本类 `getSlotKey`（外部 grep 未见直接调用）。

### `public static int getFirstStop(PlanRoute route, DepotDuties.Segment segment)`
- 返回值：本段在路线上的首站序号，`segment.fromStop` 夹到 `[0, 站数-2]`。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:3219`、`mtr/data/PidsSchedules.java:76`。

### `public static int getLastStop(PlanRoute route, DepotDuties.Segment segment)`
- 返回值：本段末站序号；`segment.toStop < 0` 表示路线末站，否则夹到 `[首站+1, 站数-1]`。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:3220`、`mtr/data/PidsSchedules.java:77`。

### `public static boolean[] getPassFlags(PlanRoute route, DepotDuties.Segment segment, int weekday)`
- 作用：从加入站到离开站，每站是否通过。先看该日所用表中该班（`segment.service`）的站例外：`pass == 1` 通过，`pass == 0` 停车，否则按路线设置 `isPass()`。加入站与离开站总是停车（数组首尾为 false）。
- 返回值：长度 `末站-首站+1` 的布尔数组。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:3221`、`mtr/data/PidsSchedules.java:78`。

### `public static RouteTimetable getTable(PlanRoute route, int weekday)`
- 返回值：路线在该星期运行的表——第一个 `weekdays` 位掩码含该日的表；没有则 null。
- 主要调用者：本类 `getPassFlags`。

### `public static int getNextSlot(DepotDuties.DayDuty day, int slotIndex)`
- 返回值：在此槽位过夜的列车第二天跑同一停车线的哪个槽位：`day.nextSlot`，为负时即本槽位。
- 主要调用者：`transitsim/sim/TrainService.java:1851`、本类 `getOvernightSlot`。

### `public static int getOvernightSlot(List<DepotDuties.Slot> slots, int slotIndex, int weekday)`
- 作用：找出前一天留在线上过夜、今天来跑本槽位运用的那个槽位。若本槽位当日运用 `fromOvernight` 为真（由另一停车线运用的列车按编号接手，P2-D），返回本槽位；否则遍历各槽位前一天的运用，找 `stayOnLine`、有段、`nextDutyId == 0` 且 `getNextSlot` 指向本槽位者。
- 返回值：槽位下标；从停车线出发时 -1。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:3156`、`transitsim/sim/DepotDispatcher.java:277`、`DepotDispatcher.java:594`。

### `public static long getEndPlatform(DepotDuties.DayDuty day, LongFunction<? extends PlanRoute> routes)`
- 返回值：运用最后一段结束的站台（过夜列车在此等待）；无运用/无段/路线缺失时 0。
- 主要调用者：本类 `getSlotKey`。

### `public static long getStartPlatform(DepotDuties.DayDuty day, LongFunction<? extends PlanRoute> routes)`
- 返回值：运用第一段开始的站台；条件同上返回 0。
- 主要调用者：main 代码中 grep 未见调用（未确定是否仅测试使用）。

### `public static String getSlotKey(List<DepotDuties.Slot> slots, int slotIndex, int weekday, LongFunction<? extends PlanRoute> routes)`
- 作用：某槽位在某星期所跑路径的 key：从停车线出发，或从前一天过夜处（`getEndPlatform` of 前一天的过夜槽位）出发。在 `getKey` 结果后追加具名回送路径（运用补完第 3 步）：从停车线出发且 `outPathId != 0` 时加 `!o<id>`；不在线上过夜且 `backPathId != 0` 时加 `!b<id>`。
- 返回值：key；无运用或 `getKey` 为 null 时 null。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:3151`、`mtr/data/PidsSchedules.java:47`、`transitsim/sim/DepotDispatcher.java:370`。

### `public static long getPathId(String key, boolean out)`
- 作用：从 key 中解析具名出库（`out` 为真）或回库路径 ID。允许 ID 前有一个负号。
- 返回值：路径 ID；key 为 null、无标记或解析失败时 0（表示按规则寻路）。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:1330`、`Siding.java:1510`。

### `public static long getDayStart(long millis)`
- 返回值：该时刻所属运营日的开始（绝对毫秒）：`millis - OperatingDay.getTimeInDay(millis)`。
- 主要调用者：`common/src/main/java/mtr/advanced/LiveSimulation.java:540`、`mtr/advanced/SandboxRuns.java:280`。

### `public static long getServiceMillis(long dayStart, int service)`
- 作用：把运用中的班次时刻（一天内的 UTC 毫秒）换算为以 `dayStart` 开始的运营日上的绝对时间：`dayStart + getTimeInDay(service)`。
- 主要调用者：`transitsim/plan/DutyLedger.java:29`、`DutyLedger.java:33`、`common/src/main/java/mtr/data/PidsSchedules.java:69`。

### `public static long getDayNumber(long dayStart)`
- 返回值：运营日编号：`floorDiv(dayStart + 半天, 一天)`（加半天以避免时区偏移落在边界）。
- 主要调用者：`common/src/main/java/mtr/data/PidsSchedules.java:40`、`PidsSchedules.java:109`、`transitsim/sim/DepotDispatcher.java:268`。
