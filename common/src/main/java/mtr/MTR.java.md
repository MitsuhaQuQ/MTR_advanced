# MTR.java（上游文件，本分支有较多改动）

服务器端入口（Fabric 与 Forge 的入口类调用，`fabric/src/main/java/mtr/MTRFabric.java:23`、`forge/src/main/java/mtr/MTRForge.java:41`）。`init` 注册方块、物品、方块实体、实体、声音，注册玩家发来的包（每个经 `PacketGuard`，编辑数据的包经 `EditGuard`），以及服务器事件：每 tick 对每个维度运行 `RailwayData.simulateTrains()`；玩家加入时发送所需设置与数据；服务器启动时读取配置文件并启动网页服务器。见 `MTR-代码说明与流程.md` 第 3 节。

本分支改动：新增方块/物品（站台端门、车站区域工具、新限速轨道连接器、圆形隧道工具、隧道墙、音箱等）；包注册全部经 `PacketGuard`（限流与校验）或 `EditGuard`（多人编辑冲突检查）；新增大量包（线路/车厂预览、召回列车、应急预案、网络设置、票价、调度台、列车调试、列车快照重发、放置列车、重排运用、节点角度、寻路边界、地图数据、时刻表时间与冲突、日模拟、站场/线路布局、音频上传/删除/下载/声音 id、图片、音箱）；删除线路前报告引用；每 tick 推进音频与图片服务器，每 200 tick（10 秒，G2）向玩家发送服务器时间；玩家加入时发版本检查、线路词、时刻表配置、服务器时间、编辑版本、编辑权限、音频与图片列表；玩家离开时清理各模块的状态；服务器启动时读取 `SecurityConfig`、`LineWords`、`TimetableConfig`、`MotionConfig` 并打开音频/图片服务器，停止时关闭。

## 类 `MTR`（implements `IPacket`）

### 字段与常量
- `gameTick`（私有静态）：服务器 tick 计数。
- `SERVER_TIME_TICKS = 200`：发送服务器时间的间隔（tick，G2）。
- `MOD_ID = "mtr"`。

### `static void init(BiConsumer<String, RegistryObject<Item>> registerItem, BiConsumer<String, RegistryObject<Block>> registerBlock, RegisterBlockItem registerBlockItem, RegisterBlockItem registerEnchantedBlockItem, BiConsumer<String, RegistryObject<…BlockEntityType…>> registerBlockEntityType, BiConsumer<String, RegistryObject<…EntityType…>> registerEntityType, BiConsumer<String, SoundEvent> registerSoundEvent)`
- 参数：各加载器提供的注册函数（物品、方块、方块物品（带创造模式标签页）、附魔外观方块物品、方块实体类型、实体类型、声音）。
- 作用（按顺序）：
  1. 注册电梯/扶梯相关物品方块（总是）；非 `Keys.LIFTS_ONLY` 时注册其余所有物品、方块、方块实体与声音（id 见源码）。
  2. 用 `PacketGuard.register` / `EditGuard.register` 注册 C2S 包处理器；站台/车站/停车轨/线路/车厂的更新和删除经 `EditGuard`（`PacketTrainDataGuiServer.receiveUpdateOrDeleteC2S`），删除线路前先 `reportRouteReferences`；`EditGuard.registerPackets()` 注册编辑批包等；`PACKET_REQUEST_TRAIN_RESYNC` 让 `RailwayData.requestTrainSnapshot(player)`。
  3. 注册音频命令（`AudioServer::registerCommand`）。
  4. tick 事件：各维度 `simulateTrains()`；`AudioServer.tick`、`ImageServer.tick`；`gameTick++`；每 200 tick 向所有玩家 `serverTimeS2C`；测试服务器（`Keys.TEST_SERVER`）200 tick 后退出进程。
  5. 玩家加入：版本检查、线路词、时刻表配置、服务器时间、编辑版本（`EditGuard.revisionsS2C`）、编辑权限、音频与图片列表、`RailwayData.onPlayerJoin`。
  6. 玩家离开：`RailwayData.disconnectPlayer`、`PacketGuard.forget`、`EditTransferPackets.forget`、`EditGuard.onPlayerQuit`、`AudioServer.forget`、`ImageServer.forget`。
  7. 非仅电梯版本：`Webserver.init()`；服务器启动时设置网页服务器回调，读取 `config/mtr_security.json`（`SecurityConfig.load` + `apply`）、线路词、时刻表配置、运动配置，启动网页服务器（端口文件 `config/mtr_webserver_port.txt`），打开音频与图片服务器；停止时关闭三者。

### `static boolean isGameTickInterval(int interval)` / `static boolean isGameTickInterval(int interval, int offset)`
- 返回：`(gameTick + offset) % interval == 0`，用于把周期任务分散到不同 tick（offset 常为位置哈希）。调用者：`common/src/main/java/mtr/data/RailwayData.java:644`、`:665`、`common/src/main/java/mtr/advanced/PathRegionSync.java:41`、`common/src/main/java/mtr/block/BlockLiftButtons.java:156`。

## 函数式接口 `RegisterBlockItem`
- `void accept(String id, RegistryObject<Block> block, CreativeModeTabs.Wrapper tab)`：注册方块及其物品到某创造模式标签页。

## 0.74.0 媒体文件夹

- 注册 `PACKET_AUDIO_FOLDER → AudioServer::receiveFolderC2S`。
