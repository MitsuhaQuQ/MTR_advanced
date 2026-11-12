# Config.java（上游文件，本分支有改动）

客户端：MTR 客户端设置，保存在 `<游戏目录>/config/mtr.json`。所有值为静态字段，读取用同名方法，设置用 `setXxx`（设置后立即写文件）。由设置界面 `common/src/main/java/mtr/screen/ConfigScreen.java` 修改；`refreshProperties` 在客户端初始化与资源重载时调用（`common/src/main/java/mtr/MTRClient.java:374`、`:377`）。

本分支新增：`showDebugInfo` 的说明（0.66.4 不用刷子也显示调试信息）、网络音频开关与允许站点、服务器音频预下载开关与大小上限。

## 类 `Config`

### 设置项（字段 · JSON 键 · 默认 · 含义 · 主要读取者）
- `useMTRFont` · `use_mtr_font` · false · 使用 MTR 字体渲染文字 · `common/src/main/java/mtr/client/IDrawing.java:45`。
- `showAnnouncementMessages` · `show_announcement_messages` · false · 在聊天栏显示报站文字 · `IDrawing.java:215`、`common/src/main/java/mtr/render/RenderTrains.java:136`。
- `useTTSAnnouncements` · `use_tts_announcements` · false · 文字转语音报站 · `IDrawing.java:212`。
- `hideSpecialRailColors` · `hide_special_rail_colors` · false · 隐藏特殊轨道颜色 · `RenderTrains.java:489`。
- `hideTranslucentParts` · `hide_translucent_parts` · false · 隐藏列车半透明部分 · `RenderTrains.java:236`。
- `optimizeTrainRendering` · `optimize_train_rendering` · false（每次 `refreshProperties` 先重置为 false 再读）· 优化列车渲染 · `common/src/main/java/mtr/data/TrainClient.java:244`、`cn/zbx1425/mtrsteamloco/mixin/LevelRendererMixin.java:46`。
- `showDebugInfo` · `show_debug_info` · false · 不用刷子也显示调试信息（0.66.4）：所看列车，以及乘客信息屏、到站投影、时刻传感器所读的到站 · `common/src/main/java/mtr/render/RenderTrainDebug.java:58`。
- `shiftToToggleSitting` · `shift_to_toggle_sitting` · false · 按潜行切换坐下 · `TrainClient.java:189`、`cn/zbx1425/mtrsteamloco/mixin/TrainClientMixin.java:149`。
- `languageOptions` · `language_options` · 0 · 0 = 显示全部语言，1 = 只显示中文段，2 = 只显示非中文段（见 `ClientCache.getTextPixels` 的过滤；取值对 3 取模）· `common/src/main/java/mtr/client/ClientCache.java:321`。
- `useDynamicFPS` · 无键 · true · 动态帧率 · `RenderTrains.java:138`。注意：`writeToFile` 不写此项、`refreshProperties` 不读此项，所以设置只在本次运行有效。
- `trackTextureOffset` · `track_texture_offset` · 0 · 轨道纹理偏移，范围 0…`TRACK_OFFSET_COUNT-1` · `RenderTrains.java:488`。
- `dynamicTextureResolution` · `dynamic texture resolution`（键中是空格）· 2 · 动态纹理分辨率等级，0…`DYNAMIC_RESOLUTION_COUNT-1` · `common/src/main/java/mtr/client/RouteMapGenerator.java:37`。
- `trainRenderDistanceRatio` · `train_render_distance_ratio` · 7 · 列车渲染距离比例，0…`TRAIN_RENDER_DISTANCE_RATIO_COUNT-1` · `RenderTrains.java:142`。
- `networkAudioEnabled` · `network_audio_enabled` · false · 允许广播器播放网址音频（否则编辑广播器的人能让客户端访问任意网站）· `common/src/main/java/mtr/client/AnnouncementPlayer.java:192`。
- `networkAudioAllowedHosts` · `network_audio_allowed_hosts`（JSON 数组）· 空 · 允许的站点（去空格、小写，空项忽略；也允许其子域名），不可修改列表 · `AnnouncementPlayer.java:202`。无 setter，只能改文件。
- `audioDownloadAll` · `audio_download_all` · true · 服务器列出广播音频后立即下载（首次就能播放）；关闭则在首次播放时下载 · `common/src/main/java/mtr/client/AudioCache.java:276`。
- `audioPrefetchMaxKilobytes` · `audio_prefetch_max_kilobytes` · 1024 · 只预下载不超过此大小（KB）的文件，读取时限制在 0…`AudioFiles.MAX_FILE_BYTES / 1024` · `AudioCache.java:277`。

### 常量
- `PATREON_LIST`：赞助者列表（`MTRClient.java:373` 填充，`ConfigScreen.java:245` 显示）。
- `TRACK_OFFSET_COUNT = 32`、`DYNAMIC_RESOLUTION_COUNT = 8`、`TRAIN_RENDER_DISTANCE_RATIO_COUNT = 16`。
- `CONFIG_FILE_PATH`：`<游戏目录>/config/mtr.json`（类加载时取 `Minecraft.getInstance().gameDirectory`）。
- 各 JSON 键常量见上。

### 读取方法
- `useMTRFont()`、`showAnnouncementMessages()`、`useTTSAnnouncements()`、`hideTranslucentParts()`、`optimizeTrainRendering()`、`showDebugInfo()`、`shiftToToggleSitting()`、`languageOptions()`、`hideSpecialRailColors()`、`useDynamicFPS()`、`trackTextureOffset()`、`dynamicTextureResolution()`、`trainRenderDistanceRatio()`、`audioDownloadAll()`、`audioPrefetchMaxKilobytes()`、`networkAudioEnabled()`、`networkAudioAllowedHosts()`：返回对应字段。

### 设置方法（均写文件）
- `boolean setUseMTRFont / setShowAnnouncementMessages / setUseTTSAnnouncements / setHideSpecialRailColors / setHideTranslucentParts / setOptimizeTrainRendering / setShowDebugInfo / setShiftToToggleSitting / setUseDynamicFPS(boolean)`：返回设置后的值。调用者：`ConfigScreen.java:71`–`:103`。
- `int setLanguageOptions(int)`：对 3 取模，返回新值。
- `void setTrackTextureOffset / setDynamicTextureResolution / setTrainRenderDistanceRatio(int)`：限制在范围内。调用者：`ConfigScreen.java:274`–`:276`。

### `static void refreshProperties()`
- 作用：先把 `optimizeTrainRendering` 设为 false，再读文件逐项覆盖（每项单独 try，缺失或格式错误的项保持当前值）。文件读不了或不是 JSON 时以当前值写出新文件并打印异常。

### `private static void writeToFile()`
- 作用：把上述各项（除 `useDynamicFPS`）写成格式化 JSON（`RailwayData.prettyPrint`）覆盖文件；异常打印。
