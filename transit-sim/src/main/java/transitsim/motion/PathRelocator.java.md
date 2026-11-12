# PathRelocator.java

路径被替换后，为正在运行的列车找到它在新路径上的位置。路径是有向轨道列表，环线或折返路径上同一轨道可能出现多次，所以除了轨道本身还要匹配"第几次经过"。纯静态工具，只用 JDK。调用者：游戏侧 `Siding.java:1668`（线路/路径重算后迁移在途列车）、`PathRelocatorTest`。

## 类 PathRelocator（final，不可实例化）

### `static Position locate(List<String> oldRails, List<Double> oldDistances, List<String> newRails, List<Double> newDistances, double railProgress)`
- 作用：先用 `findIndex` 找车头在旧路径中的轨道下标；从该轨道起往后逐条检查，按"第几次经过"在新路径中找同一轨道：
  - 若当前轨道本身就在新路径中，按在旧轨道上的比例换算到新轨道上的进度；
  - 若要往后找才匹配，则放到新路径该轨道的起点。
- 参数：`oldRails`/`newRails` 轨道键列表（字符串，同一有向轨道键相同）；`oldDistances`/`newDistances` 每条轨道终点的累计距离（方块），与轨道列表等长；`railProgress` 车头在旧路径上的进度。
- 返回：新位置 `Position`；旧路径剩余部分与新路径没有共同轨道（或旧路径为空）时返回 null。
- 副作用：无。
- 主要调用者：`Siding.java:1668`、`PathRelocatorTest.java:27`。

### `private static int findIndex(List<Double> distances, double railProgress)`
- 作用：线性查找第一条终点距离 ≥ 进度的轨道；超过全部则返回最后下标；空列表返回 −1。调用者：`locate`（第 19 行）。

### `private static int countPasses(List<String> rails, int index)`
- 作用：数 `rails[index]` 在下标 index 之前出现过几次（即这是第几次经过，从 0 起）。调用者：`locate`（第 25 行）。

### `private static int findPass(List<String> rails, String rail, int pass)`
- 作用：返回 `rail` 在 `rails` 中第 `pass` 次出现的下标；新路径经过次数更少时退回最后一次；不存在返回 −1。调用者：`locate`（第 25 行）。

## 内部类 Position（public static final）
- `railIndex`（int）：新路径中的轨道下标。
- `railProgress`（double）：新路径上的车头进度（方块）。
- 私有构造函数，仅由 `locate` 创建。
