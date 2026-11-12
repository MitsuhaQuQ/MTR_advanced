# PaintingSettings.java（本分支新增）

一幅原版画的导入图片显示设置（服务端与客户端共用），以 Gson JSON 存在画实体上（`PaintingImageMixin` 的同步字段与 NBT 键 `mtr_image`）。坐标是画自身局部坐标系中的方块单位；画在原版中的锚点不变。被 `ImageServer`（`common/src/main/java/mtr/image/ImageServer.java:84`）、`ImportedPaintingRenderer`（`render/ImportedPaintingRenderer.java:27`）、`ImportedPaintingScreen`、`ImportedImages.apply` 使用。

## 类 `PaintingSettings`（final）

### 字段（即 JSON 键）
- `image`：图片引用名 `命名空间:文件名.png|gif|jpg`，空串表示不显示导入图片。
- `x`、`y`、`z`：相对画锚点的偏移（格），各自绝对值 ≤ 16。
- `width`、`height`：显示尺寸（格），0.0625–16，默认 1。
- `rotationX`、`rotationY`、`rotationZ`：局部旋转角（度，绝对值 ≤ 360），依次按 X、Y、Z 旋转（见 `PaintingTransform`）。
- `emissive`：是否无视环境光全亮显示（0.70.0 起；旧存档没有该键，即 false）。

### `String json()`
- 校验后序列化为 JSON。

### `static PaintingSettings parse(String json)`
- 作用：null 或空串返回默认设置；否则 Gson 解析；结果为 null 或校验失败抛 `IllegalArgumentException`（消息 `gui.mtr.image_invalid` / `gui.mtr.image_invalid_settings`）。Gson 本身的语法错误抛 `JsonSyntaxException`（也是 RuntimeException）。

### `private void validate()`
- 检查所有数值有限且在上述范围内、`image` 为空或合法引用名，否则抛 `IllegalArgumentException("gui.mtr.image_invalid_settings")`。

### `private static boolean finite(double value)`
- 不是 NaN 也不是无穷。
