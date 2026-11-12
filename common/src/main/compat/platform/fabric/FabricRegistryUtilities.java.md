# FabricRegistryUtilities.java（compat/platform/fabric）

Fabric 端注册工具（1.20 版本用）。原 MTR 的 `FabricRegistryUtilities` 由 `mappings.zip` 解出；1.20 构建时 `build.gradle:179-184` 再把本目录复制到 `fabric/src/main/java/mtr/mappings`，覆盖为本文件（包名 `mtr.mappings`），以适配 1.20 的创造模式物品栏注册方式（`BuiltInRegistries.CREATIVE_MODE_TAB`、`FabricItemGroup.builder()`、`ItemGroupEvents`）。被 Fabric 入口的注册实现调用：`fabric/src/main/java/mtr/fabric/RegistryImpl.java:33`、`fabric/src/main/java/mtr/fabric/RegistryClientImpl.java:37`；ANTE 还用 mixin 改写它（`fabric/src/main/java/cn/zbx1425/mtrsteamloco/mixin/MTRFabricRegistryUtilitiesMixin.java:41`）。

## 接口 `FabricRegistryUtilities`（只有静态方法）

### `static void registerItemModelPredicate(String id, Item item, String tag)`
- 作用：给物品注册一个模型谓词（客户端）：物品 NBT 中含 `tag` 键则值为 1，否则 0，用于切换物品贴图。
- 参数：`id` 谓词的 ResourceLocation 字符串；`item` 目标物品；`tag` NBT 键名。
- 副作用：`getOrCreateTag()` 会在没有 NBT 的物品堆上创建空 NBT。
- 调用者：`fabric/src/main/java/mtr/fabric/RegistryClientImpl.java:37`。

### `static <T extends BlockEntityMapper> void registerTileEntityRenderer(BlockEntityType<T> type, Function<BlockEntityRenderDispatcher, BlockEntityRendererMapper<T>> factory)`
- 作用：注册方块实体渲染器；Fabric 上下文被忽略，`factory.apply(null)`（传入的 dispatcher 恒为 null）。
- 调用者：`fabric/src/main/java/mtr/fabric/RegistryClientImpl.java:41`。

### `static <T extends Entity> void registerEntityRenderer(EntityType<T> type, Function<EntityRendererProvider.Context, EntityRendererMapper<T>> factory)`
- 作用：注册实体渲染器。调用者：`fabric/src/main/java/mtr/fabric/RegistryClientImpl.java:45`。

### `static void registerCommand(Consumer<CommandDispatcher<CommandSourceStack>> callback)`
- 作用：挂到 `CommandRegistrationCallback.EVENT`，命令注册时把 dispatcher 交给 `callback`。

### `static void registerCreativeModeTab(CreativeModeTab creativeModeTab, Item item)`
- 作用：把物品加入指定创造模式标签页（通过 `ItemGroupEvents.modifyEntriesEvent`）。
- 异常：若标签页未在 `BuiltInRegistries.CREATIVE_MODE_TAB` 注册，`orElseThrow()` 抛 `NoSuchElementException`。
- 调用者：`fabric/src/main/java/cn/zbx1425/mtrsteamloco/fabric/RegistriesWrapperImpl.java:51`、`:57`。

### `static CreativeModeTab createCreativeModeTab(ResourceLocation id, Supplier<ItemStack> supplier)`
- 作用：创建并注册创造模式标签页，标题翻译键为 `itemGroup.<namespace>.<path>`，图标来自 `supplier`。
- 返回：已注册的 `CreativeModeTab`。
- 副作用：写入原版注册表。
- 调用者：`fabric/src/main/java/mtr/fabric/RegistryImpl.java:33`。
