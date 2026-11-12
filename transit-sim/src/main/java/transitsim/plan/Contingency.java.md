# Contingency.java

车厂对晚点或卡住列车的一份预案（故障预案，D2）：适用哪些路线、什么条件下列车归入此预案、自动启动还是等调度员启动、对这样的列车怎么处理（在最近可折返站或指定站提前折返、若干站通过不停、由车厂派车接手其余运用）。调度员当场给单列车设定的处置也用同一个类。由 `Contingencies` 持有；`transitsim.sim.DelayRules` 判定；游戏侧 `mtr.packet.DelayAlarms`、网页 `mtr.client.editor.EditorContingencies` 使用。

## 类 `Contingency`（final）

### 常量
- `TURN_NONE = 0`：不折返；`TURN_NEAREST = 1`：在最近可折返站折返；`TURN_STATION = 2`：在 `turnStationId` 指定站折返。
- `AFTER_RESUME = 0`：折返后（空车）从对向站台继续执行运用。
- `AFTER_DEADHEAD = 1`：折返后（空车）回送回车厂。
- `MAX_ROUTES = 64`、`MAX_STATIONS = 64`：路线/跳站集合上限。
- `MAX_NAME = 64`：名称最大字符数（读取时字节数上限为 `MAX_NAME*4`）。
- `MAX_MINUTES = 1440`：分钟阈值上限。
- `VERSION = 1`、`MINUTE = 60000`（私有）。

### 字段
- `id`：预案 ID。
- `name`：名称，默认空串。
- `routeIds`：适用路线；空表示车厂所有路线。
- `lateMinutes`（默认 5）：晚点 ≥ 此分钟数即适用；0 表示不按晚点判断。
- `waitMinutes`（默认 10）：等待前方进路 ≥ 此分钟数即适用；0 表示不按等待判断。
- `deadlock`（默认 true）：处于互相等待的环（死锁）中即适用。
- `manual`：为 true 时只通知调度员、等其启动；否则自动启动。
- `turn`（默认 `TURN_NONE`）、`turnStationId`：折返方式与指定站。
- `afterTurn`（默认 `AFTER_RESUME`）：折返后做什么。
- `skipStationIds`：通过不停的车站。
- `takeover`：由车厂派车接手余下运用。

### `public boolean appliesTo(long routeId)`
`routeIds` 为空或包含该路线时为 true。主要调用者：`transitsim/sim/DelayRules.java:52`。

### `public Reason getReason(long lateMillis, long waitingMillis, boolean deadlocked)`
- 作用：按严重程度依次判断：死锁（`deadlock && deadlocked`）→ 等待（`waitMinutes>0` 且 `waitingMillis ≥ waitMinutes 分钟`）→ 晚点（同理）。
- 参数：`lateMillis` 晚点毫秒；`waitingMillis` 等待进路毫秒；`deadlocked` 是否死锁。
- 返回值：最严重的 `Reason`；都不满足时 null。
- 主要调用者：`transitsim/sim/DelayRules.java:42`、`:56`、`:73`。

### `public void write(BinaryWriter writer)`
按顺序写：版本、id、名称（UTF-8 长度 + 字节）、routeIds、lateMinutes、waitMinutes、deadlock、manual、turn、turnStationId、afterTurn、skipStationIds、takeover。主要调用者：`Contingencies.writePlans`/`writeState`。

### `public static Contingency read(BinaryReader reader)`
- 作用：按 `write` 顺序读回，并约束：名称截到 `MAX_NAME` 字符；分钟数夹到 `[0, MAX_MINUTES]`；`turn`、`afterTurn` 夹到合法范围。
- 副作用：名称字节数 <0 或 > `MAX_NAME*4` 抛 `IllegalArgumentException("Name too long")`；ID 集合超限抛 `IllegalArgumentException("Too many")`。
- 主要调用者：`transitsim/plan/Contingencies.java:51`、`Contingencies.java:86`、本类 `copy`。

### `public Contingency copy()`
写入再读回的深拷贝。

### `equals(Object)` / `hashCode()`
`equals` 比较两者序列化后的字节是否完全相同；`hashCode` 只用 `id`（与 equals 一致：相等者 id 必相同）。

### `public static String limitName(String name)`
null → `""`；超过 `MAX_NAME` 字符时截断。主要调用者：`common/src/main/java/mtr/client/editor/EditorContingencies.java:49`、本类 `read`。

### `private static int clamp(int value, int min, int max)`
夹到区间。

### `private static void writeIds(BinaryWriter, Set<Long>)` / `private static void readIds(BinaryReader, Set<Long> ids, int max)`
写/读 "数量 + 各 long"；读时数量越界抛 `IllegalArgumentException("Too many: …")`，读入的 ID 加到 `ids`（不先清空）。

## 枚举 `Reason`
列车归入预案或被上报的原因：`LATE`（晚点）、`WAITING`（等待进路）、`DEADLOCK`（死锁）。主要使用者：`transitsim/sim/DelayRules.java:45`、`common/src/main/java/mtr/packet/DelayAlarms.java:205`。
