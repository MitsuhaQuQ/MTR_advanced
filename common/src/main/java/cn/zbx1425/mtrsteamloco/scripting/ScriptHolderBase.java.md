# ScriptHolderBase.java（ANTE 脚本）

客户端：加载并运行一组 JavaScript 文件（GraalJS，开启 Nashorn 兼容）的宿主，用于脚本化的车辆、装饰物、轨道模型。加载期间脚本可访问任何 Java 类；加载完成后只能访问允许的包和类。所有对脚本的调用都在唯一的脚本线程上执行（一个 Graal 上下文不能被两个线程同时进入），脚本自己安排的定时任务（`ScriptExecutors`）也在那里执行。要点：共享 `Engine` 复用已解析代码；加载后收紧类访问（白名单）；文件 IO 只允许 `./ante/script_data/` 目录；禁止本地访问和建进程；脚本的 `Executors` 换成 `ScriptExecutors`（本分支 2026-11-03 加入）。其余各项中哪些是合并后才改的，未逐项区分。子类 `ScriptHolderClient` 等；使用者：`cn/zbx1425/mtrsteamloco/data/ScriptedCustomTrains.java:46`、`EyeCandyRegistry.java:273`、`RailModelRegistry.java:137`、`block/BlockEyeCandy.java:484`；F3 调试键调用 `resetRunner`（`mixin/KeyboardHandlerMixin.java:38`）。

## 私有静态内部类 `SharedEngine`
- `INSTANCE`：所有上下文共享的 JS `Engine`（关闭"仅解释执行"警告）。上下文彼此隔离，但共享引擎可复用解析结果。
- `HOST_ACCESS`：所有上下文必须共用同一个 `HostAccess` 实例（引擎要求）。允许公共成员、实现接口/类、数组/列表/缓冲/迭代/Map 访问、继承访问、BigInteger；数字映射：JS 数字（Double）可转为 `Float`（高优先）、`Long/Integer/Short/Byte`（四舍五入，低优先）。

## 类 `ScriptHolderBase`（abstract）

### 静态字段与常量
- `SCRIPT_THREAD`：单线程执行器（脚本线程），可被 `resetRunner` 替换。
- `PRETREATMENT = "load(\"nashorn:mozilla_compat.js\");"`：载入 Mozilla 兼容函数（`importPackage` 等）。
- `ALLOWED_PACKAGES = {java.awt, java.util, mtr}`：加载后允许查找的包前缀（注意前缀匹配，`mtr` 也匹配 `mtrsteamloco` 等以 mtr 开头的包）。
- `ALLOWED_CLASSES`：加载后允许的单个 `java.lang` 类：`Runnable Iterable Comparable CharSequence Number Long Integer Short Byte Double Float Boolean Character String StringBuilder Math`；`System`、`Runtime`、`Thread`、`Class` 等不开放。

### 实例字段
- `side`（final）：脚本所在侧名，注入为 `SIDE`。
- `context`、`globalBindings`：Graal 上下文与全局绑定。
- `functions`（public final）：`函数名 → 各脚本文件定义的同名函数列表`。
- `failTime`、`failException`：最近一次执行失败的时间（毫秒）与异常；失败后 4 秒内不再调用。
- `name`、`contextTypeName`：脚本名与上下文类型名（例如 Train、EyeCandy，用于 `函数名+类型名` 形式的函数）。
- `scripts`、`config`、`key`、`functionNames`：加载参数，`reload` 时复用。
- `loading`：是否在加载期（决定类访问是否放开）。

### 构造函数 `ScriptHolderBase(String side)`

### `void load(String name, String contextTypeName, ResourceManager resourceManager, Map<ResourceLocation, String> scripts, JsonObject config, String key, String... functionNames) throws Exception`
- 作用：
  1. 记下参数；准备数据目录 `./ante/script_data/`（失败只写日志）；
  2. 建上下文：共享引擎、无本地访问、允许建线程、不允许建进程、允许宿主类加载，类查找规则为"加载期全部允许，之后只允许白名单"；IO 为组合文件系统：默认拒绝（只放行语言自身资源），数据目录内（绝对或相对路径规范化后仍在目录下）用默认文件系统；继承环境变量；`SandboxPolicy.TRUSTED`；JS 选项 `nashorn-compat`、最新 ECMAScript、外部对象原型。（`if (false)` 块是旧的宽松配置，不会执行。）
  3. `appendImporter()` 注入工具；
  4. 依次执行每个脚本（内容为 null 时从资源读取），每个文件执行后为每个函数名登记 `fn` 和 `fn + contextTypeName`；
  5. 结束加载期。
