# PathKeyIndex.java

运用路径 key（包括跳站/回库变体的完整 key 文本，往往很长）的紧凑引用。把完整文本做 SHA-256，得到 `"@path:" + 64 位十六进制` 的引用串；进程内用弱引用表在引用串与原文之间双向查找。对应"共享内容按引用存储"的思路：存档和运行记录只存短引用，文本另存一次。被 `transitsim.sim.TrainService`（读取服务时解析）、`transitsim.sim.RunRecord`、游戏侧 `mtr.data.Siding`、`mtr.data.TrainServer` 使用。

## 类 `PathKeyIndex`（final，全部静态）

### 常量与静态字段
- `PREFIX = "@path:"`：引用串前缀，用来区分引用与原文。
- `byText`：`WeakHashMap<原文, 引用串>`；原文不再被引用时条目可被回收。
- `byKey`：`HashMap<引用串, Ref>`；`Ref` 是对原文的弱引用。
- `queue`：`Ref` 的引用队列，用来清理已被回收的条目。

### 内部类 `Ref`（private static final，extends `WeakReference<String>`）
- 字段 `key`：对应的引用串。构造 `Ref(String key, String text)` 把原文注册到 `queue`。

### 内部类 `Entry`（public static final）
- 字段 `key`（引用串）、`text`（原文）。私有构造，仅由 `entry` 创建。

### `private PathKeyIndex()`
禁止实例化。

### `private static String hash(String text)`
- 作用：返回 `PREFIX + SHA-256(UTF-8 文本) 的小写十六进制`。
- 副作用：环境无 SHA-256 时抛 `IllegalStateException`。

### `public static synchronized String reference(String text)`
- 作用：为原文取得引用串，并登记到两张表中。先清理 `queue` 中已回收的 `Ref`。
- 参数：`text` 完整路径 key；null 或空返回 `""`。
- 返回值：引用串。
- 副作用：修改静态表。
- 主要调用者：本类 `resolve`/`entry`；测试 `transit-sim/src/test/java/transitsim/plan/DutyLedgerTest.java:33`。

### `public static synchronized String resolve(String value)`
- 作用：把引用串解析回原文。若 `value` 不以 `PREFIX` 开头，视为原文本身：登记后原样返回（兼容旧数据）。
- 返回值：原文；null 或空返回 `""`。
- 副作用：引用串查不到（从未登记或已被回收）时抛 `IllegalArgumentException("Missing duty path reference …")`。
- 主要调用者：`transitsim/sim/TrainService.java:300`、本类 `entry`/`capture`。

### `public static synchronized Entry entry(String text)`
- 作用：登记原文并返回 `Entry(引用串, 原文)`。持有 `Entry` 即保持原文强引用，防止被回收。
- 返回值：null 或空输入返回 null。
- 主要调用者：`common/src/main/java/mtr/data/TrainServer.java:937`。

### `public static Entry load(String key, String text)`
- 作用：从存档读取一对（引用串, 原文）时校验摘要一致后登记。
- 副作用：`key` 不等于 `hash(text)` 时抛 `IllegalArgumentException("Duty path digest mismatch")`。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:391`、`transitsim/sim/RunRecord.java:123`。

### `public static Entry capture(byte[] service)`
- 作用：从一份序列化的服务（`BinaryKeyed` 格式）中读出 `"trip_path"` 字段，解析成原文后返回其 `Entry`。
- 参数：`service` 服务二进制；长度 0 返回 null。
- 主要调用者：`transitsim/sim/RunRecord.java:218`。
