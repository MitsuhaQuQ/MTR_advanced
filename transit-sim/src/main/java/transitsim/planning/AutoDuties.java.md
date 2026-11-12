# AutoDuties.java

按时刻表运行但没有设置运用的车厂的自动运用：用 `DutyGenerator` 以最少列车从其时刻表算出。按时刻表运行的车厂总是按运用运行——只在运用开始时派车，每列车只跑自己运用中的班次（解耦文档 D 步）。相同的时刻表和停车线总得到相同的运用，所以重新计算不会无故移动列车。停车线列车不足时剩下的班次没有车跑。由游戏侧 `mtr.data.Depot:2865` 调用。

## 类 `AutoDuties`（final）

### 字段
- `duties`：生成的 `DepotDuties`，`diagramMode = true`。
- `trains`：运用最多需要的列车数。
- `uncovered`：没有空闲列车的班次数，每个班次按其运行的每个星期各计一次。
- `missingTimes`：因最快时间未知而被略去的路线。

### `private AutoDuties(DutyGenerator.Result result)`
从生成结果复制停车线槽位、列车数和未覆盖数。

### `public static AutoDuties generate(List<? extends PlanRoute> routes, Function<PlanRoute, long[][]> fastestTimes, int dayStartMillis, List<DutyGenerator.SidingInput> sidings, Map<Long, Long> stationOf, boolean staysOnLine)`
等同下一个重载，`taken` 为新的空集合。

### `public static AutoDuties generate(…, boolean staysOnLine, Set<String> taken)`
等同下一个重载，`inputs = DutyGenerator.PlanInputs.NONE`。

### `public static AutoDuties generate(…, Set<String> taken, DutyGenerator.PlanInputs inputs)`
- 作用：
  1. `DutyGenerator.servicesOf` 把路线时刻表展开成班次输入，记录缺少时间的路线；
  2. 去掉 `taken` 中已由车厂其它停车线承担的班次（`route@service`）；
  3. `DutyGenerator.generateSpread` 生成运用，最长停留时间为 `MAX_STAY_MILLIS`（留在线上）或 `MAX_LAYOVER_MILLIS`；
  4. 把新运用承担的班次加入 `taken`。
- 参数：
  - `routes` 车厂路线；
  - `fastestTimes` 路线 → 各站最快（到达, 出发），未知时 null；
  - `dayStartMillis` 运营日开始时刻（一天中的 UTC 毫秒）；
  - `sidings` 车厂停车线（按顺序）及各自的列车数；
  - `stationOf` 站台 → 车站，用于在同站站台间折返；
  - `staysOnLine` 车厂是否在班次之间把列车留在线上；
  - `taken` 已被占用的班次集合，会被追加；
  - `inputs` 路径生成在轨道上测得的数据（出库/回库/折返时间等）。
- 返回值：新的 `AutoDuties`。
- 副作用：修改 `taken`。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:2865`。
