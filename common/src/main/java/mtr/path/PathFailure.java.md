# PathFailure.java（本分支新增）

描述线路为什么找不到路径，供车厂界面、网页编辑器显示（文字由客户端 `PathFailureText` 生成）。服务端由 `Depot`/`RoutePaths` 生成（`common/src/main/java/mtr/data/Depot.java:3558`、`:4137`），随包发给客户端（`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:344`、`:618` 读取），存于 `ClientData.ROUTE_PATH_FAILURES`。

## 类 `PathFailure`（final，不可变）

### 常量 `NONE`
- 无失败。

### 字段
- `type`（`Type`）。
- `pos`：相关位置（节点 `BlockPos.asLong`、站台 id 或联络线 id，含义随类型）。
- `node1`、`node2`：挡路轨道的两个节点，或 0；对 `CONNECTING_LINE` 为必须经过的联络线第一段轨道；对 `DISCONNECTED`、`NO_ROUTE`、`WAYPOINT_UNREACHABLE` 为走不通的两个停靠站的站台 id（未知为 0）；对 `OTHER_LINE` 为他线所属第一段轨道的两端；对 `CYCLE_BACK`，`node1` 为应到达的站台。

### 构造函数 `PathFailure(Type type, long pos)` / `PathFailure(Type type, long pos, long node1, long node2)`

### 包字段顺序（`writePacket` / `readPacket`）
1. `byte type.ordinal()`；2. `long pos`；3. `long node1`；4. `long node2`。
- 读到超出范围的类型序号时按 `NO_ROUTE` 处理。注意：枚举序号就是协议，新增类型只能追加在末尾（`BOUNDARY` 因此保留）。

### `static PathFailure diagnose(RailGraph<Rail> graph, List<SavedRailBase> savedRailBases, List<List<BlockPos>> sectionWaypoints, int reached)`
- 作用：站台少于 2 个返回 `NONE`。没有图（旧寻路）时：有途经点的段返回 `OLD_SEARCH`（旧寻路不支持途经点），否则 `NO_ROUTE`。有图时取失败段 `section = max(reached − 1, 0)`：已是最后一段返回 `NONE`；该段某个途经点不在图中返回 `MISSING_WAYPOINT`（pos 为该节点）；两站台起点在图中不连通返回 `DISCONNECTED`（带两站台 id）；否则 `NO_ROUTE`（寻路边界只缩小搜索，不会是原因）。
- 参数：`reached` 为 `PathFinder`/`DeterministicPathFinder.findPath` 返回的到达数。

## 枚举 `Type`（序号即协议，只能追加）
- `NONE`、`OLD_SEARCH`、`MISSING_WAYPOINT`、`DISCONNECTED`、`NO_ROUTE`；
- `TURN_BACK`：线路列出的折返线一个都到不了（报第一个）；
- `TURN_BACK_UNUSED`：某条列出的折返线到不了，或与第一条不是从同样的轨道进入，列车不会使用它；
- `BOUNDARY`：不再产生（寻路边界只缩小搜索），保留以维持后续序号；
- `DUTY`：按运用运行的车厂中某个运用的路径找不到，该列车在对应日期不出车；
- `CONNECTING_LINE`：只能经由联络线找到路径，而其他线路避开联络线；pos 为联络线 id；
- `CYCLE_BACK`：留在线上的列车无法从车厂最后一条线路的末站回到第一条线路的首站；pos 为出发站台，`node1` 为应到达的站台；
- `NOT_KEY_NODE`：线路列出的关键节点已不再是关键节点，各段在此接不上；pos 为该节点；
- `OTHER_LINE`：只能经由他线所属轨道找到路径（阶段文档第 2 轮）；`node1/node2` 为第一段这样的轨道两端；
- `WAYPOINT_UNREACHABLE`：能从一站到下一站，但不能经过该段列出的关键节点；pos 为该节点，`node1/node2` 为两站台。
