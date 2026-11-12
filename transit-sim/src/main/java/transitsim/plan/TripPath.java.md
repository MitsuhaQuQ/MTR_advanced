# TripPath.java

一个服务（或运用）所跑的、不同于车厂常规路径的路径；列车从开始该服务起使用它。种类：
- **运用路径**（设计文档 6.12，E2）：由 `DutyPlan` 生成，`dutyStarts` 非 null；
- **直通尾巴路径**：常规路径 + `TripTail`（离开常规路径转去其它路线）；
- **折返点换走法路径**：常规路径在某 `TurnBacks` 折返点走另一种走法；
- **回库路径**：路线之后回车厂（`changeStop`）；
- **派生变体**（由宿主 `PathVariants` 帮助生成并重新计时）：跳站（D3，`~skip:`）、运用提前折返（D4，`~turn:`）、从后面某段出库开始（D5，`~from:`）、某段后交接并回库（D5/D6，`~end:`）、回到同池其它停车线（`#siding`）。

每个 `TripPath` 带有自己的 `StopTimes`、`TimeSegment` 列表和累计距离 `PathDistances`。可以序列化（`KeyedWriter`，宿主随后保存轨道本身）。游戏侧 `mtr.data.Siding` 创建、计时、保存（`Siding.java:373`、`:511`、`:1066`、`:1549`、`:1581`），组件 `transitsim.sim.TrainService`、`DepotDispatcher`、`BufferRecorder`、`TickRunner` 运行时按 key 查找与派生；`mtr.data.PidsSchedules`、`DepotLegs`、`TrainServer` 读取。

## 类 `TripPath<T extends PlanTrack>`

### 常量
- `KEY_*`：序列化键名：`trip_key`、`trip_route_ids`、`trip_cut_route`/`trip_cut_stop`（旧格式的单一截断路线）、`trip_lap_end`、`trip_cuts`、`trip_repeat_1`/`_2`、`trip_lap_stop`、`trip_entry_rail`、`trip_entries`、`trip_base`、`trip_turn_back`、`trip_turn_back_stops`、`trip_change_stop`、`trip_duty_starts`、`trip_duty_stays`、`loop_join_index`、`trip_segment_cuts`、`trip_segment_entries`、`trip_segment_entry_times`、`trip_segments_known`。
- `WRITTEN_VALUES = 20`：`write` 写出的值个数，宿主在其后保存路径本身；有测试检查。
- 私有标记：`RETURN_MARK = '#'`、`SKIP_MARK = "~skip:"`（只有跳站 key 含有）、`TURN_MARK = "~turn:"`、`FROM_MARK = "~from:"`、`END_MARK = "~end:"`（后三者在同时有跳站时位于 `SKIP_MARK` 之前）。

### 字段
- `key`：本路径 key。
- `routeIds`：依次经过的路线。
- `path`：轨道序列（`IndexedPath.copyOf` 后，可能保持索引形式）。
- `distances`：`PathDistances`，累计距离。
- `timeSegments`：`TimeSegment` 列表。
- `stopTimes`：本路径的 `StopTimes`。
- `repeatIndex1`、`repeatIndex2`：行程以环线结束时列车绕圈的轨道区间，否则 0。
- `lapStop`：该环线首站的轨道下标。
- `entryRail`：列车中途加入该环线的轨道，从首站加入时 -1。
- `tripKey`：直通到其它路线的服务 key（运用路径为 `dutyPlan.key`）；常规路径为空串。
- `turnBackStops`：每个 `TurnBacks` 折返点之前停站的轨道下标，本路径不经过的点为 -1。
- `turnBackPoint`、`turnBackWay`：本路径与常规不同的折返点及走法，否则 -1。
- `changeStop`：回库路径中列车换到它上面的停站轨道，否则 -1。
- 私有：`cutStops`、`entryStops`、`entryTimes`、`entryLeads`（中途离开/加入的路线、加入站、从首站起的最快时间、计划用引导路径，见 `TripTail.entryLeads`）；`segmentCuts`、`segmentEntries`、`segmentEntryTimes`、`segmentsKnown`（运用按段的截断/加入，D4）；`segmentLeadSources`（提前折返路径中加入段所依据的常规路径 `StopTimes`）；`joinIndex`、`lapEnd`（环线加入与一圈结束）。
- `turnRail`、`turnResumeRail`：提前折返路径（D4）中折返的停站轨道（乘客下车处）及继续运用的轨道；没有时 -1（回库时 `turnResumeRail` 为 -1）。`withEnd` 中 `turnRail` 用作清客站。
- `dutyStarts`：运用路径中各段首站的轨道下标，其它路径为 null。
- `staysOnLine`：运用路径在末站结束、列车在线上过夜。

