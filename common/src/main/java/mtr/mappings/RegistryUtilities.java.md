# RegistryUtilities.java（mtr.mappings）

注册相关的跨版本工具（服务端与客户端共用）。注意：本文件不是手写的——构建时 `build.gradle` 会下载 jonafanho 的 Minecraft-Mappings 仓库对应版本到 `common/src/main/java/mtr/mappings/mappings.zip`，再把其中文件（`@package@` 替换为 `mtr.mappings`）解压覆盖到本目录。当前提交的内容与 zip 中 `Minecraft-Mappings-1.20/RegistryUtilities.java` 逐字相同（仅换行符不同），相对上游 MTR 3.2.2 初始提交的差异只是 1.19→1.20 的 API 变化（`BuiltInRegistries`、`SoundEvent.createVariableRangeEvent`、物品属性不再带创造模式标签页、命令注册回调多一个参数）。**不要直接修改本文件，改了会在下次构建时被覆盖。**

## 接口 `RegistryUtilities`（只有静态方法）

### `static <T extends BlockEntityMapper> BlockEntityType<T> getBlockEntityType(Utilities.TileEntitySupplier<T> supplier, Block block)`
- 为单个方块建方块实体类型（无 datafixer）。调用者：`common/src/main/java/mtr/BlockEntityTypes.java:9`、`cn/zbx1425/mtrsteamloco/Main.java:65`。

### `static void registerCommand(Consumer<CommandDispatcher<CommandSourceStack>> callback)`
- Architectury 命令注册事件。调用者：`common/src/main/java/mtr/MTR.java:527`。

### `registerPlayerJoinEvent` / `registerPlayerQuitEvent` / `registerPlayerChangeDimensionEvent(Consumer<ServerPlayer>)`
- 玩家加入/离开/换维度事件。调用者：`forge/src/main/java/mtr/forge/RegistryImpl.java:44`、`:49`、`:45`。

### `registerServerStartingEvent` / `registerServerStoppingEvent` / `registerTickEvent(Consumer<MinecraftServer>)`
- 服务器启动中/停止中/每 tick 开始（`SERVER_PRE`）事件。调用者：`forge/src/main/java/mtr/forge/RegistryImpl.java:53`、`:57`、`:61`。

### `static SoundEvent createSoundEvent(ResourceLocation)`
- 1.20 写法 `SoundEvent.createVariableRangeEvent`。调用者：`common/src/main/java/mtr/client/AnnouncementPlayer.java:183`、`cn/zbx1425/mtrsteamloco/Main.java:90`。

### `static Item.Properties createItemProperties(Supplier<CreativeModeTab> creativeModeTab)`
- 1.20 中忽略参数，返回空属性（标签页改由注册时单独加入）。调用者：`common/src/main/java/mtr/item/ItemWithCreativeTabBase.java:16`。

### `registryGetItem()` / `registryGetBlock()` / `registryGetBlockEntityType()` / `registryGetEntityType()` / `registryGetSoundEvent()` / `registryGetParticleType()`
- 返回 `BuiltInRegistries` 中对应注册表。调用者：`fabric/src/main/java/mtr/MTRFabric.java:55`、`fabric/src/main/java/cn/zbx1425/mtrsteamloco/fabric/RegistriesWrapperImpl.java:43` 等。
