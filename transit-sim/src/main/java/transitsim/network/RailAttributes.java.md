# RailAttributes.java

网络（`NetworkBuilder`）需要从宿主轨道对象了解的属性。泛型参数 `R` 为宿主的轨道类型。实现：游戏侧 `RailNetworks.java:14`（`RailAttributes<Rail>`）。使用者：`NetworkBuilder.build`、`buildSection`。

## 接口 RailAttributes<R>
- `SectionType getType(R rail)`：轨道类型；普通轨道为 `SectionType.NORMAL`。
- `boolean canAccelerate(R rail)`：能否在此加速；站台、停车线等保持驶入速度的轨道为 false（此时区段限速记为 −1）。
- `double getSpeedLimit(R rail)`：限速，方块/tick。
- `double getLength(R rail)`：长度（方块）。
