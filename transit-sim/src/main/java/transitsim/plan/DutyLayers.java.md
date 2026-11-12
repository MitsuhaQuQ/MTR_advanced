# DutyLayers.java

把运用拆成三层、层间按内容引用（运用组）：
1. **Body（运用体）**：一列车一天按顺序跑哪些班次、班次间在哪等待、最后是否留在线上；不含车厂、停车线、星期。
2. **Group（运用组）**：一个车辆池在某张时刻表的若干星期中按顺序跑的所有 Body。
3. **Assignment（分配）**：把某组的某个 Body 分配给某停车线的某槽位、若干星期，并带上依赖车厂的信息：运用编号、列车从哪里来/回哪里去、留在线上后第二天跑哪个运用等。

Body 和 Group 以内容哈希为 key，因此无论多少车厂、停车线、星期使用，同一内容只存一次：两张运用相同的时刻表是同一组；一个车厂以另一车厂的运用为模板时只增加分配。三层可以精确地还原为原来的 `DepotDuties`。对应"共享内容按引用存储"的设计。由游戏侧 `mtr.data.RailwayDataFileSaveModule:718` 拆分、`mtr.data.RailwayDataSqliteStore`（`:399`–`:429`）写入 SQLite 三张表；工具 `transitsim.tools.CheckDutyLayers` 检查往返一致。

## 类 `DutyLayers`（final）

### 字段与常量
- `diagramMode`：对应 `DepotDuties.diagramMode`（含义见 `DepotDuties.java.md`）。
- `bodies`：`TreeMap<key, Body>`。
- `groups`：`TreeMap<key, Group>`。
- `assignments`：按运用顺序（停车线 → 槽位 → 槽位的各星期组）排列的分配。
- `FORMAT_VERSION = 3`（私有）：版本 2 增加 Body 的等待位置和 Assignment 的出库提前量/出入口；版本 3 增加具名回送路径与例外标志。

### `public static DutyLayers of(DepotDuties duties, LongFunction<String> poolOf)`
- 作用：拆分运用。遍历每条停车线每个槽位：没有运用的槽位记一个空分配（`bodyIndex = -1`，保留位置和编号）；每个 `DayDuty` 生成 Body（去重），并按 `池名|weekdays` 归入组的 Body 列表；然后为每个组名生成 `Group`（去重，不同星期但 Body 列表相同的组合并为同一个 key），最后为每项生成 `Assignment`，复制 `DayDuty` 中依赖车厂的字段。
- 参数：`duties` 车厂运用；`poolOf` 停车线 ID → 车辆池名（null 视为空串），同池停车线共享组。
- 返回值：新的 `DutyLayers`。
- 主要调用者：`common/src/main/java/mtr/data/RailwayDataFileSaveModule.java:718`、`transitsim/tools/CheckDutyLayers.java:52`。

### `public DepotDuties toDuties()`
- 作用：按分配顺序还原 `DepotDuties`：按需补齐槽位，设置编号与 `start`；非空分配从组中按 `bodyIndex` 取 Body，复制 Body 与分配的字段生成 `DayDuty`。
- 返回值：还原的运用；某分配引用的组或 Body 不存在时返回 null。
- 主要调用者：`transitsim/tools/CheckDutyLayers.java:53`（游戏侧读取路径未 grep 到直接调用，未确定）。

### `public Set<Long> getGroupKeys(Collection<Long> sidingIds)`
- 返回值：这些停车线的分配引用的组 key 集合（空槽位的 `groupKey` 为 0 也会被加入）。
- 主要调用者：main 代码 grep 未见调用（未确定）。

### `public byte[] toBytes()`
写：版本字节、`diagramMode`、Body 数 + 各 Body、Group 数 + 各 Group、分配数 + 各分配。main 代码中未见调用（SQLite 存储按单项 `toBytes` 分表写入）。

### `public static DutyLayers fromBytes(byte[] bytes)`
- 作用：读 `toBytes` 的结果，按版本兼容读取 Body/Assignment。
- 返回值：读取失败（任何 `RuntimeException`）或版本不认识时返回 null。注释说"或 key 与内容不符"——实际 key 是由读入内容重新计算的，并没有与保存的 key 比较（代码中没有显式校验，未确定注释所指）。

