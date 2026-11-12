# SimScope.java

仿真某些线路的一天所需的那部分世界数据（S5）：运行这些线路的车厂，以及与它们共同运行某条线路的所有车厂（它们共享服务）；其他车厂中，只保留其列车经过与上述车厂列车经过的节点相同的停车线，使它们可能互相阻挡。只与这些"邻居"列车相遇的列车不仿真，因为它们只会经由他车间接延误所报告的线路。整个世界规模的一天对编辑器来说太慢；这样只需线路自身那部分的时间。

只保留部分停车线的车厂，其他停车线的服务和发车由保留的停车线发出，所以线路可能遇到比实际更多的该车厂列车：报告偏向于多报延误。

调用者：游戏侧 `SandboxRuns.java:145`（编辑器的日仿真）、工具 `SimulateDay.java:100`；测试 `SimScopeTest`。

## 类 SimScope（final）

### 字段
- `dataset`（SimDataset）：要仿真的数据（与原数据共享线路、车厂、停车线与列车对象）。
- `depots`、`allDepots`（int）：仿真的车厂数、世界中的车厂数。
- `ownDepots`（int）：其中运行这些线路或与之共线的车厂数（其余只因部分列车而在）。

### 私有构造函数
按字段赋值。

### `static SimScope of(SimDataset full, Set<Long> reportRoutes)`
- 作用：
  1. `reportRoutes` 为空：保留整个世界。
  2. 找出运行报告线路的车厂，再用 `addSharingRoutes` 加入所有共线车厂（"自有"车厂）。
  3. 足迹 = 自有车厂各停车线常用路径与各服务路径经过的节点。
  4. 其他车厂中，路径经过足迹节点的停车线作为"邻居停车线"，其车厂也保留。
  5. 新数据集：全部线路；保留的车厂；自有车厂的全部停车线与邻居停车线；列车（被略去停车线的列车找不到停车线，自然被略去）；信号闭塞区、联锁区（L2）、站台停车位置（第 4 轮第 1 步）、提前阈值（第 4 轮第 5 步）——注释称略去这些时部分运行会缺少它们。
- 参数：`full` 完整数据；`reportRoutes` 要报告的线路，空 = 全部。
- 返回：`SimScope`。副作用：无（不修改 `full`）。

### `private static void addSharingRoutes(SimDataset full, Set<Long> kept)`
反复把与已保留车厂共同运行任一线路的车厂加入 `kept`，直到不再增加。

### `private static void forEachNode(SimDataset.SidingData siding, LongConsumer consumer)`
遍历停车线常用路径及所有服务路径中每条轨道的起止节点。
