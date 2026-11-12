# SoundPickerScreen.java（本分支新增，0.73.1）

客户端：整屏的 `SoundTree`。选中声音后交给调用方并回到打开它的界面。音频方块的片段列表（`AudioSpeakerScreen.chooseSound`）和广播器的片段列表（`TrainAnnouncerScreen.picker`）在这里选声音。

## 类 `SoundPickerScreen`（extends `ScreenBase` implements `IGui`）

### 构造函数 `SoundPickerScreen(Component title, ScreenBase back, boolean withGameSounds, Consumer<String> preview, Consumer<String> chosen)`
- `back`：返回的界面；`withGameSounds`、`preview` 原样交给 `SoundTree`；`chosen` 收到选中的值，之后 `onClose` 回到 `back`。

### `protected void init()`
- 列表居中（最宽 420 像素），底部"返回"按钮。

### `void tick()` / `boolean mouseClicked(…)` / `void mouseMoved(…)` / `boolean mouseScrolled(…)`
- 转给树和列表；左键先交给列表（文件夹、回上一级、点击选中）。

### `void render(PoseStack, int, int, float)`
- 标题后接当前路径（`SoundTree.path()`），列表背景和列表。

### `void onClose()`
- 回到 `back`。

### `boolean isPauseScreen()`
- false。
