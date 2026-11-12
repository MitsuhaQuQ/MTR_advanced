# DepotReturn.java

路径生成期间的一个记录：主路径上某条路线的末站到下一条路线之间的那段路。车厂列车在这条路线之后回库时，照常在这段路上折返，再从折返处开往车厂。游戏侧 `mtr.data.Depot` 生成路径时创建（`Depot.java:3596`）并调用 `findStop`（`Depot.java:4190`），`mtr.data.Siding` 生成回库路径时读取（`Siding.java:1623` 起）。

## 类 `DepotReturn<T extends PlanTrack, P>`

泛型 `P` 为宿主的站台类型（游戏侧为站台对象，用于寻路）。

### 字段
- `routeIndex`（final）：此记录跟在车厂第几条路线之后。
- `start`（final）：主路径上这段路开始的轨道下标。
- `way`（final）：主路径从该路线末站出发所走的路。
- `lastPlatform`（final）：该路线的末站。
- `stopRail`（private）：主路径上进入这段路之前列车停车的轨道下标，由 `findStop` 设置。

### `public DepotReturn(int routeIndex, int start, List<T> way, P lastPlatform)`
按参数赋值，含义同上字段。

### `public void findStop(List<? extends PlanTrack> mainPath)`
- 作用：从 `start - 1`（至少 0）往回找第一段停车时间不为 0 的轨道，作为 `stopRail`；都没有时为 0。
- 参数：`mainPath` 车厂的主路径。
- 副作用：修改 `stopRail`。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:4190`。

### `public int getStopRail()`
返回 `stopRail`。主要调用者：`common/src/main/java/mtr/data/Siding.java:1629`、`Siding.java:1633`。

### `public int getTurnBackRail()`
- 作用：在 `way` 中找第一个 i（1 ≤ i < size-1）使 `way[i]` 与 `way[i+1]` 互为反向，即列车在 `way[i]` 末端折返。
- 返回值：该下标；不折返时 -1。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:1623`。
