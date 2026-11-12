# RunInvariants.java

在运行过程中检查绝不能发生的事（端到端检查），无论计划如何、当天出了什么问题：列车只在停车线处出现在或离开线路，不会跳跃，不会重叠，也不会长时间无法前进。只读，不改变运行器任何东西。

每个 tick 后调用 `check()`（例如通过 `TickRunner.setAfterTick`）。违反规则的情况以可读行记入 `getViolations()`；长时间被前车挡住或等待路径而无法前进的列车单独记入 `getStuck()`（因为被有意扣住的列车也在等）。在计划要求等待的地方（如折返轨上等下一服务）停着的列车不算卡住。

调用者：工具 `SimulateDay.java:239`（`getStuck`）；测试 `GameChainTest.java:141`、`WholeDayTest.java:197`、`RunInvariantsTest`。泛型 `T` 为轨道类型（`RunTrack`）。

## 类 RunInvariants<T extends RunTrack>（final）

### 常量
- `MAX_BLOCKS_PER_MILLI`（private）：最快速度 300 km/h（留有余量取 320）换算为方块/毫秒。
- `OVERLAP_ROOM = 0.5`（private）：因舍入允许的重叠（方块）。
- `MAX_LINES = 200`（private）：最多记录的违规行数。

### 字段
- `runner`：被检查的运行器。
- `seen`（Map<Long, Seen>）：上次检查时每列车的状态。
- `violations`（List<String>）：违规记录。
- `stuck`（TreeMap<Long, String>）：卡住的列车 → 描述（每车只记一次）。
- `expectedRemovals`（Set<Long>）：宿主将有意移除的列车。
- `stuckMillis`（long，默认 10 分钟）：超过多久无法前进即记为卡住。
- `checks`（long）：已检查次数。

### 构造函数 `RunInvariants(TickRunner<T> runner)`
调用者：`GameChainTest.java:141`、`RunInvariantsTest.java:37`。

### `RunInvariants<T> setStuckMillis(long stuckMillis)`
设置卡住阈值，返回自身。调用者：`GameChainTest.java:141`、`WholeDayTest.java:197`。

### `void expectRemoval(long trainId)`
宿主将有意移除该列车，届时不算"从线路上消失"。调用者：`RunInvariantsTest.java:74`。

### `List<String> getViolations()` / `Map<Long, String> getStuck()` / `long getChecks()`
违规记录（不可修改）；卡住的列车及位置（不可修改）；检查次数。调用者：`GameChainTest.java:146`、`SimulateDay.java:239`、`RunInvariantsTest.java:40`。

### `void check()`
- 作用：第一次只记录各车位置。之后对每列车：在线且上次不在线（或新出现）而不在停车线 → "appeared on the line away from its siding"；持续在线 → `checkMove`；在线 → `checkStuck`；上次在线不在停车线、现在不在线 → "left the line away from its siding"。上次在线（不在停车线）的列车本次消失且不在预期移除中 → "vanished from the line"。最后 `checkOverlaps`。
- 副作用：更新 `seen`、`violations`、`stuck`、`expectedRemovals`。
- 调用者：`GameChainTest.java:144`、`RunInvariantsTest.java:50`。

### `private void checkMove(long millis, HeadlessTrain train, Seen before, Seen state)`
- 作用：列车应从原处沿路径前进，且不超过能跑的距离（允许 = 最快速度 × 经过时间（至少 50 毫秒）+ 车长 + 2，折返可使车头移动一个车长）。同一物理轨道上偏移变化在允许内即可；否则沿当前路径从车头往回找原来的物理轨道（不超过允许距离）；或新路径从原轨道旁开始（原轨道端点包含车头轨道起点，且进入不远，如运行段被替换）。都不满足 → "jumped"。

### `private void checkStuck(long millis, HeadlessTrain train, Seen before, Seen state)`
列车想走但走不了（`kept`），且与上次在同一物理轨道、偏移变化 < 0.01 时继承"静止起始时刻"；超过 `stuckMillis` 且未记过时，记 `"时刻 train id on 轨道 for N s (扣车原因)"`。

### `private void checkOverlaps(long millis, List<HeadlessTrain> trains)`
对每列在线列车，把车身覆盖的每段非停车线轨道按物理轨道（较小节点方向的区间 [a, b]）登记；与其他列车在同一物理轨道上重叠超过 `OVERLAP_ROOM` 时记 "overlaps train X on 轨道"。

### `private void add(long millis, long trainId, String what, Seen before, Seen state)`
未满 200 行时记 `"时刻 train id 描述, was 之前, now 现在"`。

### `private static String physical(RunTrack track)`
物理轨道键 `"较小节点-较大节点"`（与方向无关）。

### `private static String clock(long millis)`
一天中的时刻 `HH:MM:SS`。

## 内部类 Seen（private final，非静态）
某次检查时一列车的状态。
- `millis`；`onRoute`；`atSiding`（车头在停车线轨道，或无路径）；`kept`（有被挡轨道或等待时间 > 0）；`headRail`；`headProgress`；`physical`；`nodes`（车头轨道两端节点）；`offset`（车头在该轨道上距较小节点的距离）；`length`（车长）；`stillSince`（开始静止的时刻，可变）。
- 构造函数：从 `HeadlessTrain` 读取上述值；路径为空时视为在停车线。
- `toString()`：`"on|off 轨道 at 偏移"`。
