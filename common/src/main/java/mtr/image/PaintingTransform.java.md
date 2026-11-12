# PaintingTransform.java（本分支新增）

导入图片画框的旋转约定：局部坐标先绕 X、再绕 Y、再绕 Z 旋转（角度为度）。几何绘制、光照取样和编辑界面共用同一实现，保证三者一致。调用者：`common/src/main/java/mtr/render/ImportedPaintingRenderer.java:43`、`common/src/main/java/mtr/screen/PaintingRotationScreen.java:44`、`:72`、`common/src/main/java/mtr/screen/ImportedPaintingScreen.java:153`。

## 类 `PaintingTransform`（final，私有构造）

### `static double[] rotate(double x, double y, double z, double rx, double ry, double rz)`
- 作用：把点 `(x, y, z)` 依次绕 X 轴转 `rx`、绕 Y 轴转 `ry`、绕 Z 轴转 `rz`（度，右手系）。
- 返回：`{x', y', z'}`。

### `static double wrap(double angle)`
- 把角度规范到 [−180, 180)。