- 参数：`scripts` 为 `位置 → 内容（可为 null）`，按 Map 迭代顺序执行；`functionNames` 如 `create`、`render`、`dispose`、`use`。
- 异常：脚本语法或执行错误上抛。

### `void reload(ResourceManager resourceManager) throws Exception`
- `close()` 后用原参数重新 `load`。调用者：F3 调试重载（`mixin/KeyboardHandlerMixin.java:44`）。

### `protected void inject(Class clazz, String method, String alias)`
- 在脚本中定义 `var alias = Java.type('类').method;`（alias 为 null 时用方法名）。

### `protected void inject(Class clazz, String alias)`
- 定义 `var alias = Java.type('类');`（alias 为 null 时用简单类名）。

### `protected void inject(String key, String value)`
- 定义字符串变量 `var key = 'value';`（值未转义，含单引号会出错）。

### `protected void eval(String script)`
- 在上下文中执行一段 JS。

### `protected void appendImporter()`
- 作用：执行 `PRETREATMENT`；注入 `SIDE`、`CONFIG_INFO`（配置 JSON，经两次 `toJson` 作为字符串再 `JSON.parse`）、`MOD_ENV`（`Main` 包名首段，即 `cn`）；注入函数 `include`（`ScriptResourceUtil.includeScript`）、`print`、`asJavaArray`；注入类 `Timing`、`StateTracker`、`CycleTracker`、`RateLimit`、`TextUtil`、`GlobalRegister`、`WrappedEntity`、`ComponentUtil`、`OrderedMap`、`PlacementOrder`、`ShapeSerializer`、`Executors`（= `ScriptExecutors`，覆盖 `importPackage(java.util.concurrent)` 带来的同名类）、`Matrices`、`Matrix4f`、`Vector3f`、`Component`、`Optional`。子类可覆盖追加。

### `private void registerFunction(String name)`
- 若全局有该名字的可执行值，加入 `functions[name]` 并从全局删除（使下一个文件可以再定义同名函数）。

### `Future<?> callFunctionAsync(List<Value> functions, AbstractScriptContext scriptCtx, Runnable finishCallback, Object... args)`
- 作用：失败冷却期内或上下文为空返回 null；否则提交到脚本线程：`TimingUtil.prepareForScript(ctx)`，以参数 `(ctx, ctx.state 或空对象, ctx.getWrapperObject(), ...args)` 依次执行所有函数，成功后调 `finishCallback` 并清除失败状态；异常写错误日志、记失败时间与异常；记录 `ctx.lastExecuteDuration`（纳秒）。
- 返回：任务 Future 或 null。

### `void tryCallFunctionAsync(String function, AbstractScriptContext scriptCtx, Runnable callback, Object... args)`
- 作用：上次调用未完成或上下文已销毁时不做；否则调用该名字的全部函数（没有则空列表，仍会执行回调），并把 Future 存入 `scriptCtx.scriptStatus`。

### `void tryCallRenderFunctionAsync(AbstractScriptContext scriptCtx)`
- 作用：`ScriptContextManager.trackContext` 登记；尚未 `create` 时调用 `create`（完成后 `created = true`），否则调用 `render(…, true)`（完成后 `renderFunctionFinished()`）。调用者：`scripting/train/ScriptedTrainRenderer.java:106`、`:131` 等。

### `void tryCallDisposeFunctionAsync(AbstractScriptContext scriptCtx)`
- 调用 `dispose(…, false)`（完成后 `created = false`），并立即标记 `disposed = true`。

### `void tryCallUseFunctionAsync(AbstractScriptContext scriptCtx, Player player)`
- 调用 `use(…, true, WrappedEntity(player))`（玩家使用装饰物时）。

### `private boolean duringFailTimeout()`
- 上次失败后 4000 毫秒内为真。

### `static <V> V runOnScriptThread(Callable<V> task) throws Exception`
- 作用：在脚本线程执行并等待结果；任务异常解包后上抛。不能在脚本线程内调用（会死锁）。调用者：`scripting/util/ScriptExecutors.java:57`、`:86`。

### `static void resetRunner()`
- 作用：`ScriptExecutors.shutdownAll()`，立即关闭脚本线程并新建一个。

### `void close()`
- 关闭上下文并置空。
