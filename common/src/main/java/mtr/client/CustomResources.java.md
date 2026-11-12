# CustomResources.java（上游文件，本分支有改动）

客户端：每次资源重载时读取资源包中的车辆和标志（`mtr:mtr_custom_resources.json`），并把每种车辆注册到 `TrainClientRegistry`。ANTE 的 `cn/zbx1425/mtrsteamloco/mixin/CustomResourcesMixin.java:41` 在 `reload` 前后加入自己的加载，并记住经 `getOrDefault` 读到的最后一个纹理、用于下一个 OBJ 或 bbmodel 模型——所以车辆的纹理必须在其模型之前读取。调用者：`fabric/src/main/java/mtr/MTRFabricClient.java:43`、`forge/src/main/java/mtr/MTRForge.java:129`（资源重载）。

本分支改动：转向架可在资源包中单独定义（`readBogie`，先于车体纹理读取，以配合 ANTE 记住纹理的方式），注册时传入两个转向架；失败与慢加载写入日志（单车 ≥ 1000 ms 警告），并记录总耗时。

## 类 `CustomResources`（implements `IResourcePackCreatorProperties`, `ICustomResources`）

### 字段与常量
- `LOGGER`（"MTR"）、`SLOW_TRAIN_MILLIS = 1000`。
- `CUSTOM_SIGNS`：`CUSTOM_SIGN_ID_PREFIX + 键` → `CustomSign`。读取者：`common/src/main/java/mtr/render/RenderRailwaySign.java:290`、`common/src/main/java/mtr/screen/RailwaySignScreen.java:68`。
- `RELOAD_LISTENERS`：重载完成后调用的监听器。
- JSON 键常量（`CUSTOM_TRAINS_*`、`CUSTOM_SIGNS_*` 等）来自 `ICustomResources`。

### `static void reload(ResourceManager manager)`
- 作用：重置车辆注册表、列车纹理可用性缓存、字体与动态纹理（`ClientData.DATA_CACHE.resetFonts()`）、清空标志；读取所有资源包中的 `mtr:mtr_custom_resources.json`：
  - `custom_trains` 中每项（`CUSTOM_TRAINS_KEY`）：名称（默认为键）、颜色、基础车型（`base_train_type`，取其 `TrainProperties` 作为各项默认值）、描述、维基条目；读两个转向架；纹理、贯通道、车门挡板 id；车门动画类型（默认取基础模型的类型或 `STANDARD`）；是否画车门覆盖层；乘客偏移；声音：BVE 声音基名或 Jon 声音（速度声音数、速度/车门声音基名、关门声时间、惰行时用加速声、恒定播放速度）。是否用 BVE：BVE 基名为空 → 否；JSON 写了 BVE 基名 → 是；写了速度声音基名 → 否；否则跟基础车型。
  - 有基础车型时：用基础模型（简单模型按新车门类型复制）注册，id 为 `CUSTOM_TRAIN_ID_PREFIX + 键`。
  - 同时有 `model` 与 `model_properties` 时：读两个 JSON，检查结构，车型为 `<交通方式>_<长度>_<宽度>`（具体格式字符串在第 117 行，被截断，未逐字核对）；兼容旧格式（`has_gangway_connection`、`base_` 前缀、`parts_normal` → `DynamicTrainModelLegacy`），否则 `DynamicTrainModel`，再注册。
  - 单车异常写错误日志；单车耗时 ≥ 1 秒写警告。
  - `custom_signs` 中每项：纹理 id、翻转纹理、自定义文字、翻转文字、小尺寸、背景色 → `CUSTOM_SIGNS`。
- 然后调用所有重载监听器，打印加载数量与 id。

### `static int colorStringToInt(String string)`
- 去掉非十六进制字符后按 16 进制解析；失败返回 0。调用者：`common/src/main/java/mtr/client/DynamicTrainModel.java:215`、`:216`。

### `private static CustomBogie readBogie(ResourceManager, JsonObject, String key, String carTextureId, CustomBogie defaultBogie)`（本分支新增）
- 作用：JSON 无该键则返回基础车型的转向架；否则读位置（默认 0，单位同 `CustomBogie.position`）、纹理（经 `getOrDefault` 读，使 ANTE 取到转向架自己的纹理与 flipV；默认车体纹理），有 `model`+`model_properties` 时建 `DynamicTrainModel`（或旧格式），车门动画 `STANDARD`。
- 返回：`new CustomBogie(model, textureId, position)`（模型可为 null）。

### `private static void readResource(ResourceManager, String path, Consumer<JsonObject> callback)`
- 对所有资源包中该路径的资源逐个解析 JSON 并回调；异常打印；关闭资源。

### `private static <T> T getOrDefault(JsonObject, String key, T defaultValue, Function<JsonElement, T>)`
- 有键则转换，否则默认值（ANTE 的 mixin 拦截此方法以记录纹理）。

### `static void registerReloadListener(Consumer<ResourceManager> listener)`
- 调用者：`cn/zbx1425/mtrsteamloco/MainClient.java:37`。

## 内部类 `CustomSign`
- `textureId`、`flipTexture`、`customText`、`flipCustomText`、`small`、`backgroundColor`；`hasCustomText()`：自定义文字非空。使用者：`RenderRailwaySign.java:61`、`:130`。
