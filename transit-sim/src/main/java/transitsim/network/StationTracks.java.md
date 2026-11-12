# StationTracks.java

每个车站的股道（解耦文档 5.4，L2）：各站台列车的运行方向（上行/下行）、是正线还是越行线（待避线），以及股道在车站横向的排列顺序。完全由使用站台的线路推算，不需要保存任何数据。

上下行按线路首末站的位置（用户规则）：大体向东或向北为上行，向西或向南为下行。首尾相同的线路（环线）改用它在每个站台上的运行方向。设置了越行标志的站台是越行线；否则，同站同方向有两个及以上站台时，有服务不停站通过的是正线，其余为越行线。坐标为游戏坐标：x 向东增长，z 向南增长。

调用者：客户端 `ClientStationTracks.java:64`（`compute`），其结果被编辑器 `EditorData.java:678`、`PlatformScreen.java:65` 使用。

## 类 StationTracks（final，不可实例化）

### 常量
- `UP = "up"`、`DOWN = "down"`、`BOTH = "both"`：方向。
- `MAIN = "main"`、`PASSING = "passing"`：股道种类。
- `LOOP_DISTANCE = 16`：首末站距离小于此值（方块）的线路视为环线。

### `static String directionOf(double fromX, double fromZ, double toX, double toZ)`
- 作用：按位移较大的分量判断：|dx| ≥ |dz| 时 dx > 0 为上行；否则 dz < 0（向北）为上行；其余为下行。
- 调用者：`compute`（第 62、68、123 行）。

### `static Map<Long, Track> compute(List<PlatformInput> platforms, List<RouteInput> routes)`
- 作用：
  1. 对每条线路：非环线用首末站中点定方向；环线（或首末站找不到）按每个停站的 `way` 用站台两端定方向（way 未知则不定）。收集每个站台的方向集合；`passes` 的站台记为"有通过"。
  2. 每个站台：多个方向为 `BOTH`，无为空串；种类先按越行标志设 `PASSING` 或空。
  3. 按 (车站, 网络, 方向) 分组，组内 ≥ 2 个且有非越行标志的通过站台时：非越行标志站台中有通过的为 `MAIN`，其余为 `PASSING`。
  4. 按 (车站, 网络) 求车站轴线（各站台方向统一指向上行后相加），以车站中心为原点，按到轴线的叉积（左边为负）再按站台名排序，得到 `order`（从左往右看向上行）。
- 参数：`platforms` 全部站台及其车站；`routes` 全部线路及其按序停站。
- 返回：站台 id → `Track`。副作用：无。
- 调用者：`ClientStationTracks.java:64`。

## 内部类 Track（public static final）
- `direction`（String）：`UP`/`DOWN`/`BOTH`，或无线路使用时为空串。
- `kind`（String）：`MAIN`/`PASSING`，无法判断时为空串。
- `order`（int，可变）：在车站内的横向位置，从左往右（面向上行）。
- 私有构造函数：设方向与种类。

## 内部类 PlatformInput（public static final）
- 字段：`id`、`stationId`（0 = 不属于车站）、`network`（站台图网络编号）、`name`、`passingTrack`（越行标志）、`x1,z1`（第一端，即站台图方向 0 的起点）、`x2,z2`。
- 构造函数按字段赋值。调用者：`ClientStationTracks`。
- `private double middleX()` / `middleZ()`：站台中点。

## 内部类 RouteInput（public static final）
- `stops`（List<StopInput>）：按序停站（构造后由调用方填充）。

## 内部类 StopInput（public static final）
- `platformId`（long）；`way`（int）：0 = 从站台第一端驶向第二端，1 = 反向，−1 = 未知；`passes`（boolean）：该服务是否不停站通过。
- 构造函数按字段赋值。
