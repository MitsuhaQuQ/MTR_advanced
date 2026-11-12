# TrainAnnouncerScreen.java（上游文件，本分支有较多改动）

客户端界面：列车广播器设置——消息文字与声音，以及（基类提供的）线路过滤和"只在停车/行驶时"。0.72.0 起与音箱一样可依次播放多个声音、每个之后有间隔：声音文本框是第一个声音，"拼接…"按钮编辑整个列表（`ClipList`）。车站触发与站台听众已去掉（广播器只给经过它的乘客听，与 MTR 一致）。声音可以是游戏声音、服务器上的上传文件或网址。打开：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:186`。

## 类 `TrainAnnouncerScreen`（extends `TrainSensorScreenBase`）

### 字段与常量
- `initialMessage`：消息（打开子界面期间保存输入）。
- `soundTree`（0.73.1）：声音框旁的树状声音列表（`SoundTree`，含游戏声音）：根是 minecraft、mtr、上传音频（再按上传者分）及其他模组命名空间；试听按钮 `AnnouncementPlayer.play`，"添加"把值填入声音框并收起列表。
- `availableSoundsList`：即 `soundTree.list`，用来定位、显示和隐藏。
- `clips`：编辑中的声音列表（`EditedClip`：声音、间隔文本，秒）。
- `MAX_MESSAGE_LENGTH = 256`。

### 构造函数 `TrainAnnouncerScreen(BlockPos pos)`
- 基类：有速度复选框、无时间选项行；两个文本框：消息、声音（长度上限 `AnnouncementSound.MAX_LENGTH`）。从方块实体读取消息与片段（间隔用 `AudioSpeakerScreen.secondsText` 转为秒文本）；建 `soundTree`。

### `protected void init()`
- 填入消息与第一个声音；"拼接（N）"按钮（在线路编辑按钮旁）打开 `ClipList`；声音列表初始隐藏在右侧屏外。

### `private void keepFields()`
- 记下消息与第一个声音。

### `private void setFirstSound(String sound)`
- 声音框即第一个片段：列表空且有值则加入（间隔 0）；只有一个且清空则删除；否则替换第一个的声音。

### `protected String[] getTimingSettings()`
- 先同步第一个声音，再把片段转为 `AnnouncementClips.Clip`（间隔十分之一秒，`AudioSpeakerScreen.tenths`，非数字为 0），返回 `{AnnouncementClips.toText(list)}`，随保存包发送。

### `void tick()` / `void mouseMoved(…)` / `boolean mouseScrolled(…)`
- 列表 tick 与事件转发。

### `boolean mouseClicked(double, double, int)`
- 列表显示时先交给列表（点文件夹打开、点第一行回上一级）；点击声音文本框时显示列表；点击列表以外时隐藏。

### `protected void renderAdditional(PoseStack)`
- 列表背景与列表。

### `private void setListVisibility(boolean visible)`
- 列表移到右半屏或屏外。

## 私有内部类 `EditedClip`
- `sound`、`gap`（输入的秒数文本）。

## 私有内部类 `ClipList`（extends `ScreenBase`）
- 依次排列的声音（0.72.0）：每行声音文本框、"选择"（打开 `picker`）、间隔文本框（最后一个没有）、上移、下移、删除；分页（每行 22 像素）；"添加片段"（上限 `AnnouncementClips.MAX_CLIPS`，打开 `picker`）；"完成"前检查所有间隔可解析，否则显示 `gui.mtr.audio_speaker_invalid_gap`。
- `rowsPerPage()`、`init()`、`addSmall(Component, x, y, width, active, Runnable)`（按钮动作后重建界面）、`swap(a, b)`、`render(…)`（标题与帮助或"无片段"）、`onClose()`（去掉空声音后回到广播器界面）、`isPauseScreen()`（false）。

### `private SoundPickerScreen picker(ScreenBase back, Consumer<String> chosen)`
- 0.73.1：`ClipList` 的"选择"和"添加片段"打开的整屏树状选择（`SoundPickerScreen`，含游戏声音，试听用 `AnnouncementPlayer.play`），选中后回到 `back`。
