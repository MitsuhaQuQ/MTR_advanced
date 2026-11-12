# IPacket.java

所有 MTR 网络包通道 id 的集中定义（服务端与客户端共用的接口，需要发包的类 `implements IPacket` 以直接使用常量），以及网络协议版本。本分支改动：网络版本改为本分支自己的版本 `MTM_Logic <mtm_version>`（不再是 MTR 版本加协议号，因此与之前的构建也不会匹配）；新增 `getProtocol` 只比较前两段版本号；修正 `PACKET_REMOVE_LIFT_FLOOR_TRACK` 原来与 `PACKET_REMOVE_NODE` 共用 `packet_remove_node` 通道的问题（现为 `packet_remove_lift_floor_track`）；新增大量通道（见下）；`MAX_PACKET_BYTES`。

## 接口 `IPacket`

### `static String getNetworkVersion()`
- 返回 `"MTM_Logic " + Keys.MTM_VERSION`（即 `gradle.properties` 的 `mtm_version`）。客户端与服务器必须一致的东西；MTR 自己的版本对附属模组保持 3.2.2，不能说明任何问题。调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:118`（版本检查）。

### `static String getProtocol(String networkVersion)`
- 截取到第二个点之前：`"MTM_Logic 0.66.3"` → `"MTM_Logic 0.66"`；不足两个点原样返回；null 返回空串。第二段在包或存档变化时提升，第三段只在单侧改动时提升，客户端与服务器可以不同（0.66.0 起，用户决定）。调用者：`PacketTrainDataGuiClient.java:120`；测试 `common/src/test/java/mtr/packet/NetworkProtocolTest.java:14`。

### 常量 `MAX_PACKET_BYTES = 1048576`
- 1 MiB。测试 `common/src/test/java/mtr/data/TrainIdentityTest.java:155` 使用。

### 通道常量（`ResourceLocation(MTR.MOD_ID, …)`）
- 原版 MTR 已有：版本检查、打开各界面（仪表板、PIDS、到站投影、标志、售票机、列车传感器、升降梯层、升降梯外观、资源包生成器）、广播、时间与风同步、建轨/建信号/删节点/删轨/删信号/删轨道动作、生成路径、清列车、标志类型、驾驶、升降梯按钮、充值、PIDS/投影更新、分块数据、各数据的更新/删除（车站、站台、停车轨、线路、车厂、升降梯）、写轨道、列车更新/删除、升降梯更新/删除、乘客与座位位置、轨道动作、时刻、列车传感器（`packet_update_train_announcer`）、升降梯层更新。
- 本分支新增（按功能）：
  - 线路/时刻/权限：`PACKET_LINE_WORDS`、`PACKET_TIMETABLE_CONFIG`、`PACKET_EDIT_PERMISSION`、`PACKET_TIMETABLE_TIMES`、`PACKET_TIMETABLE_CONFLICTS`、`PACKET_STATION_LAYOUT`、`PACKET_ROUTE_LAYOUT`、`PACKET_SERVER_TIME`；
  - 节点与寻路：`PACKET_OPEN_STATION_AREA_TOOL`、`PACKET_OPEN_NODE_ANGLE_SCREEN`、`PACKET_UPDATE_NODE_ANGLE`、`PACKET_SET_KEY_NODE`、`PACKET_PATH_REGIONS`、`PACKET_UPDATE_PATH_BOUNDARY`；
  - 网页地图数据：`PACKET_REQUEST_MAP_RAILS`、`PACKET_MAP_DIGEST`、`PACKET_MAP_WANTED`、`PACKET_MAP_DATA`、`PACKET_STALE_DEPOTS`、`PACKET_REQUEST_MAP_ROUTE`、`PACKET_TRACK_OWNERS`；
  - 车厂运用：`PACKET_PLACE_TRAINS`、`PACKET_REPLAN_DUTIES`、`PACKET_AUTO_DUTIES`、`PACKET_UPDATE_TRAIN_STATES`、`PACKET_REQUEST_TRAIN_RESYNC`、`PACKET_HELD_TRAINS`、`PACKET_RECALL_TRAIN`、`PACKET_DELAY_ALARMS`、`PACKET_CONTINGENCIES`、`PACKET_NETWORK_SETTINGS`、`PACKET_ROUTE_PREVIEW`、`PACKET_DEPOT_WAY_PREVIEW`、`PACKET_SIMULATE`、`PACKET_SIMULATE_RESULT`；
  - 编辑协同：`PACKET_EDIT`、`PACKET_EDIT_BATCH`、`PACKET_EDIT_FRAGMENT`、`PACKET_EDITING`、`PACKET_EDIT_REVISIONS`、`PACKET_EDIT_DATA`、`PACKET_EDITORS`、`PACKET_EDIT_RESULT`；
  - 票价（0.68.0）：`PACKET_REQUEST_FARE_GROUPS`、`PACKET_FARE_GROUPS`、`PACKET_SET_FARES`；
  - 调度（D6）：`PACKET_CONSOLE`、`PACKET_DISPATCH`；列车调试（0.65.31）：`PACKET_TRAIN_DEBUG`；
  - 音频与图片：`PACKET_OPEN_AUDIO_SPEAKER`、`PACKET_UPDATE_AUDIO_SPEAKER`、`PACKET_AUDIO_SPEAKER_PLAYBACK`、`PACKET_AUDIO_LIST`、`PACKET_AUDIO_RESULT`、`PACKET_AUDIO_DATA`、`PACKET_AUDIO_UPLOAD`、`PACKET_AUDIO_DELETE`、`PACKET_AUDIO_WANTED`、`PACKET_AUDIO_SOUND_ID`、`PACKET_IMAGE_ACTION`、`PACKET_IMAGE_DATA`。
- 增删任何通道都属于包格式变化，须提升 `mtm_version` 第二段。
