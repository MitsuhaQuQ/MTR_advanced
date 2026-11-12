# RunningDirections.java

按线路的行车侧（左行/右行）确定双线（或四线）每条轨道的运行方向（用户，2026-11-12）：顺着列车前进方向看，行车侧那条轨道属于这个方向。该方向的列车沿它逐个分区跟驰；反方向使用它的列车则一次占用整段，如同单线。

- 同一线路、近似平行、相距几个方块的轨道视为并行。两条时行车侧那条为该方向；四条时中线同侧两条（方向别，上上下下）；奇数条时中间那条无方向。单独一条轨道（单线、折返轨、车厂轨道）无方向。
- 每条线路有自己的行车侧（右或左）；行车侧不同的线路的轨道无方向。
- 方向沿轨道延续：经过只连接两条轨道的节点时继续，因此线路上下行换位处（如跨线桥）各自保持方向。同一串轨道各部分意见不一时，长度占多者决定。道岔与交叉（三条及以上轨道的节点）结束一串。

只由轨道决定，两个宿主算法相同。调用者：游戏侧 `RunningSides.java:120-122`（构造 `Rail` 并 `compute`）；测试 `RunningDirectionsTest`。另见记忆中的"Running side convention"。

## 类 RunningDirections（final，不可实例化）

### 常量
- `RIGHT = 1`、`LEFT = -1`：行车侧。
- `MAX_APART = 12`：并行轨道最大间距（方块）。
- `MIN_APART = 1.5`（private）：最小间距。
- `MAX_SIN = sin(15°)`（private）：视为平行的最大夹角正弦。
- `SAMPLE_SPACING = 8`：轨道采样点最大间隔（方块）。
- `CELL = 16`（private）：空间网格单元边长。

### `static Map<UUID, Integer> compute(Collection<Rail> rails)`
- 作用：
  1. 把每条轨道的每一小段（相邻采样点）按扩大 `MAX_APART` 的包围盒登记到 16×16 网格。
  2. 投票：对有线路且行车侧非 0 的轨道的每一小段，在其中点所在网格单元里找同行车侧、有共同线路、夹角 ≤ 15° 的其他轨道小段，计算中点到其最近点的距离与左右（俯视、x 东 z 南，正值为右）；只取间距在 [1.5, 12]、且主要在侧方（沿行进方向的分量 ≤ 距离的一半）的；每条其他轨道只取最近一段。左右数量相等则不投；否则若左侧轨道更多（本轨在束的右边），投 `+side × 段长`，反之 `−side × 段长`（以 A→B 方向计）。
  3. 串联：从每条未处理轨道出发，向两端经过"恰好连接两条轨道"的节点延伸成一串，记录每条在串中的走向。串内票数（乘走向）求和，绝对值 < 1E-6 则整串无方向；否则按和的符号给串中每条轨道定方向。
- 参数：`rails` 宿主提供的轨道。
- 返回：有方向的轨道键 → 1（从较小节点驶向较大节点）或 −1；无方向的轨道不在结果中。
- 副作用：无。
- 调用者：`RunningSides.java:122`、`RunningDirectionsTest.java:35`。

### `static double[] straight(double x1, double z1, double x2, double z2)`
- 作用：生成直轨道从 (x1,z1) 到 (x2,z2) 的等距采样点（间隔不超过 `SAMPLE_SPACING`，至少两个点），格式 x,z 交替。
- 调用者：`RunningDirectionsTest.java:26`。

## 内部类 Rail（public static final）
宿主眼中的一条轨道。
- `key`（UUID）：轨道键。
- `nodeA`、`nodeB`（long）：两端节点。
- `points`（double[]）：从 A 到 B 的采样点，x、z 交替，间隔 ≤ `SAMPLE_SPACING`，首点在 A、末点在 B。
- `lines`（Set<String>）：在其上运行的线路，空表示无（车厂轨道）。
- `side`（int）：其线路的行车侧 `RIGHT`/`LEFT`，线路意见不一或没有时为 0。
- 构造函数按字段赋值，不拷贝。调用者：`RunningSides.java:120`。
- `private double length()`：采样折线长度。仓库内本类未调用（`NetworkSettings.java` 中的 `length` 为同名其他方法）。
