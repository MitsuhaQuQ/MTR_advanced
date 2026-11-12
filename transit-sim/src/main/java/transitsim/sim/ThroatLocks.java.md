# ThroatLocks.java

按运用运行的列车在运行中锁定其经过车站咽喉区的进路（解耦文档 5.4，L2），如同进站信号：出站进路的道岔随离站运行申请一起申请；下一站进站进路的道岔在列车进入其制动距离时申请，未获批前停在咽喉前。每个道岔一直持有到车尾越过它。道岔与单线一样是同一仲裁器（`RunReservations`）的资源，所以以不同进路使用同一道岔的列车依次获得，同进路的列车共享。

每列车每条路径保留一个本类对象；两个宿主（游戏服务器列车与组件运行器）用法相同。

在整体中的位置：游戏侧 `TrainServer`（`:1104` 读 `enabled`、`:1115` `isFor`、`:1132` 构造、`:1092` `approach`、`:1094` `getHeld`、`:1389` `onGranted`、`:1400`-`:1403` `getExit`/`toMoves`/`setRequested`、`:459` `isBlocked`、`:388` `position`）；组件 `TickRunner`（`:2516`、`:2519`、`:2536`、`:2500`、`:2051` `getItems`、`:2054` `toMoves`、`:2661`、`:2672`-`:2675`、`:3011`）；`SecurityConfig.java:146` 设 `enabled`；`PlanCheck.java:152` 用 `position`。

## 类 ThroatLocks（final）

### 静态字段与常量
- `enabled`（public static volatile boolean）：关闭时不锁任何东西，列车照旧运行；由服务器设置关闭，日仿真工具用 `-Dmtr.throatLocks=false`（默认开启）。
- `APPROACH_MARGIN = 32`：在制动距离之外再提前多少方块申请咽喉进路。

### 字段
- `pathKey`（Object）：宿主路径，换新路径即替换本对象。
- `pathSize`（int）：路径节点数。
- `stops`（List<Stop>）：各停站及其咽喉进路。
- `granted`（TreeMap<Long, ThroatRoutes.Item>）：已获批且未越过的道岔与联锁区，键见 `grantKey`。
- `requested`（List<Item>）：未完成申请所要的项，获批后转入 `granted`。

### 构造函数 `ThroatLocks(Object pathKey, long[] nodes, int[] stopRails, LongToIntFunction rails)`
无联锁区的形式。

### 构造函数 `ThroatLocks(Object pathKey, long[] nodes, int[] stopRails, LongToIntFunction rails, ToLongFunction<UUID> zones)`
- 作用：用 `ThroatRoutes.of` 求每个停站的进出站进路并保存。
- 参数：`pathKey` 宿主路径；`nodes` 路径节点（轨道 i 从节点 i 到 i+1）；`stopRails` 停靠的轨道（按序：站台，以及两端的停车线）；`rails` 节点处相交的轨道数；`zones` 轨道键 → 联锁区或 0。
- 调用者：`TrainServer.java:1132`、`TickRunner.java:2536`。

### `boolean isFor(Object pathKey, int nodeCount)`
是否为宿主当前路径所建（同一对象且节点数相同）。

### `static double[] position(long packed)`
从游戏打包的方块坐标解出节点的 {x, z}（x 为高 26 位，z 为中间 26 位）。调用者：`TrainServer.java:388`、`PlanCheck.java:152`；构造函数。

### `List<RunReservations.Move> getHeld(int tailRail)`
- 作用：先移除车尾已越过的（道岔：车尾越过其节点；联锁区：车尾离开其最后一条轨道，即 `endIndex <= tailRail`），返回其余获批项作为要持有的 Move。
- 副作用：修改 `granted`。

### `List<ThroatRoutes.Item> getItems()`
路径上所有停站进出站进路的全部道岔与联锁区（各一次），供运行层（5.5）随经过其节点所需的分区一起声明。调用者：`TickRunner.java:2051`。

### `List<ThroatRoutes.Item> getExit(int platformRail)`
该站台轨道所在停站的出站进路，随离站运行申请一起申请；不是停站返回空。调用者：`TrainServer.java:1400`、`TickRunner.java:2672`。

### `List<ThroatRoutes.Item> getNextEntry(int headRail)`
车头之后第一个停站的进站进路中尚未获批、且仍在车头前方的项；没有返回空。调用者：`approach`、`isBlocked`。

### `static int getFirstIndex(List<ThroatRoutes.Item> items)`
这些项中路径上最靠前的节点下标，或 −1。

### `void setRequested(List<ThroatRoutes.Item> items)`
记住申请所要的项，在 `onGranted` 时给予。调用者：`TrainServer.java:1403`、`TickRunner.java:2675`。

### `void onGranted()`
把已申请项转为获批，清空申请。调用者：`TrainServer.java:1389`、`TickRunner.java:2661`。

### `boolean approach(RunReservations reservations, long trainId, List<RunReservations.Move> held, int headRail, int tailRail, IntToDoubleFunction distanceToNode, double braking, long plannedMillis)`
- 作用：运行中的运用列车每 tick 调用：若有未完成申请且已获批则 `onGranted`；若下一咽喉尚未获批且车头到其前一节点（即会停下的位置，使停在那里的列车仍会申请）的距离 ≤ 制动距离 + `APPROACH_MARGIN`，就连同已持有的单线、未越过的获批项一起申请进站进路（批准只给申请里的东西）。
- 参数：`held` 当前持有的单线；`distanceToNode` 车头到路径某节点的距离（越过为 ≤ 0）；`braking` 以当前速度停车所需距离。
- 返回：是否已申请。副作用：可能调用 `reservations.takeGrant`/`request`，修改 `granted`/`requested`。
- 调用者：`TrainServer.java:1092`、`TickRunner.java:2500`。

### `boolean isBlocked(int rail, int headRail)`
通往下一咽喉的那条轨道（第一个道岔节点前一条）在进路未获批时对列车关闭；列车在其前停下，因此车身不会站在第一个道岔其他进路所用的轨道上。调用者：`TrainServer.java:459`、`TickRunner.java:3011`。

### `static List<RunReservations.Move> toMoves(Collection<ThroatRoutes.Item> items)`
联锁区 → `Move.zone`，道岔 → `Move.turnout(节点, 两侧)`。调用者：`TrainServer.java:1402`、`TickRunner.java:2054`。

### `private static long grantKey(ThroatRoutes.Item item)`
`index × 2 + (联锁区 ? 1 : 0)`：道岔与联锁区可能从路径同一节点开始，各需独立的键。

## 内部类 Stop（private static final）
`rail`（停站轨道下标）、`throat`（`ThroatRoutes.StopThroat`）。
