# DutyLedger.java

完整运用接续账（一列车一天的运用 `DepotDuties.DayDuty`）的不可变版本库。运行中的列车只保存引用（`"@duty:" + SHA-256`）和当前段游标，而不是整份账本；相同内容只存一份（"共享内容按引用存储"）。与 `PathKeyIndex` 结构相同，但缓存的是解析后的 `Entry`（含 `DayDuty`）。被 `transitsim.sim.TrainService`（读写服务状态）、`transitsim.sim.RunRecord`、游戏侧 `mtr.data.Siding` 使用。

## 类 `DutyLedger`（final，全部静态）

### 常量与静态字段
- `PREFIX = "@duty:"`：引用串前缀。
- `entries`：引用串 → `Ref`（对 `Entry` 的弱引用）。
- `queue`：回收队列。

### 内部类 `Ref`（private static final，extends `WeakReference<Entry>`）
字段 `key`；构造时以 `entry.key` 登记并关联 `queue`。

### 内部类 `Entry`（public static final）
- 字段：`key`（引用串）、`text`（账本文本，`DayDuty.toText()` 格式）、`day`（解析后的 `DayDuty`，私有）；以及 `segmentAt` 的缓存：`clockKey`（缓存对应的运营日基准毫秒）、`starts`（各段开始绝对毫秒）、`ordered`（`starts` 是否单调不减）。
- `public DepotDuties.DayDuty day()`：返回内部快照；调用方不得修改。
- `public synchronized int segmentAt(long dayStart, long now, int first)`
  - 作用：返回当前时间 `now` 下列车应在跑的段下标——最后一个开始时间 ≤ now 的段，不早于 `first`。
  - 参数：`dayStart` 运营日开始（绝对毫秒）；`now` 当前绝对毫秒；`first` 游标下限（会被夹到 `[0, 段数-1]`）。
  - 返回值：段下标；无段时 -1；没有段开始时返回 `first`。
  - 副作用：`dayStart` 换算出的基准变化时重算 `starts` 缓存。段开始时间无序时退化为倒序线性扫描，否则二分。
  - 主要调用者：`transitsim/sim/TrainService.java:1199`。
- 私有构造仅在本类中使用。

### `private static void clean()`
清除已被回收的 `Ref`。

### `private static String key(String text)`
`PREFIX + SHA-256(text)` 十六进制；无 SHA-256 抛 `IllegalStateException`。

### `public static Entry intern(DepotDuties.DayDuty day)`
- 作用：把一份 `DayDuty` 转成文本后登记/取得 `Entry`。`day` 为 null 返回 null。
- 主要调用者：`transitsim/sim/TrainService.java:1918`。

### `public static synchronized Entry read(String value)`
- 作用：读取引用串或账本文本。先 `clean()`；若已缓存直接返回；`value` 为引用串但缓存中没有时抛 `IllegalArgumentException("Missing duty ledger …")`；否则用 `DayDuty.fromText` 解析并缓存。
- 返回值：`Entry`；`value` 为空或解析失败返回 null。
- 主要调用者：`transitsim/sim/TrainService.java:338`、本类 `load`/`capture`。

### `public static Entry load(String key, String text)`
- 作用：从存档读取一对（引用串, 文本），校验摘要后 `read(text)`。
- 副作用：摘要不符抛 `IllegalArgumentException("Duty ledger digest mismatch")`。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:391`、`transitsim/sim/RunRecord.java:123`（均按前缀在 `PathKeyIndex.load` 与本方法间选择）。

### `public static Entry capture(byte[] service)`
- 作用：从序列化服务（`BinaryKeyed`）读 `"duty_day"` 字段并 `read`。旧存档在此字段内嵌整份账本，新存档为引用串，二者都能读。
- 返回值：长度 0 返回 null。
- 主要调用者：`transitsim/sim/RunRecord.java:217`。
