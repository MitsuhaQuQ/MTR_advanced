# Network.java

物理轨道网络的压缩快照（设计文档 5.11、6.12；契约文档 1、4.1、5.1）：只在轨道分叉或尽头处、寻路边界处、以及站台/停车线/折返轨两端保留节点（这些轨道各自成为单独区段）。两个保留节点之间的轨道合为一个区段，其长度按限速分段，因此只看区段即可算运行时间。路径以经过的区段序列保存。

快照一旦建成即不可变，带有发布版本号和列车需要的资源。由 `NetworkBuilder`（`NetworkBuilder.java:97`）构建；游戏侧 `RailNetworks.compress`（`RailNetworks.java:52`）返回它；`TrackSections` 复用其 `Section` 存储格式、`SectionPath` 与 `mix`。

## 类 Network（final，不可变）

### 字段
- `version`（long）：发布顺序号，运行据此知道自己开始于哪个网络。
- `sections`（不可修改 `Map<Long, Section>`）：区段 id → 区段。
- `resources`（不可修改 `Map<Long, Resource>`）：资源 id → 资源。
- `railSections`（private `Map<UUID, long[]>`）：轨道（两方向合一，`RailKeys.of`）→ `{区段 id, 在区段中的位置, 1 = 轨道较小节点在区段方向上在前}`。
- `sectionResources`（private）：区段 id → 使用该区段的资源列表（按资源迭代顺序，注释称按 id 顺序）。
- `fingerprint`（private long）：整个网络的哈希。
- `EMPTY`：版本 0 的空网络。

### 构造函数 `Network(long version, Map<Long, Section> sections, Map<UUID, long[]> railSections, Map<Long, Resource> resources)`（包内可见）
- 作用：保存数据，建立区段 → 资源反向索引，计算 fingerprint（按区段迭代顺序累计 id 与节点数组哈希）。
- 调用者：`NetworkBuilder.java:97`、`TrainServiceTest.java:212`；`EMPTY`。

### `long getFingerprint()`
整个网络的哈希，任一区段变化即改变。调用者：`RailNetworksTest.java:92`、`TrackSections.java:677`。

### `List<Resource> getResources(long sectionId)`
列车在该区段上使用的资源，无则空列表。调用者：`NetworkTest.java:122`。

### `SectionPath toSectionPath(List<long[]> moves)`
- 作用：把逐轨道路径转成区段路径。每个 move 为 `{起节点, 终节点, 停站时间, 站台 id 或 0}`。查不到的轨道计入 `missingRails`；方向 `along` 由轨道方向与区段中轨道朝向决定；与上一步区段或方向不同时新增一步，同区段换方向时记为折返；有停站时间或站台时在当前步记录停站 `{步下标, 站台, 停站时间}`。
- 返回：新的 `SectionPath`。副作用：无。
- 调用者：`NetworkTest.java:183`、`RailNetworksTest.java:112`。

### `static long mix(long value1, long value2)`（包内可见）
哈希混合函数（区段 id 等）。调用者：`NetworkBuilder.java:137`、`NetworkBuilder.java:191`、`TrackSections.java:512`。

### `private static int limit(int count) throws IOException`
读数量时的合法性检查：< 0 或 > 2²² 抛 `IOException("Bad count …")`。调用者：`Section.fromBytes`、`SectionPath.fromBytes`。

## 内部类 Section（public static final）
一个区段。
- `id`（long）：区段 id。
- `nodes`（private long[]）：区段经过的节点（按顺序）。
- `limits`（private 不可修改 `List<double[]>`）：沿区段顺序的 `{限速 方块/tick, 长度}`；限速 −1 表示沿用前一条轨道（站台、停车线）。
- `savedRailId`（long）：对应的保存轨道 id（站台/停车线等），否则 0（推测）。
- `type`（`SectionType`）：区段类型。
- `regions`（private int[]）：所属寻路区域。
- `forwards`、`backwards`（boolean）：能否沿区段顺序/逆序运行。

方法：
- 构造函数（包内可见）：深拷贝 limits。调用者：`TrackSections.java:339`、`TrackSections.java:916`、`fromBytes`。
- `long[] getNodes()`：节点数组拷贝。调用者：`TrackSections.java:203`。
- `int getLimitCount()`、`double getLimit(int index)`、`double getLimitLength(int index)`：限速分段个数、第 i 段限速、第 i 段长度。
- `int[] getRegions()`：区域拷贝（仓库内未找到外部调用者）。
- `double getLength()`：各段长度之和。调用者：`RailNetworks.java:40`。
- `byte[] toBytes()`：序列化（节点、限速段、savedRailId、类型名、区域、两个方向标志）；IO 异常包成 `UncheckedIOException`。调用者：`TrackSections.java:302`。
- `static Section fromBytes(long id, byte[] data) throws IOException`：反序列化，计数非法时抛 `IOException`。调用者：`TrackSections.java:313`。

## 内部类 SectionPath（public static final）
- `steps`（List<long[]>）：每步 `{区段 id, 1 = 沿区段顺序}`。
- `stops`（List<long[]>）：每个 `{步下标, 站台 id 或 0, 停站时间}`。
- `reversals`（List<Integer>）：发生折返的步下标。
- `missingRails`（int）：网络中没有的路径轨道数（网络已变化时出现）。
- `byte[] toBytes()` / `static SectionPath fromBytes(byte[] data) throws IOException`：序列化/反序列化。调用者：`GraphRecords.java:440`、`TrackSections.java:506`、`NetworkTest.java:195`。
