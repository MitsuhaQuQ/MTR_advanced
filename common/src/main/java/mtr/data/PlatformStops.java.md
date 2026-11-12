# PlatformStops.java

每个站台的停车位置（优化第 4 轮"停车点"；组件 `transitsim.plan.StopPositions`），按站台 id 存，供路径查询。只记录不是默认值 `StopPositions.EXIT_END`（停在出站端）的站台。每个 `DataCache`（服务端每个维度一份、客户端一份）在同步时提交自己的站台，查询看到所有来源的合并结果。列车在拿到路径时读取，所以变化会像停站时间变化一样，在车厂重新找路时才影响运行中的列车。

## final class PlatformStops

### 字段
- `SOURCES`：来源（`DataCache` 对象）→（站台 id → 停车位置）。`ConcurrentHashMap`。
- `merged`（volatile）：所有来源合并后的映射，整体替换（写时复制）。

### `private PlatformStops()`
工具类，禁止实例化。

### `public static void update(Object source, Collection<Platform> platforms)`
- 作用：用某个数据缓存的站台更新设置。
- 参数：`source` 站台所属的数据缓存（作身份键）；`platforms` 其全部站台。
- 副作用：若该来源的设置未变化（或首次且为空）直接返回；否则写入 `SOURCES` 并重新合并 `merged`。多个来源对同一站台 id 冲突时，后遍历到的覆盖（顺序未定义）。
- 主要调用者：`DataCache.java:128`；测试 `PlatformStopsTest.java:40`。

### `public static long fingerprint()`
- 返回：`merged.hashCode()`，任一设置变化时会变；用于缓存键。
- 主要调用者：`TrackViews.java:41`、`RoutePaths.java:205`、`RoutePaths.java:1539`。

### `public static int get(long platformId)`
- 返回：该站台的停车位置；未设置返回 `StopPositions.EXIT_END`。取值含义见组件 `StopPositions`。
- 主要调用者：`PathData.java:225`。
