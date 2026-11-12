# SimulateDay.java

命令行工具（`src/tools` 源集，不随模组发布）：不启动游戏，用游戏写入数据库的数据（`SimDataset`）仿真一个已保存世界的一个运营日（S4e），并打印列车做了什么、耗时多少。

参数：数据库，然后可选的日期（`yyyy-mm-dd`，缺省今天）、时区（缺省 `UTC`）、运营日开始小时（缺省 `OperatingDay.DEFAULT_DAY_START_HOUR`），以及标志 `--events`（打印每个事件）、`--check`（端到端检查：每秒检查一次不得违反的规则 `RunInvariants`，并用 `DayRun` 对照时刻表；有违规或死锁时以 1 退出）、`--origins`（各服务从首站出发相对服务时刻与计划的早晚）。

运行方式：`./gradlew -p transit-sim simulateDay --args="…"`（`transit-sim/build.gradle:55`），或 `C:/gtmp/fast/simday.sh`；系统属性见下。使用 `SimDataset`、`SimScope`、`EventSimulator`、`DayRun`、`RunInvariants`、`SimEvent`、`OperatingDay`、`LockNetworks`、`BlockSections`、`TickRunner`。

## 类 SimulateDay（final，不可实例化）

### 常量
- `HOUR`（private）：一小时的毫秒数。

### `static void main(String[] args) throws Exception`
- 作用（按顺序）：
  1. 无参数时打印用法。分离 `--` 标志与位置参数，`OperatingDay.set(时区, 开始小时)`。
  2. `read` 读世界数据；没有则提示先用本版本保存世界；解析失败（旧版本保存）打印 "UNREADABLE …" 并返回。
  3. 系统属性：`mtr.noLinePools=true` 清空所有停车线的线路池（不回本线其他车厂，用于对比）；`mtr.earlyThreshold=毫秒` 改用另一个提前阈值（第 4 轮第 5 步）；`mtr.simRoutes=scopes` 只打印各车厂线路的仿真范围（`printScopes`）后返回；`mtr.simRoutes=id,id` 只仿真这些线路的那部分世界（`SimScope.of`，与编辑器相同）。
  4. 计算运营日开始时刻，打印规模；打印咽喉锁网络统计（`dataset.getLockNetworks().result()`）。
  5. 用 `DayRun.startOfDay`（过夜留线列车停在原处，2026-11-09）建 `EventSimulator`；`mtr.simHours=N` 只仿真前 N 小时（默认 24）。`--check` 时每秒推进并 `invariants.check()`，否则一次推进到底。
  6. 统计事件种类与运行过的列车，可打印每个事件；统计站间平均运行时间与平均停站时间。
  7. `mtr.debugStuck=true` 列出结束时停在站间的列车（`=all` 也列出停在站台/停车线的），包括挡住它的列车（下一轨道或本轨道上的第一个被占单元）、位置（方块坐标）、线路/运用/停车线、运用发车与离开折返轨的时刻，`=all` 时还列出前后几条轨道及停站；最多 400 列。
  8. `--origins`：另跑一次 `DayRun`，把每个服务的首站出发按相对计划分为 early/late/on time/no plan，相对服务时刻提前的另标出，并列出最多 60 个提前的。
  9. 打印死锁与冲突事件；打印构建耗时、仿真耗时、跳过 tick 比例、列车移动 tick 比例。
  10. `--check` 时打印违规数、卡住超过 10 分钟的列车数、死锁数（各列前 30 条），再跑一次 `DayRun` 打印服务数与各类问题数；有违规或死锁则 `System.exit(1)`。
- 副作用：读数据库、打印标准输出、可能退出进程。调用者：Gradle 任务。

### `private static void printScopes(SimDataset dataset)`
对每个车厂（按其线路集合去重），用 `SimScope.of` 求编辑器日仿真的规模：车厂数、停车线数、列车数，以及其中自有车厂（及直接运行这些线路的车厂）数、自有停车线数、自有列车数；按行排序后打印（最小的在前）。

### `private static byte[] read(String path) throws Exception`
从 SQLite 的 `sim_data` 表读 `key = 'world'` 的字节；无记录或表不存在返回 null，其他异常抛出。

### `private static String pos(long node)`
按 Minecraft 打包方式把节点 long 解成 `(x, y, z)` 方块坐标字符串（x 高 26 位、z 中间 26 位、y 低 12 位）。

### `private static String clock(long millis)`
按 `OperatingDay` 时区显示的本地时刻。
