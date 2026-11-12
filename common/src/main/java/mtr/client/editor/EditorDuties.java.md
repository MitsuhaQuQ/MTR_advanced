# EditorDuties.java

高级编辑器中车厂运用（duty）的一侧（设计文档 6.12，E1 块）：页面读取和发送的 JSON、把车厂运用发给服务器之前的检查，以及由组件 `DutyGenerator`/`LineDuties` 算出的初稿。运用数据本体是组件的 `transitsim.plan.DepotDuties`（停车线 → 槽 slot → 按星期的日运用 DayDuty → 分段 Segment）。客户端代码：`EditorData.java:372-374`（`toJson`）、`EditorActions.java:375`（`apply`）、`EditorServer.java:196`/`:786`、`EditorSimulation.java:74-76`（`getDepot`/`read`）、`EditorServer.java:494`（`prepareGeneration`）、`LiveSimulation.java:629`（`getDepot`）。

## final class EditorDuties implements IPacket

### `private EditorDuties()`
工具类，禁止实例化。

### `public static JsonObject toJson(DepotDuties duties)`
- 返回：`{mode ("diagram"/"online"), sidings: {sidingId: [slot: [日运用...]]}, unplaced: {pool: 槽数}, moves: [{duty, from, to, pool}]}`。`unplaced` 为车池中已没有该车辆停车线、保留但不运行的槽；`moves` 为交给同池另一停车线的槽（"运用补完"第 5 步）。
- 调用者：`EditorData.java:372`（玩家设置）、`:374`（自动编排）。

### `private static JsonArray slotsToJson(List<DepotDuties.Slot> slots)`
- 每个日运用：`{days (星期位掩码), stay (是否留在线上过夜), next (次日接续的槽), duty (运用编号，P2-D), start ("only"/"any"/"prefer"，列车来源，S8), nextDuty, returnSiding, fromOvernight, segments: [{route, service, from, to}]}`；有编排结果时（`hasItems()`，"运用补完"第 1 步）再加 `waits[]`、`outLead`、`outEntrance`、`backEntrance`、`outPath`、`backPath`、`exceptions`，使页面编辑后仍保留。

### `public static String apply(JsonObject operation)`
- 作用：检查页面发来的车厂运用（`read`），通过后逐槽发送：设置运用模式（`setDutyMode`）；对旧有与新有的每条停车线，先设槽数，空则发 `setDutySlot(sidingId, -1)`，否则逐槽 `setSlot` + `setDutySlot`，均经 `PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_DEPOT, ...)`。
- 返回：错误消息（`gui.mtr.editor_depot_missing` 或 `read` 的错误）或 `null`。
- 副作用：修改客户端 `depot.duties` 并发包。

### `public static Depot getDepot(JsonObject operation)`
- 返回：`operation.depot`（字符串 id）对应的车厂或 `null`。

### `public static String read(Depot depot, JsonObject operation, DepotDuties duties)`
- 作用：把页面的车厂运用读入 `duties`，做与应用时相同的检查，不发送。检查：
  - 停车线必须存在且属于该车厂（`editor_siding_missing`）；
  - 每停车线槽数 ≤ `DepotDuties.MAX_SLOTS`（运用多于列车数是允许的：回库列车会再出车，`DepotDispatcher` 派出准备好的列车）；
  - 每槽各日运用的星期不能为空或重叠、日数 ≤ `MAX_DAYS`（`editor_duty_days_overlap`）；
  - `returnSiding` 必须是同车池的停车线（P2-D，`editor_duty_return_siding`）；
  - `next` 在范围内（`editor_duty_next_missing`）；
  - 分段数 ≤ `MAX_SEGMENTS`；每段经 `checkSegment`；
  - 编排细节（`waits` 数量须为分段数 − 1 才保留，限制在 `WAIT_ANY…WAIT_CHANGE_PLATFORM`；`outLead` ≥ −1；`exceptions & EXCEPTION_ALL`）只在页面提供时读入；
  - 槽 id 未给时用 `DepotDuties.defaultId(sidingId, i)`；
  - 同一星期两个留线过夜的运用不能接续同一个槽（`editor_duty_same_next`）。
- 返回：错误消息或 `null`。

### `private static String checkSegment(Depot depot, DepotDuties.Segment segment, int weekdays, Set<String> taken)`
- 作用：分段必须是该车厂某线路的班次（`editor_segment_route_missing`）；起止站下标在线路站数内（`editor_segment_stations_changed`）；在运用的每个星期，该线路当天的时刻表含此班次（`editor_segment_not_running`），且车厂中没有别的列车在同一天跑它（`editor_segment_twice`，用 `taken` 记录 `route@service/day`）。

### `private static RouteTimetable getTable(Route route, int weekday)`
- 返回：第一张包含该星期的时刻表；没有为 `null`。

### `public static Supplier<JsonObject> prepareGeneration(long depotId)`
- 作用：在游戏线程上复制生成初稿所需的数据，返回一个可在其它线程运行的生成函数；车厂不存在返回 `null`。
- 两种路径：
  1. 车厂属于一个"运营线路单元"（`LineDuties.of(depot, …, true)` 非空）：与游戏为按时刻表运行的车厂编排时一样，按线路整体编排（`unit.plan(...)`，用各线路最快时分与服务器测得的换站台时间 `LineDuties.turnTimesOf`），初稿只保留本车厂的停车线。返回字段：`duties, trains, uncovered, noSidings, byLine: true, lineNames, lineDepots, needed, available, suggestedSiding, missingTimes[], issues[], returns`。
  2. 否则：用 `DutyGenerator.servicesOf` 取各线路班次，停车线按名称与 id 排序，出入库时间按轨道测量（`LineDuties.sidingLinks`/`backLinksOf`），车池车数、附加缓冲、最小折返时间等组成 `PlanInputs`；最长停留为 `MAX_STAY_MILLIS`（循环线路）或 `MAX_LAYOVER_MILLIS`；生成时按日类型分别编排（`generateByDayType`，运用组第 2 步）。返回：`duties, trains, uncovered, noSidings, missingTimes[], issues[], returns`。
- 调用者：`EditorServer.java:494`。

### `private static JsonObject returnsJson(Map<String, Long> returnSidings, Set<Long> ownSidings)`
- 返回：运用列车不回本停车线时回哪里（按运行时的规则）：`"siding/slot/weekday" → 停车线 id`；`ownSidings` 非空时只保留本车厂停车线出发的条目。

### 私有辅助
- `getString(JsonObject, String)`：缺失或 null 为 `""`。
- `parseId(String)`：非数字为 0。
