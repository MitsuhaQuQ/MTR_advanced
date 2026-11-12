# ConnectionStats.java

调试用的全服统计（优化第 5 轮第 3 步，"先测量再决定"）。仅在 JVM 参数 `-Dmtr.debugConnections=true` 时生效：统计车厂（`Depot`）在为运用（duty）找路时，各类"服务之间的衔接"搜索的调用次数、不同键的个数和耗时，用来判断有多少是重复搜索。只被 `Depot` 使用，包内可见；与组件无对应。

## final class ConnectionStats

### 字段与常量
- `static final boolean ON`：`Boolean.getBoolean("mtr.debugConnections")`，调用方据此决定是否计时上报。
- `CALLS[3]`、`NANOS[3]`：三类搜索的调用次数和累计纳秒（`AtomicLong`，线程安全）。
- `KEYS[3]`：三类搜索出现过的不同键（`ConcurrentHashMap` 作集合）。
- `NAMES`：三类的名字：0 = `connections`（服务间衔接），1 = `nearby turn back nodes`（站台附近折返节点），2 = `front turn backs`（前方折返）。

### `private ConnectionStats()`
工具类，禁止实例化。

### `static void connection(String key, long nanos)`
- 作用：记一次衔接搜索（类 0）。`key` 为搜索的标识，`nanos` 为耗时（纳秒）。
- 主要调用者：`Depot.java:3725`。

### `static void nearby(long platformId, long nanos)`
- 作用：记一次站台附近折返节点搜索（类 1），键为站台 id。
- 主要调用者：`Depot.java:3958`。

### `static void front(String key, long nanos)`
- 作用：记一次前方折返搜索（类 2）。
- 主要调用者：`Depot.java:3379`。

### `private static void add(int kind, String key, long nanos)`
- 作用：对类 `kind` 递增调用数、累加耗时、记录键。

### `static void print()`
- 作用：向 `System.out` 打印一行 "Minecraft Transit Railway connection stats so far:"，随后每类 `名字 N calls, M distinct, T ms;`。
- 主要调用者：`Depot.java:1263`。