### `public double getStopDistance(int rail, double trainLength)`
- 返回值：车头在该轨道末端停站时的位置 = 累计距离 − 停车偏移（有停站时，第 4 轮）。
- 主要调用者：main 代码中 grep 未见直接调用（同名的单参数版本是 `transitsim.motion.MotionTrack.getStopDistance(int)`，不是本方法）；是否仍在使用未确定。

### `public long getBackMillis()`
- 返回值：从路径最后一个停车站出发到路径终点（回到停车线）的最快毫秒（最后一个 `TimeSegment.endTime` 与最后一个非通过停站段 `endTime` 之差 × 50）；留在线上或没有时间时 0。与 `DepotLegs` 测量回库路程的方法一致。
- 主要调用者：`transitsim/sim/BufferRecorder.java:107`。

### 构造函数
- `TripPath(DutyPlan<T, ?> dutyPlan, int[] dutyStarts, List<T> path)`：运用路径，key = `dutyPlan.key`。
- `TripPath(DutyPlan, int[] dutyStarts, List<T> path, String key)`：运用路径用自己的 key（如回到同池另一停车线）。
- `TripPath(DutyPlan, int[] dutyStarts, List<T> path, String key, int turnBackPoint, int turnBackWay, int[] turnBackStops)`：经过折返点或在某折返点走另一走法的运用路径。复制 `dutyPlan` 的路线、截断、加入、按段信息；`changeStop`/`turnRail`/`turnResumeRail` 为 -1。主要调用者：`common/src/main/java/mtr/data/Siding.java:1549`。
- `TripPath(String key, int turnBackPoint, int turnBackWay, int[] turnBackStops, TripTail<T, ?> tripTail, int joinIndex, int lapEnd, int tailStart, int repeatEnd, List<T> path)`：直通尾巴路径。若尾巴以环线结束：`lapStop = tripTail.lapStart + tailStart`，`repeatIndex1 = lapStop (+1 若原路返回)`，`repeatIndex2 = repeatEnd`；`entryRail` 同样平移。主要调用者：`common/src/main/java/mtr/data/Siding.java:1581`。
- `TripPath(String key, int turnBackPoint, int turnBackWay, int[] turnBackStops, int joinIndex, int repeatIndex1, int repeatIndex2, int changeStop, List<T> path)`：常规路径在某折返点换走法（或回库路径），`tripKey` 为空串，`lapEnd = repeatIndex2`。
- `private TripPath(TripPath<T> base, String key, List<T> path)`：与 `base` 相同但换 key 和轨道（跳站，D3）；索引路径用 `rebuild` 保持模板共享。
- `private TripPath(TripPath<T> base, String key, List<T> path, int[] dutyStarts, int segmentCount, int[] turnBackStops, int turnRail, int turnResumeRail, boolean staysOnLine)`：运用路径的改动版（提前折返、从中途开始、交接回库）：只保留前 `segmentCount` 段的路线与按段信息，`segmentsKnown = true`。
- `public TripPath(KeyedReader reader, List<T> path)`：按 `write` 读回，路径由宿主另外保存后传入；兼容旧的单一截断路线键；`dutyStarts` 仅当 key 以 `DutyShapes.KEY_PREFIX` 开头时保留；`turnRail`/`turnResumeRail` 不保存，读回为 -1。主要调用者：`common/src/main/java/mtr/data/Siding.java:373`。

