# RailwayDataMixin.java（ANTE mixin）

服务端 mixin，作用于 `mtr.data.RailwayData`。ANTE 的轨道可以有自己的形状（竖曲线、滚转等），所以这里只改"如何找出玩家附近的轨道（要发给玩家/让列车在附近运行的范围）以及范围多大"，每 tick 的其余逻辑仍是 MTR 自己的 `simulateTrains(RailNearPlayer)`，因此本分支往 tick 里加的东西在装了 ANTE 时也不会丢。

文件注释特别说明：这里只 shadow 用到的字段，而且都不给初值——Mixin 会把字段初值合并进目标类构造函数，过去给 shadow 的列车位置列表赋了初值，结果把 MTR 自己的列表替换成空表，列车不占用任何轨道，读位置的代码崩溃（与 ANTE-advanced 2f3921c8 同样的修复）。`MixinLintTest` 检查这种情况，并把本类的 `simulateTrains` 列为允许的整体替换（`common/src/test/java/cn/zbx1425/mtrsteamloco/MixinLintTest.java:40`）。

## 类 `RailwayDataMixin`

### 影子字段
- `world`（`Level`）：本数据所属世界。
- `RAIL_UPDATE_DISTANCE`（static，`@Mutable`）：MTR 的轨道更新距离（格）。

### `public void simulateTrains()`
- 作用：同名同参数，整体替换 `RailwayData.simulateTrains()`（`common/src/main/java/mtr/data/RailwayData.java:518`）。
  - `SecurityConfig.anteRunHooks` 关闭时：调用 MTR 原逻辑 `simulateTrains(RailwayData::isRailNearInBox)`；
  - 开启时：把 `RAIL_UPDATE_DISTANCE` 设为服务器视距 × 16 格，以 `RailExtraSupplier.isBetween(玩家x, y, z, 距离)`（按 ANTE 轨道实际形状）判断轨道是否在玩家附近，再调用 `simulateTrains(谓词)`。
- 副作用：修改静态 `RAIL_UPDATE_DISTANCE`（影响所有维度）。
- 调用者：服务器每 tick 调用 `RailwayData.simulateTrains()` 的地方（MTR 的世界 tick 钩子）。
