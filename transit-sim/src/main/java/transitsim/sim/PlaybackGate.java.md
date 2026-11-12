# PlaybackGate.java

在配置的播放/冷却窗口内忽略触发；从不排队。用于音频扬声器方块：触发信号到来时判断是否开始播放。调用者：`BlockAudioSpeaker.java:97`（持有）、`:190`（`accept`）、`:109`/`:122`（`reset`）；测试 `PlaybackGateTest`。

## 类 PlaybackGate（final）

### 字段
- `next`（long）：下一次可接受触发的 tick；之前的触发被忽略。

### `boolean accept(long tick, long durationTicks, boolean finish, long intervalTicks)`
- 作用：`tick < next` 时拒绝；否则接受，并设 `next = tick + (finish ? max(0, 时长) : 0) + max(0, 间隔)`。
- 参数：`tick` 当前 tick；`durationTicks` 本次播放时长；`finish` 是否要等播完才算窗口；`intervalTicks` 之后的冷却间隔。
- 返回：是否接受本次触发。副作用：接受时更新 `next`。
- 调用者：`BlockAudioSpeaker.java:190`、`PlaybackGateTest.java:7`。

### `void reset()`
`next = 0`，立即可再次触发。调用者：`BlockAudioSpeaker.java:109`、`:122`。