### 跳站（D3）
- `public static <T> TripPath<T> withSkips(TripPath<T> base, int[] rails, PathVariants<T> variants)`：把指定停站轨道（`0 < rail < size`）换成通过版本（`variants.pass`），key 为 `getSkipKey`，并重新计时。主要调用者：`transitsim/sim/TrainService.java:884`。
- `public static String getSkipKey(String baseKey, int[] rails)`：`baseKey + "~skip:" + 逗号分隔轨道`；无轨道时返回 `baseKey`。主要调用者：`transitsim/sim/TrainService.java:953`。
- `public static boolean isSkipKey(String key)`：主要调用者：`transitsim/sim/TrainService.java:861`、`:876`。
- `public static String getSkipBase(String key)`：去掉跳站部分。主要调用者：`transitsim/sim/TrainService.java:491`、`:599`、`common/src/main/java/mtr/data/TrainServer.java:1044`。
- `public static int[] getSkipRails(String key)`：解析跳站轨道，非跳站 key 或解析失败返回空数组。主要调用者：`transitsim/sim/TrainService.java:884`、`:940`。

### 提前折返（D4）
- `public static String getTurnKey(String baseKey, int segment, int turnRail, int cutStop, long target, int entryStop, boolean resume)`：`baseKey + "~turn:" + segment:turnRail:cutStop:target:entryStop:(r|d)`。参数：`segment` 在哪一段折返；`turnRail` 折返停站的轨道；`cutStop` 该站在该段路线中的站序；`target` 继续运用时为下一段继续的停站轨道，否则为折返到达的站台 ID；`entryStop` 继续时该站在下一段路线中的站序；`resume` 继续运用或空车回库。主要调用者：`transitsim/sim/TrainService.java:819`、`:825`。
- `public static boolean isTurnKey(String key)`：主要调用者：`transitsim/sim/TrainService.java:695`、`:893`。
- `public static String getTurnBase(String key)`：去掉最早出现的 `~turn:`/`~from:`/`~end:` 及之后部分；都没有时去掉跳站部分。主要调用者：`transitsim/sim/DepotDispatcher.java:517`、`transitsim/sim/TrainService.java:892`、`:2000`。
- `public static boolean isChangedKey(String key)`：是否为改动过的运用路径（含三种标记之一）。主要调用者：`transitsim/sim/TrainService.java:492`、`:600`、`:887`。
- `public static <T> TripPath<T> withTurn(TripPath<T> base, String key, PathVariants<T> variants)`：
  - 要求 `base` 为按段已知的运用路径，key 有 6 个部分，`segment` 合法且 `dutyStarts[segment] < turnRail < size`。
  - 继续（`r`）：目标轨道须在下一段范围内；取 `variants.getTurnLink(折返站台, 目标站台)`，中间轨道改为通过版本，接上 `base` 从目标轨道起的剩余部分；更新后续段起点与折返点停站（中间被跳过的为 -1）；本段截断于 `cutStop`，下一段加入于 `entryStop`，并以 `base.stopTimes` 作为该段的引导时间来源。
  - 回库（`d`）：接上 `getTurnLink(折返站台, target)` 和 `getTurnReturn(target)`（全部通过），只保留到本段，不留线上。
  - 最后重新计时。
  - 返回值：新路径；key 不符或宿主没有对应路径时 null。
  - 主要调用者：`transitsim/sim/TrainService.java:893`。

