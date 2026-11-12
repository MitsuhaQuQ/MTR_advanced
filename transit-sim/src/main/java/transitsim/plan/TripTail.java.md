# TripTail.java

一个服务离开车厂的常规路径转去另一条路线（直通/接续）时，离开点之后的"尾巴"。离开点之前与常规路径相同，所以列车可以在跑常规路径的途中换到这条尾巴上。游戏侧 `mtr.data.Depot` 生成（`Depot.java:3133`），`mtr.data.Siding` 按 key 存储（`Siding.java:170` 注释），`transitsim.sim.TrainService` 按 key 查找（`TrainService.java:2166`）。

## 类 `TripTail<T extends PlanTrack, P>`

### 常量
- `NO_RAIL = Integer.MIN_VALUE`：表示"没有该轨道下标"。

### 字段（均 public final）
- `key`：这条走法的 key，见 `getKey`。
- `routeIds`：整个行程按顺序的路线 ID。
- `cutStops`：中途离开的路线 → 列车离开该路线时所在的停站序号。
- `leaveRail`：主路径上列车离开主路径的轨道下标。
- `path`：从离开点到最后一条路线终点的其余路径。
- `lastPlatform`：最后一条路线的末站（宿主站台类型 `P`）。
- `lapStart`：若终点是环线，`path` 中其第一站所在轨道下标；否则 `NO_RAIL`。
- `isLapReversed`：一圈结束时列车是否沿进入的轨道原路返回。
- `entryRail`：列车在环线中途加入时 `path` 中的轨道下标；否则 `NO_RAIL`。
- `entryStops`：对于中途加入的（非上述环线）路线，列车加入时所在的停站序号。
- `entryLeads`：对于这些路线，从其首站到加入站的路径——列车不实际跑这段，但按它来计划（服务从首站计时）。

### `public TripTail(String key, List<Long> routeIds, Map<Long, Integer> cutStops, int leaveRail, List<T> path, P lastPlatform, int lapStart, boolean isLapReversed, int entryRail)`
委托完整构造，`entryStops`、`entryLeads` 为新的空 `HashMap`。

### `public TripTail(String key, …, int entryRail, Map<Long, Integer> entryStops, Map<Long, List<T>> entryLeads)`
按参数原样赋值（不复制集合）。主要调用者：`common/src/main/java/mtr/data/Depot.java:3133`。

### `public static String getKey(List<RouteTimetable.ServiceLink> links)`
- 作用：由一串服务接续生成与班次时刻无关的走法 key：每个链接写 `routeId@fromStop`，若 `toStop > 0` 追加 `+toStop`，链接之间以 `>` 分隔。
- 返回值：key 字符串；空列表返回 `""`。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:2985`、`Depot.java:3133`、`transitsim/sim/TrainService.java:2166`。
