# ClipSequence.java

音频扬声器的片段依次播放（0.70.0）：每个片段，然后其后设定的间隔，再下一个；最后一个之后，循环模式等待循环间隔后从第一个重新开始，单次模式则结束。播放器每 tick 告诉它刚开始的片段是否还在播放；它告诉播放器现在应开始哪个片段（如果有）。间隔为 0 时，在发现上一片段结束的同一 tick 开始下一片段。

纯状态机，与仿真无关但放在组件中以便测试。调用者：客户端 `AnnouncementPlayer.java:418`、`SpeakerPlayback.java:194`（构造、`tick`、`hasEnded`），方块 `BlockAudioSpeaker.java:65`、`:189`。

## 类 ClipSequence（final）

### 字段
- `gapTicks`（long[]）：每个片段之后的间隔（tick），每个片段一项（最后一项不用）。
- `loop`（boolean）：是否循环。
- `loopGapTicks`（long）：循环时最后一个片段后到重新开始的间隔（≥ 0）。
- `index`（int）：当前/下一个片段下标。
- `wait`（long）：剩余等待 tick。
- `playing`（boolean）：是否有片段正在播放。
- `ended`（boolean）：单次播放是否已结束。

### 构造函数 `ClipSequence(long[] gapTicks, boolean loop, long loopGapTicks)`
- 作用：拷贝间隔数组；`loopGapTicks` 负数取 0。
- 副作用：`gapTicks` 为空时抛 `IllegalArgumentException("No clips")`。
- 调用者：`AnnouncementPlayer.java:418`、`SpeakerPlayback.java:194`。

### `int tick(boolean clipPlaying)`
- 作用：推进一个 tick。已结束返回 −1。正在播放时：片段仍在播返回 −1；播完则若为最后一个且不循环 → 结束返回 −1；否则设等待（最后一个用循环间隔，否则该片段的间隔，负数按 0）并移到下一个（最后一个回到 0），等待 > 0 时返回 −1（间隔从下一 tick 起算）。未播放且在等待时：等待减 1，仍 > 0 返回 −1。否则开始播放并返回当前下标。
- 参数：`clipPlaying` 上次开始的片段是否仍在播放（没有片段在播时忽略）。
- 返回：现在要开始的片段下标，或 −1。
- 副作用：修改内部状态。
- 调用者：`AnnouncementPlayer.java:103`、`BlockAudioSpeaker.java:65`。

### `boolean hasEnded()`
单次播放是否已播完最后一个片段。调用者：`AnnouncementPlayer.java:436`、`SpeakerPlayback.java:227`。

### `static long totalTicks(long[] durationTicks, long[] gapTicks)`
单次播放总时长：各片段时长（负数按 0）之和，加上除最后一个外每个片段之后的间隔（负数按 0；间隔数组较短时缺的不计）。调用者：`BlockAudioSpeaker.java:189`、`ClipSequenceTest.java:58`。
