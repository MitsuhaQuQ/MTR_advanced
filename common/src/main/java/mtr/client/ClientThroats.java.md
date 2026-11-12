# ClientThroats.java（本分支新增）

客户端：根据仪表板地图数据（每条线路的地图路径、每个节点相连的轨道）计算各线路经过的车站咽喉道岔（仿真组件 `transitsim.network.ThroatRoutes`，L2），供网页编辑器显示某停靠站的咽喉以及在那里与之交叉的其他线路；时刻冲突检查则在服务端用线路自身路径计算。调用者：`common/src/main/java/mtr/client/editor/EditorData.java:65`（`compute`）、`:530`（`crossings`）。

## 类 `ClientThroats`（final，私有构造）

### `static Map<Long, List<ThroatRoutes.StopThroat>> compute()`
- 作用：由 `ClientData.MAP_RAILS` 建立节点相邻表；联锁区按 `ClientData.interlockColor` 取（`SIGNAL_BLOCKS.getZones`）；对每条有地图路径的未隐藏线路，找出每个停靠站台在路径中的轨道下标（两端相邻出现处，按顺序向后找，找不到为 −1），调用 `ThroatRoutes.of(节点, 节点→xz 坐标, 停靠轨道下标, 节点→相邻数, 轨道→联锁区)`。
- 返回：线路 id → 各停靠站的咽喉（进站与出站）。

## 内部类 `Crossing`（final）
- 另一条线路的停靠站在同一车站与本站的进/出站咽喉交叉：`otherRouteId`、`otherStop`（对方停靠序号）、`entry`（本站一侧是进站）、`otherEntry`、`node`（交叉的道岔节点或联锁区入口节点）、`zone`（两者是共用同一联锁区而非道岔）。

### `static List<Crossing> crossings(Map<Long, List<ThroatRoutes.StopThroat>> throats, Route route, int stop, int limit)`
- 作用：对同一车站的其他线路的每个停靠站，两两比较本站与对方的进站/出站咽喉（`ThroatRoutes.findConflict`），有冲突就记一条，最多 `limit` 条。
- 返回：交叉列表（本线路无咽喉信息或车站未知时为空）。
