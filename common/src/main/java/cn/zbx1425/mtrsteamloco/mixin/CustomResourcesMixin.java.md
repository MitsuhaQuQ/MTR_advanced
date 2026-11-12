# CustomResourcesMixin.java（ANTE mixin）

客户端 mixin，作用于 `mtr.client.CustomResources`（MTR 读取资源包中自定义列车、标志等的类）。在 `reload` 前后加入 ANTE 自己的加载流程（GL 版本检测、模型预加载、延迟上传、加载计时、错误界面），并接管 `readResource` 以支持 `.obj`/`.bbmodel` 模型和 ANTE 的伪路径（含 `|`），以及在 `getOrDefault` 时捕获列车的纹理 id 等属性。其中加载计时（`LoadTimings`）、模型预加载与延迟上传（`TrainModelPreloader`、`DeferredModelUpload`）、几何共享内存统计（`CompactArrays`）、加载后 `System.gc()` 是合并时 ANTE 已带的优化或之后的改动（未逐项区分）。`readResource` 在 `MixinLintTest` 中列为允许的头部取消（`common/src/test/java/cn/zbx1425/mtrsteamloco/MixinLintTest.java:44`）。

## 类 `CustomResourcesMixin`

### 静态字段（`@Unique`）
- `TRAIN_BUILD_READ_DEPTH = 3`：读取嵌套深度为 3 时（自定义资源文件 → 列车 → 列车模型属性文件）回调里构建列车，计入 `trainBuild` 计时。
- `readDepth`：当前 `readResource` 嵌套深度。
- `capturedTextureId`、`capturedFlipV`、`captureBbModelPreload`：最近一次从列车 JSON 捕获的纹理 id、`flipV`、`preloadBbModel`，用于随后构造 ANTE 的模型数据包。

### `private static void reloadHead(ResourceManager manager, CallbackInfo ci)`
- 注入点：`reload(ResourceManager)` 的 `HEAD`。
- 作用：检测 OpenGL 上下文版本（是否 GL4ES）并写日志；`GlStateTracker.capture()` 保存 GL 状态；清空 `MtrModelRegistryUtil.loadingErrorList` 并记下 `resourceManager`；`CustomResources.reset(manager)`（ANTE 自己的资源）；`TrainModelPreloader.preload(manager)`（并行预读模型）；`DeferredModelUpload.begin()`；写日志。

### `private static void reloadTail(ResourceManager manager, CallbackInfo ci)`
- 注入点：`reload` 的 `TAIL`。
- 作用：`DeferredModelUpload.finish()`（把攒下的模型一次上传 GPU）；`ObjModelLoader.clearPreloadedModels()`；记录 `CompactArrays.clear()` 节省的 MB 数；执行 `System.gc()` 并计入 `gc` 计时；`LoadTimings.report()`；`CustomResources.resetComponents()`；有加载错误时打开 `ErrorScreen`；`GlStateTracker.restore()`。
- 副作用：触发完整 GC（注释：重载产生的大多是垃圾，只有完整回收才会归还内存）；可能切换界面。

### `private static void readResource(ResourceManager manager, String path, Consumer<JsonObject> callback, CallbackInfo ci)`
- 注入点：`readResource` 的 `HEAD`，总是取消原方法。
- 作用：
  - 路径以 `.obj` 结尾或含 `|`：用捕获的纹理参数生成占位 BB 数据包（`MtrModelRegistryUtil.createDummyBbDataPack`），在 `readDepth` +1 下回调后返回；
  - 否则：取该位置的所有资源（计 `jsonLookup`），逐个解析 JSON（计 `json`），交给 `acceptJson`；解析失败或关闭资源失败只写错误日志；查找失败静默忽略。
- 参数：`path` 资源路径字符串；`callback` MTR 的处理回调。

### `private static void acceptJson(String path, JsonObject modelObject, Consumer<JsonObject> callback)`
- 作用：`.bbmodel` 文件额外附上 `dummyBbData`；调用回调；深度为 `TRAIN_BUILD_READ_DEPTH` 时计 `trainBuild` 时间。

### `private static <T> void getOrDefault(JsonObject jsonObject, String key, T defaultValue, Function<JsonElement, T> function, CallbackInfoReturnable<T> cir)`
- 注入点：`CustomResources.getOrDefault` 的 `RETURN`。
- 作用：读到 `CUSTOM_TRAINS_TEXTURE_ID` 键时捕获纹理 id（缺省用默认值）、`flipV`、`preloadBbModel`；读到 `CUSTOM_TRAINS_BASE_TRAIN_TYPE` 且 JSON 中有 `base_type` 时，把返回值改为 `"$NTE_DUMMY_BLANK_PROPERTY"`（让 MTR 不使用其内置基础车型，由 ANTE 处理）。
