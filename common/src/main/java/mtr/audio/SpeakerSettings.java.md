# SpeakerSettings.java（本分支新增）

音箱方块（`BlockAudioSpeaker`）保存的设置，也是刷子编辑界面提交的数据（服务端与客户端共用，不可变对象）。0.70.0 起一个音箱按顺序播放一组片段（`Clip`），每段后有间隔；`sound` 是第一段的声音（保持旧字段含义）。间隔和重复间隔以 0.1 秒为单位存储；`intervalSeconds` 为向下取整的秒数（保持旧字段）。使用者：`common/src/main/java/mtr/block/BlockAudioSpeaker.java`、`packet/AudioSpeakerPackets.java`、`client/SpeakerPlayback.java`、`screen/AudioSpeakerScreen.java`、`audio/AnnouncementClips.java`。测试：`common/src/test/java/mtr/audio/SpeakerSettingsTest.java`。

## 类 `SpeakerSettings`（final）

### 常量
- `MAX_RADIUS = 128`（格）、`MAX_CLIPS = 16`、`MAX_TENTHS = 36000`（一小时，单位 0.1 秒）。
- `ALL_DIRECTIONS = 0x3F`：六个方向全开的听众方向位掩码。
- `DEFAULT_APPEARANCE = minecraft:jukebox`：默认外观方块。
- `FACES`：`Direction.values()`。

### 字段（全部 final）
- `sound`：第一段声音文本（无片段时为空串）。
- `mode`：播放模式（`Mode`）。
- `radius`：可听半径（格，1–128）。
- `triggerCenter`、`triggerRadius`：区域触发模式的中心与半径（格）。
- `appearance`：方块外观（伪装成的方块 id）。
- `audienceMask`：听众方向位掩码（位 = `1 << Direction.get3DDataValue()`）；只有在所选面前方的玩家能听到。
- `finishBeforeRepeat`：是否等播放完才允许再次触发。
- `intervalSeconds`、`intervalTenths`：循环模式下最后一段之后到重新开始的静默；其他模式下两次触发的最小间隔（`finishBeforeRepeat` 时从播放结束算起）。
- `clips`：不可修改的片段列表，空声音的片段会被去掉。

### 构造函数
- `SpeakerSettings(String sound, Mode, int radius, BlockPos triggerCenter, int triggerRadius, ResourceLocation appearance)`：全方向。
- `SpeakerSettings(..., int audienceMask)`：不等播完、间隔 0。
- `SpeakerSettings(..., boolean finishBeforeRepeat, int intervalSeconds)`：单片段，间隔秒 ×10。
- `SpeakerSettings(List<Clip> clips, Mode, int radius, BlockPos triggerCenter, int triggerRadius, ResourceLocation appearance, int audienceMask, boolean finishBeforeRepeat, int intervalTenths)`：主构造，片段过多、半径/触发半径/掩码/间隔不合法抛 `IllegalArgumentException("Invalid speaker settings")`；外观为 null 用默认。

### `static SpeakerSettings defaults(BlockPos position)`
- 无声音、红石模式、半径 24、触发中心为自身、触发半径 4、默认外观。

### `boolean seamlessLoop()`
- 只有一段且间隔 0：循环时用游戏自带循环声音，完全无缝。

### NBT 键（`write(CompoundTag)` / `read(CompoundTag, BlockPos)`）
- `sound`、`mode`（枚举名）、`radius`、`trigger_center`（long）、`trigger_radius`、`appearance`、`audience_mask`、`finish_before_repeat`、`interval_seconds`、`interval_tenths`、`clips`（列表，每项 `sound`、`gap_tenths`）。
- `read`：有 `clips` 列表则读（最多 16 段），否则把旧的单个 `sound` 当一段（0.70.0 前的存档）；有 `interval_tenths` 则读，否则用 `interval_seconds × 10`；方向掩码用 `readAudienceMask`；任何不合法回退为 `defaults(position)`。

### `private static int readAudienceMask(CompoundTag tag)`
- 有 `audience_mask` 则用（不合法用全方向）；否则读旧键 `audience_direction`（单个方向名）转位掩码，没有则全方向。

### 包字段顺序（`write(FriendlyByteBuf)` / `read(FriendlyByteBuf)`）
1. 片段（`writeClips`：VarInt 数量，每段 UTF(≤1024) 声音 + VarInt 间隔）
2. `Mode` 枚举
3. VarInt 半径
4. BlockPos 触发中心
5. VarInt 触发半径
6. ResourceLocation 外观
7. byte 方向掩码
8. boolean 等播完
9. VarInt 间隔（0.1 秒）
- `read` 不合法时抛 `IllegalArgumentException`。

### `static void writeClips(FriendlyByteBuf, List<Clip>)` / `static List<Clip> readClips(FriendlyByteBuf)`
- 读时数量 < 0 或 > 16 抛 `IllegalArgumentException("Too many clips")`。

### `long[] gapTicks()`
- 每段后的间隔（tick），最后一段的不用。

### `static long tenthsToTicks(int tenths)`
- 0.1 秒 = 2 tick。

### `static boolean validTenths(int)` / `private static int checkedSeconds(int)` / `private static List<Clip> single(String)` / `static boolean validRadius(int)`
- 范围校验（0–36000；秒 0–3600 否则抛异常；半径 1–128）与单片段列表构造。

### `static boolean inRange(BlockPos center, int radius, double x, double y, double z)`
- 全方向下是否在半径内。调用者：`common/src/main/java/mtr/block/BlockAudioSpeaker.java:232`、`packet/AudioSpeakerPackets.java:38`。

### `static int directionBit(Direction)` / `static boolean validAudienceMask(int)`
- 方向位与掩码合法性（只用低 6 位）。

### `static boolean audibleFrom(BlockPos center, int radius, int audienceMask, double x, double y, double z)`
- 作用：以方块中心为原点，距离 ≤ 半径，且掩码为全方向、或位于任一所选面的前方（与该面法线点积 > 0；正好在中心也算）时为真；掩码为 0 时永远听不到。

### `static float volumeAtDistance(double distanceSquared, int radius)`
- 线性衰减 `max(0, 1 − 距离 / 半径)`。

### `static float volumeAt(BlockPos center, int radius, int audienceMask, double x, double y, double z)`
- 听不到为 0，否则按距离衰减。可听性只影响音量，不影响声音的存续与播放进度。调用者：`common/src/main/java/mtr/client/SpeakerPlayback.java:279`。

## 内部类 `Clip`（final）
- 字段 `sound`（经 `AnnouncementSound.clean`）、`gapTenths`。
- 构造函数：非空声音必须是上传文件形式（`splitUploaded` 非 null），间隔须合法，否则抛 `IllegalArgumentException("Invalid speaker clip")`。
- `equals`/`hashCode`：按声音与间隔。

## 枚举 `Mode`
- `LOOP`（循环）、`REDSTONE`（红石上升沿触发）、`REGION`（玩家进入区域触发）。

## 内部类 `Trigger`（final）
- 上升沿检测；加载或编辑后的第一次采样只建立基准，不触发。
- `boolean sample(boolean signal)`：已初始化且上次为假、本次为真时返回 true。
- `void reset()`：回到未初始化。
