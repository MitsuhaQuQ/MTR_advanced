# AudioSpeakerScreen.java（本分支新增）

客户端界面：音箱（`BlockAudioSpeaker`）设置，用刷子打开（服务器发包 → `common/src/main/java/mtr/client/SpeakerPlayback.java:48`）。0.70.0 起声音是一列依次播放、每段之后有间隔的片段（`ClipList`），"间隔"以十分之一秒精度的秒数输入：循环模式下是最后一段之后的静音，否则是多久之后才能再次触发。完成时把 `SpeakerSettings` 发给服务器（`PACKET_UPDATE_AUDIO_SPEAKER`：`BlockPos` + 设置）。声音只能从服务器上传、且已有游戏声音位置的文件中选。

## 类 `AudioSpeakerScreen`（final，extends `ScreenBase`）

### 字段
- `world`（打开时的世界，完成时世界已变则直接关闭）、`pos`、`initialSettings`。
- 文本框：`radius`（听众半径，方块）、`centerX/Y/Z`（区域触发中心）、`triggerRadius`（区域触发半径）、`interval`（秒）。
- 按钮：`modeButton`（红石 / 循环 / 区域）、`soundButton`（打开片段列表）、`directionButton`（打开方向选择）、`appearanceButton`（外观方块）、`repeatButton`（"播完再触发"/"打断"）。
- `mode`、`audienceMask`（听众方向位掩码）、`clips`（`EditedClip`）、`appearance`（方块 id）、`invalid`（输入错误）、`left`、`top`、`panelWidth`、`finishBeforeRepeat`。

### 构造函数 `AudioSpeakerScreen(BlockPos pos, SpeakerSettings settings)`
- 从设置填充所有控件（片段间隔转为秒文本）。

### `protected void init()`
- 面板宽至多 320，居中；依次放置声音、模式、半径、方向、中心坐标、触发半径、外观、重复方式、间隔、完成/取消。

### `void render(…)`
- 标题、各标签（区域模式才显示中心与触发半径；循环模式间隔标签为 `gui.mtr.audio_speaker_loop_interval`）、外观方块图标、输入错误提示。

### `boolean isPauseScreen()`
- false。

### `private void chooseSound(ScreenBase back, Consumer<String> chosen)`
- 0.73.1：打开 `SoundPickerScreen`（不含游戏声音，没有试听按钮，点击即选）：上传音频按上传者分文件夹，只列有游戏声音位置的文件；选中后交给 `chosen` 并回到 `back`。

### `private void chooseAppearance()`
- 列出所有可作音箱外观的方块（`BlockAudioSpeaker.appearance(id) != null`），显示"名称 [id]"，打开 `Picker`。

### `private void apply()`
- 区域模式取输入的中心（须在世界范围内）与触发半径，否则保留原值；片段间隔与间隔用 `tenths` 转换；构造 `SpeakerSettings` 写包发送并关闭。任一数字无效时显示错误（不关闭）。

### `private Component repeatLabel()` / `soundLabel()` / `appearanceLabel()` / `directionLabel()` / `modeLabel()`
- 按钮文字：声音按钮显示"无声音"/第一个声音/第一个声音与段数；方向按钮显示全部/无/各方向名。

### `private static Component faceLabel(Direction face)`
- `gui.mtr.audio_speaker_direction.<方向>`。

### `private void updateRegionFields()`
- 循环模式下禁用重复方式按钮（间隔仍可编辑：0.70.0 前循环时间隔被锁定）；只有区域模式显示中心与触发半径。

### `private void place(AbstractWidget, int x, int y, int width)`
- 放置并（文本框）把光标移到开头（赋值时文本框尚无可见宽度）。

### `private static WidgetBetterTextField number(int value, String hint, int length)` / `private static int value(WidgetBetterTextField)`
- 整数文本框；解析整数（失败抛 `NumberFormatException`，属于 `IllegalArgumentException`）。

### `private static WidgetBetterTextField seconds(int tenths)`
- 秒数文本框（小数）。

### `static String secondsText(int tenths)`
- 十分之一秒 → 秒文本（整数时不带小数）。调用者：`common/src/main/java/mtr/screen/TrainAnnouncerScreen.java:62`。

### `static int tenths(String text)`
- 返回：秒文本四舍五入为十分之一秒；空为 0。非数字、负数或超过 `SpeakerSettings.MAX_TENTHS` 抛 `IllegalArgumentException`。调用者：`TrainAnnouncerScreen.java:137`、`:258`。

## 私有内部类 `EditedClip`
- `sound`（上传文件引用）、`gap`（秒文本）。

## 私有内部类 `ClipList`（extends `ScreenBase`）
- 片段列表（0.70.0）：每行"序号. 声音"按钮（点击重选）、间隔框（最后一段没有，它之后是间隔设置）、上移、下移、删除；分页；"添加片段"（上限 `SpeakerSettings.MAX_CLIPS`）；"完成"前检查所有间隔。修改只在点主界面"完成"时才发送。方法：`rowsPerPage`、`init`、`addSmall`、`swap`、`render`、`onClose`（更新声音按钮文字并返回）、`isPauseScreen`。

## 私有内部类 `DirectionPicker`（extends `ScreenBase`）
- 六个方向复选框（两列）、"全部"按钮、完成（写回 `audienceMask`）/取消。

## 私有内部类 `Choice`
- `value`（保存值）、`label`（显示文字）。

## 私有内部类 `Picker`（extends `ScreenBase`）
- 带搜索的选择列表（现在只用于外观方块）：每行都设为"标题行"，点击即选择（用对象身份映射到值，不依赖 id 或下标）并返回 `back`。方法：`init`、`tick`、`mouseClicked`、`mouseScrolled`、`render`、`onClose`、`isPauseScreen`。
