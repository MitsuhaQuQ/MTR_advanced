# DeferredModelUpload.java（ANTE）

客户端资源重载期间的"延迟上传"：收集重载中创建的列车模型（`SowcerModelAgent`），先在工作线程并行准备顶点数据，再在渲染线程一次性执行 GL 上传，最后并行压缩模型。等待中的模型以 Java 对象形式占用的内存远大于上传后，所以等待量达到堆的一定比例就先上传一批。由 `CustomResourcesMixin.reloadHead` 调 `begin`（`common/src/main/java/cn/zbx1425/mtrsteamloco/mixin/CustomResourcesMixin.java:57`），`SowcerModelAgent` 在活跃期间调 `add`（`common/src/main/java/cn/zbx1425/mtrsteamloco/render/integration/SowcerModelAgent.java:25`、`:27`），重载结束调 `finish`（`CustomResourcesMixin.java:64`、`cn/zbx1425/mtrsteamloco/CustomResources.java:54`）。

## 类 `DeferredModelUpload`（只有静态成员）

### 常量与静态字段
- `PENDING`：待上传的模型代理列表。
- `HEAP_SHARE_FOR_PENDING = 8`：待上传估算字节超过最大堆的 1/8 就上传一批。
- `BYTES_PER_VERTEX = 128`、`BYTES_PER_FACE = 48`：内存估算系数（字节）。
- `active`：是否处于收集期。
- `pendingBytes`：当前估算占用。

### `static void begin()`
- 进入收集期（`active = true`）。

### `static boolean isActive()`
- 是否收集期；`SowcerModelAgent` 据此决定立即上传还是交给本类。

### `static void add(SowcerModelAgent agent, RawModel model)`
- 作用：加入待上传列表，累加 `顶点数×128 + 面数×48` 字节；超过阈值立即 `uploadPending()`。
- 参数：`agent` 模型代理；`model` 用于估算大小的原始模型。

### `static void finish()`
- 退出收集期并上传剩余全部。

### `private static void uploadPending()`
- 作用：三阶段，各自计入 `LoadTimings`：
  1. `uploadPrepare`：用 `TrainModelPreloader.runParallel` 并行调用各 `agent.prepareUpload()`，结果按下标存入；
  2. `uploadGl`：在当前（渲染）线程依次 `completeUpload(prepared)`，单个失败只记日志和加载错误（`MtrModelRegistryUtil.recordLoadingError`），不影响其他；
  3. `compact`：并行 `agent.compactModel()`。
  最后清空列表与计数。准备阶段失败的任务结果为 null，由 `completeUpload` 自己回退处理（见 `SowcerModelAgent`）。
