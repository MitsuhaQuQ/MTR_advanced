# JonModelTrainRenderer.java

客户端：用 MTR 自己的模型画列车——内置 Java 模型和资源包的 JSON 模型（没有模型时画原版矿车/船）。同时画转向架：车厢在近处时把转向架放到轨道上并随轨道转向；对带自定义转向架的车型，像 MTR 4 那样把车身放在两个转向架上（见仓库根目录 `MTR-加载MTR4资源包设计.md` 第 10 节）。由 `TrainClientRegistry` 为每种车型创建（`common/src/main/java/mtr/client/TrainClientRegistry.java:45`），`CustomResources` 读取资源包时使用（`common/src/main/java/mtr/client/CustomResources.java:69`）；ANTE 的 `JonModelTrainRendererMixin` 修改其行为；`TrainClient` 用 `getCarCullRadius` 做剔除（`common/src/main/java/mtr/data/TrainClient.java:141`）。本分支改动：类说明；`getCarCullRadius`；自定义转向架（`CustomBogie`）与车身放在转向架上（`getPlacementOnBogies`）；转向架上轨（`renderBogieOnTrack`）或随车身（`renderBogieOnBody`）；`builtInBogiesFollowTrack`；世界坐标绘制（`translateToWorld`、`drawTextureWorld`）。

## 类 `JonModelTrainRenderer`（extends `TrainRendererBase`，implements `IGui`）

### 字段与常量
- `train`（模板实例为 null）、`model`（`ModelTrainBase`，可为 null）、`textureId`、`gangwayConnectionId`（贯通道纹理前缀）、`trainBarrierId`（车端挡板纹理前缀）：路径去掉 `.png` 并小写。
- 静态：`MODEL_MINECART`、`MODEL_BOAT`、`BOATS`（列车 id → 假船实体，用于划桨动画）、`MODEL_CABLE_CAR_GRIP`、`MODEL_BOGIE`。
- `BOGIE_HALF_LENGTH = 1`：转向架朝向取其中心前后各 1 格的轨道点。
- `builtInBogiesFollowTrack`（public static，默认 true）：客户端设置，内置转向架在近处也随轨道转向（自定义转向架总是如此）；由 ANTE `ClientConfig` 读写（`cn/zbx1425/mtrsteamloco/ClientConfig.java:75`）。

### 构造函数
- 私有 `JonModelTrainRenderer(ModelTrainBase model, String textureId, String gangwayConnectionId, String trainBarrierId, TrainClient train)`。
- `JonModelTrainRenderer(ModelTrainBase model, String textureId, String gangwayConnectionId, String trainBarrierId)`：车型模板。

### `TrainRendererBase createTrainInstance(TrainClient train)`
- 为列车创建实例。

### `double getCarCullRadius()`
- 作用：车厢剔除半径（格）：非动态模型（`DynamicTrainModel`）、非火车或有自定义转向架时为无穷大（不剔除）；否则取 `车长 + |转向架位置| + 4`（内置转向架在弯道可能偏离车身弦线）与模型自身剔除半径的较大值。

### `void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen)`
- 作用：
  1. 记下两车端之间的中心（转向架上轨时以此为基准）；有自定义转向架且不是雅可比转向架时，用 `getPlacementOnBogies` 把车身移到两个转向架上并改用其偏航/俯仰；
  2. 门开度、是否在站台（当前路径段停站时间 > 0）、是否允许俯仰；不可见（`applyAverageTransform` 为 null）则返回；
  3. 变换到世界、绕 Y 转 `π + yaw`、绕 X 转 `π + pitch`；方块与天空光照；
  4. 无模型或无纹理：画矿车或船（船按速度播放划桨动画）；有纹理：判断是否在细节半径（32 格，Replay Mod 下总是）内，调用模型 `render`；转向架：自定义转向架近处标记上轨、远处随车身画；内置转向架近处且允许随轨时上轨，雅可比转向架只在首末车两端画，其余在 ±`bogiePosition` 画两个；
  5. 缆车画抱索器（`_rht` 结尾的车型转 180°）；
  6. 弹出本层后，若标记上轨则用 `renderBogieOnTrack` 画两个转向架（内置的用 ±`bogiePosition` 构造临时 `CustomBogie`）；最后弹出 `applyAverageTransform` 压入的一层。

### `private double[] getPlacementOnBogies(TrainProperties trainProperties, int carIndex)`
- 作用：MTR 4 的车身放置：车身经过两个转向架在轨道上的中心，再沿自身方向按两转向架平均位置平移。只有一个转向架或两者同位时车身取该转向架的朝向。
- 返回：`{相对两端中心的 dx, dy, dz, 偏航(弧度), 俯仰(弧度)}`；轨道点取不到或方向为零时 null。

### `private void renderBogieOnBody(CustomBogie bogie, int carIndex, int light, boolean renderDetails, boolean atPlatform)`
- 第 1 级：转向架随车身，沿车身平移 `bogie.position` 后画。

### `private void renderBogieOnTrack(CustomBogie bogie, int carIndex, double x, double y, double z, int light, boolean atPlatform)`
- 第 2 级：转向架站在轨道上并随轨道转向（车身仍由两端放置）：在 `(x, y, z)`（含乘车偏移的车身中心）基础上加上转向架中心相对车身中心的偏移，按转向架前后点求偏航与俯仰后画。

### `private void renderBogieModel(CustomBogie bogie, int carIndex, int light, boolean renderDetails, boolean atPlatform)`
- 无模型时画内置转向架（非半透明批次），否则用资源包模型与纹理画。

### `void renderConnection(...)`
- 作用：画贯通道（外侧四面用外部渲染层；内侧两壁、顶、底在列车运营中用最亮内部光照）；雅可比转向架车型在连接处画转向架；最后弹出 `applyAverageTransform` 的一层。

### `void renderBarrier(...)`
- 有挡板纹理时画车端挡板四面。

### `ResourceLocation resolveTexture(String textureId, Function<String, String> formatter)`
- 作用：纹理存在性缓存在 `RenderTrains.AVAILABLE/UNAVAILABLE_TEXTURES`；不存在时打印并回退到基础车型渲染器的同名纹理（基础车型不是本类时用透明纹理）。

### `private ResourceLocation getConnectorTextureString(boolean isConnector, String partName)`
- `<前缀>_connector_<部件>.png` 或 `<前缀>_barrier_<部件>.png`。

### `private void drawTexture(PoseStack, VertexConsumer, Vec3 pos1, Vec3 pos2, Vec3 pos3, Vec3 pos4, int light)`
- 不在车上第一人称时用世界坐标版本（`IDrawing.drawTextureWorld`），否则用相对坐标版本。

### `private static String resolvePath(String path)`
- 小写并去掉 `.png` 及之后部分；null 原样返回。

## 私有内部类 `FakeBoat`（extends `Boat`）
- 用于驱动原版船模型的划桨动画：`getRowingTime` 累加进度。
