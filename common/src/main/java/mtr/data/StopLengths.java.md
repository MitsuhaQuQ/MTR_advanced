# StopLengths.java

线路停站组合时的长度检查（优化第 3b 轮第 3 步）：运行该线路的列车若比它停靠的站台长，就指出来，除非该停站设置了允许超长（`allowLonger`）。游戏（`RailwayData`）和网页编辑器（`EditorData`）用同一套判断。

## final class StopLengths

### 常量
- `TOLERANCE = 1.5`（方块）：列车可比站台长多少而不报。

### `private StopLengths()`
工具类，禁止实例化。

### `public static double getLongestTrain(Route route, DataCache dataCache, Collection<Siding> sidings)`
- 作用：找运行该线路的最长列车长度：遍历所有停车线，所属车厂（`dataCache.sidingIdToDepot`）的 `routeIds` 包含该线路时取 `siding.getTrainLength()` 的最大值。
- 返回：方块数；无车运行返回 0。
- 主要调用者：`RailwayData.java:771`、`EditorData.java:487`；测试 `StopLengthsTest.java:42`。

### `public static double getLength(Platform platform)`
- 作用：站台可容纳的列车长度：两端节点是端头方块的中心，所以长度 = 两节点直线距离 + 1（30 格的电车适合节点相距 29 的站台）。
- 返回：方块数。
- 主要调用者：`RailwayData.java:778`、`EditorData.java:503`。

### `public static List<Integer> getTooShort(Route route, DataCache dataCache, double longestTrain)`
- 作用：返回站台太短的停站下标。`longestTrain <= 0` 时返回空表。中间通过站（`pass` 且非首末站）不算；`allowLonger` 的停站不算；判定 `longestTrain > getLength(platform) + TOLERANCE`（注释：弯道上直线量的长度略短，所以放宽）。
- 返回：`route.platformIds` 下标列表。
- 主要调用者：`RailwayData.java:772`、`EditorData.java:487`；测试 `StopLengthsTest.java:33`。
