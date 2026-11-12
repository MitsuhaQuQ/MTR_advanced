# ObjModelLoader.java（ANTE sowcerext）

客户端 OBJ 模型加载器：把 OBJ（+MTL）文件读成 ANTE 的 `RawModel`（按材质分网格），支持按组拆成多个模型、外部文件、导出为 OBJ。本分支（含合并时带来的优化）增加了预加载：`TrainModelPreloader` 在重载开始时并行 `parseModels` 并通过 `setPreloadedModels` 登记，之后 `loadModels` 直接复制预加载结果；各步骤计入 `LoadTimings`。调用者：`cn/zbx1425/mtrsteamloco/render/integration/DynamicTrainModelLoader.java:89`、`:175`、`:185`，`TrainModelPreloader.java:69`、`:83`，`CustomResourcesMixin.java:65`，`cn/zbx1425/mtrsteamloco/Debug.java:41`；测试 `common/src/test/java/cn/zbx1425/sowcerext/model/loader/ObjModelLoaderTest.java`。

## 类 `ObjModelLoader`（只有静态成员）

### 静态字段 `preloadedModels`（volatile）
- `OBJ 位置 → (组名 → RawModel)`，默认空表。

### `static RawModel loadModel(InputStream obj, InputStream mtl, ResourceLocation location, AtlasManager atlasManager) throws IOException`
- 从两个流读 OBJ 与 MTL，整体转成一个模型，`sourceLocation = location`。

### `static Map<String, RawModel> loadModels(InputStream obj, InputStream mtl, ResourceLocation location, AtlasManager atlasManager) throws IOException`
- 同上但按组（`g`）拆分，每组一个模型；`sourceLocation` 为 `location/规范化组名`（小写，非 `[a-z0-9/._-]` 字符换成 `_`）。

### `static RawModel loadModel(ResourceManager resourceManager, ResourceLocation objLocation, AtlasManager atlasManager) throws IOException`
- 从资源包读 OBJ 及其 `mtllib` 引用的 MTL（相对路径解析），转成一个模型。注意：此处输入流未显式关闭。

### `static void setPreloadedModels(Map<ResourceLocation, Map<String, RawModel>> models)` / `static void clearPreloadedModels()`
- 设置/清空预加载结果。

### `static Map<String, RawModel> loadModels(ResourceManager resourceManager, ResourceLocation objLocation, AtlasManager atlasManager) throws IOException`
- 作用：有预加载结果则逐个 `copy()` 返回（调用方会就地变换模型，且多辆列车可能共用一个文件；脚本也可能请求同一文件，所以预加载结果保留到重载结束），计 `objCopy`；否则 `parseModels`。

### `static Map<String, RawModel> parseModels(ResourceManager resourceManager, ResourceLocation objLocation, AtlasManager atlasManager) throws IOException`
- 作用：读 OBJ（计 `objRead`）、MTL（`objMaterials`）、按组拆分（`objSplit`）、逐组转换（`objConvert`）。线程安全程度取决于 `atlasManager` 只读（见 `TrainModelPreloader`）。

### `static Map<String, RawModel> loadExternalModels(String path, AtlasManager atlasManager) throws IOException`
- 从本地文件路径读 OBJ（不读 MTL），按组拆分；`sourceLocation` 命名空间为 `mtrsteamloco-external`，路径为规范化文件路径/组名。

### `private static RawModel loadModel(Obj srcObj, ResourceLocation objLocation, Map<String, Mtl> materials, AtlasManager atlasManager)`
- 作用：按材质组拆分；每个非空组：
  - 解析材质名选项（`splitMaterialOptions`）：`#` 后第一个无等号项为渲染类型（默认 `exterior`），`flipv` 为 1 时翻转 v；
  - 有 MTL 且有位置：用 MTL 的 `map_Kd` 作纹理（相对路径，补 `.png`），`Kd` 和 `d` 作颜色；无 MTL：材质名本身作纹理名（`_` 表示无纹理），白色；无位置：无纹理白色；
  - `ObjUtils.convertToRenderable` 转成可渲染（计 `objRenderable`），建 `RawMesh`、设渲染类型，复制顶点（缺法线/uv 用零值）和三角面，应用图集（`atlasManager.applyToMesh`），校验下标，追加进模型。
  最后 `model.generateNormals()`（`objNormals`）和 `model.distinct()`（`objDistinct`）。

### `private static Map<String, Mtl> loadMaterials(ResourceManager, Obj srcObj, ResourceLocation objLocation) throws IOException`
- 读 OBJ 中所有 `mtllib` 文件并合并。

### `private static Map<String, Mtl> loadMaterials(InputStream mtlIn) throws IOException`
- 读一个 MTL 流，返回 `材质名 → Mtl`。

### `private static Map<String, String> splitMaterialOptions(String src)`
- 作用：`名称#类型,键=值,标志` 拆为 Map：`""` → 名称；第一个无等号项 → `"#"`；其后无等号项 → `小写键 = "1"`；`键=值` 原样。

### `static void saveModels(Map<String, RawModel> models, Path objOutputFile, Path mtlOutputFile, boolean withNormal) throws IOException`
- 作用：把模型导出为 OBJ+MTL：每组写 `g`，每个网格按纹理名与由着色器/光照推回的渲染类型写 `usemtl 纹理#类型`（MTL 中每种组合只写一次，`map_Kd 纹理.png`），写顶点（`vt` 的 v 取 `1 - v`）、可选法线和面（下标从 1 起，跨网格累加）。注意：只读 `vertices`/`faces` 列表，已 `compact` 的网格会导出为空。调用者：`cn/zbx1425/mtrsteamloco/Debug.java:41`。

## 私有内部类 `ZeroFloatTuple`（implements `FloatTuple`）
- 常量 `ZERO2`、`ZERO3`；所有分量返回 0，`getDimensions()` 返回维数。用于缺失的法线和纹理坐标。
