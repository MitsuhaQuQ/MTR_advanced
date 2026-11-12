# TrainModelPreloader.java（ANTE）

客户端：在 MTR 逐个加载自定义列车之前，先并行解析这些列车的 OBJ 模型文件，结果交给 `ObjModelLoader.setPreloadedModels`，之后列车构建时直接复制预加载结果。预加载失败的文件会被跳过，正常加载路径照常报告其错误。由 `CustomResourcesMixin.reloadHead` 调用 `preload`（`common/src/main/java/cn/zbx1425/mtrsteamloco/mixin/CustomResourcesMixin.java:56`）；`runParallel` 也被 `DeferredModelUpload` 复用（`DeferredModelUpload.java:63`、`:82`）。

## 类 `TrainModelPreloader`（只有静态成员）

### 常量
- `MAX_THREADS = 24`：线程上限。
- `HEAP_PER_THREAD_BYTES = 3 << 29`（1.5 GB）：每个线程按 1.5 GB 堆估算（百万面模型解析时要数 GB），堆小时减少线程。
- `TIMEOUT_MINUTES = 5`：并行任务总等待上限。
- `SLOWEST_FILES_TO_LOG = 10`：日志列出最慢的文件数。

### `static void preload(ResourceManager resourceManager)`
- 作用：
  1. `scanCustomTrains` 找出所有 OBJ 路径（并顺带加载模型属性里引用的纹理图集），计 `preloadScan`；没有则返回；
  2. 每个文件一个任务：`ObjModelLoader.parseModels(resourceManager, 位置, MainClient.atlasManager)`，每个模型 `compact()`，存入结果；记录每文件耗时；IO 异常转 `UncheckedIOException`（在 `runParallel` 中被吞掉，该文件跳过）；
  3. `runParallel`，计 `preloadParse`；
  4. `ObjModelLoader.setPreloadedModels(results)`；写日志：成功数/总数、总耗时、线程数，以及最慢 10 个文件（耗时与面数）。
- 注意：图集必须在工作线程开始前加载完成（工作线程只读图集）。

### `private static int getThreadCount()`
- 返回 `max(1, min(CPU 数 − 1, 24, 最大堆 / 1.5GB))`。

### `private static int getFaceCount(Map<String, RawModel> models)`
- 所有网格的面数之和；null 返回 0。

### `static void runParallel(List<Runnable> tasks)`
- 作用：建 `getThreadCount()` 个守护线程（名 `ANTE Model Loader`）的固定线程池，执行全部任务；任务抛出的 `RuntimeException`/`LinkageError` 被忽略（调用方对失败项自行回退）；等待最多 5 分钟，超时写警告并 `shutdownNow`；被中断时恢复中断标志。
- 副作用：阻塞调用线程直到完成或超时。

### `private static void scanCustomTrains(ResourceManager resourceManager, Set<ResourceLocation> objLocations)`
- 作用：读所有 `mtr:<CUSTOM_RESOURCES_ID>.json`，遍历其中自定义列车：同时有模型和模型属性键时，模型字符串含 `|` 则按"路径|xxx|路径|xxx…"取偶数位为 OBJ 路径，或以 `.obj` 结尾则直接加入；然后读该列车的模型属性 JSON，有 `atlasIndex` 就 `loadAtlas`。任何异常忽略该项。

### `private static void loadAtlas(ResourceManager resourceManager, String atlasIndex)`
- 作用：`MainClient.atlasManager.load(...)`，失败忽略；计 `preloadAtlas`。

### `private static void readJson(ResourceManager resourceManager, ResourceLocation location, Consumer<JsonObject> callback)`
- 作用：读该位置所有资源（多个资源包都有时逐个回调），解析 JSON 回调；任何异常忽略；每个资源读完关闭。
