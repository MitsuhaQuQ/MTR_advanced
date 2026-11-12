# TimingCache.java

浏览器编辑器的运行时分（timing）结果缓存。缓存有效性取决于线路的物理输入，而不仅是线路 id：键由线路 id、交通方式、限速、每段路径参数、停站与时刻表的最小加减速/最高速拼成字符串。用请求序号防止过期的异步计算结果被接受。由 `EditorTimes`、`EditorData` 使用。

## final class TimingCache

### 字段
- `keys`：线路 id → 当前已接受结果对应的键。
- `requests`：线路 id → 最近一次未完成的请求（`Request`）。
- `sequence`：请求序号，从 0 开始递减（负数），到 `Integer.MIN_VALUE` 后回到 −1。

### static final class Request
- 字段 `id`（请求序号）、`key`（请求时的键）。构造函数 `Request(int id, String key)` 仅赋值。

### `public static String key(Route route)`
- 作用：生成线路物理输入的字符串指纹。
- 内容：`id:transportMode:speedLimit`，然后每段路径 `;railProduct:savedRailBaseId:dwellTime:railLength:planSpeed`，每个停站 `|platformId:pass:dwellOverride:waypoints`，每张时刻表 `/minAcceleration:minDeceleration:minMaxSpeed`。
- 主要调用者：`EditorData.java:803`、`EditorTimes.java:83`、`EditorTimes.java:84`。

### `public int request(Route route)`
- 作用：登记一次新的计算请求，返回其序号；覆盖该线路之前的未完成请求。
- 副作用：改 `sequence`、`requests`。
- 主要调用者：测试 `TimingCacheTest.java:15`（游戏代码中的调用未找到，未确定）。

### `public boolean accept(Route route, int id)`
- 作用：计算结果返回时检查：序号与最近请求一致、且线路当前键仍与请求时相同才接受。
- 返回：接受返回 `true`（并把键记入 `keys`、移除请求），否则 `false`。
- 主要调用者：测试 `TimingCacheTest.java:17`。

### `public void remember(Route route)`
- 作用：直接把线路当前键记为已缓存。
- 主要调用者：`EditorTimes.java:112`、`EditorTimes.java:163`。

### `public boolean matches(Route route)`
- 作用：线路当前键是否等于已缓存的键（缓存仍有效）。
- 主要调用者：`EditorTimes.java:158`。

### `public void clear()`
- 作用：清空 `keys` 与 `requests`。
- 主要调用者：`EditorData.java:128`、`EditorTimes.java:143`。
