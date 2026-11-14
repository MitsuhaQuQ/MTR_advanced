# Lines.java

MTM_TestKit 服务端测试线网 `lines`：在一个新世界里并排搭 10 条（`LAYOUTS`）复线测试线，每条相距 300 格（z 方向），全部用轨道连接器同样的步骤（`Fixture.connectRail`）建成，覆盖车厂与折返规则必须处理的各种配线（用户 2026-11-12 提出）：

1. 站前折返（终点站前渡线）+ 站后车库；
2. 站后折返线，车厂接在折返线后；
3. 站后折返线，车厂在折返线前分叉接出；
4. 站前折返 + 单独的出库站台；
5. 中间站折返线 + 车厂接在其后；
6. 中间站折返线 + 车厂只接单边（下行正线）；
7. 长区间：三站，最后一站在 2000 格外，中间一条长轨供信号分段，每 30 秒一班；
8. 首尾直通：线路 A 一站↔三站、B 三站↔五站共用轨道，A 的部分班次续跑为 B；A 自己在三站折返线折返；
9. 中途接入与三段直通：A 一站→三站在中间站三站并入 B（二站→四站），B 续跑为 C（四站→五站），回程反之；三站站台是时刻表边界（`timetableBoundary`）。
10. 站后双折返（2026-11-13）：五站后两条并排的尽头折返线 T1（接上行 z 0）、T2（接下行 z 8），渡线 A 让上行车进 T2，渡线 B 让 T1 出来的车回下行；进 T2 和出 T1 的车在两渡线之间的下行正线上对向，靠咽喉进路分开。车厂从下行正线在 B 与 T2 之间分岔（z 16），车向西出库；不接在折返线尾部，因为那样折返线不再是尽头线，游戏就不把它当可选折返股道（`Depot.findNearbyTurnBackNodes` 只取尽头节点）。

每条线：东行正线在相对 z = 0，西行正线在 z = 8；一站…五站各有 60 格站台；一站后方有折返线。线路"上行"（一站→五站，z 0）、"下行"（五站→一站，z 8），5、6 号线另有"短上行/短下行"（一站↔三站）。1–4 号线车厂在五站端。所有线路的行车方向设为左侧行车（`RunningDirections.LEFT`，因为东行在北、西行在南）。由 `ServerDriver` 的 `/fixture {"name":"lines"}` 调用（`testkit/src/main/java/mtm/testkit/serverdriver/ServerDriver.java:97`）。

## 类 `Lines`（final，私有构造）

### 常量
- `Y = Fixture.Y`：轨道高度。
- `SPACING = 300`：线间 z 间距。
- `STATION_X = {0, 200, 400, 640, 840}`：各站站台起点 x（三站之后间隔更长，给 5、6 号线的设施留位置）。
- `LONG_STATION_X = {0, 200, 2200}`：7 号线三站的 x。
- `PLATFORM = 60`：站台长度（格）。
- `STATION_NAMES`：`一站|Station 1` … `五站|Station 5`。
- `LAYOUT_NAMES`：10 种配线的中文名。
- `COLORS`：每条线颜色。
- `LAYOUTS = 10`。

### `static JsonObject build(ServerLevel level)`
- 作用：要求世界还没有车站和站台；对 layout 1…10 各建一个 `Builder(level, railwayData, layout, (layout-1)*SPACING)` 并 `build()`；最后 `dataCache.sync()`。
- 返回：`{"y": Y, "lines": [每条线的描述]}`。
- 异常：无铁路数据或世界已有数据抛 `IllegalStateException`。

## 私有内部类 `Lines.Builder`

### 字段
- `level`、`railwayData`、`player`（Fabric `FakePlayer`）、`layout`、`z0`（本线 z 偏移）。
- `mains`：正线节点，`相对 z → 有序 x 集合`，最后逐段连接。
- `mainTypes`：正线中非 IRON 的区段类型，键 `"z x1 x2"`（站台区段为 PLATFORM）。
- `placed`：已放置的节点坐标。
- `ids`：未使用的 JSON 对象（声明了但代码中未写入）。
- `stationX`：7 号线用 `LONG_STATION_X`，其余用 `STATION_X`。

### 构造函数 `Builder(ServerLevel, RailwayData, int layout, int z0)`

