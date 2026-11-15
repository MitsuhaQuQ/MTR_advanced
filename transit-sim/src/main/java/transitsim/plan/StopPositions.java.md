
# StopPositions.java

站台上的停车位置设置（优化第 4 轮"停车点"）：车头停在出站端（一贯做法）、按车长居中、车尾对齐进站端（0.84.6），或距出站端若干方块。游戏和组件用同一函数算停车偏移；沿路径的偏移（含站前折返原地停车，0.84.6）也只在这里算，两个宿主一致。游戏侧 `mtr.data.Platform` 读写设置时用 `normalise`，`mtr.path.PathData.getStopOffset` 用 `getOffset(setting, …)`；见 `PlanTrack.getStopOffset`。

## 类 `StopPositions`（final，工具类）

### 常量
- `EXIT_END = 0`：车头停在站台出站端。
- `CENTRED = -1`：列车停在站台中间。
- `TAIL_AT_ENTRY_END = -2`（0.84.6）：车尾对齐站台进站端，即车头距出站端 = 余量。存档仍是普通 int，旧版读作居中。
- 正数：距出站端的方块数。

### `private StopPositions()`
禁止实例化。

### `public static double getOffset(int setting, double platformLength, double trainLength)`
- 作用：计算车头距出站端的停车偏移。可用余量 `room = max(0, 站台长 - 车长)`；`CENTRED` 返回 `room/2`；`TAIL_AT_ENTRY_END` 返回 `room`；正数返回 `min(setting, room)`；其它（0 或其它负数）返回 0。保证车尾不出站台，车长 ≥ 站台长时为 0。
- 参数：`setting` 设置值；`platformLength` 站台轨道长度；`trainLength` 列车长度（与长度同单位）。
- 返回值：偏移量。
- 主要调用者：`common/src/main/java/mtr/path/PathData.java:226`、`SimDataset.DataTrack.getStopOffset`。

### `public static double afterReversal(double arrivingOffset, double platformLength, double trainLength)`（0.84.6）
- 作用：站前折返（在停过的站台上折返）后返程停站的偏移 = `max(0, room − 进站时偏移)`。折返时车头移到原车尾处，正好离返程出站端这么远，所以列车已站在停站点，开门前不再沿站台挪动。

### `public static boolean turnsBackOnPlatform(PlanTrack previous, PlanTrack track)`（0.84.6）
- 作用：`track` 是否为站前折返后的停站：两条都有停站时间、`track` 是站台且站台 ID 非 0、两条站台 ID 相同、`previous.isOppositeOf(track)`。
- 主要调用者：`getOffset(List, …)`、`common/src/main/java/mtr/data/PathTiming.java`（折返时对齐到返程停站点）。

### `public static double getOffset(List<? extends PlanTrack> path, int rail, double trainLength)`（0.84.6）
- 作用：路径上第 `rail` 条轨道的停车偏移：无停站时间为 0；站前折返后的停站（`turnsBackOnPlatform(path[rail-1], path[rail])`）为 `afterReversal(前一条的偏移, 轨道长, 车长)`（前一条的偏移递归按本函数求）；否则按站台设置 `track.getStopOffset(trainLength)`。进站那次停站不受影响。
- 主要调用者：组件 `TripPath.getStopDistance`、`TurnTimes.legs`、`RunTrack.buildTrackView`、`TickRunner.HeadlessTrain.getStopOffset`；游戏 `PathTiming.generateTimeSegments`、`Train.getStopOffset`、`TrackViews.of`。

### `public static int normalise(int setting)`
- 作用：`TAIL_AT_ENTRY_END` 原样保留；其它负数统一为 `CENTRED`；其余原样。
- 主要调用者：`common/src/main/java/mtr/data/Platform.java:68`、`Platform.java:121`、`Platform.java:173`。
