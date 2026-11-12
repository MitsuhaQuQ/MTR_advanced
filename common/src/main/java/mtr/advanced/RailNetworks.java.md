# RailNetworks.java

把 MTR 的轨道（`Rail`）转成 `transit-sim` 组件的网络快照（`Network`，设计文档 5.11、6.12）。实际压缩由组件的 `NetworkBuilder.build` 完成，本类只提供游戏侧的 `RailAttributes<Rail>` 适配：区段类型、能否加速、限速、长度。

## final class RailNetworks

### 常量 `ATTRIBUTES`（`RailAttributes<Rail>` 匿名实现）
- `getType(Rail rail)`：按 `rail.railType` 映射：`PLATFORM → SectionType.PLATFORM`、`SIDING → SIDING`、`TURN_BACK → TURN_BACK`，其余 `NORMAL`。
- `canAccelerate(Rail rail)`：返回 `rail.railType.canAccelerate`。
- `getSpeedLimit(Rail rail)`：返回 `rail.railType.maxBlocksPerTick`（单位：方块/tick）。由组件 `NetworkBuilder.java:127` 调用。
- `getLength(Rail rail)`：返回 `rail.getLength()`（方块）。
以上四个方法都只由组件 `NetworkBuilder` 通过接口调用。

### `private RailNetworks()`
工具类，禁止实例化。

### `public static Network compress(RailGraph<Rail> graph, PathRegions regions, Boundaries boundaries, Map<UUID, Long> savedRails, long version)`
- 作用：构建组件网络快照。
- 参数：`graph` 组件的轨道图（节点为方块坐标 long，边为 `Rail`）；`regions` 路径分区；`boundaries` 分区边界；`savedRails` 每条轨道上的站台/停车线 id，键与 `PathBoundaries#segmentKey` 相同；`version` 网络发布的序号。
- 返回：`NetworkBuilder.build(...)` 的结果。
- 副作用：无（纯转换）。
- 主要调用者：测试 `RailNetworksTest.java:46`（游戏代码中的直接调用未找到，可能经由 `GraphRecords` 间接构建，未确定）。
