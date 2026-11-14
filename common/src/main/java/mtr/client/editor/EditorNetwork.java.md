# EditorNetwork.java

高级编辑器"全网配线"视图的数据：全网的轨道布局，来自客户端为仪表盘地图已有的数据——每条轨道端到端经过其曲线上的若干点（`ClientData.MAP_RAIL_SHAPES`），是否为站台/停车线/停留闭塞，其隔离状态（`MapIsolation`），车站，以及各线路的路径；另附服务器算出的轨道区段所有者（网页分层第 2 步，`TrackOwnersPackets`）。客户端代码，由 `EditorServer.java:537` 在游戏线程调用。

## final class EditorNetwork

### 常量（轨道种类）
- `KIND_PLAIN = 0`、`KIND_PLATFORM = 1`、`KIND_SIDING = 2`。
- `KIND_STABLING = 3`：停留闭塞中的轨道（"运用补完"第 4 步，终点站附近一列车等候的位置；只在游戏中标记）。

### `private EditorNetwork()`
工具类，禁止实例化。

### `public static JsonObject build(String modeName, boolean fresh, int seenVersion)`
- 参数：`modeName` 要显示的交通方式，空则取第一个有轨道的；`fresh` 页面刚打开视图，此时向服务器重新请求地图（`PacketTrainDataGuiClient.requestMapRailsC2S()`）；`seenVersion` 页面已有的版本，未变时只回版本。
- 版本号：由 `MapDataCache.getVersion()`、交通方式、信号闭塞版本、停留颜色、`TrackOwnersPackets.version` 组合；与 `seenVersion` 相同且非 `fresh` 时返回 `{version, same: true}`。
- 返回 JSON：
  - `version`、`modes[]`（所有有轨道的交通方式）、`mode`；
  - `nodes[]`：`[x+0.5, z+0.5, 节点 id 字符串]`；
  - `rails[]`：`[较小节点下标, 较大节点下标, 种类, 隔离状态, x, z, ...]`，曲线点按服务器发送的从较小节点开始，四舍五入到 0.25；
  - `owners`：`{ready (TrackOwnersPackets.version > 0), names[], rails[]}`，`rails` 每项 `[轨道下标, manual(1=手动), 区段编号, 逻辑线路 id 或 "", 所有者下标...]`（区段编号使页面能把一个区段所有者的变化显示在其所有轨道上；逻辑线路为 2026-11-09 新增）；
  - `boundaryNodes[]`：寻路边界节点的下标（`ClientData.PATH_REGIONS.boundaries`）；
  - `stations[]`：有该交通方式站台的车站 `{id, name, x, z}`，位置为其站台中点的平均；
  - `routes[]`：未隐藏、路径至少 2 个节点的线路 `{id, name, color, lineKey ("模式|线路键"), nodes[] (节点下标), stopNodes[]}`，`stopNodes` 为每个停站其站台轨道终点在 `nodes` 中的下标或 −1（网页分层第 3 步，让页面知道线路在两站之间走哪些轨道）。
  - `dutyUses[]`（0.80.0）：这种运输方式各车厂运用路径走过的轨道，`[节点下标, 节点下标（按走的方向）, 线路 id, 车厂 id]`，来自客户端车厂的 `clientTrackUses`；给车站股道运用图（`StationFlows`）区分折返、换站台、出入库这些运用走行。网络的 `version` 也算上 `Depot.clientTrackUsesVersion`，寻路结果一到就重发。
- 副作用：`fresh` 时发请求包。
- 主要调用者：`EditorServer.java:537`。

### `private static int nodeIndex(long node, Map<Long, Integer> nodeIndices, JsonArray nodesArray)`
- 作用：取节点在节点表中的下标，第一次遇到时追加 `[x+0.5, z+0.5, id]`（节点画在方块中心）。
