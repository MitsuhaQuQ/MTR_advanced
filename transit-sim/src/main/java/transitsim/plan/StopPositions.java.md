# StopPositions.java

站台上的停车位置设置（优化第 4 轮"停车点"）：车头停在出站端（一贯做法）、按车长居中，或距出站端若干方块。游戏和组件用同一函数算停车偏移。游戏侧 `mtr.data.Platform` 读写设置时用 `normalise`，`mtr.path.PathData.getStopOffset` 用 `getOffset`；见 `PlanTrack.getStopOffset`。

## 类 `StopPositions`（final，工具类）

### 常量
- `EXIT_END = 0`：车头停在站台出站端。
- `CENTRED = -1`：列车停在站台中间。
- 正数：距出站端的方块数。

### `private StopPositions()`
禁止实例化。

### `public static double getOffset(int setting, double platformLength, double trainLength)`
- 作用：计算车头距出站端的停车偏移。可用余量 `room = max(0, 站台长 - 车长)`；`CENTRED` 返回 `room/2`；正数返回 `min(setting, room)`；其它（0 或除 -1 外的负数）返回 0。保证车尾不出站台，车长 ≥ 站台长时为 0。
- 参数：`setting` 设置值；`platformLength` 站台轨道长度；`trainLength` 列车长度（与长度同单位）。
- 返回值：偏移量。
- 主要调用者：`common/src/main/java/mtr/path/PathData.java:226`。

### `public static int normalise(int setting)`
- 作用：任何负数统一为 `CENTRED`，其余原样。
- 主要调用者：`common/src/main/java/mtr/data/Platform.java:68`、`Platform.java:121`、`Platform.java:173`。