### 中途出库开始 / 交接回库（D5、D6）
- `public static String getFromKey(String baseKey, int segment)`：`baseKey + "~from:" + segment`。主要调用者：`transitsim/sim/DepotDispatcher.java:497`、`transitsim/sim/TrainService.java:637`。
- `public static boolean isFromKey(String key)`：主要调用者：`transitsim/sim/TrainService.java:893`、`:1998`。
- `public static int getFromSegment(String key)`：开始的段号，非此类 key 或解析失败为 0。主要调用者：`transitsim/sim/TrainService.java:1197`。
- `public static String getEndKey(String baseKey, int segment, int lastStopRail)`：`baseKey + "~end:" + segment:lastStopRail`。
- `public static String getEndKey(String baseKey, int segment, int lastStopRail, int clearOutRail)`：清客站与末站相同时同上，否则再加 `:clearOutRail`（D6：在更早的站清客，余下空车跑完本段）。主要调用者：`transitsim/sim/TrainService.java:542`、`:639`。
- `public static boolean isEndKey(String key)`：主要调用者：`transitsim/sim/TrainService.java:576`。
- `public static <T> TripPath<T> withFrom(TripPath<T> base, String key, PathVariants<T> variants)`：
  - 从第 `segment` 段起：用 `variants.getWayOut(站台)` 从停车线出到该段首站，然后接上运用余下部分；前面的段不跑任何站（`segmentCuts[i] = -1`）。
  - 若该段首站是"前一段在站台原地折返后的反向轨道"（本身无停站，前一轨道有停站且互为反向）：若出库路径末端与首站反向，则在出库末端加停站（`withStop`）后照常折返；否则把首站换成带停站的版本。
  - 更新段起点与折返点停站，重新计时。
  - 返回值：新路径；不是按段已知的运用、段号非法、宿主没有出库路径或不能加停时 null。
  - 主要调用者：`transitsim/sim/DepotDispatcher.java:526`、`transitsim/sim/TrainService.java:893`。
- `public static <T> TripPath<T> withEnd(TripPath<T> base, String key, PathVariants<T> variants)`：
  - 到第 `segment` 段末站为止，乘客在清客站下车，此后（清客站之后到末站的站）改为通过，然后空车回库：最后一段沿运用自己的回库路，较早的段用 `variants.getTurnReturn(末站站台)`（全部通过）。
  - `turnRail` 记为清客站，不留线上，重新计时。
  - 返回值：新路径；key 解析失败、清客站在末站之后、段/轨道越界或宿主没有回库路时 null。
  - 主要调用者：`transitsim/sim/TrainService.java:893`。

### `private static <T> T passing(T track, PathVariants<T> variants)`
有停站的站台轨道换成通过版本，其它原样（提前折返/回库的列车不载客，只在终点停车线停）。

### 序列化辅助
- `private static void readTimes(long[] values, BiConsumer<Long, long[][]> consumer)`：读 `writeTimes` 格式（key、站数、每站到达/出发）。
- `private static long[] writeTimes(Map<Integer, long[][]> timesByKey)`：按 key 排序写出。
- `private static long[] writePairs(Map<Integer, Integer> pairs)`：按 key 排序写成 (key, value) 对。
- `private static int[] toInts(long[] values)`。

### `private void applyShape(StopTimes times)`
把路线截断/加入、按段信息、引导时间来源写入 `times` 的包内字段；对每个中途加入的段，优先用路径自己的 `segmentEntryTimes`，否则若该段路线按路线记录的加入站与之相同则用 `entryTimes`。