### `JsonObject build()`
- 作用：
  1. 每站在 z 0 和 z 8 两条正线上登记站台两端节点，区段类型为 PLATFORM；
  2. 一站后方：z 8 加节点 x = -40，连 `(0,0)→(-40,8)` IRON、`(-40,8)→(-100,8)` TURN_BACK；
  3. 按 layout 加折返线、渡线、车厂停车轨，并记录车厂矩形（相对坐标）：1、4 用 `frontCrossover`，车厂两条停车轨东端都接到一段尾端折返线（`shuntingNeck`，车厂内调车，2026-11-13，车厂矩形东延到 +200）；2、7、9 用 `rearTurnBack + depotBehindTurnBack`；8 再加 `middleFrontCrossovers`（0.80.0：三站两侧各一组渡线，A 在东行站台、B 在西行站台站前折返，进站保持正线方向、出站才过渡线到对向；A下行和 B上行各把三站另一侧站台加进 `startPlatforms`）；11（0.80.0 新加，"首尾直通冲突进路示例"）是原来的 8 号线：`rearTurnBack + depotBehindTurnBack + middleTurnBack(middle, 50)`，B 线车在三站经尽头折返线掉头三次、和 A 线对向，用来测冲突进路提示和尽头线兜底；3 在折返线前分叉接车厂；4 在五站外侧加第三站台（z 16）作为出库站台，并给回库列车单独停车轨；5、6 用 `rearTurnBack + middleTurnBack(middle, 长度)`，5 车厂在两正线之间（折返线 50 格，接着进车厂），6 车厂只接西行正线（两条线的折返线都是 50 格，比 75 格的车短；0.80.0 试过把 6 号线加长到 90 格，结果在一站碰上另一种死锁，规则开关都一样，见 `MTR-待验证.md` 的 E，所以仍是 50 格）；10 用 `doubleRearTurnBack`（两条尽头折返线、两组渡线、车厂从西行正线分岔）；
  4. 按 `mains` 把正线逐段连起来；
  5. 建车站（站台前后各扩 5 格，z ∈ [-4, 12]，4 号线五站扩到 18）、按 layout 建线路（8、9 号线为直通组合；2 号线另加三站通过不停的"快车"，用于三站的时刻表传感器）；1、4 号线上行可在五站西行站台终到（`terminalPlatforms`）；4 号线下行可从出库站台始发（`startPlatforms`）；5、6 号线加短交路；
  6. 每条线路在 `networkSettings.runningSides` 中以键 `"R" + LineDuties.getLineKey(route)` 设为左侧行车，并置 `networkSettingsChanged = true`；
  7. 建车厂（区域取第一个车厂矩形，关联本线所有线路）；
  8. 2 号线在三站放时刻表传感器（`placeSensors`）。
- 返回：`{"layout","name","z","depot","depotName","depotBox"(相对坐标),"stationX","routes":[{id,name}],"stations":[id],"sidings":[车厂区域内停车轨 id],"sensors"?,"tails"?}`；10 号线的 `tails` 是两条折返线的 `[x 起, x 止, z]`（绝对坐标），测试据此判断列车在哪条折返线上。
- 异常：未知 layout 抛 `IllegalArgumentException`；找不到站台抛 `IllegalStateException`。

### `private void frontCrossover()`
- 五站前的渡线：节点 `(x-50, 0)`、`(x-10, 8)`，连 IRON，让列车在站前折返。

### `private JsonArray placeSensors(int x)`
- 作用：在东行站台北侧 3 格（相对 z -3）、x+4 起每隔 10 格放 5 个时刻表传感器（`TRAIN_SCHEDULE_SENSOR`），设置依次为 `{触发方式, 类别}`：`BEFORE_ARRIVAL/0`（到站前 10 秒，所有列车）、`BEFORE_ARRIVAL/2`（只通过列车）、`BEFORE_ARRIVAL/1`（只停站列车）、`LEGACY/0`、`LEGACY/1`；调用 `TileEntityTrainScheduleSensor.setData(空集合, false, false, 10, "10", trigger, kind)`；强制加载所在区块（无人在旁也会 tick）；`ServerDriver.watchSensor` 登记监视。类别数字含义按代码注释（0 全部、1 停站、2 通过）。
- 返回：`[{x,y,z,trigger,kind}]`。
- 副作用：`setChunkForced`、放方块、改方块实体。
- 异常：放置后没有对应方块实体抛 `IllegalStateException`。

### `private void middleTurnBack(int middle, int length)`
- 三站后两正线之间的尽头折返线：两正线 `middle+30` 处节点各接到 `(middle+70, 4)`，再 `(middle+70,4)→(middle+70+length,4)` TURN_BACK（5、6、11 号线都是 50 格）。

### `private void middleFrontCrossovers()`（0.80.0）
- 8 号线三站两侧的渡线：西侧 `(STATION_X[2]-20, 0)→(STATION_X[2]-60, 8)`（A 线车从东行站台往西出站换到西行线），东侧 `(STATION_X[2]+PLATFORM+20, 8)→(+60, 0)`（B 线车从西行站台往东出站换到东行线）。

### `private void depotBehindTurnBack(int east, List<int[]> depotBoxes)`
- 折返线后接车厂：`(east+100,0)→(east+130,0)` IRON、`→(east+210,0)` SIDING；车厂矩形 `{east+120,-6,east+220,6}`。

### `private void rearTurnBack(int east)`
- 五站后的折返线：`(east,8)→(east+40,0)` IRON、`(east+40,0)→(east+100,0)` TURN_BACK。

### `private void mainNode(int z, int x)`
- 在 `mains[z]` 中登记 x。

### `private BlockPos pos(int x, int z)`
- 返回 `(x, Y, z0 + z)`。

### `private void rail(int x1, int z1, int x2, int z2, RailType railType)`
- 作用：两端未放过节点则放东西向轨道节点，再 `Fixture.connectRail`。

### `private <T extends SavedRailBase> T savedRail(Set<T> savedRails, int x, int z)`
- 找包含该点的站台/停车轨，找不到抛 `IllegalStateException`（含 layout）。

### `private Station station(String name, int color, int xMin, int zMin, int xMax, int zMax)`
- 新建车站，区域 z 加上 `z0`。

### `private Route route(String name, Platform[] platforms, int from, int to)`
- 新建火车线路，依次加入 `platforms[from..to]`（含两端），颜色为本线颜色。

### `private static Platform[] reversed(Platform[] platforms)`
- 返回倒序数组。
