# ForgeUtilities.java（compat/platform/forge）

Forge 端注册与事件工具（1.20 版本用）。原 MTR 的 `ForgeUtilities` 由 `mappings.zip` 解出；1.20 构建时 `build.gradle:185-189` 把本目录复制到 `forge/src/main/java/mtr/mappings`，覆盖为本文件（包名 `mtr.mappings`）。仓库里同时提交了复制后的结果 `forge/src/main/java/mtr/mappings/ForgeUtilities.java`，两份内容完全相同（见该文件的同名 `.md`）。适配 1.20 的 `RegisterEvent`/`BuildCreativeModeTabContentsEvent` 创造模式标签页注册、`RenderLevelStageEvent`、`RenderGuiOverlayEvent`。被 Forge 入口调用：`forge/src/main/java/mtr/MTRForge.java:46`、`:55`、`:70`，`forge/src/main/java/mtr/forge/RegistryImpl.java:28`，ANTE 的 `forge/src/main/java/cn/zbx1425/mtrsteamloco/forge/MainForge.java:47`。

## 类 `ForgeUtilities`

### 静态字段
- `renderTickAction`（`Runnable`，默认空）：每帧世界渲染到 `AFTER_CUTOUT_BLOCKS` 阶段时运行。
- `renderGameOverlayAction`（`Consumer<Object>`，默认空）：HUD 绘制后回调，参数是 `GuiGraphics`。
- `textureStitchEvent`（`Consumer<Object>`，默认空）：纹理图集拼接时回调，参数是 atlas。
- `CREATIVE_TAB_ORDER`：标签页注册顺序（`ResourceLocation` 列表）。
- `CREATIVE_TABS`：`ResourceLocation → CreativeModeTabWrapper`。
- `ENTITY_RENDERER_PAIRS`：待注册的实体渲染器。

### `static void registerModEventBus(String modId, IEventBus eventBus)`
- 作用：交给 Architectury `EventBuses.registerModEventBus`。调用者：`forge/src/main/java/mtr/MTRForge.java:46`、`forge/src/main/java/cn/zbx1425/mtrsteamloco/forge/MainForge.java:47`。

### `static void registerKeyBinding(KeyMapping keyMapping)`
- 作用：Architectury `KeyMappingRegistry.register`。调用者：`forge/src/main/java/mtr/forge/RegistryClientImpl.java:44`。

### `static Packet<?> createAddEntityPacket(Entity entity)`
- 作用：返回 Forge 的实体生成包（`NetworkHooks.getEntitySpawningPacket`）。调用者：`forge/src/main/java/mtr/forge/RegistryImpl.java:36`。

### `static Supplier<CreativeModeTab> createCreativeModeTab(ResourceLocation resourceLocation, Supplier<ItemStack> iconSupplier, String translationKey)`
- 作用：登记一个标签页（同 id 只登记一次），真正的 `CreativeModeTab` 要到 `RegisterEvent` 时才创建。
- 返回：延迟取值的 Supplier（注册事件前取到的是 null）。
- 调用者：`forge/src/main/java/mtr/forge/RegistryImpl.java:28`。

### `static void registerCreativeModeTab(ResourceLocation resourceLocation, Item item)`
- 作用：把物品记入对应标签页的物品列表；标签页未登记则忽略。调用者：`forge/src/main/java/mtr/forge/RegistryImpl.java:32`。

### `registryGetItem()` / `registryGetBlock()` / `registryGetBlockEntityType()` / `registryGetEntityType()` / `registryGetSoundEvent()` / `registryGetParticleType()`
- 作用：返回 1.20 的 `Registries.X` 注册表键，供 DeferredRegister 使用。调用者：`forge/src/main/java/mtr/MTRForge.java:34`–`:38`、`forge/src/main/java/cn/zbx1425/mtrsteamloco/forge/RegistriesWrapperImpl.java:43`–`:48`。

### `static void renderTickAction(Runnable runnable)`
- 作用：设置（覆盖）`renderTickAction`；只保留最后一次设置。调用者：`forge/src/main/java/mtr/MTRForge.java:57`、`forge/src/main/java/cn/zbx1425/mtrsteamloco/forge/MainForge.java:54`。

### `static void renderGameOverlayAction(Consumer<Object> consumer)`
- 作用：设置 HUD 回调。调用者：`forge/src/main/java/mtr/MTRForge.java:69`。

### `static void registerTextureStitchEvent(Consumer<Object> consumer)`
- 作用：设置纹理拼接回调。调用者：`forge/src/main/java/mtr/MTRForge.java:127`。

### `static <T extends Entity> void registerEntityRenderer(Supplier<EntityType<? extends T>> entityType, EntityRendererProvider<T> entityRendererProvider)`
- 作用：先存入 `ENTITY_RENDERER_PAIRS`，在 `RegisterRenderers` 事件时统一注册。调用者：`forge/src/main/java/mtr/MTRForge.java:58`、`:59`。

## 内部类 `Events`（Forge 事件总线订阅者，由 `forge/src/main/java/mtr/MTRForge.java:70` 注册）

### `onRenderTickEvent(RenderLevelStageEvent event)`
- 作用：阶段为 `AFTER_CUTOUT_BLOCKS` 时运行 `renderTickAction`。

### `onRenderGameOverlayEvent(RenderGuiOverlayEvent.Post event)`
- 作用：把 `event.getGuiGraphics()` 交给 `renderGameOverlayAction`。注意每个 HUD 覆盖层绘制后都会触发一次。

## 内部类 `ClientsideEvents`（mod 总线，由 `forge/src/main/java/mtr/MTRForge.java:71` 注册）

### `onEntityRendererEvent(EntityRenderersEvent.RegisterRenderers event)`
- 作用：注册 `ENTITY_RENDERER_PAIRS` 中的所有渲染器。

### `onTextureStitchEvent(TextureStitchEvent event)`
- 作用：把 `event.getAtlas()` 交给 `textureStitchEvent`。

## 内部类 `RegisterCreativeTabs`（mod 总线，由 `forge/src/main/java/mtr/MTRForge.java:55` 注册）

### `onRegisterCreativeModeTabsEvent(RegisterEvent event)`
- 作用：在 `CREATIVE_MODE_TAB` 注册时，按 `CREATIVE_TAB_ORDER` 顺序构建每个标签页（图标、翻译标题）并注册，写回 wrapper 的 `creativeModeTab`。

### `onRegisterCreativeModeTabsEvent(BuildCreativeModeTabContentsEvent event)`
- 作用：找到与 `event.getTab()` 相同的 wrapper，把其物品以 `PARENT_AND_SEARCH_TABS` 可见性加入。

## 私有内部类 `EntityRendererPair<T>`
- 字段：`entityTypeSupplier`、`entityRendererProvider`。
- `register(RegisterRenderers event)`：取 Supplier 当前值注册渲染器。

## 私有内部类 `CreativeModeTabWrapper`
- 字段：`creativeModeTab`（注册事件后才赋值）、`iconSupplier`、`creativeModeTabSupplier`（返回当前 `creativeModeTab` 的 lambda）、`translationKey`、`items`（待加入的物品）。
