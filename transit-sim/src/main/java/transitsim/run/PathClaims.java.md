# PathClaims.java

列车为沿路径某段运行而向仲裁器申请的东西（运行层，解耦文档 5.5）：按到达顺序排列的资源，分成全批或全不批的组。
- 线路上每个闭塞分区单独一组，后车可逐分区跟进。
- 双向运行的一段轨道（单线）连同其方向作为一组，列车不会在中途对头相遇。
- 列车折返的站台连同其两个方向作为一组（掉头车优先），后车不能跟进来堵住它的出路。

涂色信号闭塞区不论包含多少轨道都是一个分区。停车线自身的轨道不是分区：在停车线内的列车不占用线路。轨道方向是独立于其分区的资源（一个持有者对每个资源只有一个声明）。

调用者：`Authority`（`between`、`attach`、`claimsOf`）；`TickRunner.java:2031`、`TrainServer.java:1476` 实现 `Path`；`TickRunner.java:2054` 构造 `Attached`。使用 `BlockSections`、`Arbiter.Claim`、`RunTrack`。

## 类 PathClaims（final，不可实例化）

### 常量
- `DIRECTION_MARK`（private）：生成"方向资源"键时异或的常数。
- `RUNNING_SIDES`（public static final boolean）：系统属性 `mtr.runningSides=false` 时为 false，此时双向轨道不论线路行车侧一律按单线预留（0.69.0 之前的行为，用于对比）。

### `static List<Group> attach(List<Group> groups, Collection<Attached> attached)`
- 作用：把附加项（咽喉道岔、联锁区）附到带列车经过其位置的那一组：附加项起点在上一组终点之后、本组终点之内（含）。无附加项时原样返回。
- 返回：新列表（未变化的组复用原对象）。调用者：`Authority.java:118`。

### `static UUID directionOf(UUID trackKey)`
双向轨道的方向资源键（轨道键高低位各异或 `DIRECTION_MARK`）。调用者：本类 `between`、`PathClaimsTest.java:147`。

### `static <T extends RunTrack> List<Group> between(Path<T> path, double from, double to, BlockSections sections)`
- 作用：路径上从 `from` 到 `to` 的各组（按序）：区间所经过的轨道；区间长度为 0 时取所在轨道。
  - 二分跳过在区间之前结束的轨道。
  - 停车线轨道或没有信号的轨道不是分区：结束当前单线段。
  - 每条轨道按 `BlockSections.getPieces` 求覆盖的片，每片的声明为其所在涂色闭塞区的全部单元（同一单元只声明一次）或片本身；记录每片沿路径的起止。
  - 双向轨道按线路行车侧运行（`RUNNING_SIDES` 且 `isTwoWay` 且不折返且 `getConvention() == CONVENTION_WITH`，复线）：每片照常一组，第一片附带该方向的 `singleTrack` 声明（同向列车共享该方向，逆向运行者需等它们全部离开）。
  - 双向轨道逆行车侧运行（单线）或在此折返：所有片累积进一个单线组；折返时加 `singleTrackBothWays` 并结束该组；否则加该方向的 `singleTrack` 并延长单线组终点。
  - 其他（单向轨道等）：结束单线组，每片一组；某片的声明已被前一组包含（如跨多条轨道的闭塞区）时，把前一组的终点延长到此片终点。
- 参数：`path` 路径；`from`/`to` 区间（沿路径距离）；`sections` 涂色信号闭塞区，可 null。
- 返回：组列表。副作用：无。
- 调用者：`Authority.java:108`、`Authority.java:118`、`PathClaimsTest.java:63`。
- 注：源码中此方法前有一段孤立 javadoc（`@param sections`），属于本方法。

### `private static int indexOf(UUID rail, UUID piece, int count)`
在轨道的 count 片中找片键对应的片号，找不到返回 0。调用者：`between`。

### `static List<Arbiter.Claim<UUID>> claimsOf(Collection<Group> groups)`
- 作用：多组声明按序合并，每个资源只保留一次；同一方向资源以不同方向声明时合并为"双向"声明。
- 调用者：`Authority.java:122`、`Authority.java:147`、`TickRunner.java:2054`（经 `RunReservations.claimsOf`，未确定是否同名方法）。

## 内部类 Group（public static final）
一起批准的一组资源。
- `claims`（不可修改 List<Claim<UUID>>）。
- `start`（double）：组在路径上的起点；没有它时列车可运行到此。
- `end`（double）：持有此组可运行到的位置。
- 构造函数（包内）`Group(claims, end)`（起点 = 终点）与 `Group(claims, start, end)`（起点取 min(start, end)）。调用者：`Authority.java:113`、本类。
- `toString()`：`"N to 终点"`（保留一位小数），用于调试显示（`TrainServer.java:1311`）。

## 内部类 Attached（public static final）
需要与某分区一起声明才能经过路径上某处的东西（站咽喉道岔或联锁区）：从路径上需要它的位置到车尾离开它的位置。
- `start`、`end`（end ≥ start）、`claims`。
- 构造函数按字段赋值。调用者：`TickRunner.java:2054`。

## 内部接口 Path<T extends RunTrack>
声明所读取的列车路径。
- `List<T> getTracks()`：路径上的轨道。
- `double getEnd(int track)`：该轨道终点沿路径的距离。
- `boolean turnsBack(int track, int nextTrack)`：两条轨道之间是否折返（后者沿前者反向）。
- 实现：`TickRunner.java:2031`、`TrainServer.java:1476`（匿名类）。
