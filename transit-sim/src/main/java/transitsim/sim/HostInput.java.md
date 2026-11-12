# HostInput.java

宿主给仿真的输入，带时间和宿主给出的序号，使同一时刻的输入按宿主的顺序生效（契约文档 2.1 规则 5）。由 `EventSimulator.submit` 接收，在 `EventSimulator.apply` 中按子类分别处理。游戏侧构造处：`LiveSimulation.java:159`（Skip）、`:172`（TurnBack）、`:185`（HandOver）、`:198`（Dispatch）、`:259`（TakeOver）、`:264`（Release）；`SimInputs.java:80`（ExternalTrain）、`:112`（ExternalRun）。其余子类主要在测试与 `DayRun` 的 `inputs` 中使用。

## 抽象类 HostInput（实现 `Comparable<HostInput>`）

### 字段
- `time`（long）：生效时刻（毫秒）。
- `sequence`（long）：宿主给出的序号。

### 私有构造函数 `HostInput(long time, long sequence)`
只能由内部子类调用。

### `int compareTo(HostInput other)`
先按时刻，再按序号。用于 `EventSimulator.inputs` 优先队列与快照排序。

## 子类（均为 public static final，构造函数第一、二个参数为 time、sequence）

### Hold
让列车在下一停站开门停住，直到 `Resume`。字段 `trainId`、`reason`（原因）。生效时设宿主扣车并发 `HELD` 事件。

### Resume
解除扣车。字段 `trainId`。生效时发 `RELEASED` 事件（仅在原来被扣时）。

### Skip
让列车从下一停站起不停站通过这些站台，空数组则恢复按计划停站（D3）。字段 `trainId`、`platformIds`（拷贝）。
（源码中 Skip 上方有一段孤立 javadoc "列车已离开……放弃所持一切（3.1 规则 4）"，描述的是 `Remove`。）

### TurnBack
让列车提前折返（D4），`mode` 为 0 表示不折返，见 `TrainService.setTurnBack`。字段 `trainId`、`mode`、`platformIds`（拷贝）、`after`。

### HandOver
让列车把运用的剩余部分交给备用车，或不交（D5），见 `TrainService.setTakeover`。字段 `trainId`、`takeover`。

### Dispatch
调度员对列车的命令（D6），见 `TrainService.dispatch`。字段 `trainId`、`command`、`value`、`platformIds`（拷贝）。

### Remove
列车已被删除或清除，放弃其所持一切（3.1 规则 4）。字段 `trainId`。

### ExternalOccupancy
仿真不移动的东西（如手动驾驶列车）占用某轨道直到某时刻。字段 `rail`（UUID）、`untilTime`。

### ExternalTrain
仿真不移动的列车（手动驾驶，或仍按旧规则的车厂的列车）现在所在的轨道（G0），替换之前的。空集合表示它已离开。被接管的列车也这样报告。字段 `trainId`、`rails`（不可修改拷贝）。

### ExternalRun
仿真不移动的列车（如仍由游戏运行的车厂的列车）现在持有的运行区段及方向（G1），替换之前的；空表示不持有。仿真中的列车不会被分配与之相对的运行。字段 `trainId`、`moves`（`List<RunReservations.Move>`，不可修改拷贝）。

### TakeOver
宿主接管列车（3.1 规则 4，如玩家开始驾驶）：仿真停止移动它，并把它已占用的轨道当作外部列车的，直到宿主用 `ExternalTrain` 报告。字段 `trainId`。

### Release
宿主交还接管的列车（3.1 规则 4），给出它现在沿路径的位置与速度；仿真从那里重新计算运行段并重新申请运行。字段 `trainId`、`railProgress`、`speed`。

### PublishPlan
计划的新版本：就地修改仿真线路的时刻表并递增修订号。正在运行某服务的列车保留开始时的版本（5.1 规则 3）。变更以"被应用的那个仿真的线路"为参数，因此分叉重放时改的是它自己的线路。字段 `change`（`Consumer<LongFunction<? extends PlanRoute>>`）。

### PublishNetwork
网络的新版本，删除了这些轨道（5.1 规则 5）：路径仍完整的列车继续运行；前方有被删轨道的列车在其前停下并报告；站在被删轨道上的列车被移除，由宿主另行放置；路径含被删轨道的停车线不再发车。字段 `removedTracks`（不可修改拷贝）。
