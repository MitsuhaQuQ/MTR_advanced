# TurnTimes.java

站内两班次之间要多久，按规划器读的格式（`DutyGenerator.PlanInputs` 的 turnTimes）：绕经线路末站台外的折返线再回到该站台（列车在那里等下一班次，混合折返），以及从每条线路末站台到同站其他线路首站台。2026-11-13 从游戏的车厂路径构建（`mtr.data.DutyPathBuilder.turnTimesFor`）原样搬进组件（拆分车厂第 2b 步，0.75.3），规则和结果不变：宿主找路并计时，保留哪些、怎么读时间在这里。

调用者：游戏 `DutyPathBuilder.turnTimesFor`（`Depot` 规划运用、网页折返时间）。

## 接口 `Host<R, P>`
- `lastPlatform(route)` / `firstPlatform(route)`：线路末/首站台，没有站台为 0。
- `station(platform)`：站台所在车站，没有为 0。
- `holdWay(before, platform)`：线路在该站台结束后绕经站台外折返线再回来的走法，没有为 null。
- `changeWay(before, after, from, to)`：从一条线路结束处到另一条开始处的路，没有为 null。
- `turnsInto(before, after, platform)`（0.75.4）：线路列车能否改停在该站台（线路列出可在此结束，经站前过渡线进入）并作为下一条线路离开（站前折返）；默认 false。
- `kept(...)`、`tooFar(...)`：默认什么都不做，宿主用来打印；站前折返时 `kept` 的路为 null。

## 类 `Way<P>`
- `path`：宿主的路，只回传给打印。
- `legsByVehicle`：跑两条线路的每种车辆（宿主的顺序）的 {进入, 出来} 毫秒。
- `farthest`：离出发站台最远多少格。
- `places`：绕经折返线时能同时等待的列车数。
- `turnBackRail`：绕经的折返轨两节点，或 null。

## `static Map<Long, Map<Long, Long>> of(List<R> routes, Host<R, P> host, double maxReach)`
- 对每条线路（前）：末站台不在车站的跳过。每个站台只一次：有绕经折返线的走法就记 站台→自身 = 最慢车辆的进入 + 出来，`HOLD_IN_KEY` = 其进入段，可等待数大于 1 记 `HOLD_COUNT_KEY`，有折返轨记 `HOLD_RAIL_KEY`（`railKey`）。
- 再对每条线路（后）：首站台为 0、等于末站台、在别的车站、或这一对已记过的跳过；宿主说能站前折返进入该站台（`turnsInto`）就记 0（不换站台，只换端，由计划加网络最短换端时间；0.75.4，六线图 1、4 号线原来只有一条 970 格的绕行，没有车能折返到它们的班次），不再问路；否则问宿主要路，没有跳过；`farthest > maxReach` 的不算换站台（会沿线跑出又折回，与对向列车迎面，2026-11-09），告诉 `tooFar`；否则记最慢车辆的进入 + 出来（不含在折返处停留，由计划加网络最短换端时间，第 4 轮）。没路的对下一条在此结束的线路还会再问（宿主有缓存）。
- 返回：从站台 → 到站台 → 毫秒；没有路的对不出现，规划视为无路。

## `static long[] slowest(List<long[]> legsByVehicle)`
和最大的那组，一样大取先出现的；没有为 {0, 0}。

## `static long railKey(long node1, long node2)`
折返轨两端不分方向的编号，不为 0（8 号线共用折返线，2026-11-12）。

## `static <T extends PlanTrack> long[] legs(List<T> way, List<TimeSegment> timeSegments, double trainLength, double millisPerTick)`
把绕行在折返处切开（第 4 轮）：折返点 = 到第一处“下一段是本段反向”为止的长度，本段有停站时减去停车位置偏移；进入段 = 终点不超过折返点的时间段的最晚结束，出来段 = 从起点超过折返点的第一段开始到最后。宿主计时在折返处把车头前移到车尾处（车长加两倍停车偏移），所以出来段起点在折返点之后。无折返全算进入，无时间段为 {0, 0}。

## 测试
`TurnTimesTest`：站前折返记 0、不再问路；按折返处切开（含停在站台短处折返）、最慢取和最大且同时取先、折返轨编号不分方向、整张表的键和跳过规则以及问宿主的次序。
