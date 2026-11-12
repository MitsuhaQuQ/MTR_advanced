# DepotRules.java

车厂的出入库规则（运用补完第 2 步），针对部分线路和星期设定：经由哪个车站出库/回库、比出库路程所需提前多久离开停车线、回哪条停车线、走哪条具名回送路径、班次间等待多久以上就离开站台去等；以及车厂的具名回送路径（回送路径）。规划器按每个运用的首班/末班匹配到的规则来规划；运用自己的例外（`DepotDuties.DayDuty.exceptions`）保持不变。游戏侧 `mtr.data.Depot` 保存与收发（`Depot.java:405`、`:484`、`:565`），`mtr.data.LineDuties`、`mtr.data.RoutePaths` 匹配规则，网页编辑器 `mtr.client.editor.EditorRules` 编辑。

## 类 `DepotRules`（final）

### 字段与常量
- `rules`：规则列表（顺序有意义，同等具体时先者优先）。
- `paths`：具名回送路径列表。
- `MAX_RULES = 256`、`MAX_PATHS = 256`、`MAX_WAYPOINTS = 64`：读取时的数量上限。
- `FORMAT_VERSION = 1`（私有）：格式版本。

### `public boolean isEmpty()`
规则与路径都为空时 true。

### `public Rule match(String lineKey, int weekday)`
- 作用：找某线路某星期的班次所依据的规则。跳过线路不符（`lineKeys` 非空且 `isForLine` 为假）或星期不符（`weekdays != 0` 且该位为 0）的规则；具体程度 rank = (指定线路 ? 2 : 0) + (指定星期 ? 1 : 0)，取 rank 最高者，rank 相同取列表中靠前者。
- 参数：`lineKey` 线路 key（`LineDuties.getLineKey` 的格式）；`weekday` 0 = 周一。
- 返回值：规则；无匹配时 null。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:2358`、`mtr/data/LineDuties.java:136`、`mtr/data/RoutePaths.java:983`。

### `public static boolean isForLine(Rule rule, String lineKey)`
规则的某个线路 key 与 `lineKey` 相同或 `sameLine` 时为 true。主要调用者：本类 `match`。

### `public static boolean sameLine(String key1, String key2)`
- 作用：两个线路 key（`<交通方式>|<各语言线路名>`）是否指同一线路：完全相等，或交通方式相同且至少一种语言的名称（去空白后非空）相同。注释举例：线路叫 "S1" 时设的规则，在路线改名为 "S1各停|S1 Local"（key 为 "TRAIN|s1|s1"）后仍然适用（Q 世界，2026-11-12）。
- 主要调用者：本类 `isForLine`。

### `public DeadheadPath getPath(long id)`
按 ID 线性查找回送路径，找不到返回 null。主要调用者：`common/src/main/java/mtr/data/Depot.java:1236`、`mtr/data/RoutePaths.java:988`。

### `public void write(BinaryWriter writer)`
写：版本字节、规则数 + 各 `Rule`、路径数 + 各 `DeadheadPath`。

### `public void read(BinaryReader reader)`
- 作用：清空后按 `write` 格式读入。
- 副作用：版本不在 `[1, FORMAT_VERSION]` 抛 `IllegalArgumentException("Unknown depot rules version …")`；数量越界抛 `IllegalArgumentException("Too many depot rule entries")`。

### `public byte[] toBytes()`
`write` 到字节数组。主要调用者：`common/src/main/java/mtr/data/Depot.java:565`。

### `public void fromBytes(byte[] bytes)`
- 作用：读 `toBytes` 的结果；null/空或读取出错（`RuntimeException`，打印堆栈）时规则为空。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:405`、`Depot.java:484`。

### `private static int limit(int count, int max)`
数量检查，越界抛 `IllegalArgumentException`。

### `static void writeString(BinaryWriter, String)` / `static String readString(BinaryReader)`（包内）
UTF-8 字节长度 + 字节；读取长度上限 4096 字节。

## 内部类 `Rule`（public static final）
某些线路在某些星期的出入库方式。
- `id`：规则 ID。
- `lineKeys`：适用线路 key 集合；空表示车厂所有线路。
- `weekdays`：适用星期位掩码（bit 0 = 周一）；0 表示每天。读取时与 `DepotDuties.ALL_WEEKDAYS` 按位与。
- `outEntrance`、`backEntrance`：出库/回库经由的车站（出入口）；车厂只有一个时为 0。
- `extraLeadMillis`：比出库路程所需再提前多少毫秒离开停车线（读取时 ≥0）。
- `backSiding`：回库停车线；0 表示回到其列车池下一个需要列车的停车线。
- `outPath`、`backPath`：具名回送路径 ID（`DeadheadPath.id`）；0 表示按离车厂最近车站寻路。
- `waitThresholdMillis`：班次间等待达到多久就离开站台到折返线等待；-1 表示用车站或线网的设置。保存时 +1，读取时 -1 并至少为 -1。
- `private write(BinaryWriter)` / `private static Rule read(BinaryReader)`：按上述字段顺序序列化；线路 key 数上限 1024。
- 创建者：`common/src/main/java/mtr/client/editor/EditorRules.java:72`。

## 内部类 `DeadheadPath`（public static final）
一条具名路径：停车线与站台之间，或两站台之间，按顺序经过列出的节点/站台。
- 常量 `OUT = 0`（出库）、`BACK = 1`（回库）、`SHUNT = 2`（调车/站间）。
- `id`、`name`（默认空串）、`kind`（读取时超出范围按 `OUT`）。
- `from`、`to`：两端的停车线或站台 ID。
- `waypoints`：按顺序经过的节点，以位置编码的 long 表示；站台取其中点（具体编码由宿主定义，未确定）。读取时上限 `MAX_WAYPOINTS`。
- `private write` / `private static read`：顺序为 id、name、kind（字节）、from、to、路点数 + 各 long。
- 创建者：`common/src/main/java/mtr/client/editor/EditorRules.java:94`。
