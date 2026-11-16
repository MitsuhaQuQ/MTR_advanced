# SharedAreas.java

被不止一个排班单位走过的轨道（用户 2026-11-16：两个分开排班的系统不应共用轨道，所有共用区域必须成为一张运行图）。排班单位即一起排运用的东西：按线排班的车厂连同所跑线路（游戏 `LineDuties`），或单独一个车厂。轨道取自单位的路径（存车线路径、车次路径、折返的另一条路），一条轨道按两端节点算，不分方向。两个以上单位走过的轨道在节点处相连成区域，区域列出其上所有单位。两个宿主同样算，结果与输入顺序无关。

在整体中的位置：组件 `SimDataset.getSharedAreas`（`SimulateDay --shared`）；游戏 `mtr.advanced.SharedTrack.find`（服务端日志、`SharedTrackPackets` 发给网页）。

## 类 SharedAreas（final）
- `addPath(String unit, long[] nodes)`：一个单位的一条路径经过的节点（按序）；相邻节点相同的跳过。
- `result()`：共用区域列表，按单位、再按最小轨道排序。
- `getUnits(node1, node2)`：一条轨道上的单位（不分方向），无为空集。

## 类 Area（static final）
- `units`（有序）、`rails`（有序）；`describe()` 给出“单位@最小轨道+轨道数”，用于判断是否变化。

## 类 Rail（static final，Comparable）
- 两端节点，小者在前；equals/hashCode/compareTo 按两节点。

测试：`SharedAreasTest`。
