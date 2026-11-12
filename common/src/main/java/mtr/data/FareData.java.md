# FareData.java

游戏中的按距离计费（设计文档 6.13，0.68.0）：票务规则（0.73.0；在组件 `transitsim.fare` 中称为 group）、用于计算票价的网络（由各线路路径生成）、以及一个车站的闸机代表什么。

规则由玩家手动建立和删除（`NetworkSettings#fareRules`）；一条线（line）按其被放入的规则计费，对其所有线路生效（`NetworkSettings#fareRouteGroups`），否则用默认规则。车站属于停靠它的线路的规则，除非手动设置（`NetworkSettings#fareStationGroups`）；其闸机（按车站区域查找，区域可按高度设置）代表其规则，有多个规则时为共享闸机。规则的票价档位最远到其参考线路的长度。规则本身在组件 `transitsim.fare`。`RailwayData` 持有一个实例（`RailwayData.java:231-234`）；`TicketSystem`（闸机/检票）、`FareScreen`（票务界面）、`FarePackets` 使用静态方法。

## final class FareData

### 字段与常量
- `AUTO_PREFIX = "line:"`。
- `network`、`networkKey`：缓存的 `FareNetwork` 及其键。
- `SEARCH_AGAIN_MILLIS = 60000`：没找到路径的"直线计费"线路再次搜索前的间隔。
- `searchedMillis`：线路 id → 上次搜索时间。
- `searching`（`AtomicBoolean`）：是否有后台搜索在进行。

### `public static String getAutoGroupKey(Route route)`
- 返回：线路所属"线"的键 `line:<交通方式>:<线路键>`，同一条线的所有方向与服务类型相同；规则按它选择，规则的参考线路也用它命名。
- 调用者：`FareScreen.java:390`。

### `public static String getGroupKey(Route route, DataCache dataCache, NetworkSettings settings)`
- 返回：线路适用的规则：自身设置（且规则存在）；否则同一条线中其它线路的设置（线路在规则设定后才加入该线时）；否则默认规则。
- 调用者：`FareScreen.java:398`、`FareScreen.java:449`。

### `public static Map<String, String> getAllGroups(DataCache dataCache)` / `getAllGroups(DataCache, NetworkSettings)`
- 返回：所有规则 id → 名称（名称为空时用 id），保持设置中的顺序（默认规则在前）。
- 调用者：`FarePackets.java:44`、`FareScreen.java:118`。

### `public static Map<String, String> getLines(DataCache dataCache)`
- 返回：显示中（非隐藏、至少 2 站）线路的线键 → 格式化线名，按键排序。
- 调用者：`FareScreen.java:91`。

### `public static Set<String> getRouteGroups(DataCache dataCache, long stationId)` / `getRouteGroups(DataCache, NetworkSettings, long)`
- 返回：停靠该车站的线路的规则集合（`TreeSet`）。
- 调用者：`FareScreen.java:354`、`FareScreen.java:368`。

### `public static Set<String> getStationGroups(DataCache dataCache, long stationId)`
- 返回：手动设置（只保留存在的规则）的规则；为空时返回 `getRouteGroups`。

### `public FareNetwork getNetwork(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails)`
- 作用：返回计费网络；线路、其规则或路径变化时重建。键由 `routePaths.getTimingVersion()`、`fareRouteGroups`、`fareRules` 的哈希，以及每条线路的 id、隐藏状态、线键、停站序列、手绘路径（大小与首尾元素的对象身份哈希）组合。重建时：`new FareNetwork(getLegs(...))`，每条规则 `setLongestBlocks(id, 参考线路长度)`（0.73.0），再 `findPathsLater`。
- 副作用：可能发起后台找路。
- 调用者：`RailwayData.java:234`。

### `private void findPathsLater(RailwayData railwayData, List<Route> crowFlies)`
- 作用（2026-11-12）：对按直线计费的线路，在后台查找路径；同时只进行一个搜索，每条线路最多每分钟一次；找到后网络会因 `timingVersion` 变化而重建。调用 `railwayData.findRoutePathsInBackground(toFind, 完成回调)`。

### `private static List<FareNetwork.Leg> getLegs(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails, List<Route> crowFlies, Map<String, Long> lineLengths)`
- 作用：每条显示中线路相邻两站之间的一段：距离优先用手绘路径（`RoutePaths.getBlocksBetweenStops`），否则用已找到的路径，否则直线距离（站台中点之间）。可找路但尚无路径的线路加入 `crowFlies`。`lineLengths` 记录每条线最长线路首站到末站的长度。两站任一不属于车站时跳过该段。
- 返回：`Leg(规则, 起车站 id, 终车站 id, 方块数)` 列表。

### `public static FareTrip.Gate getGate(DataCache dataCache, Station station)`
- 返回：车站闸机：`Gate(车站 id, 规则集合, 是否共享 = 规则数 > 1)`。
- 调用者：`TicketSystem.java:76`、`TicketSystem.java:260`、`TicketSystem.java:278`。

### `public static FareLevels getLevels(RailwayData railwayData, String group)`
- 返回：规则的票价档位；无则默认 `new FareLevels()`。
- 调用者：`TicketSystem.java:287`、`FareScreen.java:293`。

### `public static String getGroupNames(DataCache dataCache, Set<String> groups)`
- 返回：规则名称以 `", "` 连接，供消息使用。
- 调用者：`TicketSystem.java:295`。
