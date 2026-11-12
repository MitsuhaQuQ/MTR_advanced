# core/schematic.ts

单条路线的配线示意图：沿路线路径的轨道（`api/route-layout`，服务器 `RouteLayouts` 已把它拉直为"沿路径距离 × 侧向偏移"）铺在平行的"道"上，加上道岔、站台、车站，以及每段轨道上跑哪些路线。
- 道按顺序而非距离：路径本身是第 0 道；旁边的其他股道不论多远，依次占同侧往外的下一道（相距 4 或 5 格的算同一道）。偏移不变的轨道是道上的水平段；偏移变化的（道岔导曲线、渡线、离开线路的轨道）是两道之间的斜线。
- 沿线方向，重要位置（道岔、站台两端、停站、换道处）之间保持原间距，长的空段被缩短，让车站和道岔区有空间。

纯函数：页面 `views/TrackDiagram.tsx` 负责绘制；`test/schematic.test.ts` 检查。

## 类型
- `RouteLayout`：`api/route-layout` 的回答：`ready?`、`found`、`length`（路径长度，格）、`halfWidth?`、`truncated?`、`stops`（各停站在路径上的距离，-1 为不在路径上）、`rails`（每段 `[距离, 偏移, 距离, 偏移, …]`）、`railKeys`（每段的 `[节点, 节点, 隔离状态]`，状态 0 普通、1 联络线、2 放行、3 边界）、`turnouts`（`[距离, 偏移]`）、`platforms`（`id`、`name`、`station`、`stationName`、`passingTrack?`、`line: [d1, o1, d2, o2]`）、`boundaryNodes?`。
- `Usage`：轨道键 → 跑在上面的路线 `{route, color}[]`。
- `Segment`：画出的一段：`x1`、`x2`（缩短后的横坐标）、`lane1`、`lane2`、`diagonal`、`key`、`a`、`b`、`status`、`routes`（本图路线排第一）、`ours`。
- `Schematic`：`width`、`lanes: {min, max}`、`segments`、`turnouts: {x, lane}[]`、`platforms`（含 `x1`、`x2`、`lane`、`ours`）、`stations`（`{station, name, x, stop（最近停站序号，30 以内，否则 -1）}`）、`stops: {stop, x}[]`、`boundaries: {x, lane}[]`、`breaks`（被缩短处的横坐标，用于画折断符号）。
- `Piece`（内部）：一段轨道按较小距离到较大距离：`index`、`d1`、`d2`、`o1`、`o2`、`level`（两端偏移差 ≤ `CHANGES_LANE`）、`mean`（平均偏移）、`a`、`b`、`status`、`startNode`、`endNode`（哪一端是哪个节点服务器不发，是猜测）。

## 常量（内部）
- `SAME_TRACK = 1.6`：偏移差小于此算同一股道。`CHANGES_LANE = 1.2`：两端偏移差大于此算换道。`KEEP = 40`：重要位置之间超过 40 格的空段被缩短。

## 函数
- `railKey(a, b)`（导出）：两个节点键（长整数字符串）组成的轨道键，较小者在前（同 `editor.js` 的 `railKey`）。调用者：本文件；`legacy/retired.ts:255` 用的是旧代码的 `v.railKey`（是否为本函数未确定）。
- `usageOf(network, first?)`（导出）：从全网数据（`api/network`：路线路径为节点序号，节点第三个值为键）得出每段轨道上的路线；`first` 路线排在最前；同一路线在同一轨道只记一次。调用者：`views/TrackDiagram.tsx:81`。
- `buildSchematic(layout, usage, routeId, routePlatforms = new Set())`（导出）：
  - 把每段转成 `Piece`。
  - `laneAt(offset, from, to)`：某偏移在某段距离范围内的道号：同侧、在它与路径之间的水平段平均偏移按 `SAME_TRACK` 分组计数，加 1，带符号；接近 0 为第 0 道。
  - 水平段取 `laneAt(mean)`；记录每个节点所在的道（取最靠近路径的）。斜线两端用 `endLane`（节点已知道号，否则按偏移：接近 0 为 0，否则比该侧最外再多一道），并在两种端点对应方式中选与偏移符号更吻合的。
  - 重要位置：0、终点、道岔、站台两端、停站、斜线两端；相邻间距 ≤40 保持，否则为 `40 + 3√(差-40)`；超过 120 的记录 `breaks`。`xOf(d)` 在重要位置之间按比例插值。
  - 生成 `segments`、`turnouts`、`boundaries`、`platforms`（`ours` 表示在 `routePlatforms` 中），更新道号范围；按车站合并站台求站名位置并匹配最近停站；`stops` 只保留在路径上的。
  - 返回 `Schematic`。调用者：`views/TrackDiagram.tsx:83`。
