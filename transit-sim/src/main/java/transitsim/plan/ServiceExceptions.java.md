# ServiceExceptions.java

单班在若干停站上与其所属表不同的"服务例外"，在高级编辑器中设置：到某站的自定义运行时间、自定义停站时间、路线停而本班通过（或反之）、在此待避。按表保存，先按班次再按路线中的停站序号（`RouteTimetable.exceptions`）。所有计算计划时间的地方都经过 `applyTo`，因此列车、PIDS 与冲突检查结果一致。在按运用运行的车厂里每个运用有自己的路径（见 `DutyShapes`），单班的通过/加停就是列车真正停的地方；其它情况只改变时间，列车仍按路径停车。被 `StopRules`、`StopTimes`、`RouteTimetable`、`TimetableBinary` 及游戏侧 `mtr.data.Route`、网页编辑器使用。

## 接口 `ServiceExceptions`（常量 + 静态方法 + 嵌套类）

### 常量
- `MAX_EXCEPTIONS = 16384`：一张表最多保存的例外数（按"班次 × 站"计）。
- `ADDED_STOP_MILLIS = 30000`：本班在路线通过的站加停时，若无自定义停站时间，停 30 秒。使用者：`transitsim/plan/RouteTimetable.java:425`。
- `MAX_SECONDS = RouteTimetable.MAX_STOP_SECONDS`：秒数上限。
- `SAVED_INTS = 6`：每条例外保存为 6 个 int（班次、站、四个值）。

### 嵌套类 `StopException`
一班在一站的差异，每个值 -1 表示按表。
- `runSeconds`：从上一站出发到本站到达的秒数。
- `dwellSeconds`：本站停站秒数。
- `pass`：1 通过，0 停车。
- `overtake`：1 在此待避，0 不待避。
- `isEmpty()`：四个值都 < 0。
- `copy()`：复制。
- `private clamp()`：秒数夹到 `[-1, MAX_SECONDS]`，`pass`/`overtake` 夹到 `[-1, 1]`。
- 创建者：`common/src/main/java/mtr/client/editor/EditorActions.java:1038`、`mtr/client/editor/EditorTimes.java:259`。

### `static void applyTo(RouteTimetable.StopPlan stopPlan, StopException arrivalException, StopException departureException)`
- 作用：把例外写入某站的计划：到达例外的 `runSeconds ≥ 0` → `exceptionRunMillis`；出发例外的 `dwellSeconds ≥ 0` → `exceptionDwellMillis`，`pass` 原样 → `exceptionPass`，`overtake ≥ 0` → `overtake`。一站可能同时是上一路线终点和下一路线起点，因此到达与出发各取自己路线的例外。
- 参数：两个例外均可为 null。
- 副作用：修改 `stopPlan`。
- 主要调用者：`transitsim/plan/StopTimes.java:353`、本接口另一 `applyTo`。

### `static void applyTo(List<RouteTimetable.StopPlan> stopPlans, Map<Integer, StopException> stopExceptions)`
- 作用：对单条路线逐站套用同一站的例外（到达、出发都用它）。`stopExceptions` 为 null 时不做事。
- 主要调用者：`transitsim/plan/StopRules.java:47`。

### `static void sanitize(RouteTimetable table)`
- 作用：删除表中已不存在的班次的例外；对每条例外 `clamp`，删除空例外和负站号；删除空映射。若总数超过 `MAX_EXCEPTIONS`，按班次号从小到大保留（较早的班次最可能被编辑），超出部分整班删除。
- 主要调用者：`transitsim/plan/RouteTimetable.java:181`。

### `static void remapStops(RouteTimetable timetable, List<? extends PlanStop> newStops, List<? extends PlanStop> oldStops)`
- 作用：路线停站变化时，按站台 ID 把旧站号映射到新站号（每个新站只匹配一次），保留能匹配上的例外，其余丢弃。作用于所有表。
- 主要调用者：`common/src/main/java/mtr/data/Route.java:694`。

### `static void limitStops(RouteTimetable table, int stopCount)`
删除站号 ≥ `stopCount` 的例外及空映射。主要调用者：`common/src/main/java/mtr/data/Route.java:269`、`Route.java:1124`。

### `static void copy(RouteTimetable from, RouteTimetable to)`
深拷贝例外（覆盖 `to` 原有的）。主要调用者：`common/src/main/java/mtr/data/Route.java:913`。

### `static int[] toInts(RouteTimetable table)`
- 作用：按班次、站号排序输出扁平数组（每条 6 个 int）。顺序固定，运用规划的 key 依赖"相同例外总是写成相同内容"（第 4 轮第 4 步）。
- 主要调用者：`common/src/main/java/mtr/data/Route.java:466`、本接口 `write`。

### `static void fromInts(List<Integer> values, RouteTimetable table)`
- 作用：清空并从扁平列表读回，最多 `MAX_EXCEPTIONS` 条；不足 6 个的尾部忽略。不做 `clamp`（由 `sanitize` 负责）。
- 主要调用者：`common/src/main/java/mtr/data/Route.java:462`、本接口 `read`。

### `static void write(BinaryWriter packet, RouteTimetable table)`
写 int 数量 + 各 int。主要调用者：`transitsim/plan/TimetableBinary.java:37`、`common/src/main/java/mtr/data/Route.java:1226`。

### `static void read(BinaryReader packet, RouteTimetable table)`
- 作用：读回 `write` 的格式；即使超过上限也读完全部数值，保证数据包后续内容对齐，只保留上限内的部分。
- 主要调用者：`transitsim/plan/TimetableBinary.java:105`、`common/src/main/java/mtr/data/Route.java:1189`。
