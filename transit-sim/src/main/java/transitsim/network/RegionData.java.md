# RegionData.java

由轨道和寻路边界算出、宿主可发送给玩家的数据（设计文档 5.13、6.12，P 块和 D 块）：边界本身、每个站台和停车线所在区域、站台间的通行关系。由 `RegionComputer.compute` 生成；游戏侧 `RegionDataPackets.java:40` 收发，`DashboardScreen`、`NodeAngleScreen` 显示。

## 类 RegionData（final，字段公开不可变引用）

### 字段
- `boundaries`（`Boundaries`）：寻路边界。
- `regionCount`（int）：区域数。
- `uncutNodes`、`uncutSegments`：未起切分作用的边界（见 `PathRegions`）。
- `missingNodes`、`missingSegments`：已不存在的边界节点/轨道。
- `platformRegions`、`sidingRegions`（`Map<Long, int[]>`）：站台/停车线 id → 所在区域。
- `platformGraph`（`PlatformGraph`）：站台图。
- `EMPTY`：空数据（空边界、0 区域、`PlatformGraph.EMPTY`）。

### 构造函数 `RegionData(Boundaries, int, List<Long>, List<UUID>, List<Long>, List<UUID>, Map<Long,int[]>, Map<Long,int[]>, PlatformGraph)`
按字段赋值，不拷贝。调用者：`RegionComputer.java:22`、`RegionDataPackets.java:40`。

### `boolean isBoundaryNode(long pos)`
节点是否为边界节点。仓库内未找到外部调用者。
