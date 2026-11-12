# PlatformSpeeds.java

站台通过速度（优化第 3b 轮第 2 步）：设了非默认通过速度的站台，按其轨道（`RailKeys`）存，供路径运行时查询——站台轨道按该速度跑，而不是按站台轨道类型标签。每个 `DataCache` 在同步时提交自己的站台，查询看到所有来源的合并结果，因此修改立刻生效、无需重新找路。由 `SpeedRules` 查询，指纹进入 `GraphRecords`、`TrackViews`、`RoutePaths` 的缓存键。

## final class PlatformSpeeds

### 常量与字段
- `DEFAULT_KMH = 80`：未设置时的通过速度（km/h），即站台轨道类型的标签值。
- `SOURCES`：来源（`DataCache`）→（轨道键 UUID → km/h）。
- `merged`（volatile）：合并后的映射，整体替换。

### `private PlatformSpeeds()`
工具类，禁止实例化。

### `public static void update(Object source, Collection<Platform> platforms)`
- 作用：用某个数据缓存的站台更新通过速度。只收 `passSpeed > 0` 且 `≠ DEFAULT_KMH` 的站台，键为 `RailKeys.of(两端节点)`（`getOrderedPositions(BlockPos.ZERO, false)` 的前两个位置）。
- 副作用：若该来源的设置未变化（或首次且为空）直接返回；否则写 `SOURCES` 并重新合并 `merged`。
- 主要调用者：`DataCache.java:126`；测试 `PlatformSpeedsTest.java:26`。

### `public static long fingerprint()`
- 返回：`merged.hashCode()`。
- 主要调用者：`GraphRecords.java:118`、`TrackViews.java:41`、`RoutePaths.java:205`。

### `public static int getKmh(long node1, long node2)`
- 参数：轨道两端节点（`BlockPos.asLong()`）。
- 返回：该轨道上站台的通过速度（km/h）；未设置或不是站台返回 `DEFAULT_KMH`。
- 主要调用者：`SpeedRules.java:39`。
