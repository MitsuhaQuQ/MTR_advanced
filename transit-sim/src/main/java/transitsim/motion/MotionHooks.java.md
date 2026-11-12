# MotionHooks.java

运动规则（`TrainMotion`）向宿主询问或通知的回调点。它们原本是宿主列车类可覆盖的方法（解耦文档 2.2），调用顺序保持和以前一致。实现：游戏侧 `Train.Motion`（`Train.java:1288`，再转给 `TrainServer`/`TrainClient`）和组件 `TickRunner.HeadlessTrain`（`TickRunner.java:1680`）。

## 接口 MotionHooks
- `boolean isClientSide()`：是否为客户端。客户端只跟随列车，不检查前方轨道，也不负责让列车从站台出发。
- `int getTotalDwellTicks()`：在下一停站的总停站时长（tick），晚点列车可缩短。
- `boolean canDeploy()`：停在停车线里的列车现在能否出发。
- `void startUp(boolean isOppositeRail)`：让列车从停站点或停车线起步；宿主设定速度、下一停站，必要时把列车掉头。`isOppositeRail` 表示下一条轨道是否反向（需要折返）。
- `boolean keepsOnLineAtEnd()`：路径终点是否为线路上的停站（而不是回停车线的入口）。
- `void clearRiders()`：列车已回停车线，清空乘客。
- `boolean isRepeat()`：是否为循环运行的路径。
- `void onLapComplete()`：循环列车开始下一圈时、找下一停站之前调用。
- `void onStopReached()`：列车刚停下、停站计时开始之前调用；可能被调用不止一次。
- `default void onDwell(int rail, float departureTicks)`：报告实际停站情况：`rail` 为停站轨道，不在站时为 −1；`departureTicks` 剩余 tick，负数表示发车未确认。默认空实现。调用者：`PlatformDwell.java:31`；实现于 `Train.java:1165`。
- `boolean isHeldAtStop()`：为真时停站列车保持开门且不出发（扣车）。
- `boolean isRailBlocked(int rail)`：路径上下标为 `rail` 的轨道是否被占用（整条）。
- `default double getSectionBlockedPosition(int rail, double fromPosition)`：对被切成闭塞分区（`BlockSections`）的轨道，返回从 `fromPosition`（路径进度，方块）起第一个被别车占用分区的起点位置，列车可跟进到那里。轨道只有一个分区、或前方分区都空闲时返回 NaN，此时由 `isRailBlocked` 判断整条是否占用。默认返回 NaN。调用者：`TrainMotion.java:116`、`Train.java:1191`。
- `boolean openDoors()`：是否应开门（具体判断由宿主实现）。
- `void emit(MotionEvent.Type type, int rail)`：列车发生了某事件；宿主补上时间和列车，在 tick 末收集。只有服务器上的新规则才产生事件。
