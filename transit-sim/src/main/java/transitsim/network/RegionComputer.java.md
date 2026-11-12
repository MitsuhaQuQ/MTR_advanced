# RegionComputer.java

在服务器主线程之外，从网络快照计算 `RegionData`：寻路边界的区域，以及列车在站台间的通行关系（`PlatformGraph`）。调用者：游戏侧 `PathRegionSync.java:68`（后台线程），测试 `PlatformGraphTest.java:71`。

## 类 RegionComputer（final，不可实例化）

### `static <R> RegionData compute(RailGraph<R> graph, Boundaries boundaries, List<SavedRail> platforms, List<SavedRail> sidings)`
- 作用：`PathRegions.compute` 求区域；为每个站台和停车线求其轨道所在区域（`railRegions`）；`PlatformGraph.compute` 求站台图；组装 `RegionData`（边界拷贝一份）。
- 参数：`graph` 轨道图；`boundaries` 寻路边界；`platforms`、`sidings` 站台与停车线。
- 返回：新的 `RegionData`。副作用：无。
- 调用者：`PathRegionSync.java:68`。

## 内部类 SavedRail（public static final）
从服务器数据拷贝出的站台或停车线。
- `id`（long）：站台/停车线 id。
- `pos1`、`pos2`（long）：轨道两端节点。
- `stationId`（long）：所属车站 id（停车线时含义未确定，可能为车厂 id 或 0）。
- `transportMode`（int）：交通方式序号。
- 构造函数按字段赋值。调用者：游戏侧 `PathRegionSync`、测试。
