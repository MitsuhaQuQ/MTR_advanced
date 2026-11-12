# PaintingRotationScreen.java（本分支新增）

客户端界面：导入画作（图片）的旋转控制器——把 X/Y/Z 三个旋转环投影到屏幕，围绕图片中心拖动即可旋转，图片本身按当前旋转画在中央。从导入画作编辑界面打开：`common/src/main/java/mtr/screen/ImportedPaintingScreen.java:76`；关闭后回到它（`ImportedPaintingScreen.java:157` 用 `editor` 字段识别）。旋转值通过 `ImportedPaintingScreen.setRotation` 写入草稿。

## 类 `PaintingRotationScreen`（包内 final，extends `ScreenBase`）

### 字段
- `editor`：所属的导入画作编辑界面。
- `COLORS`：X 红、Y 绿、Z 蓝（ARGB）。
- `axis`：正在拖动的轴（−1 无）；`lastAngle`（上次鼠标在环上的角度，弧度）、`angleValue`（累计的旋转值，度）。
- `shown`：本帧显示所用的设置（`editor.draft()`）。

### 构造函数 `PaintingRotationScreen(ImportedPaintingScreen editor)`
- 标题 `gui.mtr.image_gizmo`，读取草稿。

### `protected void init()`
- "完成"按钮（关闭）与"重置旋转"按钮（三轴设为 0），位于底部。

### `private void button(String key, int x, Runnable run)`
- 在底部（`height - 27`）加一个宽 100 的按钮。

### `private double scale()`
- 投影半径（像素）：`max(24, min(宽*0.32, (高-100)*0.43))`。

### `private double[] project(double x, double y, double z)`
- 固定的斜轴测投影到屏幕坐标（以屏幕中心为原点）。

### `private double[] ring(int axis, double angle)`
- 某轴旋转环上某角度的点的屏幕坐标：按欧拉顺序（X 在 Y/Z 之内，Y 在 Z 之内）把环转到该数值控件实际对应的轴。

### `private double angleAt(int axis, double x, double y)`
- 屏幕点相对该环（看作投影后的椭圆）的角度；椭圆退化时用普通 `atan2`。

### `boolean mouseClicked(double x, double y, int button)`
- 左键：在三个环上各取 192 个采样点，选距离最近且小于 8 像素的环开始拖动，记下起始角与当前旋转值。

### `boolean mouseDragged(double x, double y, int button, double dx, double dy)`
- 拖动中：累加角度变化（`PaintingTransform.wrap` 规整到 ±180°），按住 Shift 时吸附到 15° 的倍数，写入编辑器。

### `boolean mouseReleased(…)` / `void onClose()` / `boolean isPauseScreen()`
- 结束拖动；回到编辑器；不暂停。

### `void render(PoseStack, int mouseX, int mouseY, float delta)`
- 标题与帮助文字；若有图片纹理（`ImportedImages.texture`），按宽高比与当前旋转把四角投影后画出（背面时 U 翻转）；画三个环（拖动中的为白色）与轴标签、中心点；底部显示 `X/Y/Z` 角度（度，一位小数）。

### `private static void line(GuiGraphics, double[] a, double[] b, int color)`
- 逐像素画线段。
