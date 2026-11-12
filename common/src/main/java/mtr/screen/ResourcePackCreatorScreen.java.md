# ResourcePackCreatorScreen.java（上游文件，本分支仅兼容性改动）

客户端界面：资源包车辆制作器——左侧是模型中的可用部件，右侧是已用部件及正在编辑部件的属性（镜像、远处不画、显示屏及其边距、中文字号比例、显示类型、颜色、滚动、大写、单行、测试文字、渲染阶段、车门偏移、渲染条件、位置、白/黑名单车厢），中间用鼠标拖动/滚轮查看三维预览。编辑的是全局 `RenderTrains.creatorProperties`。打开：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:448`。预览由 `fabric/src/main/java/mtr/mixin/GameRendererMixin.java:16`（forge 同）在世界渲染时调用静态 `render(PoseStack)` 绘制。显示屏测试文字被 `common/src/main/java/mtr/client/DynamicTrainModel.java:225` 读取。ANTE 有 mixin：`cn/zbx1425/mtrsteamloco/mixin/ResourcePackCreatorScreenMixin.java:16`。

本分支改动：继承 `mtr.compat.ScreenBase`，绘制经 `GuiCompat`；无逻辑变化。

## 类 `ResourcePackCreatorScreen`（extends `ScreenBase`，implements `IResourcePackCreatorProperties`, `IGui`）

### 实例字段
- `editingPartIndex`：正在编辑的已用部件下标（−1 无）。
- `buttonOptions`（打开 `ResourcePackCreatorOptionsScreen`）、两个部件列表、车厢数滑块（1…32）、亮度滑块（0…100）、车头方向、左/右车门按钮、交通方式、长度（1…32）、宽度（1…8）、门最大开度（1…32）、车门动画类型、各部件属性控件（见上）、`buttonDone`。
- 文本框格式：位置 `[x,z],…`；车厢名单如 `5,-4,%3,%2+1`（含义见 `DynamicTrainModel.matchesFilter`）。

### 静态字段（预览状态，跨实例保留）
- `guiCounter`（界面绘制后设为 2，静态 `render` 每帧减 1，期间隐藏游戏 HUD）、`hideGui`（打开前的 HUD 设置）、`translation`（沿车身平移）、`yaw`、`roll`（弧度）、`scale`（相机距离，最小 `MIN_SCALE = 1`）、`cars`、`brightness`（0–100）、`head1IsFront`、`doorLeftValue`、`doorRightValue`、`openingLeft`、`openingRight`。
- `MOUSE_SCALE = 0.005`、`DEFAULT_TEST_STRING = "測試|Test"`。

### 构造函数 `ResourcePackCreatorScreen()`
- 创建控件，回调调用 `creatorProperties.editXxx(…)` 后 `updateControls`。

### `protected void init()`
- 左右两个面板布局；编辑部件时右侧显示部件属性，否则显示车辆整体属性。（布局细节未逐行核对。）

### `void render(PoseStack, int, int, float)`
- 首次绘制时记下 HUD 设置并涂黑背景；左右面板背景、两个列表、控件；编辑部件时显示部件名和各标签，否则显示"可用/已用部件"标题；最后 `guiCounter = 2`。

### `void mouseMoved(…)`
- 转发给列表。

### `boolean mouseScrolled(…)`
- 在中间区域滚动缩放（并限制平移范围 ±车厢数 × 长度 / 2）；转发给列表。

### `boolean mouseDragged(…)`
- 中间区域：左键沿车身平移，其他键旋转（yaw/roll）。

### `void tick()`
- 列表与文本框 tick；左右车门按开/关状态以 `1 / Train.DOOR_MOVE_TIME` 每 tick 变化，上限为动画类型的 `maxTime`。

### `String getTestText()`
- 显示屏测试文字（空为"未命名"）。

### `private List<DataConverter> updatePartsList(JsonArray, Function<JsonObject, Integer> color, Function<JsonObject, String> extraText)`
- JSON 部件数组 → 列表行（名称可经模板加工，颜色不透明）。

### `private void updateControls(boolean formatTextFields)`
- 刷新两个列表、各按钮文字与滑块、部件编辑控件的可见性与值（`formatTextFields` 时重填文本框）。

### `private void onAdd(…)` / `onEdit(…)` / `onDelete(…)` / `onUpdateColor(boolean isCjk)` / `private boolean isEditing()`
- 添加部件、开始编辑、删除部件、修改显示颜色；是否在编辑有效部件。

### `static void render(PoseStack matrices)`
- 在 `guiCounter > 0` 时：设置 HUD 隐藏；画一个大的背景平面；按 `scale`、yaw、roll 变换后，以亮度对应的光照逐节车厢调用 `creatorProperties.render(…)`（车厢间隔 长度 + 1）。

### `private static int getColor(JsonObject)` / `private static String getName(JsonObject)`
- 已用部件行颜色按渲染阶段（外部绿、内部红、内部半透明橙、灯黄、常亮灯蓝）；名称模板 `[*]%s (N×)`（`*` 表示镜像，N 为位置数）。
