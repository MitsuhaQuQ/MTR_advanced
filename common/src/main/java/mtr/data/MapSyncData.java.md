# MapSyncData.java

仪表盘地图的增量同步格式。地图被分成若干条目，每条带一个哈希，客户端只下载变化了的条目：轨道按方形区域分组，每条线路一个条目，每条停车线生成的路径一个条目，弯道形状点也按区域分组。本类只定义常量、区域键和哈希函数；打包与收发在 `RailwayData`（`RailwayData.java:1011-1121` 一带）。

## final class MapSyncData

### 常量
| 名称 | 值 | 含义 |
|---|---|---|
| `KIND_RAILS` | 0 | 轨道区域条目 |
| `KIND_ROUTES` | 1 | 线路条目 |
| `KIND_SIDINGS` | 2 | 停车线路径条目 |
| `KIND_RAIL_SHAPES` | 3 | 弯道上的若干点，按与轨道相同的区域分组：`node, node, count`，然后 count 个由 `RailShapes#pack` 打包的点，使地图把长弯道画成曲线 |
| `KIND_COUNT` | 4 | 种类数 |
| `RAIL_LONGS` | 3 | 每条轨道在载荷中占的 long 数 |
| `REGION_BITS` | 7 | 区域边长 2^7 = 128 方块 |
| `HASH_MULTIPLIER` | `0x9E3779B97F4A7C15L` | 混合常数 |

### `private MapSyncData()`
工具类，禁止实例化。

### `public static long regionKey(int x, int z)`
- 作用：方块坐标 → 区域键：高 32 位 `x >> 7`，低 32 位 `z >> 7`。
- 主要调用者：`RailwayData.java:1011`；测试 `MapSyncDataTest.java:26`。

### `public static long hashRails(long[] payload)`
- 作用：区域内轨道到达顺序不确定，所以对每条轨道（每 3 个 long）单独混合后相加，初值为 `payload.length`。与顺序无关。
- 主要调用者：`RailwayData.java:1036`、`RailwayData.java:1121`。

### `public static long hashRoute(long[] payload)`
- 作用：有序哈希：从 `payload.length` 开始依次 `mix`。
- 主要调用者：`RailwayData.java:1033`、`RailwayData.java:1046`、`RailwayData.java:1072`。

### `public static Map<Long, long[]> toPayloads(Map<Long, ? extends List<Long>> regions)`
- 作用：把区域键 → `List<Long>` 转成区域键 → `long[]`，结果用 `TreeMap`（按键排序）。
- 主要调用者：`RailwayData.java:1031`、`RailwayData.java:1034`。

### `private static long mix(long hash, long value)`
- 作用：`m = (hash ^ value) * HASH_MULTIPLIER; return m ^ (m >>> 29)`。
