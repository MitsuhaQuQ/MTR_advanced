# DepotDuties.java

车厂列车的运用（运用），来自高级编辑器（设计文档 6.12，E 块）。每条停车线有一排槽位（库位），一个槽位一列车；每个槽位对每组"跑同样时刻表的星期"有一个当日运用：按顺序跑哪些班次、最后回库还是留在线上。还记录停车线所属车辆池、因停车线消失而无处安放的槽位及搬移记录（运用补完第 5 步）。注释说 E1 阶段只保存与编辑；车厂改为按运用运行后才真正生效。序列化格式版本 7。游戏侧 `mtr.data.Depot` 持有、保存、收发（`Depot.java:473`、`:556`、`:623`、`:809`、`:923`），网页编辑器 `mtr.client.editor.EditorDuties` 编辑，`mtr.data.LineDuties`、`PidsSchedules`；组件 `transitsim.planning.DutyGenerator` 生成，`transitsim.sim.TrainService`、`DepotBoard`、`DutyLedger`、`DutyShapes`、`DutyLayers` 使用。

## 类 `DepotDuties`（final）

### 字段
- `diagramMode`：车厂是否按运用图运行（按运用图），否则在线挑选班次（在线预约）。
- `sidings`：`TreeMap<停车线 ID, 槽位列表>`。
- `sidingPools`：有槽位的停车线上次放置槽位时所属的车辆池（运用补完第 5 步）。运用属于池，停车线只是列车出发处；停车线消失或换车型时，其槽位交还给池。
- `unplaced`：`池名 → 槽位列表`，池在车厂里已没有停车线时暂存，不运行，等该池的停车线回来。
- `moves`：被移到同池其它停车线或暂存的槽位记录（最新在后），供编辑器提示，直到玩家修改运用。

### 常量
- `MAX_MOVES = 64`、`MAX_SLOTS = 256`、`MAX_DAYS = 7`、`MAX_SEGMENTS = 4096`。
- `ALL_WEEKDAYS = 0x7F`：七天位掩码。
- `FORMAT_VERSION = 7`（私有）：数据以版本字节开头。最早的数据以模式布尔值开头，读作 0 或 1，因此能区分。

### `public boolean isEmpty()`
所有停车线槽位列表都为空时 true（不看 `unplaced`）。

### `public boolean rehome(Map<Long, String> livePools)`
- 作用：把消失或换池的停车线的槽位交给其池（运用补完第 5 步）：
  - 停车线仍在且池未变（或从未记录池）：不动。
  - 停车线仍在但换了车型、且车厂里已没有原车型的停车线：视为这条停车线的车换了，运用跟着它（用户 2026-11-09），只更新记录的池。
  - 否则移除该停车线的槽位，交给同池中槽位最少的停车线（`place`），没有则暂存到 `unplaced`。
  - 再把 `unplaced` 中已有停车线的池的槽位放回。
  - 最后用当前池更新 `sidingPools`，并只保留有槽位的停车线。
- 参数：`livePools` 车厂当前每条停车线及其池。
- 返回值：是否有槽位被移动。
- 副作用：修改 `sidings`、`sidingPools`、`unplaced`、`moves`。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:809`。

### `private void place(String pool, List<Slot> slots, long fromSidingId, Map<Long, String> livePools)`
逐个槽位找 `findSiding`，有则加入并记录池，否则放入 `unplaced`；每个都记一条 `Move`（目标为 0 表示暂存）。

### `private Long findSiding(String pool, Map<Long, String> livePools)`
返回该池中槽位最少且未满 `MAX_SLOTS` 的停车线（相同时 ID 小者），没有则 null。

### `private void addMove(Move move)`
追加移动记录，超过 `MAX_MOVES` 删除最旧的。

### `public List<Slot> getSlots(long sidingId)`
返回该停车线的槽位，没有时返回不可修改空列表。主要调用者：`common/src/main/java/mtr/data/Depot.java:1020`、`Depot.java:1721`、`mtr/client/editor/EditorDuties.java:122`。

### `public void setSlot(long sidingId, int slotCount, int index, Slot slot)`
- 作用：修改一条停车线的槽位：先把数量设为 `slotCount`（夹到 `[0, MAX_SLOTS]`，多删少补，新增槽位用 `defaultId` 编号）；`index` 在范围内且 `slot` 非 null 时替换该位置（编号为 0 时补默认编号）；槽位为空时移除该停车线。
- 副作用：清空 `moves`（玩家改了运用，就已看到搬移结果）。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:923`、`mtr/client/editor/EditorDuties.java:123`、`EditorDuties.java:128`。

