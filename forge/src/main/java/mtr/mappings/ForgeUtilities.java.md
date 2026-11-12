# ForgeUtilities.java（forge/mtr/mappings）

这是 `common/src/main/compat/platform/forge/ForgeUtilities.java` 在 1.20 构建时由 `build.gradle:185-189` 复制过来的结果，被提交进了仓库；当前两份内容逐字相同（`diff` 无差异）。Forge 端注册与事件工具，包名 `mtr.mappings`。每次构建都会被源文件覆盖，**修改请改 compat 目录下那份**。

函数说明见 `common/src/main/compat/platform/forge/ForgeUtilities.java.md`，此处只列摘要：

## 类 `ForgeUtilities`
- 静态字段：`renderTickAction`、`renderGameOverlayAction`、`textureStitchEvent`（回调，默认空）；`CREATIVE_TAB_ORDER`、`CREATIVE_TABS`、`ENTITY_RENDERER_PAIRS`（延迟注册表）。
- `registerModEventBus(String, IEventBus)`：Architectury 注册 mod 事件总线（`forge/src/main/java/mtr/MTRForge.java:46`）。
- `registerKeyBinding(KeyMapping)`：注册按键（`forge/src/main/java/mtr/forge/RegistryClientImpl.java:44`）。
- `createAddEntityPacket(Entity)`：Forge 实体生成包（`forge/src/main/java/mtr/forge/RegistryImpl.java:36`）。
- `createCreativeModeTab(ResourceLocation, Supplier<ItemStack>, String)`：登记标签页，返回延迟 Supplier（`forge/src/main/java/mtr/forge/RegistryImpl.java:28`）。
- `registerCreativeModeTab(ResourceLocation, Item)`：物品加入标签页（`forge/src/main/java/mtr/forge/RegistryImpl.java:32`）。
- `registryGetItem/Block/BlockEntityType/EntityType/SoundEvent/ParticleType()`：返回 `Registries.X`（`forge/src/main/java/mtr/MTRForge.java:34`）。
- `renderTickAction(Runnable)`、`renderGameOverlayAction(Consumer<Object>)`、`registerTextureStitchEvent(Consumer<Object>)`：设置回调（`forge/src/main/java/mtr/MTRForge.java:57`、`:69`、`:127`）。
- `registerEntityRenderer(Supplier<EntityType>, EntityRendererProvider)`：延迟注册实体渲染器（`forge/src/main/java/mtr/MTRForge.java:58`）。
- 内部类 `Events`：`onRenderTickEvent`（`AFTER_CUTOUT_BLOCKS` 阶段跑 `renderTickAction`）、`onRenderGameOverlayEvent`（HUD 后回调）。
- 内部类 `ClientsideEvents`：`onEntityRendererEvent`（注册实体渲染器）、`onTextureStitchEvent`（atlas 回调）。
- 内部类 `RegisterCreativeTabs`：`RegisterEvent` 时按顺序建标签页；`BuildCreativeModeTabContentsEvent` 时填物品。
- 私有内部类 `EntityRendererPair`、`CreativeModeTabWrapper`：延迟注册用的数据容器。
