# TurnBackPoint.java

`TurnBacks` 中的一个折返点：列车在此可以采用的各种折返走法，用轨道 key（`UUID`，两方向共用）表示，便于在运行时判断某走法是否被别的列车占用。可由 `TurnBackSection` 或直接由走法轨道序列构造，并序列化成 long 数组。游戏侧 `mtr.data.Siding:1357` 创建运用的折返点；`transitsim.sim.TrainService`（`TrainService.java:1430`–`1472`）选走法时读取。

## 类 `TurnBackPoint`

### 字段
- `rails`（包内可见，final）：每种走法中列车折返所在的轨道 key；不可用为 null。没有折返的走法（如环形绕行）保存全部轨道，使整条走法保持空闲。
- `lengths`（private final）：每种走法从停站到下一路线首站的轨道数。
- `wayRails`（private final）：每种走法从停站到下一路线首站之间的全部轨道 key；不可用为 null。用于判断另一列车是否已占用该走法。
- `defaultWay`（public final）：默认走法。

### `public TurnBackPoint(List<List<UUID>> rails, int[] lengths, int defaultWay)`
`wayRails` 取与 `rails` 相同（旧数据没有完整轨道时的退化）。

### `public TurnBackPoint(List<List<UUID>> rails, int[] lengths, List<List<UUID>> wayRails, int defaultWay)`
原样赋值。

### `TurnBackPoint(TurnBackSection<?> section)`（包内）
等同 `TurnBackPoint(section.ways, section.defaultWay, section.start - section.getStopRail())`。由 `TurnBacks.fromSections` 使用。

### `public TurnBackPoint(List<? extends List<? extends PlanTrack>> ways, int defaultWay, int stopOffset)`
- 作用：对每种走法，取去掉首尾的中间轨道 key 作为 `wayRails`；其中与下一段互为反向的轨道作为折返轨 `rails`（没有则用全部中间轨道）；`lengths[i] = stopOffset + way.size() - 1`。
- 参数：`ways` 每种走法从离开的停站到下一路线首站的轨道，不可用为 null；`defaultWay` 默认走法；`stopOffset` 停站在走法第一段轨道之前多少段。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:1357`。

### `static TurnBackPoint read(long[] values)`（包内）
- 作用：读 `write` 的格式：`defaultWay`、走法数；每种走法 `length`、折返轨数（-1 表示 null）及每个 UUID 的高/低 64 位；之后是每种走法的全部轨道（同样格式）。若数组在全部轨道部分之前结束（旧数据），只用折返轨构造。
- 主要调用者：`TurnBacks.read`。

### `long[] write()`（包内）
按上述格式输出 long 数组。主要调用者：`TurnBacks.write`。

### `public int getWayCount()`
走法数。

### `public List<UUID> getRails(int way)`
该走法的折返轨；不可用为 null。

### `public List<UUID> getWayRails(int way)`
该走法的全部轨道；不可用为 null。

### `public int getLength(int way)`
该走法从停站到下一路线首站的轨道数。