### `public void write(BinaryWriter packet)`
写：版本、`diagramMode`、停车线数 + 每条（ID、槽位数、各 `Slot.write`）；格式 7 起再写 `sidingPools`、`unplaced`、`moves`（字符串用 `DepotRules.writeString`）。主要调用者：`common/src/main/java/mtr/data/Depot.java:623`、`Depot.java:817`。

### `public long[] findSlot(long id)`
- 返回值：运用编号为 `id` 的槽位 `{停车线 ID, 槽位下标}`；`id` 为 0 或找不到时 null。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:1935`、`transitsim/sim/DepotBoard.java:192`。

### `public static long defaultId(long sidingId, int index)`
- 作用：给没有编号的槽位（编号出现之前保存的数据）生成确定性编号：对 (停车线, 下标) 做 splitmix 风格的混合；结果为 0 时返回 1。相同停车线与下标总得到相同编号，直到带编号保存（P2-D）。
- 主要调用者：`transitsim/planning/DutyGenerator.java:523`、`common/src/main/java/mtr/data/LineDuties.java:873`、`mtr/client/editor/EditorDuties.java:228`。

### `public void read(BinaryReader packet)`
- 作用：首字节在 `[2, FORMAT_VERSION]` 视为版本号，否则视为版本 1 的模式布尔值。读停车线与槽位（编号为 0 时补 `defaultId`，空槽位列表不保留）；版本 ≥7 再读池、暂存槽位、移动记录。
- 副作用：覆盖全部字段；数量越界抛 `IllegalArgumentException("Too many duty entries")`。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:473`、本类 `fromBytes`。

### `public byte[] toBytes()` / `public void fromBytes(byte[] bytes)`
字节数组形式；`fromBytes` 读取出错时打印堆栈并清空全部（`diagramMode = false`）。主要调用者：`common/src/main/java/mtr/data/Depot.java:556`、`mtr/advanced/SandboxRuns.java:206`、`mtr/client/editor/EditorSimulation.java:79`。

### `private static int limit(int count, int max)`
越界抛 `IllegalArgumentException`。

## 内部类 `Move`（public static final）
一个槽位被交给同池另一停车线，或暂存（`toSidingId = 0`）；从暂存放回时 `fromSidingId = 0`。字段 `slotId`（运用编号）、`fromSidingId`、`toSidingId`、`pool`。编辑器在 `common/src/main/java/mtr/client/editor/EditorDuties.java:43` 读取。

## 内部类 `Slot`（public static final）
一条停车线的一列车，每组星期一个当日运用，各组星期不重叠。
- `id`：运用编号，不随停车线和位置改变（P2-D）；0 表示尚未编号。
- `start`（默认 `START_PREFER`）：列车从哪里来（S8）：`START_PREFER = 0` 优先本停车线，否则同池其它有车的停车线；`START_ONLY = 1` 只用本停车线；`START_ANY = 2` 同池中有车且出库到首站最快的停车线。
- `days`：当日运用列表。
- `public DayDuty getDuty(int weekday)`：返回包含该星期（0 = 周一）的第一个运用，没有为 null。主要调用者：`common/src/main/java/mtr/data/Depot.java:3155`、`mtr/data/PidsSchedules.java:43`、`mtr/client/editor/EditorDuties.java:236`。
- `public void write(BinaryWriter)`：编号、start、天数，每天：weekdays、stayOnLine、nextSlot+1、nextDutyId、returnSidingId、fromOvernight、段数与各段（routeId、service、fromStop、toStop+1），再 `writeItems`。
- `public static Slot read(BinaryReader)`：按当前版本读。主要调用者：`common/src/main/java/mtr/data/Depot.java:923`。
- `public static Slot read(BinaryReader, int version)`：版本 1 没有 nextSlot；2 之前没有编号；3 之前没有 start；5 之前没有等待位置与出库时刻（注释写 4，代码在 `version >= 5` 才读 items，`>= 4` 读 start）。一天只归属第一个包含它的运用（后面的运用去掉已用的星期），星期为空的运用丢弃。

