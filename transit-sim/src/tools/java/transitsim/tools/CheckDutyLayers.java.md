# CheckDutyLayers.java

命令行工具（不随模组发布，位于 `src/tools` 单独源集）：把一个世界中每个车厂的运用拆成运用分层（`DutyLayers`）再还原，检查是否能精确还原，以及各车厂之间有多少内容相同（运用组第 1 步，在真实世界上测量）。

运行方式：`./gradlew -p transit-sim checkDutyLayers --args="path/to/mtr.db"`（`transit-sim/build.gradle:50` 注册的任务）。依赖 plan 包的 `DepotDuties`、`DutyLayers` 与 `SimDataset`，用 JDBC 读 SQLite。

## 类 CheckDutyLayers（final）

### `static void main(String[] args) throws Exception`
- 作用：
  1. 从数据库 `args[0]` 的 `sim_data` 表读 `key = 'world'` 的数据，没有则打印 "No simulation data in …" 并返回。
  2. `SimDataset.fromBytes` 解析，建立停车线 → 池键表。
  3. 对每个有运用的车厂：`DutyLayers.of(duties, 池键)` 拆层，`toDuties()` 还原并与原字节比较（记录不一致的车厂 id）；累计分配行数、原运用字节数、共享的 body 与 group（按 id 合并），以及只含分配的层的字节数。
  4. 统计星期组合（各掩码对应运用行数）、留线过夜的行数、接续到另一运用的行数（为按时刻表编制做准备，第 2 步）。
  5. 打印四行汇总：星期组合与过夜情况；有运用的车厂数与精确还原数（及不一致列表）；分配行数、body 数、group 数；原保存大小与分层后（共享部分 + 分配部分）的大小（kB）。
- 参数：`args[0]` SQLite 数据库路径。
- 副作用：读数据库、打印到标准输出。调用者：Gradle 任务（无代码调用者）。
