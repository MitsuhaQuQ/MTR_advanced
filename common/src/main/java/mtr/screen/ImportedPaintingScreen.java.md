# ImportedPaintingScreen.java（本分支新增）

客户端界面：一幅画（原版 `Painting` 实体）的导入图片设置——选择服务器上的图片（或原版画）、宽高、偏移、旋转、是否自发光。修改只在本地预览（`previewFor` 被渲染器读取），只有点"应用"才把设置发给服务器（`ImportedImages.apply`）。画作的设置字符串经 mixin 接口 `PaintingImage.mtr$getImageSettings()` 读取。预览：`common/src/main/java/mtr/render/ImportedPaintingRenderer.java:27`。旋转控制器：`PaintingRotationScreen`。

## 类 `ImportedPaintingScreen`（final，extends `ScreenBase`）

### 字段与常量
- `painting`：被编辑的画；`images`：服务器图片列表（`ImportedImages.ENTRIES` 的副本）。
- `fields`：8 个有符号小数文本框，对应 `labels`：宽、高（方块）、偏移 x/y/z（方块）、旋转 x/y/z（度）。
- `selectedImage`（空串表示原版画）、`error`（错误翻译键）、`emissive`。
- `left`、`top`：布局原点；`choose`：图片下拉按钮；`dropdownOpen`、`dropdownScroll`、`highlighted`。
- `search`、`shown`：下拉打开时输入的搜索词（0.65.28：名称须包含每个词；原版画只在搜索为空或匹配时显示）与显示的行（null 表示原版画）。
- `SEARCH_HEIGHT = 14`；`previousMouseX/Y`：只在鼠标移动时用悬停改变高亮。

### 构造函数 `ImportedPaintingScreen(Painting painting)`
- 确保图片连接（`ImportedImages.ensureConnection`）；解析画的现有设置（失败用默认）；没有图片时宽高取原版画尺寸 / 16；填入文本框。

### `protected void init()`
- 布局：下拉按钮（打开时刷新图片列表、清空搜索、高亮当前图片、取消文本框焦点）；8 个文本框；"旋转控制器"按钮（草稿无效时显示 `gui.mtr.image_invalid_settings`）；自发光开关；"应用"（`ImportedImages.apply(painting.getId(), draft())` 后关闭）；"取消"。

### `private int dropdownRows()` / `private int rowsTop()`
- 下拉显示行数（1–6，至少 1 行用于显示"无匹配"）；行区域顶部 y。

### `private void refilter()`
- 按 `ImageSearch.filter` 过滤图片名，原版画在搜索为空或匹配时放第一行；高亮与滚动归零。

### `private void selectImage(int index)`
- 选择显示中的第 index 行并关闭下拉。

### `boolean mouseClicked(…)` / `boolean mouseScrolled(…)`
- 下拉打开时：点搜索行无动作，点行则选择，点别处关闭；滚轮滚动列表。

### `boolean keyPressed(int key, int scan, int modifiers)`
- 下拉打开时：Esc 先清空搜索再关闭；退格删一个字符；回车选择高亮行；上下键移动高亮并保持可见。

### `boolean charTyped(char, int)`
- 下拉打开时输入字符加入搜索（上限 64），空格也算搜索字符。

### `private Component emissiveLabel()` / `private String selectionName()` / `private void place(AbstractWidget, int x, int y, int width)`
- 自发光按钮文字；当前选择名称（原版画为 `gui.mtr.image_vanilla`）；放置控件。

### `PaintingSettings draft()`（包内）
- 用当前控件值建 `PaintingSettings` 并调用 `json()` 校验；数字解析或校验失败抛 `RuntimeException`。调用者：本类、`PaintingRotationScreen`。

### `void setRotation(int axis, double angle)`（包内）
- 把旋转文本框设为规整后的角度（一位小数）。调用者：`PaintingRotationScreen`。

### `static PaintingSettings previewFor(Painting painting)`
- 当前屏幕是本界面（或其旋转控制器）且编辑的正是这幅画时返回草稿（无效时 null），否则 null。

### `void render(…)` / `private void renderDropdown(PoseStack, int mouseX, int mouseY)`
- 标签、错误、控件；下拉在 z=400 层绘制：搜索行（空时提示，否则带闪烁光标）、每行缩略图（`ImportedImages.texture(hash)`）与名称、高亮、滚动条，无匹配时提示 `gui.mtr.image_search_none`。

### `boolean isPauseScreen()`
- false。
