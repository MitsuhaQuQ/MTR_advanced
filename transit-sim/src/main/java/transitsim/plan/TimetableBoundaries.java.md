# TimetableBoundaries.java

"时刻边界"规则：服务在哪里可以中途离开自己的路线转到另一条，或中途加入一条路线。类似日本直通运转的分界站，只能在宿主标记为时刻边界的站台（参数 `isBoundaryPlatform`）且必须停车。没有边界时，服务只能从本路线末站接续到下一路线首站。环线在引入边界之前就允许在任何停车站离开/加入，至今仍然如此，以保证旧存档可用。被游戏侧时刻表界面 `mtr.screen.TimetableScreen`、网页编辑器 `mtr.client.editor.EditorData`/`EditorActions` 和服务器收包 `mtr.packet.PacketTrainDataGuiServer` 使用。

## 接口 `TimetableBoundaries`（只有静态方法）

### `static boolean isBoundaryStop(PlanRoute route, int stop, LongPredicate isBoundaryPlatform)`
- 作用：判断服务能否在该路线的第 `stop` 站离开或加入。首站（≤0）和末站（≥size-1）返回 false；该站通过不停返回 false；环线返回 true；否则看站台是否为边界站台。
- 参数：`stop` 停站序号；`isBoundaryPlatform` 站台 ID → 是否标记为边界。
- 主要调用者：`common/src/main/java/mtr/client/editor/EditorData.java:492`、`mtr/screen/TimetableScreen.java:161`、`TimetableScreen.java:174`。

### `static boolean hasBoundaryStops(PlanRoute route, LongPredicate isBoundaryPlatform)`
- 返回值：路线中间是否存在任一边界站。
- 主要调用者：`common/src/main/java/mtr/screen/TimetableScreen.java:516`、`:518`、`:547`。

### `static String checkLink(PlanRoute route, RouteTimetable.ServiceLink link, LongFunction<? extends PlanRoute> getRoute, LongPredicate isBoundaryPlatform)`
- 作用：检查一条服务接续（直通）是否合法。
- 参数：`route` 本路线；`link` 接续（目标 `routeId`、离开站 `fromStop`、加入站 `toStop`）；`getRoute` 按 ID 查路线。
- 返回值：不合法时返回中文原因字符串（"直通到的线路不存在"、"直通到的线路运输方式不同"、"只能在标了时刻边界、并且停车的站台接出"、"只能在标了时刻边界、并且停车的站台接入"）；合法返回 null。`fromStop` 为负或为末站视为从末站接出，不检查；`toStop ≤ 0` 视为从首站接入，不检查。
- 主要调用者：`common/src/main/java/mtr/client/editor/EditorActions.java:979`、本接口 `sanitizeLinks`。

### `static boolean sanitizeLinks(PlanRoute route, LongFunction<? extends PlanRoute> getRoute, LongPredicate isBoundaryPlatform)`
- 作用：删除路线所有表中不合规的接续（只有不做检查的客户端会发来这种数据）。
- 返回值：是否删除了任何接续。
- 副作用：修改 `route.getTimetable()` 各表的 `links`。
- 主要调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:385`。
