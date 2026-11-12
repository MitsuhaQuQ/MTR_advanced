# TimetableTimes.java

时刻表多表（table：同一路线的多个时刻表，各有自己的列车性能）时间数组的打包工具。打包格式：每个停站一行，行内依次是第 0 表的（到达, 出发）、第 1 表的（到达, 出发）……旧格式每站只有两列，被所有表共用。被游戏侧打包（`SandboxRuns`、`RoutePaths`、`PacketTrainDataGuiServer`）和组件读取（`StopRules`、`StopTimes`、`SimDataset`）使用。

## 类 `TimetableTimes`（final，工具类）

### `private TimetableTimes()`
禁止实例化。

### `public static long[][] pack(List<long[][]> tables)`
- 作用：把每个表的 `[停站][2]`（到达, 出发）数组合并成 `[停站][表数*2]`。
- 参数：`tables` 每个元素为一个表的 `long[停站数][≥2]`；时间单位由调用方决定，此处不解释。
- 返回值：合并后的数组；`tables` 为空时返回 `new long[0][]`。
- 副作用：各表停站数不一致时抛 `IllegalArgumentException("Inconsistent stop count")`。
- 主要调用者：`common/src/main/java/mtr/advanced/SandboxRuns.java:194`、`mtr/data/RoutePaths.java:1493`、`mtr/packet/PacketTrainDataGuiServer.java:1049`。

### `public static boolean samePerformance(RouteTimetable first, RouteTimetable second)`
- 作用：判断两份路线时刻表的各表列车性能是否相同：表数相同，且每一表的 `minAcceleration`、`minDeceleration`、`minMaxSpeed` 都相等。
- 返回值：相同为 true。
- 主要调用者：`transitsim/sim/SimDataset.java:316`。

### `public static int column(long[] stop, int table)`
- 作用：返回某表在一行中的起始列。旧的两列格式（`stop.length == 2`）所有表共用第 0 列；否则为 `table * 2`。
- 主要调用者：`transitsim/plan/StopRules.java:39`、`transitsim/plan/StopTimes.java:375`。
