# Contingencies.java

一个车厂的故障预案集合（D2）：预案列表（在网页编辑器里像车厂其它设置一样配置）、调度员已启动的预案，以及调度员对单列车临时设定的处置。后两者属于运行状态，存入存档直到调度员结束。二进制读写依赖 `transitsim.io`。游戏侧 `mtr.data.ContingencySystems` 持有并收发/保存，`mtr.packet.ContingencyPackets` 发包，网页编辑器 `mtr.client.editor.EditorContingencies` 编辑；组件 `transitsim.sim.DelayRules` 读取。

## 类 `Contingencies`（final）

### 常量
- `MAX_PLANS = 64`：预案上限（也是"已启动"集合上限）。
- `MAX_ADHOC = 256`：临时设定的列车上限。
- `VERSION = 1`（私有）：格式版本号，写入但读取时忽略其值。

### 字段
- `plans`：`TreeMap<预案ID, Contingency>`，ID 小者在前。
- `active`：`TreeSet<预案ID>`，调度员启动的预案，结束前一直有效。
- `adhoc`：`TreeMap<列车ID, Contingency>`，调度员给单列车的临时处置。

### `public boolean isEmpty()`
三者都空时为 true。

### `public void writePlans(BinaryWriter writer)`
- 作用：写版本号、预案数、各预案（`Contingency.write`）。
- 主要调用者：`common/src/main/java/mtr/data/ContingencySystems.java:139`、`mtr/packet/ContingencyPackets.java:67`、本类 `toBytes`。

### `public void readPlans(BinaryReader reader)`
- 作用：清空并读回预案；读完后从 `active` 删掉已不存在的预案（删除的预案不能处于启动状态）。
- 副作用：预案数 <0 或 > `MAX_PLANS` 抛 `IllegalArgumentException("Too many plans: …")`；`Contingency.read` 也可能抛异常。
- 主要调用者：`common/src/main/java/mtr/data/ContingencySystems.java:163`、本类 `fromBytes`。

### `public void writeState(BinaryWriter writer)`
- 作用：写版本号、`active`（数量 + 各 long）、`adhoc`（数量 + 每条列车 ID 与 `Contingency`）。
- 主要调用者：`common/src/main/java/mtr/data/ContingencySystems.java:140`、`mtr/packet/ContingencyPackets.java:69`。

### `public void readState(BinaryReader reader)`
- 作用：清空并读回 `active`、`adhoc`，再按当前 `plans` 过滤 `active`（因此应在 `readPlans` 之后调用）。
- 副作用：数量越界抛 `IllegalArgumentException`（"Too many on"/"Too many trains"）。
- 主要调用者：`common/src/main/java/mtr/data/ContingencySystems.java:164`。

### `public byte[] toBytes()`
`writePlans` + `writeState` 写到字节数组（存档用）。主要调用者：`common/src/main/java/mtr/data/ContingencySystems.java:191`。

### `public void fromBytes(byte[] bytes)`
- 作用：读 `toBytes` 写出的内容；null/空数组则全部清空。读取出错时打印堆栈（`e.printStackTrace()`）并清空全部，不抛出。
- 主要调用者：`common/src/main/java/mtr/data/ContingencySystems.java:211`。

### `public Contingencies copy()`
通过 `toBytes`/`fromBytes` 深拷贝。主要调用者：`common/src/main/java/mtr/client/editor/EditorContingencies.java:182`。