### 无世界运行用的工厂（main 代码中仅测试调用）
- `public static <T> TripPath<T> ofDuty(DutyPlan<T, ?> dutyPlan, int[] dutyStarts, List<T> path, StopTimes newStopTimes)`。
- `public static <T> TripPath<T> ofDuty(…, StopTimes newStopTimes, int turnBackPoint, int turnBackWay, int[] turnBackStops)`：key 为 `dutyPlan.key` 或 `getTurnBackKey(dutyPlan.key, point, way)`。
- `private static <T> TripPath<T> ofDuty(TripPath<T> tripPath, StopTimes newStopTimes)`：复制停站时间、合并其 `entryTimes`、`applyShape`，`firstRouteJoined = true`。
- `public static <T> TripPath<T> of(TripTail<T, ?> tripTail, List<T> path, int tailStart, int repeatEnd, StopTimes newStopTimes)`：直通尾巴路径。
- `public static <T> TripPath<T> ofTurnBack(int point, int way, int[] turnBackStops, List<T> path, StopTimes newStopTimes)`：常规路径换折返走法。
- `public static <T> TripPath<T> ofDepotReturn(int routeIndex, int changeStop, List<T> path, StopTimes newStopTimes)`：回库路径。
- 调用者见测试 `transit-sim/src/test/java/transitsim/sim/DutyRunTest.java:108`、`BufferRecorderTest.java:43`、`common/src/test/java/mtr/data/PidsSchedulesTest.java:74`。

### `public boolean isDuty()`
`dutyStarts != null`。主要调用者：`common/src/main/java/mtr/data/DepotLegs.java:152`、`mtr/data/PidsSchedules.java:49`、`transitsim/sim/BufferRecorder.java:90`。

### key 生成
- `public static String getTurnBackKey(String tripKey, int point, int way)`：`tripKey + "|" + point + ":" + way`。主要调用者：`common/src/main/java/mtr/data/Siding.java:1394`、`:1601`、`:1609`。
- `public static String getDepotReturnKey(int routeIndex)`：`"depot|" + routeIndex`。主要调用者：`common/src/main/java/mtr/data/Siding.java:1632`、`transitsim/sim/TrainService.java:1609`。
- `public static String getReturnKey(String dutyKey, long sidingId)`：`dutyKey + "#" + sidingId`，同一运用回到同池另一停车线（解耦文档 D 步，车厂派车第 5 点）。主要调用者：`common/src/main/java/mtr/data/Siding.java:1376`、`transitsim/sim/TickRunner.java:294`、`common/src/main/java/mtr/data/TrainServer.java:1044`。
- `public static long getReturnSidingId(String fullKey)`：去掉跳站后取最后一个 `#` 之后的停车线 ID；不是此类 key 或解析失败为 0。主要调用者：`transitsim/sim/BufferRecorder.java:109`、`transitsim/sim/TrainService.java:1007`。
- `public static boolean isReturnKey(String key)`：主要调用者：`transitsim/sim/BufferRecorder.java:109`、`transitsim/sim/TrainService.java:492`。

### `public void generateTimes(TimeGenerator<T> generator)`
- 作用：为路径计时：把截断信息写入 `stopTimes`；对每条中途加入路线的引导路径（只"想象中"跑），用 `generator.calculateStopTimes` 求时间，若停站数等于加入站序 + 1 则存为 `entryTimes`；`applyShape`；设置 `firstRouteJoined = isDuty()` 与末尾环线加入区间（`tailJoinStart = entryRail`，`tailJoinEnd = lapStop` 或 0，`tailLapEnd = repeatIndex2`）；调用 `generator.generateTimeSegments(path, timeSegments, stopTimes, joinIndex, lapEnd)`；重置距离表。
- 副作用：修改 `entryTimes`、`stopTimes`、`timeSegments`、`distances`。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:1066`、`Siding.java:1550`、`Siding.java:1582`；本类各 `with*` 工厂。

### `public void write(KeyedWriter writer) throws IOException`
写出 `WRITTEN_VALUES`（20）个值：key、changeStop、tripKey、折返点/走法、折返点停站、路线、截断、joinIndex、lapEnd、repeat1/2、lapStop、entryRail、加入（每路线：ID、加入站、站数、各站到达/出发）、dutyStarts、staysOnLine、按段截断、按段加入、按段加入时间、segmentsKnown。宿主随后保存 `path`。主要调用者：`common/src/main/java/mtr/data/Siding.java:511`、`Siding.java:2012`。
