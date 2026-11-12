# PaintingImage.java（本分支新增）

接口：原版画（`Painting` 实体）额外带的、会同步和保存的显示设置。由 Fabric/Forge 的 `PaintingImageMixin` 在 `Painting` 上实现（`fabric/src/main/java/mtr/mixin/PaintingImageMixin.java`），因此代码里把 `Painting` 强转为本接口使用：服务端写入（`common/src/main/java/mtr/image/ImageServer.java:86`）、客户端读取（`common/src/main/java/mtr/screen/ImportedPaintingScreen.java:43`、`render/ImportedPaintingRenderer.java:27`）。

## 接口 `PaintingImage`

### `String mtr$getImageSettings()`
- 返回设置 JSON（`PaintingSettings` 格式），空串表示没有导入图片。

### `void mtr$setImageSettings(String settings)`
- 规范化后写入（实现会先 `PaintingSettings.parse`，非法时抛 `IllegalArgumentException`）。
