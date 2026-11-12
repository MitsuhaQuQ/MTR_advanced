# ScriptExecutors.java（ANTE 脚本，本分支新增）

客户端 ANTE 资源包脚本（GraalJS）中替代 `java.util.concurrent.Executors` 的类，以名字 `Executors` 注入脚本环境（`common/src/main/java/cn/zbx1425/mtrsteamloco/scripting/ScriptHolderBase.java:400`）。资源包脚本会自己开定时器，但一个 JS 上下文绝不能同时被两个线程进入，所以这里的线程池只负责计时，真正执行时把任务交给脚本线程（`ScriptHolderBase.runOnScriptThread`）。脚本重置时 `ScriptHolderBase` 调 `shutdownAll`（`ScriptHolderBase.java:495`），让已卸载脚本的池不再调用已关闭的上下文。

## 类 `ScriptExecutors`（只有静态成员）

### 静态字段
- `POOLS`（`CopyOnWriteArrayList`）：已创建的所有池。
- `THREAD_INDEX`：线程编号计数。

### `static ScheduledExecutorService newScheduledThreadPool(int corePoolSize)` / `newSingleThreadScheduledExecutor()`
### `static ExecutorService newFixedThreadPool(int threads)` / `newSingleThreadExecutor()` / `newCachedThreadPool()`
- 作用：都返回一个新的 `ScriptScheduledPool`（大小为给定值或 1；缓存池也只有 1 个线程），并登记到 `POOLS`。
- 调用者：资源包脚本。

### `static void shutdownAll()`
- 作用：对所有池 `shutdownNow()` 并清空列表。

### `private static ScriptScheduledPool create(int size)`
- 新建（大小至少 1）并登记。

### `private static Runnable onScriptThread(Runnable command)`
- 作用：包装为"在脚本线程运行 `command`"；异常只写错误日志不上抛，使周期任务不会因一次失败而停止（与渲染调用失败的处理一致）。

## 私有内部类 `ScriptScheduledPool`（extends `ScheduledThreadPoolExecutor`）

### 构造函数 `ScriptScheduledPool(int size)`
- 守护线程，名 `ANTE-Script-Scheduler-<n>`。

### `schedule(Runnable, long, TimeUnit)` / `schedule(Callable<V>, long, TimeUnit)` / `scheduleAtFixedRate(...)` / `scheduleWithFixedDelay(...)`（均 @Override）
- 作用：把任务包装为在脚本线程执行后再交给父类调度。`execute`、`submit` 在 JDK 中都经过 `schedule`，所以这四个覆盖了所有入口。Callable 版本异常会通过 Future 传给调用者（不吞掉）。
