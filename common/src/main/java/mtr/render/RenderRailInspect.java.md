# RenderRailInspect.java（本分支新增）

客户端：手持刷子时高亮准星下的轨道，并在动作栏显示它的信息（长度、限速、是否被占用、所在信号闭塞数；长轨道被切成多个闭塞分段时显示分段数、每段长度和被占用的分段）。由 `common/src/main/java/mtr/render/RenderTrains.java:264` 每帧调用。

## 类 `RenderRailInspect`（final，私有构造）

### 静态字段与常量
- `lastTick`（上次查找所在的游戏 tick）、`aimedRail`、`aimedRailProduct`（准星下的轨道及其无方向键）。
- `MAX_DISTANCE = 64`（只考虑 64 格内、视线方向 64 格内的轨道）、`MAX_AIM_DISTANCE = 1.5`（采样点到视线的最大距离，格）、`MIN_SAMPLES = 2`、`MAX_SAMPLES = 64`、`BLOCKS_PER_SAMPLE = 2`（每 2 格取一个采样点）、`LINE_Y_OFFSET = 0.5`（高亮线抬高 0.5 格）。

### `static void render(PoseStack matrices, MultiBufferSource vertexConsumers, Player player, float gameTick)`
- 作用：没拿刷子则清空并返回；每个游戏 tick 重新查找一次准星下的轨道，若找到且没有对准节点（对准节点时动作栏让给节点信息），在动作栏显示信息；之后每帧沿轨道画折线：被占用为红色，空闲为黄色。

### `private static void findAimedRail(Player player)`
- 遍历客户端所有轨道（只看 64 格内），在每条轨道的采样点（抬高 0.5 格）中找离视线最近者，距离须 < 1.5 格。

### `private static double distanceToRay(Vec3 position, Vec3 eye, Vec3 look)`
- 点到视线射线的垂直距离；在眼睛后方或超过 64 格时为最大值。

### `private static int getSamples(Rail rail)`
- `长度 / 2` 夹到 2–64。

### `private static boolean isOccupied()`
- 有分段占用信息（`ClientData.OCCUPIED_SECTIONS`）且该轨道有被占分段，或整轨被占（`OCCUPIED_RAILS`），或服务器没发分段信息时其信号闭塞被占用。

### `private static Component getInfoText()`
- `gui.mtr.rail_info`（长度保留 1 位、限速、占用状态、闭塞数）；若 `BlockSections.getPieceCount(长度, TrainServer.cutSpeed(类型)) > 1`，改为 `gui.mtr.rail_sections`（加分段数、每段长度、被占分段序号或 `gui.mtr.rail_sections_none`）。

### `private static boolean isLookingAtNode(Player player)`
- 准星是否对着轨道节点方块。
