# DynamicTrainModel.java（上游文件，本分支有改动）

客户端：资源包车辆的模型——由 Blockbench 模型 JSON（`resolution`、`elements`、`outliner`）和"模型属性" JSON（部件列表：位置、镜像、渲染阶段、车门偏移、白/黑名单车厢、显示屏等）组成。构造者：`common/src/main/java/mtr/client/CustomResources.java:125`、`:194`（转向架）、`common/src/main/java/mtr/client/ResourcePackCreatorProperties.java:398`（制作界面）。ANTE 的 `cn/zbx1425/mtrsteamloco/mixin/DynamicTrainModelMixin.java:13` 在构造后调用 `DynamicTrainModelLoader.loadInto`，可改用 OBJ/bbmodel 网格。

本分支改动：计算模型几何半径并提供视锥剔除半径 `getCullRadius`（`common/src/main/java/mtr/render/JonModelTrainRenderer.java:89` 使用），ANTE 加载器可用 `setGeometryRadius` 改写（`cn/zbx1425/mtrsteamloco/render/integration/DynamicTrainModelLoader.java:59`、`:290`）。

## 类 `DynamicTrainModel`（extends `ModelTrainBase`，implements `IResourcePackCreatorProperties`）

### 字段
- `doorLeftX`、`doorRightX`、`doorLeftZ`、`doorRightZ`、`head1IsFront`：本次渲染的车门位移与方向（`render` 时记下，供 `renderTextDisplays`/`shouldSkipRender` 用）。
- `parts`：outliner 顶层组名 → `ModelMapper`。
- `partsInfo`：组名 → 其中各立方体的 `PartInfo`（用于在面上放显示屏文字）。
- `properties`：模型属性 JSON；`doorMax`：门最大开度（`door_max`）。
- `whitelistBlacklistCache`：`编组车厢数|车厢序号|白名单|黑名单` → 是否跳过。
- `geometryRadius`（默认 +∞）：模型几何到车厢原点的最大距离（方块）；`cullRadius`（−1 表示未算）。

### 构造函数 `DynamicTrainModel(JsonObject model, JsonObject properties, DoorAnimationType doorAnimationType)`
- 作用：读纹理分辨率；为每个 element 建 `ModelMapper`；按 outliner 建立组层次（`addChildren`）；为每个 element 设置原点（x、y 取反）、旋转（度转弧度，x、y 取反）、UV 偏移，按 from/to/inflate/镜像（`shade=false` 视为镜像）加立方体；累计几何半径（原点距离 + 立方体相对原点的半对角线，加 inflate 和 1 像素余量，除以 16 化为方块）；宽高均 > 0 的立方体记录 `PartInfo`（坐标除以 16）。最后生成模型部件。几何部分异常只打印。然后检查属性结构（`checkSchema`）并读 `doorMax`。

### `void setGeometryRadius(double radius)`（本分支新增）
- 设几何半径并使剔除半径重算。

### `double getCullRadius()`（本分支新增）
- 作用：剔除半径 = 几何半径 + 部件放置位置的最大水平偏移（`hypot(x,z)/16`）+ `(2|doorMax| + 8)/16` + 2（覆盖车门行程、侧倾支点与显示面偏移）。有显示屏内边距为负的部件、结果非有限或解析异常时为 +∞（不剔除）。结果缓存。
- 返回：方块单位的半径。调用者：`JonModelTrainRenderer.java:89`。

### `protected void render(PoseStack, VertexConsumer, RenderStage renderStage, int light, float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ, int currentCar, int trainCars, boolean head1IsFront, boolean renderDetails)`（覆盖）
- 作用：记下车门与方向；对本车厢适用的每个部件（`iterateParts`）：不画细节时跳过 `skip_rendering_if_too_far` 部件；只画阶段匹配（`stage` 大写等于 `renderStage`）且未被渲染条件排除（`shouldSkipRender`）的部件；在每个位置画一次（镜像时 `renderOnceFlipped`，车门偏移取反）。

### `protected void renderTextDisplays(…)`（覆盖）
- 作用：为有 `display` 的部件在其每个立方体面上绘制文字。文字来源按显示类型：终点（`getDestinationString`）、线路号（`lightRailRouteNumber`）、下一站（在站时为本站）、港铁/九广式下一站、伦敦式下一站；在资源包制作界面中改用测试文字。可强制大写、强制单行（`IGui.formatStationName`）。颜色取 `color`/`color_cjk`（加不透明）。尺寸 = 立方体宽高减去内边距。可滚动时用 `ScrollingText` + `DATA_CACHE.getPixelatedText`（高度 < 0.2 时像素对齐），否则 `IDrawing.drawStringWithFont` 居中绘制。变换顺序：放置位置 → 镜像旋转 180° → 组原点 → Z/Y/X 旋转 → 立方体偏移（略向外 `SMALL_OFFSET`）。

### `int getDoorMax()`
- 返回 `doorMax`。

### `private ModelMapper addChildren(JsonObject, Map<String, ModelMapper> children, Map<String, String> uuidToParentString, ModelDataWrapper)`
- 递归建立组：子项为对象则递归，为 uuid 字符串则加入该 element，并记下它所属的组名。

### `private int matchesFilter(String[] filters, int currentCar, int trainCars)`
- 车厢过滤强度：空列表为 1；`%n[+a]` 形式（`(currentCar+1+a) % n == 0`）匹配为 2；数字等于车厢序号（从 1 起）或负数从尾数（`currentCar - trainCars`）匹配立即返回 3；否则 0。

### `private static <T> void getArrayFromValue(T[] array, JsonObject, String key, Function)`
- 有该键时把 JSON 数组前 `array.length` 项转换写入。

### `private void iterateParts(int currentCar, int trainCars, Consumer<JsonObject> callback)`
- 对每个部件：黑名单强度 > 白名单强度则跳过（结果缓存），否则回调。

### `private boolean shouldSkipRender(JsonObject partObject)`
- 按 `render_condition`：车门开/关（左、右、任一）、前进/后退（`head1IsFront`）决定是否跳过；`ALL` 不跳过。

### `private float getOffsetX(JsonObject)` / `private float getOffsetZ(JsonObject)`
- 按 `door_offset`（LEFT/RIGHT_POSITIVE/NEGATIVE）返回该部件随车门移动的 X（左为 `-doorLeftX`、右为 `doorRightX`）与 Z（正负对应）偏移；`NONE` 为 0。

## 私有内部类 `PartInfo`
- `originX/Y/Z`（组原点，方块）、`offsetX/Y/Z`（立方体前表面中心相对原点，方块）、`rotationX/Y/Z`（度，已按渲染方向取反）、`width`、`height`（方块）。
