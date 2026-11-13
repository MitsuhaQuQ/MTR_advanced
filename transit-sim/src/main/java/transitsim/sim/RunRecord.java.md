# RunRecord.java

游戏保存的运行中列车和车厂状态（G0，契约文档 6.4）：仿真从存档续跑、或长时间停机后恢复所需的一切。

在整体中的位置：游戏侧 `LiveSimulation.java:343`、`SimExport.java:68` 构造并 `toBytes`；`TrainServer.java:947` 构造 `TrainRecord`；`Depot` 存取车厂部分；组件 `TickRunner.java:605` 生成记录、`TickRunner.restore` 使用；`SimDataset.java:178` 读入（`fromBytes`）；`EventSimulator.resume` 续跑。使用 plan 包的 `DutyLedger`、`PathKeyIndex`（共享的运用内容与路径键按哈希引用存储，见记忆"Shared content by reference"）。

## 类 RunRecord（final）

### 字段
- `time`（long）：记录时刻。
- `dutyLedger`（private TreeMap<String, Object>）：读档时载入的运用账本条目与路径键索引条目（键 → `DutyLedger.Entry` / `PathKeyIndex.Entry`）。代码中只写不读；推测其作用是持有强引用，使这些全局弱引用表中的条目在列车服务按键查找时不被回收（未确定）。
- `trains`（List<TrainRecord>）：各列车。
- `lastDeployedTrips`（Map<Long, Map<Long, Long>>）：车厂（以其首个停车线为键）→ 首线路 → 最后发出的服务。
- `dutyRunDays`（Map<Long, Map<String, Long>>）：车厂 → 槽位键 → 最后运行的运营日。
- `lastDeployedMillis`（Map<Long, Long>）、`deployIndices`（Map<Long, Integer>）：按频率发车的车厂上次发车时刻与下一个停车线下标（G1）。
- `sheds`（Map<Long, List<Long>>）：每条停车线车库中存放的列车，最早的在前（P2-C）。
- `lateServices`（Map<Long, List<String>>，版本 7）：每个车厂的晚点改挂登记，同 `LateServices.toList()`。
- `takeovers`（Map<Long, List<String>>）：每个车厂由备用车接管的运用（D5），同 `DepotDispatcher.takeovers`。

### 常量
- `VERSION = 7`（private）：版本 2 增加按频率发车的车厂，3 增加车库，4 增加备用车接管（D5），5 增加许可终点和运行段（见 `TrainRecord`），6 增加运用账本表，7 增加晚点改挂登记；旧版本读时缺少这些部分。

### 构造函数 `RunRecord(long time)`
调用者：`LiveSimulation.java:343`、`SimExport.java:68`、`TickRunner.java:605`。

### `byte[] toBytes()`
- 作用：序列化：版本、时刻；账本表（收集所有列车 `ledger` 与 `pathReference` 的 键 → 文本，去重）；列车；以及上述各车厂表。
- 调用者：`LiveSimulation.java:490`、`SimExport.java:36`。

### `static RunRecord fromBytes(byte[] bytes)`
- 作用：读回；版本不在 [1, VERSION] 抛 `IllegalArgumentException("Unknown run record version …")`；版本 ≥ 6 时读账本表（条目数超出 [0, 10⁶] 抛异常；键以 `PathKeyIndex.PREFIX` 开头的用 `PathKeyIndex.load`，否则 `DutyLedger.load`，两者都会校验摘要）；按版本读其余部分。
- 调用者：`SimDataset.java:178`、测试。

### `private static void writeString(BinaryWriter writer, String text)` / `private static String readString(BinaryReader reader)`
UTF-8 字符串（varint 长度 + 字节）。

## 内部类 TrainRecord（public static final）
一列车，含游戏列车保存的字段及其服务（`TrainService.write` 的格式）。
- `sidingId`、`trainId`、`speed`、`railProgress`、`elapsedDwellTicks`、`nextStoppingIndex`、`nextPlatformIndex`、`reversed`、`onRoute`：同游戏列车字段。
- `service`（byte[]）：服务状态字节（`BinaryKeyed` 格式）。
- `ledger`（private `DutyLedger.Entry`）、`pathReference`（private `PathKeyIndex.Entry`）：从 `service` 中抓取的运用账本与路径键引用（构造时 `capture`），存档时写入账本表。
- `cruiseCap`（float）：出发时的巡航上限（见 `TrainRules.getCruiseCap`），游戏尚未保存时为 NaN。注释说明：从当前位置重算时，对停在被占轨道前的列车会得到不同值，从而到站时刻不同。
- `authorityEnd`（double）：行车许可沿路径到达的位置（运行层 5.5，版本 5），或 NaN。
- `segment`（`RunSegment.Spec`）：所在运行段（版本 5），或 null；续跑时列车沿同一曲线继续。

方法：
- 构造函数（11 参数）：无许可与运行段的形式。
- 构造函数（13 参数）：全部字段；并从 `service` 抓取账本与路径键引用。调用者：`TrainServer.java:947`、`read`。
- `private void write(BinaryWriter writer)`：按字段顺序写出（浮点数按位），然后许可终点与可选运行段。调用者：`RunRecord.toBytes`。
- `private static TrainRecord read(BinaryReader reader, int version)`：读回；版本 < 5 时许可为 NaN、运行段为 null。调用者：`RunRecord.fromBytes`。
