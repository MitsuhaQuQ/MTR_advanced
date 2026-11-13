# BakedTrainModels.java

MTR 自带车型（`ModelSimpleTrainBase` 的子类）的烘焙缓冲（0.73.5，存车线帧率第 5 项）：车门关着的车厢每帧都一样，所以每种车厢只捕获一次成 ANTE 的 `ModelCluster`，之后每帧每节车厢入队一次绘制，不再逐个方块变换、写顶点。由 `ModelTrainBase.render` 调用。

## 类 `BakedTrainModels`（final，私有构造）

### 静态字段
- `inWorld`：`JonModelTrainRenderer.renderCar` 画车时为真（界面预览不走缓冲）。
- `capturing`（public）：捕获时为真，`ModelMapper.render` 据此改用 `CapturingVertexConsumer.dumpModelPartQuads` 逐个方块写（Sodium 替换了 `ModelPart.render`）。
- `CACHE`：模型 → (`Key` → `ModelCluster`、`NOTHING`（没东西可画）或 `FAILED`（捕获出错，照旧画）)。

### `static void setInWorld(boolean value)`；`static void clear()`
- `clear`：关闭所有缓冲并清空；ANTE `CustomResources.reset`（资源重载）调用。

### `static boolean draw(ModelTrainBase model, PoseStack matrices, ResourceLocation texture, int light, float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ, int car, int cars, boolean head1IsFront, boolean lightsOn, boolean isTranslucent, boolean renderDetails)`
- 条件：`inWorld`、自带车型、四个门值都是 0、`ClientConfig.bakeBuiltInTrains`、`useRenderOptimization()`、着色器就绪、**没装 Sodium**（`WITH_SODIUM`，0.76.2：装了 Sodium 时这些缓冲画不出任何东西，原因未明，见代码注释与测试清单 U454）；不满足返回 false（照旧画）。
- 半透明一遍：返回 true 不画（已在缓冲里）。
- 否则按 `Key`（贴图、1 端是否车头、2 端是否车头、车头朝向、近处细节、车灯）取缓冲，没有就 `capture`；出错记 `FAILED` 并写日志。入队 `MainClient.drawScheduler.enqueue(缓冲, 当前姿态（`baseTransform` 之前）, 世界光照)`。
- 返回：是否已处理（调用者据此跳过各阶段的逐个方块绘制）。

### `private static Object capture(…)`
- 恒等 `PoseStack` 加 `baseTransform`，`capturing` 期间依次以各 `RenderStage`（无细节时不含车内半透明）调模型的 `render`，顶点进 `Capture`；去掉空网格；在 `GlStateTracker.capture/restore` 之间上传成 `ModelCluster`（不登记到 `ModelManager`，由本类关闭）。

### `private static MaterialProp material(ResourceLocation texture, RenderStage stage, boolean lightsOn)`
- 按 `ModelTrainBase.render` 的渲染层和光照：车灯 开灯时 `rendertype_beacon_beam`（cutoutHack，发光）否则实体剪切；车内 实体剪切，开灯时光照固定为 `MAX_LIGHT_INTERIOR`；常亮灯 `beacon_beam` 半透明不写深度；车内半透明 `entity_translucent_cull` 半透明，开灯时全亮；车外 实体剪切。

### 内部类 `Key`、`Capture`
- `Capture`：`VertexConsumer`，收位置、UV、法线，每四个顶点拆成两个三角形加进当前材质的网格。
