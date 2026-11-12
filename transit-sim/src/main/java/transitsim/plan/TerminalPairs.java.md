# TerminalPairs.java

确定"列车到达路线终点后接着跑哪一班"，只收确定的配对而非猜测：一是玩家设置的（某班从末站接续另一路线首站，与直通运转同一数据），二是运用里前后相连的两段。玩家设置优先。剩下未配对的班次由使用它的冲突检查 `TimetableConflicts` 按最近原则配对，网页编辑器同理。类注释还说明：在到达站台原地折返的列车从到达起一直占用站台到下一班出发，因此末站的出发时间取下一班的，下一班首站到达取自己的。被游戏侧 `mtr.packet.PacketTrainDataGuiServer:801` 调用，结果交给 `TimetableConflicts`。

## 类 `TerminalPairs`（final，工具类）

### `private TerminalPairs()`
禁止实例化。

### `public static Map<Long, Map<Integer, long[]>> fixedNext(Map<Long, RouteTimetable> routes, List<List<DepotDuties.Segment>> duties)`
- 作用：
  1. 遍历每条运用（一列车一天内按顺序的各段），若某段跑到路线终点（`segment.toStop < 0`）且下一段从路线首站开始（`following.fromStop == 0`），记录 前段(路线, 班次) → 后段 `{routeId, service}`。
  2. 再遍历各路线时刻表各表的 `links`（玩家设置的接续），若 `link.fromStop < 0 && link.toStop == 0`，同样记录；后写入的覆盖前者，因此玩家设置优先。
- 参数：
  - `routes`：路线 ID → 时刻表。
  - `duties`：各车厂运用，每个元素是一列车一天按序的 `DepotDuties.Segment`。
- 返回值：路线 ID → (班次号 → `{下一路线ID, 下一班次号}`)。
- 副作用：无。
- 主要调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:801`。
