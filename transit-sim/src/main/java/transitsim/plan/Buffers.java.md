# Buffers.java

"实测超时缓冲"（设计编号 D1：把实际超时记录成缓冲）。记录列车在三类区间上实测比计划多用的毫秒数：出库（停车线 → 第一个站台）、折返（一个服务的末站 → 下一个服务的首站）、回库（末站 → 停车线）。生成运用时把这些缓冲加进去，使下一版计划吸收造成晚点的因素，而时刻表本身不变。不可变值对象。由 `transitsim.sim.BufferRecorder` 生成，由 `transitsim.planning.DutyGenerator` 读取，游戏侧 `mtr.data.Depot` 负责保存/收发，网页编辑器 `mtr.client.editor.EditorData`/`EditorActions` 展示和修改。

## 类 `Buffers`（final）

### 常量与字段
- `NONE`：空缓冲单例（无任何条目）。
- `OUT = 0`：出库，停车线 → 站台。
- `TURN = 1`：折返，站台 → 站台。
- `BACK = 2`：回库，站台 → 停车线。
- `private final Map<String, Long> millis`：`key(kind, from, to)` → 毫秒数，按注释每个值都大于 0。构造时复制为不可修改的 `TreeMap`（键有序，保证序列化顺序稳定）。

### `public Buffers(Map<String, Long> millis)`
- 作用：复制传入映射并包装为不可修改的有序映射。
- 参数：`millis` 键为 `key(...)` 格式，值为毫秒。
- 副作用：无（不持有传入映射）。
- 主要调用者：`transitsim/sim/BufferRecorder.java:159`、`BufferRecorder.java:254`、本类 `fromList`。

### `public static String key(int kind, long from, long to)`
- 作用：生成键 `"kind:from:to"`。
- 参数：`kind` 为 `OUT`/`TURN`/`BACK`；`from`、`to` 分别是起点/终点 ID（停车线 ID 或站台 ID，取决于 kind）。
- 返回值：字符串键。
- 主要调用者：`common/src/main/java/mtr/client/editor/EditorActions.java:204`、本类 `get`/`fromList`。

### `public static long[] parse(String key)`
- 作用：把键拆回 `{kind, from, to}`。
- 返回值：长度 3 的 long 数组。
- 副作用：格式不对时抛 `NumberFormatException` 或 `ArrayIndexOutOfBoundsException`（未做校验）。
- 主要调用者：`common/src/main/java/mtr/client/editor/EditorActions.java:201`、本类 `toList`。

### `public long get(int kind, long from, long to)`
- 作用：查某类区间的缓冲毫秒数。
- 返回值：毫秒；没有记录时为 0。

### `public long out(long sidingId, long platformId)` / `turn(long fromPlatformId, long toPlatformId)` / `back(long platformId, long sidingId)`
- 作用：`get(OUT|TURN|BACK, …)` 的便捷写法。
- 返回值：毫秒，无记录为 0。
- 主要调用者：`transitsim/planning/DutyGenerator.java:318`（out）、`DutyGenerator.java:345`、`DutyGenerator.java:361`（turn）。

### `public Map<String, Long> getMillis()`
返回内部不可修改映射。主要调用者：`common/src/main/java/mtr/client/editor/EditorData.java:219`、`mtr/data/Depot.java:724`。

### `public boolean isEmpty()`
是否没有任何条目。

### `equals(Object)` / `hashCode()`
按 `millis` 映射内容比较/求哈希。

### `public List<Long> toList()`
- 作用：用于数据包与存档的扁平序列化：先放条目数，再对每条依次放 kind、from、to、毫秒（每条 4 个 long）。
- 返回值：新的 `ArrayList<Long>`。

### `public static Buffers fromList(List<Long> list)`
- 作用：`toList` 的逆操作。读取条目数后逐条解析；列表长度不足时提前停止（循环条件 `4*i+4 < list.size()`）。
- 参数：`list` 可为 null 或空。
- 返回值：`list` 为空/null 或解析出 0 条时返回 `NONE`，否则新的 `Buffers`。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:423`、`Depot.java:714`。
