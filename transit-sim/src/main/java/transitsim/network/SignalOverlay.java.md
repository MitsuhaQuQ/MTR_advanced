# SignalOverlay.java

叠加在轨道区段上的信号层（优化阶段文档第 1 轮）：对每个含有信号闭塞区轨道的区段，记录其每条轨道按 `BlockSections` 规则被切成几个闭塞分区、属于哪些信号闭塞区。按区段哈希保存，因此信号变化只改这一层，轨道变化只改变化区段的条目。

调用者：游戏侧 `GraphRecords.java:156`（`build`）及其存档读写（`toBytes`/`fromBytes`），测试 `TrackSectionsTest`。

## 类 SignalOverlay（final，不可实例化）

### 常量
- `FORMAT = 1`（private）：条目序列化格式版本。

### `static Map<Long, Entry> build(Map<Long, TrackSections.Section> sections, Map<Long, ? extends Collection<UUID>> blocks)`
- 作用：先建立轨道键 → 所在信号闭塞区 id 集合（有序）；对每个区段的每条轨道：不在任何闭塞区则 1 片、无闭塞区；否则片数 = `BlockSections.getPieceCount(长度, getCutSpeed(rail))`，闭塞区为有序 id 数组。至少有一条轨道在闭塞区内的区段才生成条目。
- 参数：`sections` 区段哈希 → 区段；`blocks` 信号闭塞区 id → 其轨道键。
- 返回：区段哈希 → `Entry`（TreeMap）。副作用：无。
- 调用者：`GraphRecords.java:156`、`TrackSectionsTest.java:61`。

### `static float getCutSpeed(TrackSections.Rail rail)`
- 作用：轨道切分所依据的速度（方块/tick）：能加速的轨道取 `SpeedLimits.toBlocksPerTick(rail.speedKmh)`，否则 −1（永不切分）。
- 调用者：`build`（第 126 行）、`TrackSectionsTest.java:361`。

## 内部类 Entry（public static final）
一个区段的信号信息。
- `pieces`（private int[]）：每条轨道的闭塞分区数。
- `blocks`（private long[][]）：每条轨道所在信号闭塞区（有序）。
- 私有构造函数：保存数组。
- `int getPieces(int rail)`：第 rail 条轨道的分区数（不在闭塞区或不切分为 1）。
- `long[] getBlocks(int rail)`：所在闭塞区（拷贝）。
- `int getRailCount()`：轨道数。调用者：`TrackSectionsTest.java:76`。
- `byte[] toBytes()`：序列化：`FORMAT`、轨道数、每条（片数、闭塞区数、各 id）。IO 异常包成 `UncheckedIOException`。调用者：`GraphRecords`（保存信号层）。
- `static Entry fromBytes(byte[] data) throws IOException`：反序列化；格式号不符抛 `IOException("Unknown signal overlay format")`，轨道数超出 [0, 2²⁰] 或闭塞区数超出 [0, 2¹⁶] 抛 `IOException`。调用者：`GraphRecords`（读档）。
- `equals`/`hashCode`：按两个数组的内容比较。调用者：`GraphRecords.java:158`（比较新旧信号层以确定变化）。
