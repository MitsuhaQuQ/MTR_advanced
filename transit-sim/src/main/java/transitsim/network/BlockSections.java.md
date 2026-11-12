# BlockSections.java

把长轨道切成闭塞分区（解耦文档 5.4，2b）：列车只占用并只被挡在它所在的分区之外，而不是整条长轨道，后车可以沿长线跟进。

轨道的分区长度 = 以其自身限速常用制动的制动距离 `v²/(2a)`（a = `deceleration`），且不小于 `minLength`。更长的轨道被切成等长的若干"片"（piece），从较小节点一端编号，两个方向看到同样的片；第 0 片沿用轨道本身的键。

玩家涂色的信号闭塞区保持整体：其所有轨道的所有片构成一个分区，不论多长——若切开，两车可能各占一部分而永久互相卡住（大地图一日测试发现；用户决定，2026-11-05）。

本类只依赖轨道数据，两个宿主切法相同。构造：游戏侧 `SignalBlocks.java:278`（信号闭塞区）、组件 `TickRunner.java:395`；静态方法被 `PathClaims`、`TickRunner`、`TrainServer`、`SignalOverlay`、客户端 `SignalAspects`、`RenderRailInspect` 使用。全局参数由 `SecurityConfig.java:147-148` 按服务器设置写入。

## 类 BlockSections（final）

### 静态字段与常量
- `deceleration`（public static volatile double，默认 1.0）：计算制动距离用的减速度，单位 米/秒²（= 方块/秒²）；服务器设置写入。
- `minLength`（public static volatile double）：最短分区长度（方块），默认 400（比多数列车长，列车停在一两个分区内）；可由系统属性 `mtr.blockMinLength` 指定（下限 1）。
- `PIECE_MIX = 0x9E3779B97F4A7C15L`：生成片键时的混合常数。
- `TICKS_PER_SECOND = 20`。

### 实例字段
- `sectionOf`（`Map<UUID, List<UUID>>`）：单元（片键）→ 它所在分区的全部单元。轨道属于多个颜色的闭塞区时为这些闭塞区单元的并集。
- `pieceOf`（`Map<UUID, Map.Entry<UUID,Integer>>`）：闭塞区内单元 → (所属轨道, 片号)。

### `private static double parseLength(String value)`
解析 `mtr.blockMinLength`：null 或格式错误返回 400，否则 max(1, 值)。调用者：`minLength` 初始化（第 28 行）。

### 内部接口 RailInfo
宿主提供的轨道信息。
- `double getLength(UUID rail)`：轨道长度（方块），未知为 0。
- `float getMaxSpeed(UUID rail)`：限速（方块/tick），未知为 0，< 0 表示永不切分的轨道。

### 构造函数 `BlockSections(List<? extends Collection<UUID>> blocks, RailInfo railInfo)`
- 作用：对每个信号闭塞区，登记其所有轨道的每一片到 `pieceOf`（先登记者优先）；把闭塞区所有片作为一个分区（`allUnits`）登记到 `sectionOf`，已有的则合并。
- 参数：`blocks` 各信号闭塞区及其轨道键；`railInfo` 轨道信息。
- 调用者：`SignalBlocks.java:278`、`BlockSectionsTest.java:69`。

### `List<UUID> getSection(UUID unit)`
返回单元所在分区的全部单元（所有所在闭塞区的），不在任何闭塞区时 null。调用者：`PathClaims.java:191`、`TickRunner.java:2094`、`SignalAspects.java:67`。

### `Map.Entry<UUID, Integer> getPiece(UUID unit)`
返回闭塞区单元所在轨道与片号，不在闭塞区时 null。调用者：`RailwayData.java:736`、`SignalBlocks.java:155`。

### `static double getSectionLength(float maxSpeed)`
- 作用：限速为 `maxSpeed`（方块/tick）的轨道分区长度 = max(`minLength`, (v·20)² / (2·max(deceleration, 0.01)))。
- 调用者：`getPieceCount`、`BlockSectionsTest.java:27`。

### `static int getPieceCount(double length, float maxSpeed)`
- 作用：轨道被切成几片：`maxSpeed < 0`（站台、停车线、折返轨等不能加速、两车不能同时在上的轨道）恒为 1；长度 ≤ 分区长度（+1E-6）为 1；否则 ⌈长度 / 分区长度⌉。
- 调用者：`SignalAspects.java:107`、`SignalOverlay.java:126`、`RenderRailInspect.java:111`。

### `static UUID getPieceKey(UUID rail, int piece)`
- 作用：第 piece 片的键；第 0 片为轨道键本身，其余由高位异或 `PIECE_MIX × piece`、低位取反异或 piece 生成。
- 调用者：`PathClaims.java:250`、`TickRunner.java:517`、`SignalBlocks.java:164`。

### `static List<UUID> getPieces(UUID rail, double length, float maxSpeed, boolean forwards, double from, double to)`
- 作用：轨道上从 `from` 到 `to`（沿路径方向的距离）这一部分覆盖的片，按路径方向排列。不切分时只返回轨道键。零长度部分取其所在的那一片；恰在片末端的零长度部分不覆盖下一片。
- 参数：`forwards` 路径是否从较小节点走向较大节点（决定片号顺序）。
- 调用者：`TrainServer.java:606`、`PathClaims.java:187`、`TickRunner.java:2422`。

### `static List<Map.Entry<Double, UUID>> getPieceStarts(UUID rail, double length, float maxSpeed, boolean forwards)`
- 作用：切分轨道每一片沿路径方向的起点距离与片键；不切分时返回空列表。
- 调用者：`TrainServer.java:623`、`TickRunner.java:2432`。

### `private static List<UUID> allUnits(Collection<UUID> rails, RailInfo railInfo)`
按轨道键排序后列出每条轨道的全部片键。调用者：构造函数（第 77 行）。
