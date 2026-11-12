# FareTrip.java

一次行程（从某闸机进、从另一闸机出）应收多少钱（设计文档 6.13）。闸机要么属于一个路线组，要么由该站所有组共用：
- 从某组专用闸机出站：该组就是行程的最后一组；
- 从共用闸机出站：由跨组最少的票价路径决定；
- 两端都是共用闸机：在可能指代的各组中取最便宜的。

路径每段按其组自己的档位计价、各自封顶，然后相加。同站进出收该组起步价或超时罚款。纯静态工具类，使用 `FareNetwork`（找路径、最长距离）和 `FareLevels`（每段票价）。游戏侧 `TicketSystem`（`TicketSystem.java:280`）出闸时调用 `charge`，`FareData` 构造 `Gate`。

## 类 FareTrip（final，不可实例化）
- 私有构造函数 `FareTrip()`：防止实例化。

### `static Result charge(FareNetwork network, Function<String, FareLevels> levels, Gate entry, Gate exit, long minutesInside)`
- 作用：计算一次行程的票价。
  1. 包装 `levels`：组为 null 或找不到规则时用默认 `new FareLevels()`。
  2. 进出站相同 → `sameStation`。
  3. 否则对每个进站组（闸机无组时用 null 表示"任意"）调用一次 `network.find`，终点组集合列表为每个出站组各一个（null → 空集合）。对每条找到的路径累加各段 `getFare`（仅当 `needsLongest()` 时才去求 `getLongestBlocks`）。
  4. 择优：两端都共用时按 票价 → 跨组数 → 距离 取最小；否则按 跨组数 → 距离 → 票价 取最小。
- 参数：`network` 票价网络；`levels` 组名 → 票价规则；`entry`/`exit` 进出闸机；`minutesInside` 从进闸到出闸的分钟数（只在同站时使用）。
- 返回：`Result`；所有组合都不连通时 `fare = -1`、`path = null`、`group = null`。
- 副作用：可能写入 `network` 的路径缓存和最长距离缓存。
- 主要调用者：`TicketSystem.java:280`、`FareTest.java:120`、`FarePathEquivalenceTest.java:67`。

### `static Result charge(FareNetwork network, Map<String, FareLevels> levels, Gate entry, Gate exit, long minutesInside)`
- 作用：以 Map 提供票价规则的重载，委托给上面的版本（`levels::get`）。
- 主要调用者：`FareTest.java:137`；测试中多处。

### `private static Result sameStation(Function<String, FareLevels> levelsOf, Gate entry, Gate exit, long minutesInside)`
- 作用：同站进出计价。候选组优先取出站专用闸机的组，其次进站专用闸机的组，否则两者组的并集；对每个候选（空则 null → 默认规则）取 `getSameStationFare(minutesInside)`，选最便宜的。`overtime` 标记取所选组是否启用超时且已超时。
- 返回：`Result(bestFare, null, bestGroup, overtime)`。
- 调用者：`charge`（第 77 行）。

## 内部类 Gate（public static final）
计价视角下的闸机。
- `station`（long）：车站 id。
- `groups`（不可修改 `Set<String>`，保持插入顺序）：该闸机可代表的组。
- `shared`（boolean）：是否为全站共用闸机。
- 构造函数 `Gate(long station, Set<String> groups, boolean shared)`：拷贝组集合。调用者：`FareData.java:217`（`shared = groups.size() > 1`）。

## 内部类 Result（public static final）
- `fare`（int）：票价，-1 表示两站不连通。
- `path`（`FareNetwork.Path`）：计价所用路径；同站或无路径时 null。
- `group`（String）：同站时为收费的组，否则为路径最后一段的组（路径为空时取出站组）。
- `overtime`（boolean）：是否按超时罚款收费（仅同站情况可能为 true）。
- 构造函数（包内可见）按字段赋值；调用者：`charge`（第 116、122 行）、`sameStation`（第 148 行）。
