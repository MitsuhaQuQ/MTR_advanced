# TimetableBinary.java

路线时刻表（包括所有表、接续、回送班次、例外等）的二进制格式，供组件自己的数据集使用（S4e）；宿主的存档仍用自己的格式。格式按"后加的字段放在最后、读取时 `hasMore()` 判断"的方式向后兼容。游戏侧 `mtr.data.Depot:2005`、`mtr.data.LineDuties:451` 写出给组件；组件 `transitsim.sim.SimDataset` 读写；`mtr.advanced.SandboxRuns:190` 读取。

## 类 `TimetableBinary`（final，工具类）

### `private TimetableBinary()`
禁止实例化。

### `public static void write(BinaryWriter writer, RouteTimetable timetable)`
- 作用：按以下顺序写出：
  1. `enabled`（boolean）、`entryPlatformId`（long）、表数；
  2. 每个表：`marginPercent`、`weekdays`、`services`（数量 + 各 int）、`links`（数量 + 每条：班次、routeId、目标班次、fromStop、toStop）、`returnServices`（数量 + 各 int）、`ServiceExceptions.write` 写的例外；
  3. 每个表的 `returnOrders`、`deployOrders`（见 `writeOrders`）；
  4. 每个表的 `suspended`（数量 + 排序后各 int）；
  5. 每个表的最低性能 `minAcceleration`、`minDeceleration`、`minMaxSpeed`（float 的 int 位）。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:2005`、`mtr/data/LineDuties.java:451`、`transitsim/sim/SimDataset.java:787`。

### `private static void writeOrders(BinaryWriter writer, Map<Integer, RouteTimetable.ServiceOrder> orders)`
按班次号排序写：数量，然后每条（班次 int、`order.stop` int、`order.depotId` long）。

### `private static void readOrders(BinaryReader reader, Map<Integer, RouteTimetable.ServiceOrder> orders)`
读回上面格式，放入 `orders`。

### `public static RouteTimetable read(BinaryReader reader)`
- 作用：新建 `RouteTimetable` 并按 `write` 的顺序填充；第 0 表就是返回的对象本身，其余表通过 `addTable` 加入。第 3–5 部分只在 `reader.hasMore()` 时读取（兼容旧数据）。最后调用 `timetable.sanitize()`。注释：只作为另一路线接续运行的班次的标记，由拥有全部路线的一方负责，这里不做。
- 返回值：时刻表。
- 副作用：数据截断时由 reader 抛异常（具体类型取决于 `BinaryReader` 实现，未确定）。
- 主要调用者：`transitsim/sim/SimDataset.java:316`、`SimDataset.java:1278`、`common/src/main/java/mtr/advanced/SandboxRuns.java:190`。
