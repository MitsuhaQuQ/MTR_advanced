# Fixture.java

MTM_TestKit 服务端：在超平坦新世界里用与玩家"轨道连接器"相同的步骤搭一个小而固定的测试线网（名为 `basic`），让每次测试从相同数据开始。全部在 z = 0、y = `Y`（默认超平坦草地上一格）上：

```
x: -140  -110  -90   -60   -15    15    60    90    110   140
    [W 停车轨]-[ A 站台 ]-[ B 站台 ]-[ C 站台 ]-[E 停车轨]
```

车站 甲/乙/丙（A/B/C），车厂 西（停车轨 W，列车数不限）、东（停车轨 E，最多 1 列），线路"测试线"A→B→C 由西车厂服务、"测试线回程"C→B→A 由东车厂服务，另有 30 个没有区域的填充车站（使仪表板车站列表超过一页，供测试 U24 切换列表）。由 `ServerDriver` 的 `/fixture` 调用（`testkit/src/main/java/mtm/testkit/serverdriver/ServerDriver.java:97`）；`connectRail` 和 `Y` 也被 `Lines` 复用。

## 类 `Fixture`（final，私有构造）

### 常量
- `Y = -60`：轨道节点所在高度（超平坦世界草地上一格）。
- `FILLER_STATIONS = 30`：填充车站数。

### `static JsonObject build(ServerLevel level, String name)`
- 作用：只接受 `name == "basic"`；要求该世界还没有车站和站台。依次：在 10 个 x 位置放东西向（`FACING = true`）轨道节点；用 `connectRail` 按 `SIDING, IRON, PLATFORM, IRON, PLATFORM, IRON, PLATFORM, IRON, SIDING` 连成直线；按坐标找出生成的站台/停车轨；建车站（带矩形区域 z ∈ [-10, 10]）、填充站、两条线路、两个车厂（关联线路）；反射设置停车轨列车上限；最后 `railwayData.dataCache.sync()`。
- 参数：`level` 目标世界；`name` 线网名。
- 返回：各对象 id 的 JSON（`y, platformA/B/C, sidingWest/East, stationA/B/C, route, routeBack, depotWest/East, fillerStations`）。
- 副作用：放方块、改 `RailwayData`（添加车站/线路/车厂/轨道），使用 Fabric `FakePlayer` 作为操作者。
- 异常：名字不对抛 `IllegalArgumentException`；无铁路数据或世界已有数据抛 `IllegalStateException`。
- 调用者：`testkit/src/main/java/mtm/testkit/serverdriver/ServerDriver.java:97`。

### `static void connectRail(ServerLevel level, RailwayData railwayData, FakePlayer player, BlockPos posStart, BlockPos posEnd, RailType railType)`
- 作用：复现轨道连接器（`ItemNodeModifierBase.onEndClick` + `ItemRailModifier.onConnect`）对一段轨道的处理：按两端节点角度和连线方向求两端朝向（`RailAngle`），构造正反两条 `Rail`，校验 `isValid` 与 `goodRadius`，`railwayData.addRail` 两次（第二次参数 `true`），把两端节点设为 `IS_CONNECTED`，最后确认 `containsRail`。
- 参数：两端节点坐标；`railType` 轨道类型（IRON、PLATFORM、SIDING、TURN_BACK…）。
- 异常：轨道无效或未加入时抛 `IllegalStateException`（信息含节点角度）。
- 调用者：本类 `build`、`testkit/src/main/java/mtm/testkit/serverdriver/Lines.java:398`。

### `private static <T extends SavedRailBase> T platformAt(Set<T> savedRails, int x)`
- 找包含 `(x, Y, 0)` 的站台或停车轨；找不到抛 `IllegalStateException`。

### `private static Station station(RailwayData, String name, int color, int xMin, int xMax)`
- 新建车站，区域为 `(xMin, -10)–(xMax, 10)`，加入数据并返回。

### `private static Depot depot(RailwayData, String name, int color, int xMin, int xMax)`
- 新建火车车厂，区域同上，加入数据并返回。

### `private static void setSidingLimit(Siding siding, boolean unlimited, int maxTrains)`
- 反射写 `Siding` 私有字段 `unlimitedTrains`、`maxTrains`。失败抛 `IllegalStateException`。
