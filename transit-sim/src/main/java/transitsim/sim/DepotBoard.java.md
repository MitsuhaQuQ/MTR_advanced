# DepotBoard.java

无世界运行时（组件的无头仿真）列车所看到的车厂：它的线路、列车已占用的服务和运用，由 `DepotDispatcher` 以与游戏车厂（`Depot`）相同的方式决定。实现 `TrainService.Board` 接口，供 `TrainService` 查询与登记。

在整体中的位置：`SimDataset.java:370` 为每个车厂构造，`:398` 设 `throatLocksAllowed`，`:391` 设 `shareServicesWith`；`TickRunner` 使用（`:254`/`:255` 设回库选择与交接回调，`:603`/`:1995` 调 `record`，`:1273` `changedWithPeers`，`:2516` 读 `throatLocksAllowed`）。游戏侧 `Depot` 实现同一 `Board` 接口（列出的 `Depot.java` 调用者多为其自身同名方法）。

## 类 DepotBoard（final，实现 `TrainService.Board`）

### 字段
- `dispatcher`（public final `DepotDispatcher`）：服务/运用的占用与分派。
- `duties`（public final `DepotDuties`）：车厂运用表。实例初始化块把 `dispatcher.slotsOf` 设为 `duties::getSlots`。
- `routes`（private List<PlanRoute>）：车厂线路。
- `trainRoutes`（private Map<String, List<PlanRoute>>）：只运行部分服务类型的车辆 → 自己的线路。
- `cycleRoutes`（boolean）：列车从最后一条线路折返到第一条，而不回车厂。
- `departures`（List<Integer>）：按频率（而非时刻表）发车的发车时刻（一天中的时间，有序），可空。
- `sidingOrder`（List<Long>）：游戏车厂轮流发车时的停车线顺序；空则按 id 选有车可发的停车线。
- `lastDeployedMillis`、`deployIndex`：上次发车时刻与 `sidingOrder` 中下一个位置（与游戏车厂保存的一致）。
- `dutiesOnly`（boolean）：只按运用发车（R2）：没有运用就不发车，而不是按频率或时刻表轮流。
- `changed`（包内 boolean）：本 tick 占用的服务是否变化，等待发车的列车据此重新计算何时能走（S4f）。
- `peers`（包内 List<DepotBoard>）：与本车厂共用线路的车厂（见 `DepotDispatcher.shareServicesWith`），本车厂占用或释放服务时它们等待发车的列车也重看。
- `returnChooser`（包内 `ReturnChooser`）：列车运用结束后回哪条停车线，由拥有停车线的运行器计算；null 回自己的停车线。
- `returnOrderMisses`（List<String>）：被指定回某车厂但该车厂没有停车线可接收、改按常规选择回库的记录，格式 `"<停车线> <车厂>"`。
- `throatLocksAllowed`（boolean，默认 true）：其列车是否锁咽喉进路；轨道与不按运用运行的车厂共用时关闭（`LockNetworks`）。
- `depotId`（long）：构造它的数据中的车厂 id，或 0。
- `recorder`（private List<Long>）：列车本 tick 预订服务时同时写入的记录列表。
- `takeoverRequests`（public `TakeoverRequests`）：处理列车请求备用车接管运用（D5）的回调，null 为无。

### `void shareServicesWith(DepotBoard other)`
两车厂共同运行某线路，一方列车占用的服务另一方不再占用：互相加入 `peers` 并让 dispatcher 共享。对自身或已是同伴时不做事。调用者：`SimDataset.java:391`、`SharedServicesTest.java:49`。

### `boolean changedWithPeers()`（包内）
本车厂或任一同伴本 tick 是否占用/释放了服务。调用者：`TickRunner.java:1273`。

### 构造函数 `DepotBoard(List<? extends PlanRoute> routes)`
保存车厂线路。调用者：`SimDataset.java:370`、`DepotRoutesOrderTest.java:20`。

### `List<PlanRoute> getRoutes()`
车厂线路（可变列表本身）。

### `void setTrainRoutes(String trainId, List<? extends PlanRoute> newRoutes)`
为只运行部分服务类型的车辆设置自己的线路。调用者：`SimDataset.java:405`。

### `RouteTimetable getTimetable(String trainId)`（覆盖）
车辆线路中第一条时刻表有效的线路的时刻表（线路无序：任一有时刻表即按时刻表运行），没有则 null。

### `List<PlanRoute> getTrainRoutes(String trainId)`（覆盖）
车辆自己的线路，未设置则为车厂全部线路。

### `long findNextTrip(String trainId, long readyMillis)`（覆盖）
车辆第一条线路上 `readyMillis` 之后的下一个服务；无线路返回 0。

### `long findNextService(PlanRoute route, long readyMillis)`（覆盖）
置 `changed`，委托 `dispatcher.findNextService(route, readyMillis, cycleRoutes)`。

### `void reserveTrip(long routeId, long tripMillis)`（覆盖）
委托 dispatcher 预订；正在记录且 `tripMillis != 0` 时把 `DepotDispatcher.getTripKey` 写入记录列表。调用者：`TickRunner.java:1366`、`TrainService`。

### `void record(List<Long> newRecorder)`（包内）
开始把此后预订的服务写入该列表，null 停止。调用者：`TickRunner.java:603`、`TickRunner.java:1995`。

### `boolean isTripReserved(long routeId, long tripMillis)`（覆盖）
委托 dispatcher。

### `boolean keepsTrainsOnLine()`（覆盖）
返回 `cycleRoutes`。

### `List<DepotDuties.Slot> getDutySlots(long sidingId)`（覆盖）
该停车线的运用槽位（`duties.getSlots`）。

### `boolean hasDutyRun(long sidingId, int slot, long day)` / `void markDutyRun(long sidingId, int slot, long day)`（覆盖）
查询/标记某停车线某槽位在某天是否已运行，委托 dispatcher。调用者：`TickRunner.java:808`、`TrainService`。

### `long[] findDuty(long dutyId)`（覆盖）
按运用 id 找槽位（`duties.findSlot`）。

### `void noteReturnMissed(long sidingId, long targetSidingId, String reason)`（覆盖）
记录 `"<sidingId> <targetSidingId>"` 到 `returnOrderMisses`（`reason` 未使用）。

### `int requestTakeover(long dutySidingId, int slot, long dayStart, int fromSegment, String fromKey, long serviceMillis, Set<UUID> affected)`（覆盖）
委托 `takeoverRequests`；未设置时返回 `TrainService.TAKEOVER_NO_SPARE`。

### `long chooseReturnSiding(long sidingId, long depotId, LongPredicate canGoBack)`（覆盖）
委托 `returnChooser`；未设置时回自己的停车线。

## 内部接口 TakeoverRequests（public，函数式）
`int request(long dutySidingId, int slot, long dayStart, int fromSegment, String fromKey, long serviceMillis, Set<UUID> affected)`：处理接管请求，返回结果码（见 `TrainService`）。实现：`TickRunner.java:255`（转给 `DepotDispatcher.requestTakeover`）。

## 内部接口 ReturnChooser（包内，函数式）
`long choose(long sidingId, long depotId, LongPredicate canGoBack)`：见 `TrainService.Board#chooseReturnSiding`。实现：`TickRunner.java:254`。
