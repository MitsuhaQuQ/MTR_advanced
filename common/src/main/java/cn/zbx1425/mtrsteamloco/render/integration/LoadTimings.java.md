# LoadTimings.java（ANTE）

客户端自定义资源重载的分阶段计时：把渲染线程上（以及并行阶段整体）各阶段耗时累加，重载结束时一次性写日志并报告堆内存。阶段名由调用方给出，例如 `preloadScan`、`preloadParse`、`preloadAtlas`、`uploadPrepare`、`uploadGl`、`compact`、`jsonLookup`、`json`、`trainBuild`、`gc`。被 `TrainModelPreloader`、`DeferredModelUpload`、`CustomResourcesMixin`（`common/src/main/java/cn/zbx1425/mtrsteamloco/mixin/CustomResourcesMixin.java:70`、`:71`）、`cn/zbx1425/mtrsteamloco/CustomResources.java:58` 使用。

## 类 `LoadTimings`（只有静态成员）

### 静态字段
- `TOTALS`：`阶段名 → {累计纳秒, 次数}`，保持插入顺序。
- `BYTES_PER_MEGABYTE = 1024*1024`。

### `static synchronized void add(String phase, long startNanos)`
- 作用：把 `System.nanoTime() - startNanos` 累加到该阶段，次数 +1。
- 参数：`startNanos` 阶段开始时的 `System.nanoTime()`。

### `static synchronized void report()`
- 作用：写一条 info 日志 `Load timings: 阶段 N ms xK, …`，清空累计，再 `reportMemory()`。调用者：`CustomResourcesMixin.reloadTail`。

### `private static void reportMemory()`
- 作用：汇总所有堆内存池的当前已用、已提交、峰值、上次 GC 后已用，以及最大堆，写一条 info 日志（MB）。注释说明：操作系统看到的是堆扩张到的大小，"上次 GC 后"最接近真实所需。
