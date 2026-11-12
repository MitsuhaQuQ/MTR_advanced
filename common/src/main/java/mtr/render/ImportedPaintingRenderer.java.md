# ImportedPaintingRenderer.java（本分支新增）

客户端：把原版画显示为导入的图片——双面的图片平面，支持局部旋转与偏移，按小格分别取光照（旋转后每格位置的光照）。由 Fabric/Forge 的 `ImportedPaintingRendererMixin` 在原版 `PaintingRenderer.render` 开头调用（`fabric/src/main/java/mtr/mixin/ImportedPaintingRendererMixin.java:27`）；返回 true 时原版不再画。手持刷子时还会画出编辑锚点（原版命中框、十字、偏移连线和标签）。

## 类 `ImportedPaintingRenderer`（final，私有构造）

### `static boolean render(Painting painting, float yaw, PoseStack pose, MultiBufferSource buffers)`
- 作用：
  1. 设置取编辑界面正在预览的设置（`ImportedPaintingScreen.previewFor`），否则从画实体读取并解析；解析失败返回 false（走原版）；没有图片返回 false。
  2. 纹理未就绪（`ImportedImages.texture` 返回 null，可能还在下载）：只画编辑锚点，返回 true。
  3. 变换：绕 Y 转 `180 − yaw`，平移 `(−x, y, −z − 1/32)`，再依次绕 Z、Y、X 旋转设置角度（与 `PaintingTransform` 的 X→Y→Z 局部旋转等价）。
  4. 使用 `RenderType.entityTranslucentCull(texture)`（发光时只是光照拉满：发光渲染类型不写深度也不剔面，会让镜像的背面和后面的东西透出来，光影包下尤其明显，0.70.1）。
  5. 把图片按 1×1 格切块（列数 `ceil(width)`、行数 `ceil(height)`）；每块中心经 `PaintingTransform.rotate` 求世界位置并取该处光照（发光时 `FULL_BRIGHT`）；正面画一个四边形，背面用反向绕序与镜像 U 再画一个，背面也能正确阅读。
  6. 若缓冲是 `BufferSource`，立即 `endBatch(renderType)`：后面的画可能把这张纹理从有界缓存中挤掉，必须在那之前提交顶点。
  7. 画编辑锚点。
- 参数：`yaw` 画的朝向（度）。
- 返回：是否已由本类绘制。

### `private static void drawEditAnchor(Painting painting, float yaw, PaintingSettings settings, PoseStack pose, MultiBufferSource buffers)`
- 作用：只在玩家手持刷子时：按原版画的尺寸画青色线框（原版命中目标，与图片变换无关）；在前后两面画黄色十字；有偏移时画一条从锚点到图片位置的蓝线；在锚点处画面向相机的黄色 `+`，在上方画 `gui.mtr.image_edit_anchor` 标签（透视可见）。

### `private static void quad(PoseStack matrices, VertexConsumer buffer, float[] positions, float u0, float u1, float v0, float v1, float nx, float ny, float nz, int light)`
- 写一个四边形的 4 个顶点（白色、uv、无覆盖、光照、法线）。
