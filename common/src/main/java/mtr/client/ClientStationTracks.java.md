# ClientStationTracks.java（本分支新增）

客户端：用客户端已知的数据（站台、各线路停靠、各线路在地图路径上经过每个站台的方向）计算车站的股道属性（仿真组件 `transitsim.network.StationTracks`，L2：上行/下行、正线/到发线等），供网页编辑器和站台界面使用，保证两处说法一致。调用者：`common/src/main/java/mtr/client/editor/EditorData.java:678`、`common/src/main/java/mtr/screen/PlatformScreen.java:65`。

## 类 `ClientStationTracks`（final，私有构造）

### `static Map<Long, StationTracks.Track> compute()`
- 作用：为每个站台构造 `PlatformInput`（站台 id、所属车站、所在寻路网络编号（来自 `ClientData.PATH_REGIONS.platformGraph`）、名称、是否通过线、两端坐标等）；为每条未隐藏且至少两站的线路构造 `RouteInput`：对每个停靠站，在该线路地图路径节点（`ClientData.MAP_ROUTES`）中从上次位置之后找到站台两端相邻出现的位置，以出现顺序决定经过方向（0 或 1，找不到为 −1）；首末站以外设为通过的记为通过。然后 `StationTracks.compute(platforms, routes)`。
- 返回：站台 id → 股道属性。

### `static String describe(StationTracks.Track track)`
- 作用：把股道属性翻译为文字，如"上行 正线"（`gui.mtr.track_<方向>`、`gui.mtr.track_<种类>`）；null 或无信息返回空串。
