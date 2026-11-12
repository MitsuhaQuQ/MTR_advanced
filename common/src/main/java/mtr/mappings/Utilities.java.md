# Utilities.java（mtr.mappings）

实体、物品、资源、命令等的跨版本小工具（服务端与客户端共用）。与 `RegistryUtilities` 一样由构建从 Minecraft-Mappings 的 zip 解出覆盖；当前内容与 `Minecraft-Mappings-1.20/Utilities.java` 逐字相同（仅换行符不同），相对 MTR 3.2.2 初始提交的差异是 1.20 API（`Resource.open()`、`performPrefixedCommand`、`CreativeModeTabs.getDefaultTab()`、`Holder.Reference<SoundEvent>`）。**不要直接修改，构建会覆盖。**

## 接口 `Utilities`

### `static float getYaw(Entity)` / `static void setYaw(Entity, float)` / `static void incrementYaw(Entity, float)`
- 读/设/累加实体偏航角（度）。调用者：`common/src/main/java/mtr/block/BlockNode.java:159`、`fabric/src/main/java/mtr/mixin/EntitySpawnMixin.java:64`、`common/src/main/java/mtr/data/VehicleRidingClient.java:162`。

### `static boolean isHolding(Player player, Function<Item, Boolean> predicate)`
- 玩家任一只手的物品是否满足条件。调用者：`cn/zbx1425/mtrsteamloco/render/rail/RailRenderDispatcher.java:133`。

### `static Inventory getInventory(Player)` / `static Abilities getAbilities(Player)`
- 调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiServer.java:1232`、`common/src/main/java/mtr/data/RailwayDataCoolDownModule.java:95`。

### `static void scheduleBlockTick(Level world, BlockPos pos, Block block, int ticks)`
- 安排方块刻。调用者：`common/src/main/java/mtr/block/BlockTicketBarrier.java:50`、`cn/zbx1425/mtrsteamloco/block/BlockEyeCandy.java:200`。

### `static boolean entityRemoved(Entity)`
- 实体为 null 或已移除。调用者：`common/src/main/java/mtr/data/RailwayDataCoolDownModule.java:36`。

### `static InputStream getInputStream(Resource)` / `getInputStream(Optional<Resource>)`
- 打开资源；Optional 为空时返回空流。调用者：`cn/zbx1425/mtrsteamloco/data/EyeCandyRegistry.java:68`、`render/integration/TrainModelPreloader.java` 等。

### `static void closeResource(Resource)`
- 1.20 中为空操作（资源不再需要关闭）。

### `static void sendCommand(MinecraftServer server, CommandSourceStack source, String command)`
- 执行命令（可带 `/`）。调用者：`common/src/main/java/mtr/data/RailwayData.java:1790`。

### `static CreativeModeTab getDefaultTab()`
- 原版默认标签页。

### `static SoundEvent unwrapSoundEvent(Holder.Reference<SoundEvent>)`
- 取 Holder 中的声音事件。调用者：`common/src/main/java/mtr/data/Lift.java:332`。

## 函数式接口 `TileEntitySupplier<T extends BlockEntityMapper>`
- `T supplier(BlockPos pos, BlockState state)`：方块实体构造器。
