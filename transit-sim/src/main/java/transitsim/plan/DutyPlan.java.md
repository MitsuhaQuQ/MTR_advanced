# DutyPlan.java

车厂为一条运用路径算出的公共部分，由该车厂所有停车线共享：运用的各段从首站到末站连成的主体 `body`，以及计划路径所需的路线信息。每条停车线再加上自己的出库和回库路段（设计文档 6.12，E2 块）。游戏侧 `mtr.data.Depot` 创建（`Depot.java:3352`），`mtr.data.Siding` 使用（`Siding.java:1431`、`:1444`），`StopTimes`/`TripPath` 按其字段计划。

## 类 `DutyPlan<T extends PlanTrack, P>`

`P` 为宿主站台类型。

### 字段
- `key`（final）：运用路径 key。
- `routeIds`（final）：按顺序跑的路线。
- `cutStops`（final）：按路线，中途离开时的停站序号。
- `entryStops`（final）：按路线，中途加入时的停站序号。
- `entryLeads`（final）：按路线，从首站到加入站的计划用路径（不实际运行），参见 `TripTail`。
- `body`（final）：从运用首站到末站的路径。
- `segmentStarts`（final）：`body` 中每一段首站所在轨道下标。
- `indexBoundaries`（可变）：模板边界——服务运行段及其间连接移动的分界下标；构造时复制 `segmentStarts`，`Depot.java:3355` 会用更细的边界覆盖，`Siding.java:1444` 读取。
- `firstPlatform`、`lastPlatform`（final）：首末站台。
- `startsOnLine`、`staysOnLine`（final）：运用是否从线上（非车厂）开始、是否留在线上过夜（不回库），据字段名推断。
- `turnBackSections`（final，初始空）：运用中可有多种折返方式的位置；每个 section 从 `body` 上某停站（section 起点即该站轨道）到下一段首站，见 `TurnBackSection`。
- `segmentCuts`（final `TreeMap`）：按段号，该段跑到的本路线停站（不是路线末站时）（D4）。同一路线可在运用中出现多段，各自在不同站被截/接入，`cutStops` 按路线无法区分。
- `segmentEntries`（final `TreeMap`）：按段号，该段加入本路线的停站（不是首站时）（D4）。
- `segmentsKnown`：`segmentCuts`/`segmentEntries` 是否已填写；为真时计划按段而非按路线（`Depot.java:3359` 设置，`StopTimes` 读取）。

### `public DutyPlan(String key, List<Long> routeIds, Map<Long, Integer> cutStops, Map<Long, Integer> entryStops, Map<Long, List<T>> entryLeads, List<T> body, int[] segmentStarts, P firstPlatform, P lastPlatform, boolean startsOnLine, boolean staysOnLine)`
- 作用：原样赋值（集合不复制），`indexBoundaries = segmentStarts.clone()`。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:3352`。
