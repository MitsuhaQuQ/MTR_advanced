# Main.java（ANTE）

合并进来的 ANTE（mtrsteamloco）的通用入口，与 `mtr.MTR.init` 并列，由加载器入口调用（`fabric/src/main/java/cn/zbx1425/mtrsteamloco/fabric/MainFabric.java:22`、`forge/src/main/java/cn/zbx1425/mtrsteamloco/forge/MainForge.java:41`）。注册装饰物（eye candy）方块/物品/方块实体、发车铃、保留的直接节点 `direct_node`、桥梁/复合/位移/路线路径等工具物品、声音、蒸汽粒子，以及 ANTE 的客户端→服务端数据包。ANTE 对 MTR 的大多数改动在 `mixin/` 里，不在这里。本分支改动：`direct_node` 只作为普通 MTR 节点保留（见 `BlockDirectNode`）；ANTE 不再单独做版本检查，因为它已在同一个 jar 中，由 MTR 的协议版本检查（MTM_Logic 版本，`IPacket.getNetworkVersion`）覆盖。

## 类 `Main`

### 常量与静态字段
- `MOD_ID = "mtrsteamloco"`：ANTE 的命名空间（资源、包 id）。
- `LOGGER`：名 `MTR-ANTE` 的 logger。
- `JSON_PARSER`：共用 Gson `JsonParser`（`cn/zbx1425/mtrsteamloco/ClientConfig.java:63` 等使用）。
- `enableRegistry`：静态初始化时检查本类所在 jar 路径；若以 `-client.jar` 结尾（仅客户端版本）则为 false，不注册任何内容；取路径失败时为 true。被 `MainClient.java:39`、`mixin/RailMixin.java:329` 读取。
- `BLOCK_DEPARTURE_BELL`、`BLOCK_EYE_CANDY`、`BLOCK_ENTITY_TYPE_EYE_CANDY`、`ITEM_EYE_CANDY`：发车铃和装饰物的注册对象。
- `BLOCK_DIRECT_NODE`：只为旧存档保留的节点方块（`BlockDirectNode`），不再给出物品。
- `BRIDGE_CREATOR_1`、`COMPOUND_CREATOR`、`DISPLACEMENT_TOOL`、`ROUTE_PATH_CREATOR`：工具物品。
- `REGISTERIES`：`init` 时传入的注册包装器。
- `EYE_CANDY_TAB`：装饰物创造模式标签页。`MC_VERSION <= 12000` 用 `Registry.getCreativeModeTab`；更高版本用 `CreativeModeTab.builder`，内容由 `BlockItemEyeCandy.Client.fillItemCategory` 填充。
- `SOUND_EVENT_BELL`：`mtrsteamloco:bell` 声音。
- `PARTICLE_STEAM_SMOKE`：蒸汽烟雾粒子类型（`init` 时创建）。

### `static void init(RegistriesWrapper registries)`
- 作用：打印 ANTE 版本与构建时间；`enableRegistry` 为真时依次注册：装饰物标签页（1.20+）、`eye_candy` 物品/方块/方块实体、`departure_bell` 方块+物品（放入 MTR 的 `RAILWAY_FACILITIES` 标签页）、`direct_node` 方块（无物品）、四个工具物品、`bell` 声音、`steam_smoke` 粒子；以及 C2S 包接收器：`PacketUpdateBlockEntity`、`PacketUpdateRail`、`PacketUpdateHoldingItem`、`PacketUpdateTrainCustomConfigs.C2S`、`PacketRoutePathCreator.ROUTE_C2S`。
- 参数：加载器提供的注册包装器（Fabric/Forge 各自实现）。
- 副作用：写注册表、注册网络接收器、写日志。

## 接口 `Main.RegisterBlockItem`（函数式）
- `void accept(String string, RegistryObject<Block> block, CreativeModeTabs.Wrapper tab)`：注册方块+物品回调的签名。本文件内未使用，供加载器侧实现。