## 内部类 `DayDuty`（public static final）
列车在 `weekdays`（bit 0 = 周一）这些天做什么。
- `weekdays`。
- `stayOnLine`：最后一段之后留在线上过夜，第二天的运用从那里接着跑。
- `nextSlot`（默认 -1）：留在线上的列车第二天跑同一停车线哪个槽位；-1 为自己。
- `nextDutyId`：留在线上的列车第二天按编号跑哪个运用（可属于本车厂其它停车线）；0 表示按 `nextSlot`（P2-D）。
- `returnSidingId`：最后一段后回哪条停车线（A 出 B 入），须为同池；0 表示运行时按池需要（P1-B）。
- `fromOvernight`：由其它停车线运用中留在线上过夜的列车来跑，因此没有列车从停车线出发，从线上开始。
- `segments`：各段（`Segment`）。
- `waits`：除最后一段外每段之后列车在哪里等下一段（`WAIT_*`，运用补完第 1 步）；空或 `WAIT_ANY` 表示运行时自行决定。
- `outLeadMillis`（默认 -1）：首班从首站出发前多久离开停车线；-1 为运行时自己的值（出库路程时间加余量）。
- `outEntrance`、`backEntrance`：出库/回库经由车站（出入口），车厂接入线路不止一处时使用；0 表示照旧寻路。
- `outPathId`、`backPathId`：具名回送路径（`DepotRules.DeadheadPath.id`）；0 为按离车厂最近车站寻路（运用补完第 2 步）。
- `exceptions`：哪些设置是本运用单独设定的（`EXCEPTION_*` 位掩码），重新规划时保留；其余跟随车厂规则。

常量：
- `EXCEPTION_OUT_LEAD = 1`、`EXCEPTION_OUT_PATH = 2`、`EXCEPTION_BACK_SIDING = 4`、`EXCEPTION_BACK_PATH = 8`、`EXCEPTION_WAITS = 16`、`EXCEPTION_ENTRANCES = 32`、`EXCEPTION_ALL = 63`。
- `WAIT_ANY = 0`：运行时决定（直通服务的各部分之间也是）；`WAIT_PLATFORM = 1`：在本段终点站台等（下一段从此出发）；`WAIT_TURN_BACK_RAIL = 2`：在站台外的折返线上等（混合折返），让出站台；`WAIT_CHANGE_PLATFORM = 3`：在到达站台等，然后换到同站另一站台跑下一段；`MAX_WAIT_KIND = 3`（私有）。

方法：
- `public int getWait(int segment)`：该段之后的等待方式，越界为 `WAIT_ANY`。主要调用者：`transitsim/sim/TrainService.java:1429`。
- `public boolean hasItems()`：是否含有比旧运用更多的信息（出库提前量、出入口、回送路径、例外、非 ANY 的等待）。主要调用者：`common/src/main/java/mtr/client/editor/EditorDuties.java:83`、本类 `toText`。
- `private writeItems(BinaryWriter)` / `private readItems(BinaryReader, int version)`：waits（数量 + 字节；读时非法值改为 `WAIT_ANY`）、outLeadMillis+1、出入口；版本 ≥6 还有回送路径与例外。
- `public void keepExceptions(DayDuty other)`：保留另一运用中单独设定的项，覆盖本运用对应字段；等待位置只在两者段数（waits 数）相同时保留，否则清除该例外位。main 代码 grep 未见调用（未确定）。
- `public boolean sameAs(DayDuty other)`：除星期、nextSlot、nextDutyId、returnSidingId、fromOvernight 外全部相同。主要调用者：`transitsim/planning/DutyGenerator.java:536`。
- `public DayDuty copy()`：副本，共享不可变的 `Segment` 对象。主要调用者：`transitsim/sim/TrainService.java:1957`、`common/src/main/java/mtr/data/LineDuties.java:854`。
- `public String toText()`：文本形式，供列车保存时随身带着所取的运用（解耦文档 D 步）。格式：`weekdays,stay,nextSlot,nextDutyId,returnSidingId,fromOvernight[,outLead,outEntrance,backEntrance,w<各等待数字>,outPath,backPath,exceptions]` 之后每段 `;routeId,service,fromStop,toStop`。只有 `hasItems()` 时才写方括号部分，使旧运用写出不变。主要调用者：`transitsim/plan/DutyLedger.java:53`。
- `public static DayDuty fromText(String text)`：`toText` 的逆操作，按头部字段数兼容旧格式（≥6、≥10、≥13）；空或解析失败返回 null。主要调用者：`transitsim/plan/DutyLedger.java:61`。

## 内部类 `Segment`（public static final）
一条路线的一个班次，从某站跑到某站；中途加入/离开时按路线的直通链接（`RouteTimetable.ServiceLink`）。
- `routeId`。
- `service`：班次，一天中的 UTC 毫秒，与 `RouteTimetable.services` 相同。
- `fromStop`：加入站，0 为首站（构造时至少 0）。
- `toStop`：离开站，-1 为末站（构造时至少 -1）。
- `equals`/`hashCode`：按四个字段。
- 创建者：`common/src/main/java/mtr/client/editor/EditorDuties.java:205`、本类 `Slot.read`、`DayDuty.fromText`、`DutyLayers.Body.read`。
