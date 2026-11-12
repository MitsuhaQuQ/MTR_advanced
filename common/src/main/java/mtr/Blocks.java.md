# Blocks.java（上游文件，本分支有改动）

服务器与客户端共用：MTR 所有方块的注册对象表（接口中的常量，共 193 个 `RegistryObject<Block>`）。每个常量用一个工厂延迟创建方块；实际注册（方块 id、物品、创造模式标签页）在 `common/src/main/java/mtr/MTR.java` 的 `init` 中按名称逐个进行（如 `MTR.java:224` 注册 `platform_end_psd_2`，`:316` `tunnel_wall`，`:396` `audio_speaker`）。其他代码通过 `Blocks.XXX.get()` 取得方块实例。

本分支改动：
- 方块属性改用兼容层 `mtr.compat.Material.properties(Material, MaterialColor)`（1.20 没有原版 `Material`，由兼容层映射），无行为变化。
- 新增 `PLATFORM_END_PSD_2`、`PLATFORM_END_PSD_1`、`PLATFORM_END_APG`（`BlockPlatformEndDoor(2/1/0)`，站台端门：两种屏蔽门高度与安全门）。
- 新增 `TUNNEL_WALL`、`TUNNEL_WALL_CABLES`（普通方块）、`TUNNEL_WALL_PANEL`、`TUNNEL_WALL_CABLES_PANEL`（`BlockTunnelWallPanel`），属性复制白色混凝土。
- 新增 `AUDIO_SPEAKER`（`BlockAudioSpeaker`）。
- `RAIL_NODE` 改为 `BlockNode.BlockRailNode::new`（原为 `new BlockNode(TransportMode.TRAIN)`）。

## 接口 `Blocks`

### 常量分组（按名称前缀，未逐个列出）
- 屏蔽门/安全门：`APG_*`、`PSD_*`、`PLATFORM_END_*`。
- 到站投影、PIDS 及支柱：`ARRIVAL_PROJECTOR_*`、`PIDS_*`。
- 天花板、时钟、扶梯、玻璃围栏、闸机、站名牌、站色方块、标志与支柱、触觉地图、垃圾桶、售票机、大理石/金属/瓷砖方块与台阶等车站建材。
- 站台：`PLATFORM`、`PLATFORM_INDENTED`、`PLATFORM_NA_*`、`PLATFORM_UK_*`。
- 轨道节点：`RAIL_NODE` 等各交通方式的节点。
- 信号灯与臂板信号：`SIGNAL_LIGHT_*`、`SIGNAL_SEMAPHORE_*`、`SIGNAL_POLE`。
- 电梯、列车传感器类（时刻传感器、广播器、红石传感器等）、隧道墙、音箱等。
- 各常量的具体方块类与属性见源码对应行；方块行为见各方块类的文档（`mtr/block/*.java.md`）。

无方法。
