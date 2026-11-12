# MTRClient.java（上游文件，本分支有改动）

客户端入口：注册方块渲染层与方块实体渲染器（列车由 `EntitySeat` 的渲染器绘制，见 `RenderTrains`）、服务器发来的包的接收器、按键、客户端 tick（`ClientData.tick()`）与资源重载监听。调用者：`fabric/src/main/java/mtr/MTRFabricClient.java`、`forge/src/main/java/mtr/MTRForge.java`（`init`、`initItemModelPredicate`、每帧 `incrementGameTick`）。

本分支改动：注册站台端门、音箱等新方块的渲染；新增大量 S2C 包接收器（线路词、时刻表配置、编辑权限、寻路区域、分块传输、车站区域工具、时刻表时间/冲突、日模拟结果、站场/线路布局、服务器时间、过期车厂、地图摘要与数据、音频列表/结果/数据、图片、关键节点、节点角度界面、音箱打开/播放、自动运用与缺车（`PACKET_AUTO_DUTIES`）、列车状态增量、编辑版本/编辑者/结果/数据、线路与车厂路线预览、扣车、晚点告警、应急预案、网络设置、票价组、轨道归属、调度台、列车调试）；每 tick 推进音箱与广播播放；玩家加入时停止上一世界的网页编辑器服务（`EditorServer.stop()`），连接远程服务器时读取客户端本地 `config/mtr_security.json` 并启动本地网页服务器；新物品（35/45 限速连接器、圆形隧道工具）的"已选中"模型谓词。

## 类 `MTRClient`（implements `IPacket`）

### 静态字段与常量
- `isReplayMod`、`isVivecraft`、`isPehkui`：玩家加入时检测（Replay Mod 按玩家类名，后两者按类是否存在）。
- `gameTick`：客户端游戏 tick（浮点，按帧时长累加）；`lastPlayedTrainSoundsTick`。
- 跳帧估计：`tick`、`startSampleMillis`、`startSampleGameTick`、`gameTickTest`、`skipTicks`、`lastSkipTicks`、`SAMPLE_MILLIS = 1000`。
- `TICKS_PER_SPEED_SOUND = 4`：列车速度声音的最小间隔（tick）。调用者：`common/src/main/java/mtr/sound/JonTrainSound.java`。
- `TACTILE_MAP_SOUND_INSTANCE`：触觉地图的循环音乐（`tactile_map_music`）。

### `static void init()`
- 作用（非 `Keys.LIFTS_ONLY` 时完整注册，否则只注册电梯相关）：
  1. 方块渲染层（cutout / translucent 等）与方块实体渲染器、实体渲染器。
  2. S2C 包接收器（见上；更新/删除车站、站台、停车轨、线路、车厂、电梯走 `PacketTrainDataGuiClient.receiveUpdateOrDeleteS2C`；列车、电梯、乘客、轨道操作、到站走 `ClientData`；编辑走 `ClientEditing`；音频走 `AudioCache`；地图走 `MapDataCache`（经 `PacketTrainDataGuiClient`））。`PACKET_AUTO_DUTIES`：读 `long 车厂 id`、`DepotDuties`（二进制）、`Depot.Shortfall`，主线程写入车厂的 `lineShortfall` 与 `autoDuties`（图表模式与停车轨）。
  3. tick：`SpeakerPlayback::tick`、`AnnouncementPlayer::tick`。
  4. 按键：电梯菜单；驾驶（加速、制动、开关门）与调试键。
  5. 读取赞助者列表与客户端设置（`Config.refreshProperties`）。
  6. 玩家加入：刷新设置、停止网页编辑器、检测 Replay Mod / Vivecraft / Pehkui；连接非本机世界时设置网页服务器回调（数据来自 `ClientData`）、读取安全设置（`SecurityConfig.load` + `apply`）并启动网页服务器。
  7. `Webserver.init()`，玩家离开时停止；触觉地图：设置声音位置回调与使用回调（播报"欢迎来到 <站名>"）。

### `static int getStationColor(BlockPos pos)`
- 返回：该位置所在车站的颜色，无车站或 null 时为 `0x7F7F7F`。调用者：`fabric/src/main/java/mtr/fabric/RegistryClientImpl.java`、`forge/src/main/java/mtr/forge/RegistryClientImpl.java`（方块着色）。

### `static void initItemModelPredicate()`
- 为所有点击两个方块的工具（轨道连接器、信号连接/移除器、桥梁/隧道/隧道墙工具、电梯按钮连接/移除器）注册 `mtr:selected` 模型谓词（物品 NBT 中有 `ItemBlockClickingBase.TAG_POS` 时显示"已选中"模型）。

### `static boolean isReplayMod()` / `isVivecraft()` / `isPehkui()`
- 检测结果。调用者：`cn/zbx1425/mtrsteamloco/mixin/LevelRendererMixin.java`、`common/src/main/java/mtr/render/RenderTrains.java` 等。

### `static float getGameTick()`
- 客户端游戏 tick。调用者：`cn/zbx1425/mtrsteamloco/mixin/GameRendererMixin.java` 等。

### `static void incrementGameTick()`
- 每帧调用：累加测试 tick；Replay Mod 时或每 `skipTicks` 帧一次把帧时长加到 `gameTick`；每秒按实际 tick 速率重算 `skipTicks`（变化时打印）；`ClientData.tick()`。（`skipTicks` 的具体意图从代码看是按帧率降低 `gameTick` 更新频率，未进一步确认。）

### `static float getLastFrameDuration()`
- 上一帧时长（tick）；Replay Mod 时固定 20/60。

### `static boolean canPlaySound()`
- 距上次播放列车速度声音至少 4 tick 时记下并返回 true（同一 tick 内多次调用都返回 true），游戏暂停时 false。

## 函数式接口 `RegisterItemModelPredicate`
- `void accept(String id, Item item, String tag)`。
