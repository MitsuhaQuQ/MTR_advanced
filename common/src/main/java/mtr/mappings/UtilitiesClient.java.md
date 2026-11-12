# UtilitiesClient.java（mtr.mappings）

客户端绘制、控件、资源的跨版本小工具。与 `RegistryUtilities` 一样由构建从 Minecraft-Mappings 的 zip 解出覆盖；当前内容与 `Minecraft-Mappings-1.20/UtilitiesClient.java` 逐字相同（仅换行符不同），相对 MTR 3.2.2 初始提交的差异是 1.20 API（JOML `Matrix4f`、`Axis`、`Font.DisplayMode`、`Button.builder`、控件 `getX/setX`、`getResourceStack` 等）。**不要直接修改，构建会覆盖。**

## 接口 `UtilitiesClient`

### `static void beginDrawingRectangle(BufferBuilder buffer)` / `static void finishDrawingRectangle()`
- 设置位置+颜色着色器并以 QUADS/POSITION_COLOR 开始；结束为空操作。调用者：`common/src/main/java/mtr/screen/DashboardList.java:225`、`:230`。

### `static void beginDrawingTexture(ResourceLocation textureId)`
- 设置位置+纹理着色器并绑定纹理 0。调用者：`common/src/main/compat/legacy/GuiCompat.java:62`。

### `static int drawInBatch(Font, FormattedCharSequence, float x, float y, int color, boolean shadow, Matrix4f, MultiBufferSource, int overlay, int light)`
- 以 `DisplayMode.NORMAL` 批量画字。调用者：`common/src/main/java/mtr/client/IDrawing.java:104`。

### `static void setScreen(Minecraft client, ScreenMapper screen)`
- 调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:156`。

### `static EntityModel<Minecart> getMinecartModel()` / `static EntityModel<Boat> getBoatModel()`
- 现场烘焙原版矿车/船模型。调用者：`common/src/main/java/mtr/render/JonModelTrainRenderer.java:51`、`:52`。

### `static void setPacketCoordinates(Entity, double, double, double)` / `static float getPacketYaw(ClientboundAddEntityPacket)`
- 实体生成包的坐标同步与偏航角（度）。调用者：`fabric/src/main/java/mtr/mixin/EntitySpawnMixin.java:60`、`:64`。

### `static int getRenderDistance()`
- 客户端渲染距离（区块数）。调用者：`common/src/main/java/mtr/render/RenderTrains.java:133`。

### `static List<Resource> getResources(ResourceManager, ResourceLocation)`
- 该位置在所有资源包中的资源栈。调用者：`cn/zbx1425/mtrsteamloco/mixin/CustomResourcesMixin.java:100` 等。

### `static boolean hasResource(ResourceLocation)`
- 客户端资源是否存在。调用者：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:87`。

### `static boolean isHovered(AbstractWidget)`
- 悬停或获得焦点。调用者：`common/src/main/java/mtr/screen/WidgetShorterSlider.java:95`。

### `static File getResourcePackDirectory(Minecraft)`
- 调用者：`common/src/main/java/mtr/client/ResourcePackCreatorProperties.java:363`。

### `static Button newButton(Button.OnPress)` / `newButton(Component, OnPress)` / `newButton(int height, Component, OnPress)`
- 用 `Button.builder` 新建按钮（位置与宽度之后再设）。

### `getWidgetX` / `setWidgetX` / `setWidgetY` / `getWidgetY`
- 控件坐标读写。

### `rotateX` / `rotateXDegrees` / `rotateY` / `rotateYDegrees` / `rotateZ` / `rotateZDegrees(PoseStack, float)`
- 绕对应轴旋转矩阵栈（弧度或度）。调用者：`common/src/main/java/mtr/client/DynamicTrainModel.java:289` 等。
