# TurnBackSection.java

车厂常规路径（主路径）上，某条路线在此结束、列车在接下一条路线之前可以选择多条折返轨之一的那一段。保存每种折返走法的轨道序列和默认走法，并负责给非默认走法编号停站、生成换用某走法后的主路径。游戏侧 `mtr.data.Depot` 生成路径时创建（`Depot.java:2753`、`:3275`、`:3442`）并编号（`Depot.java:4181`、`:4187`）；`mtr.data.Siding` 生成各走法路径（`Siding.java:1596`）；`DutyPlan.turnBackSections` 持有；`TurnBackPoint`/`TurnBacks` 由它派生运行时数据。

## 类 `TurnBackSection<T extends PlanTrack>`

### 字段
- `start`（final）：主路径上从停站出发的那条路开始的轨道下标。
- `ways`（final）：按折返轨顺序，每种走法从停站到下一站的轨道序列；不可用时为 null。
- `defaultWay`（final）：主路径实际采用的走法下标。
- `stopRail`（private）：主路径上进入此段之前停车的轨道下标，由 `numberStops` 设置。

### `public TurnBackSection(int start, List<List<T>> ways, int defaultWay)`
原样赋值。

### `public int getStopRail()`
返回 `stopRail`（`numberStops` 之前为 0）。

### `public List<T> getDefaultWay()`
返回 `ways.get(defaultWay)`。主要调用者：`common/src/main/java/mtr/data/Depot.java:3056`、`:3590`、`:3736`。

### `public void numberStops(List<T> mainPath)`
- 作用：先像 `DepotReturn.findStop` 一样从 `start-1` 往回找到停车轨道作为 `stopRail`；再对每条非默认、非 null 的走法，以主路径 `start-1` 处（`start` 为 0 时用走法第一段）的站序为起点，遇到从非站台进入站台轨道时站序加 1，把站序写入走法中每段轨道。要求主路径已编号。
- 副作用：修改 `stopRail` 和各走法轨道的 stop index。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:4181`。

### `public void setEndStopIndex(int mainSize, int stopIndex)`
- 作用：若本段是主路径的最后一段（`getEnd() == mainSize - 1`，即结束一圈的走法以首站结束），把每条走法最后一段轨道的站序设为 `stopIndex`（视为那一站）。
- 主要调用者：`common/src/main/java/mtr/data/Depot.java:4187`。

### `public int getLength()`
默认走法的轨道数。

### `public int getEnd()`
本段在主路径上的最后一段轨道下标：`start + getLength() - 1`。

### `public List<T> getMainPath(List<T> mainPath, int way)`
- 作用：返回把默认走法替换为第 `way` 种走法后的新主路径（新 `ArrayList`）。
- 主要调用者：`common/src/main/java/mtr/data/Siding.java:1596`。
