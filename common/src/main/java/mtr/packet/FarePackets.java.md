# FarePackets.java（本分支新增）

按距离计费（设计文档 6.13，0.68.0）的网络包，从仪表板的票价界面（`FareScreen`）设置：票价规则（0.73.0）及其票价与参考线路、每条线路所用的规则、车站的规则。只有有编辑权限的玩家能修改；服务器把它们存在线网设置（`NetworkSettings`）里，随线网设置发给所有玩家。客户端：`common/src/main/java/mtr/screen/FareScreen.java:124`（提交）、`:179`（请求分组）；注册：`common/src/main/java/mtr/MTR.java:474`、`:475`，`common/src/main/java/mtr/MTRClient.java:340`。

## 包格式
- C2S `PACKET_REQUEST_FARE_GROUPS`：空包。
- S2C `PACKET_FARE_GROUPS`：`VarInt 分组数`，每组 `String 分组键(≤256)`、`String 名称`、`VarLong 组内两站最远距离（格）`。
- C2S `PACKET_SET_FARES`：一个只填了票价相关字段的 `NetworkSettings`（`writePacket` 格式）。

## 类 `FarePackets`（final，implements `IPacket`，私有构造）

### 常量 `MAX_GROUP_LENGTH = 256`

### `static void requestGroupsC2S()`
- 客户端请求所有分组及其最远站间距离（用来生成到该距离为止的票价档位）。

### `static void receiveRequestGroupsC2S(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet)`
- 服务器线程中：`FareData.getAllGroups(dataCache)` 列出分组，`railwayData.getFareNetwork().getLongestBlocks(group)` 求最远距离，回发。

### `static void setC2S(NetworkSettings edited)`
- 客户端：新建一个设置对象，只复制票价规则（先清空默认规则）、票价档（`fareLevels`）、线路分组（`fareRouteGroups`）、车站分组（`fareStationGroups`），发给服务器（替换服务器上的这几项）。

### `static void receiveSetC2S(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet)`
- 服务器线程中：无编辑权限忽略；去掉已不存在的线路与车站，去掉引用不存在规则的票价和分配（`keepFareRulesKnown`）；与现值完全相同（规则按顺序比较）则忽略；否则替换四项、打印日志、置 `networkSettingsChanged = true`（之后保存并广播）。也被 `common/src/main/java/mtr/data/NetworkSettings.java:457` 引用（注释中）。

### `static void receiveGroupsS2C(FriendlyByteBuf packet)`
- 客户端：读分组（只保留前 `NetworkSettings.MAX_FARE_GROUPS` 个），主线程中若当前界面是 `FareScreen` 就 `setGroups(分组键 → {名称, 最远距离})`。
