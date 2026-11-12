# PathFailureText.java（本分支新增）

客户端：把寻路失败原因（`PathFailure`）和停车轨出入库失败原因转为文字，供车厂界面和网页编辑器的线路视图显示。调用者：`common/src/main/java/mtr/client/editor/EditorData.java:273`、`:276`、`:291`、`:554`。

## 类 `PathFailureText`（final，私有构造）

### `static String get(PathFailure failure)`
- 作用：按类型返回翻译文字（键 `gui.mtr.path_failure_<类型>`）：`MISSING_WAYPOINT`/`TURN_BACK`/`TURN_BACK_UNUSED`/`BOUNDARY`/`NOT_KEY_NODE` 带节点坐标；`DISCONNECTED`/`NO_ROUTE` 有站台时带两站名（`_between` 版本）；`WAYPOINT_UNREACHABLE` 带两站与节点；`CONNECTING_LINE` 带联络线名和挡路轨道中点坐标；`OTHER_LINE` 带他线轨道中点；`CYCLE_BACK` 带出发与应到站台；`DUTY`、`OLD_SEARCH` 无参数；`NONE` 等返回空串。

### `static String depotWay(long[] reason)`
- 作用：停车轨无出入库路径的原因（`DepotLegs.outReasons` 的格式：首元素为原因码）：`REASON_NO_TRACK`、`REASON_AGAINST`（附后续元素中的站台名列表）、`REASON_ENTRANCE_UNSET`、`REASON_RULE`、`REASON_WRONG_WAY`，对应 `gui.mtr.depot_way_*`；空或未知返回空串。

### `private static String platformName(long platformId)`
- `车站名 站台名`，站台不存在时为 id。

### `private static String format(BlockPos pos)`
- `x, y, z`。
