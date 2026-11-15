# TurnBackWayStore.java

存车线运用折返点的另一种走法（0.84.0）：每种不同的走法只存一次，按点记各走法是哪一条、默认走法有几根轨道。车在某个点走另一种走法时，`TripPath.withTurnBackWay` 由运用常规路径和这里的走法当场拼出路径，不再像 0.83.x 那样存车线为每个点每种走法保存整份运用路径（明珠线一份运用全天 160 次站前折返，就有 160 份整天的拷贝）。游戏 `mtr.data.Siding` 在寻路时用 `Builder` 收集、存进存档；组件 `SimDataset` 由 `SidingData.turnBackWays`/`turnBackWayPoints` 建；都经 `PathVariants.getTurnBackWays()` 交给组件。

## 类 `TurnBackWayStore<T extends PlanTrack>`

### 常量
- `KEY_POINTS = "turn_back_way_points"`：`write` 写的键。

### `public static <T> TurnBackWayStore<T> none()`
空表（没有点）。

### `public List<T> get(int point, int way)`
这个点这种走法的轨道（从停站轨道到默认走法终点，首尾是常规路径的那两根）；默认走法、不能用的走法、没有的点返回 null。

### `public int getUsualLength(int point)`
默认走法在停站轨道之后有几根轨道（走法终点 = 停站轨道 + 这个数）；没有的点为 −1。

### `public List<List<T>> getWays()`
每条不同的走法一次。遍历存车线路径的地方（联锁 `LockNetworks`、`OpposingMoves`、轨道使用、图记录、是否过期、信号区段、模拟数据）都要把它们算上，因为已经没有哪条保存的路径含有它们。

### `public boolean isEmpty()`、`public int pointCount()`

### `public void write(KeyedWriter writer)`、`public List<long[]> getPointValues()`
每个点一组：`[点, 默认走法轨道数, 走法 0 是第几条或 −1, 走法 1 …]`，按点排序。走法的轨道由宿主按 `getWays()` 的顺序自己写。

### `public static <T> TurnBackWayStore<T> read(KeyedReader reader, List<List<T>> ways)`、`of(List<long[]> pointValues, List<List<T>> ways)`
读回；没写过（0.84.0 前的存档）为空表。

## 类 `Builder<T>`
- `Builder(Function<List<T>, String> signature)`：按内容签名去重（游戏用 `PathPieces.contentKey`，停站号相对首轨，不影响）。
- `add(int point, TurnBackSection<T> section, long routeId)`：一个运用折返点（`section.start` 是停站轨道）；默认走法和不能用的记 −1，其余按签名找已有的那条或新加一条，`routeId` 记下第一次找到这条走法的运用的首条线路（轨道使用图的颜色）。
- `getRouteIds()`、`build()`。
