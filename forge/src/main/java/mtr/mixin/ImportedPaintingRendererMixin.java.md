# ImportedPaintingRendererMixin.java（forge）

客户端 mixin，注入原版 `PaintingRenderer`（画的实体渲染器），让挂了"导入图片"的原版画改用本分支的 `ImportedPaintingRenderer` 绘制。在 `forge/src/main/resources/mtr.mixins.json:11` 中登记；Fabric 侧有逐字相同的 `fabric/src/main/java/mtr/mixin/ImportedPaintingRendererMixin.java`。画的图片设置字符串由 `PaintingImageMixin` 挂在 `Painting` 实体上（实现 `PaintingImage` 接口），格式由 `PaintingSettings.parse` 解析。

## 类 `ImportedPaintingRendererMixin`（abstract，extends `EntityRenderer<Painting>`）

### 构造函数 `ImportedPaintingRendererMixin(EntityRendererProvider.Context context)`
- 仅为满足继承编译要求，转给父类；mixin 合并后不会被单独调用。

### `boolean shouldRender(Painting painting, Frustum frustum, double x, double y, double z)`（覆盖父类方法，合并进 `PaintingRenderer`）
- 作用：画带有非空导入图片时，用放大 32 格的包围盒（`getBoundingBox().inflate(32)`）做视锥剔除，避免放大显示的大图在原画框离开视野时被剔掉；否则走原版判断。
- 参数：`x`/`y`/`z` 相机位置（世界坐标）。
- 返回：是否渲染。
- 副作用：无；`PaintingSettings.parse` 抛出的 `RuntimeException` 被吞掉，按原版处理。

### `private void mtr$render(Painting painting, float yaw, float delta, PoseStack matrices, MultiBufferSource buffers, int light, CallbackInfo ci)`
- 注入点：`PaintingRenderer.render(Painting, float, float, PoseStack, MultiBufferSource, int)` 的 `HEAD`，可取消。
- 作用：调用 `ImportedPaintingRenderer.render(painting, yaw, matrices, buffers)`；返回 true（已画导入图片）时 `ci.cancel()`，不再画原版画。
- 参数：`yaw` 实体朝向（度）；`delta` partial tick；`light` 打包光照（此处未使用）。
