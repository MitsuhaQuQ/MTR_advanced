# WorkPool.java

全服务器共享的工作线程（优化第 5 轮第 4、5 步）：核心数减 1、最多 8 个，或由 `-Dmtr.workThreads` 指定，优先级低于服务器线程。长任务（如车厂路径生成）用 `submit` 提交；可拆成独立部分的工作用 `forEach`，每部分只处理自己的元素，所以无论线程数多少，结果都与逐个执行相同。

调用者：游戏侧 `Depot.java:1274`（`submit`）、`Siding.java:1066`（`forEach` 生成路径时刻）；组件 `SimDataset.java:419`（`forEach`）。

## 类 WorkPool（final，不可实例化）

### 常量与静态字段
- `THREADS`（public static final int）：线程数 = `mtr.workThreads`（默认 max(1, min(8, CPU 数 − 1))），再夹到 [1, 64]。
- `IN_POOL`（ThreadLocal<Boolean>）：当前线程是否为池内线程。
- `executor`（volatile ThreadPoolExecutor）：懒创建的线程池。
- `ORDER`（AtomicLong）：任务到达序号。
- `SHORT = 0`、`LONG = 1`：任务种类（`forEach` 的部分优先于长任务）。

### `static Future<?> submit(Runnable task)`
- 作用：在池中运行长任务，同时运行的数量不超过线程数；取消返回的 Future 会中断任务。
- 返回：`FutureTask`。调用者：`Depot.java:1274`、`WorkPoolTest.java:75`。

### `static <T> void forEach(List<T> items, Consumer<T> task)`
- 作用：对每个元素执行任务，全部完成后返回。调用线程自己也取元素来做，只等待别的线程已取走的元素，从不等待空闲线程：池中线程都在忙长任务时，调用者自己全部做完。元素 ≤ 1、线程数 ≤ 1、或在池内线程中调用时直接顺序执行。帮手任务以 SHORT 优先级入队（排在等待中的长任务前）。
- 副作用：全部完成后若有任务抛出异常，抛出第一个 `RuntimeException`（非运行时异常包成 `IllegalStateException`）；等待时被中断则恢复中断标志并抛 `IllegalStateException`。
- 调用者：`Siding.java:1066`、`SimDataset.java:419`。

### `private static ThreadPoolExecutor getExecutor()`
双重检查懒创建固定大小线程池：优先级队列；线程为守护线程、优先级 `NORM_PRIORITY − 1`、名为 "MTR work pool N"，启动时设置 `IN_POOL`。

## 内部类 Queued（private static final，Runnable、Comparable）
等待线程的任务：先 `forEach` 拆出的部分（SHORT），再按到达顺序。
- 字段 `kind`、`order`（创建时取 `ORDER` 自增）、`task`。
- `run()`：执行任务。
- `compareTo`：先比种类，再比序号。
