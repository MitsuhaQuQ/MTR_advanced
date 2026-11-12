# AnnouncementStops.java

报站用的站序工具。核心思想（类注释）：按"停站出现次数"计数，而不是按站台名去重——环线回到起点时的那次停靠要和第一次停靠区分开。提供三个静态方法：在多条路线拼接的站表里找最接近提示位置的站序、给一条运用路径按停站编号、以及从列车自己的路径上找"下一站"。只依赖 `PlanTrack`；被 `mtr.data.RailwayData`（报站站序）与 `mtr.data.TrainClient`（下一站显示）使用。

## 类 `AnnouncementStops`（final，工具类）

### `private AnnouncementStops()`
私有构造，禁止实例化。

### `public static int nearest(int hint, long platform, List<long[]> routes, boolean[] sharedEnd)`
- 作用：在若干条路线首尾相接组成的"全局站序"里，找所有等于 `platform` 的站位，返回与 `hint` 距离最近的那个全局下标。
- 参数：
  - `hint`：期望的全局站序（通常是列车当前已知的站序），用于在重复站台中选最近者。
  - `platform`：站台 ID；为 0 时直接返回 `hint`。
  - `routes`：每条路线的站台 ID 数组，按运行顺序排列。
  - `sharedEnd`：与 `routes` 对应；`sharedEnd[r]` 为真表示第 r 条路线的终点与下一条路线的起点是同一站，此时全局下标基数减 1（不重复计数）。数组可比 `routes` 短，越界部分视为 false。
- 返回值：最近的全局站序；找不到该站台时返回 `hint`。
- 副作用：无。
- 主要调用者：`common/src/main/java/mtr/data/RailwayData.java:1615`。

### `public static void numberDuty(List<? extends PlanTrack> path, int firstServiceRail)`
- 作用：给一条运用路径上的每段轨道写入站序号（`PlanTrack.setStopIndex`）。从 `firstServiceRail` 起，每遇到一个与上一段不同的站台轨道，计数加 1；出库引线（`firstServiceRail` 之前）不算站台，站序保持 0。
- 参数：`path` 路径；`firstServiceRail` 运用中第一段载客服务轨道的下标（出库段之后）。
- 返回值：无。
- 副作用：修改 `path` 中每个 `PlanTrack` 的 stop index。
- 主要调用者：在 main/tools 代码中 grep 未找到调用（可能只在测试中使用或已不再使用，未确定）。

### `public static long nextStop(List<? extends PlanTrack> path, int head)`
- 作用（0.65.27）：从车头所在下标 `head` 往前找第一个"有停站时间（`getDwellTime() > 0`）且不是车头当前所在站台"的站台轨道，返回其站 ID。路线列出但本车通过不停（服务例外或调度员跳站）的站没有停站时间，因此不会被报为下一站。
- 参数：`path` 列车自己的路径；`head` 车头所在轨道下标（越界时返回 0）。
- 返回值：下一站 stop ID；前方没有时为 0。
- 副作用：无。
- 主要调用者：`common/src/main/java/mtr/data/TrainClient.java:331`。