### `private static void writeString(BinaryWriter, String)` / `private static String readString(BinaryReader)`
UTF-8 长度 + 字节；读取长度超过 4096 抛 `IllegalArgumentException("Pool name too long")`。

### `private static int limit(int count)`
数量 <0 或 > 2^20 抛 `IllegalArgumentException("Too many duty entries")`。

### `static long keyOf(Consumer<BinaryWriter> write)`（包内）
- 作用：对 `write` 写出的字节求 SHA-256，取前 8 字节组成 long 作为内容 key。
- 副作用：异常时抛 `IllegalStateException`。

## 内部类 `Body`（public static final）
- 字段：`key`（内容哈希）、`stayOnLine`、`segments`（不可修改副本）、`waits`（每段之后在哪里等待，见 `DepotDuties.DayDuty.waits`；空表示由运行时决定）。
- `Body(boolean stayOnLine, List<Segment> segments)`：`waits` 为空。
- `Body(boolean stayOnLine, List<Segment> segments, List<Integer> waits)`：复制并计算 key。
- `private writeContent(BinaryWriter)`：key 的哈希内容。没有 waits 时与引入 waits 之前完全相同，使旧的 Body 保持同一 key。
- `private writeSegments(BinaryWriter)`：`stayOnLine`、段数、每段 routeId / service / fromStop / toStop+1（VarInt 不能为负，所以 +1）。
- `private write(BinaryWriter)`：段 + waits 数量 + 每个 waits 字节（总是写出 waits）。
- `public byte[] toBytes()`：单独保存一个 Body（如 Body 表中）。主要调用者：`common/src/main/java/mtr/data/RailwayDataSqliteStore.java:399`。
- `private static Body read(BinaryReader, int version)`：段数上限 `DepotDuties.MAX_SEGMENTS`；版本 ≥2 才读 waits。

## 内部类 `Group`（public static final）
- 字段：`key`、`pool`（车辆池名）、`bodies`（Body key 有序列表，不可修改）。
- `Group(String pool, List<Long> bodies)`：复制并按 `write` 内容计算 key。
- `private write`：池名 + Body 数 + 各 key。
- `public byte[] toBytes()`：主要调用者：`common/src/main/java/mtr/data/RailwayDataSqliteStore.java:411`。
- `private static Group read(BinaryReader)`。

## 内部类 `Assignment`（public static final）
某停车线某槽位在若干星期跑某组的某个 Body。
- `sidingId`、`slotIndex`：停车线与槽位。
- `dutyId`：槽位的运用编号（`DepotDuties.Slot.id`）；`start`：列车从哪里出发（`Slot.start`，取值含义见 `DepotDuties`）。
- `weekdays`：星期位掩码。
- `groupKey`、`bodyIndex`：引用的组与组内 Body 下标；`bodyIndex < 0` 表示空槽位。
- `nextSlot`（默认 -1）、`nextDutyId`：留在线上后第二天跑的槽位/运用。
- `returnSidingId`：回库停车线。
- `fromOvernight`：由前一天过夜的（其它停车线的）列车接手。
- `outLeadMillis`（默认 -1）、`outEntrance`、`backEntrance`：出库提前量与出入口（见 `DayDuty.outLeadMillis`）。
- `outPathId`、`backPathId`：具名回送路径；`exceptions`：哪些依赖车厂的设置是本运用单独设定的（`DayDuty.exceptions` 位掩码）。
- `isEmptySlot()`：`bodyIndex < 0`。
- `private write(BinaryWriter)`：字段顺序如上，`bodyIndex`、`nextSlot`、`outLeadMillis` 各 +1 保存。
- `public byte[] toBytes()`：主要调用者：`common/src/main/java/mtr/data/RailwayDataSqliteStore.java:429`。
- `public long getBodyKey(DutyLayers layers)`：引用的 Body key；空槽位或组不存在/越界时 0。主要调用者：`common/src/main/java/mtr/data/RailwayDataSqliteStore.java:428`。
- `private static Assignment read(BinaryReader, int version)`：槽位号、`nextSlot` 夹到 `DepotDuties.MAX_SLOTS` 范围内；`weekdays` 与 `ALL_WEEKDAYS` 按位与；版本 ≥2 读出库提前量与出入口，≥3 读回送路径与例外（与 `EXCEPTION_ALL` 按位与）。
