# PlanStop.java

接口：时刻表规则眼中的"路线的一个停站"——它的站台以及在每个表（table）中的设置。由宿主的路线停站实现：游戏侧 `mtr.data.Route`（`Route.java:1240` 的内部类）、网页编辑器 `mtr.client.editor.EditorTimes:282`；组件内 `PlanVersions.java:146` 与 `transitsim.sim.SimDataset:1323` 也有实现。被 `StopRules`/`StopTimes` 等时刻表计算读取。

## 接口 `PlanStop`

### `long getPlatformId()`
返回此停站的站台 ID。

### `boolean isPass()`
路线是否通过此站不停车。

### `int getArrival(int table)`
- 参数：`table` 时刻表表号（从 0 起）。
- 返回值：设定的到达时间，单位秒，从路线起点算起；`-1` 表示跟随前一站推算（不设定）。

### `int getDeparture(int table)`
- 参数：`table` 表号。
- 返回值：设定的出发时间，单位秒，从路线起点算起；`-1` 表示按通常停站时长停车。

### `boolean getOvertake(int table)`
列车在此站是否等待被越行（待避）。
