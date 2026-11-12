# Lift.java

升降机（电梯）的公共基类，服务端 `LiftServer` 与客户端 `LiftClient` 继承它。本分支相对原 MTR 3.2.2 的改动：加入可调的最高速度与加速度（与 MTR 3.5.0 相同的存档键和单位：m/tick、m/tick²，使其存档中的升降机保留设置），以及 `tick` 接受经过的 tick 数（掉帧补偿，见 `TickCompensator`）。由 `RailwayData`（存档、发包、`update`、无效检查）、`ItemLiftRefresher`（设置楼层）、`LiftInstructions`、渲染（`RenderLiftButtons`、`RenderLiftPanel`、`RenderTrains`）、`LiftCustomizationScreen` 使用。

## abstract class Lift extends NameColorDataBase implements IPacket

### 常量
- `MAX_LIFT_DIMENSION = 16`：尺寸与偏移的限制。
- `MAX_SPEED_DEFAULT = 1`、`MAX_SPEED_MIN = 0.025`、`MAX_SPEED_MAX = 1`（m/tick）。
- `DOOR_MAX = 24`：门开启值的一半行程（`doorValue` 在 0…48 之间变化）。
- 键：`KEY_LIFT_UPDATE = "lift_update"`（增量更新），存档键 `lift_height`、`lift_width`、`lift_depth`、`lift_offset_x/y/z`、`is_double_sided`、`lift_style`、`facing`、`current_position_x/y/z`、`riding_entities`、`floors`、`max_speed`、`acceleration`。

### 字段
- 公共设置：`liftHeight`、`liftWidth`、`liftDepth`、`liftOffsetX/Y/Z`、`isDoubleSided`、`liftStyle`、`facing`、`liftMaxSpeed`（m/tick）、`liftAcceleration`（m/tick²）。
- 运行状态（protected）：`currentPositionX/Y/Z`、`liftDirection`、`speed`、`doorOpen`、`doorValue`、`frontCanOpen`、`backCanOpen`。
- `liftInstructions`（public final）：呼梯指令队列。
- `floors`：楼层（轨道楼层方块位置）；`ridingEntities`：乘坐实体 UUID。

### 构造函数
- `Lift(BlockPos pos, Direction facing)`：默认 4×2×2、透明样式、默认速度与加速度（`Train.ACCELERATION_DEFAULT`）。
- `Lift(Map<String, Value> map)`：读存档；速度与加速度经 `clampMaxSpeed`/`clampAcceleration`（缺省用默认值）；门开、值 0。
- `Lift(FriendlyByteBuf packet)`：按下述包顺序读取。

### `toMessagePack` / `messagePackLength`
- 写全部 16 个存档键；当前位置写的是最近楼层的坐标（`getCurrentFloorBlockPos()`，保存时吸附到楼层）。长度 = 基类 + 16。
- 调用者：`RailwayData.java:1668-1669`。

### `writePacket(FriendlyByteBuf)`
- 包字段顺序：基类字段，`int height, int width, int depth, int offsetX, int offsetY, int offsetZ, boolean doubleSided, String style, int facing(yRot), double maxSpeed, double acceleration, double posX, double posY, double posZ, String direction, double speed, boolean doorOpen, float doorValue, int n + UUID×n 乘客, int m + BlockPos×m 楼层`，然后 `liftInstructions.writePacket`。
- 调用者：`RailwayData.java:558`、`RailwayData.java:717`、`LiftInstructions.java:30`。

### `public void update(String key, FriendlyByteBuf packet)`
- 作用：`lift_update` 键：读并限制尺寸（1–16）、偏移（±16）、双面、样式、朝向、最高速、加速度；其它交给基类。
- 调用者：`RailwayData.java:648`。

### `protected boolean hasTransportMode()`
- 返回 `false`（升降机没有交通方式）。

### 楼层与位置
- `setFloors(List<BlockPos>)`：替换楼层。调用者：`ItemLiftRefresher.java:131`。
- `hasFloor(BlockPos)`：调用者：`LiftInstructions.java:129`、`RailwayData.java:1525`。
- `getPositionX/Y/Z()`、`getLiftDirection()`：取值。
- `hasUpDownButtonForFloor(int checkFloor, boolean[] hasButton)`：有高于该层的楼层时置 `hasButton[0]`（上行按钮），有低于的置 `hasButton[1]`。
- `pressButton(int floor)`：加入指令：当前层取整（上行时向下取整，否则向上取整）、方向、目标层。调用者：`PacketTrainDataGuiServer.java:1214`。
- `getCurrentFloorBlockPos()`：离当前 y 最近的楼层（假设楼层按高度排序，遇到距离变大即停止）；无楼层返回 `null`。
- `isInvalidLift(Level world)`：无楼层，或任一已加载楼层位置不是 `BlockLiftTrackFloor` 时为无效。调用者：`RailwayData.java:1773`、`ItemLiftRefresher.java:57`。

### `protected void tick(Level world, float ticksElapsed)`
- 参数：`ticksElapsed` 本次推进的 tick 数（可为小数，掉帧补偿）。
- 作用：
  - 有指令且门完全打开（`doorValue == 48`）时开始关门并按目标定方向；无指令时方向为 NONE。
  - 门关闭（`!doorOpen && doorValue == 0`）时向目标层运动：距离小于默认加速度阈值则到达（速度 0、开门、对齐楼层、`arrived()`，服务端若该楼层方块设置了提示音则播放音符盒 pling）；否则距离不足以按加速度制动时减速（`v - v²/(2d)·Δt`，不低于默认加速度值），否则加速到最高速；按方向移动 `speed × Δt`。
  - 否则按 `ticksElapsed` 开/关门（0…48），检查前后（双面时）屏蔽门（`checkDoor`）。
- 调用者：`LiftServer.java:35`、`LiftClient.java:28`。

### `public static double clampMaxSpeed(double value)` / `clampAcceleration(double value)`
- 返回：非有限值时用默认值，否则夹在 `[MAX_SPEED_MIN, MAX_SPEED_MAX]` / `[Train.MIN_ACCELERATION, Train.MAX_ACCELERATION]`。
- 调用者：`LiftCustomizationScreen.java:108`、`:116`。（`Siding.java:330` 调用的是 `Siding` 自己的同名方法。）

### `protected float getYaw()`
- 返回：朝向顺时针旋转后的 yRot 取负并转弧度（渲染用）。调用者：`LiftClient.java:33`、`LiftServer.java:38`。

### `private BlockPos getBlockPos()`
- 当前位置所在方块。

### `private boolean checkDoor(Level world, boolean front)`
- 作用：在轿厢前（或后）一侧的 3 个位置检查上下两格是否都是解锁的屏蔽门/安全门方块实体；附近有玩家且区块已加载时，服务端把门的开度设为 `min(round(doorValue), 24)`。
- 返回：是否有门。

## public enum LiftDirection
- `NONE(0)`、`UP(1)`、`DOWN(-1)`；`speedMultiplier` 为位移符号。

## public enum LiftStyle
- `TRANSPARENT`、`OPAQUE`。
