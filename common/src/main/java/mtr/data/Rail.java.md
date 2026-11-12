# Rail.java

两个节点之间的一条轨道。形状最多由两段圆弧（或直线）组成，由两节点的位置与朝向（`RailAngle`）确定；`getPosition(double)` 给出沿轨道某距离处的点。轨道双向保存：`rails.get(start).get(end)` 与 `rails.get(end).get(start)`。本分支在原 MTR 基础上加了：折返停留时间 `turnBackDwell`、读取 ANTE 贝塞尔轨道并转为 MTR 形状、0.1° 精度角度下更稳的形状求解（避免零半径圆、圆弧越过终点、改用双圆弧 `Biarc`）、地图形状缓存、节点旋转 `rotateNode`（刷子设定角度）、方向判断（离开/到达朝向、是否回折）、缆车下垂曲线、施工动作（圆形隧道及墙、桥梁按半砖处理、保护特定方块、每 tick 2 ms 预算）。数据三写：msgpack 存档、整包。被几乎所有轨道相关代码使用（`RailwayData`、`PathData`、`GraphRecords`、渲染、`CompoundCreator`、`RailwayDataRailActionsModule` 等）。

## class Rail extends SerializedDataBase

### 字段
- `railType`、`transportMode`、`facingStart`、`facingEnd`（public final；朝向由形状重新计算）。
- `turnBackDwell`（public）：列车在此轨道上折返前等待的时间，单位半秒；0 为立即折返。
- 形状参数（private final）：第一段 `h1, k1, r1, tStart1, tEnd1, reverseT1, isStraight1`，第二段同理；`yStart`、`yEnd`。
  - 曲线：`x = h + r·cos(T/r)`，`z = k + r·sin(T/r)`（T 为弧长参数）；
  - 直线（|h|、|k| 都 ≥ 0.5 时）：`x = h·T`，`z = k·T + h·r`；其它直线：`x = h·T + k·r`，`z = k·T + h·r`。
  - 实际位置再加 0.5（方块中心）。
- `mapShape` / `mapShapeDone`：地图粗略形状的惰性缓存（轨道总是重建而不修改）。

### 常量
- `ACCEPT_THRESHOLD = 1E-4`、`ANGLE_THRESHOLD = 1E-9`、`MIN_RADIUS = 2`。
- `MAX_TURN_BACK_DWELL = 7200`（public，半秒）。
- `TURN_BACK_CHECK_STEPS = 256`、`TURN_BACK_TOLERANCE = 0.5`：`turnsBack` 的取样。
- `CABLE_CURVATURE_SCALE = 1000`、`MAX_CABLE_DIP = 8`：缆车下垂。
- 存档键：`h_1, k_1, h_2, k_2, r_1, r_2, t_start_1, t_end_1, t_start_2, t_end_2, y_start, y_end, reverse_t_1, is_straight_1, reverse_t_2, is_straight_2, rail_type, transport_mode, turn_back_dwell`（19 个）。
- 只读的 ANTE 键：`path_mode`、`pos_start`、`facing_start`、`pos_end`、`facing_end`。

### `public Rail(BlockPos posStart, RailAngle facingStart, BlockPos posEnd, RailAngle facingEnd, RailType railType, TransportMode transportMode)`
- 作用：由两端位置与朝向求形状。把终点差向量旋转到起点朝向坐标系（`deltaForward`、`deltaSide`）：
  1. 两朝向平行：在同一直线上用一段直线；否则横向偏移非零时用两段等半径反向圆弧（`r = (dv² + dp²)/(4dv)`）；横向偏移为零（节点垂直于连线）为无效轨道（全 0）。
  2. 不平行：必要时把起/终朝向取反使其指向对方；若"先直线后圆弧"可行（同侧、半径 ≥ 2）用之；否则若"先圆弧后直线"可行（同侧、半径 ≥ 2，且圆弧不会越过终点使直线部分倒回）用之；否则用 `Biarc`（两段相切圆弧，方向相反或超出单圆范围）。
- 调用者：`CompoundCreator.java:183`、`RailwayData` 铺轨、`rotateNode` 等。

### `public Rail(Map<String, Value> map)`
- 作用：读存档。ANTE 保存的贝塞尔轨道（`fromAnteBezier` 非空）改用其两端与角度求出的 MTR 形状；`turnBackDwell` 读取；朝向由形状重新计算。

### `private static Rail fromAnteBezier(MessagePackHelper messagePackHelper, Map<String, Value> map, RailType railType, TransportMode transportMode)`
- 作用：ANTE 可把轨道设为两节点间的贝塞尔曲线（`path_mode == 1`），并另存两端与角度（弧度）。读回时若这些键齐全，用同样的两端与角度构造 MTR 形状（直线加圆弧或两段圆弧），有效则返回，否则 `null`（保留存档的形状）。

### `public Rail(CompoundTag compoundTag)`（已弃用）
- 读旧 NBT（无 `turnBackDwell`）。

### `public Rail(FriendlyByteBuf packet)` / `public void writePacket(FriendlyByteBuf packet)`
- 包字段顺序：`double h1, k1, h2, k2, r1, r2, tStart1, tEnd1, tStart2, tEnd2; int yStart, yEnd; boolean reverseT1, isStraight1, reverseT2, isStraight2; String railType, String transportMode; int turnBackDwell`。

### `toMessagePack(MessagePacker)` / `messagePackLength()`（= 19）
- 按存档键写出。

### `public int getTurnBackDwellTime()`
- 返回：`max(turnBackDwell, 1)`——路径停站时间须大于 0 列车才会停车折返。
- 调用者：`RoutePaths.java:828`、`:1226`、`DeterministicPathFinder.java:104`。

### `public Vec3 getPosition(double rawValue)`
- 参数：沿轨道距离（方块），夹在 `[0, 长度]`。
- 返回：该点的三维位置（高度见 `getPositionY`）。

### `public float[] getMapShape()`
- 返回：地图用的若干点（`RailShapes.of`，缓存），直线为 `null`。调用者：`RailwayData.java:1017`。

### `public double getLength()`
- 返回：两段弧长之和。

### `public void render(RenderRail callback, float offsetRadius1, float offsetRadius2)`
- 作用：按约 1 方块步长把两段分别切成四边形回调（`renderSegment`），用于渲染轨道带。

### `public static RotatedNode rotateNode(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos, RailAngle angle)`
- 作用：为节点的新角度重建其所有轨道（另一端保持不变），服务端刷子设定和客户端刷子界面预览共用。参考朝向为 `getNodeFacing`；每条轨道保持从原来那一侧离开（`getRotatedFacing`）。失败原因：缺少反向轨道或形状无效 → `gui.mtr.invalid_orientation`；半径过小 → `gui.mtr.radius_too_small`；原来不回折的轨道变成回折（超过四分之一圈，如道岔或气球线）→ `gui.mtr.rail_would_loop`。新轨道沿用原折返停留时间。
- 返回：`RotatedNode`（失败时含已建的轨道，最后一条为失败的那条）。
- 调用者：`RailwayData.java:944`、`NodeAnglePreview.java:111`。

### `public static RailAngle getNodeFacing(Map<BlockPos, Rail> connections)`
- 返回：节点的朝向：节点是一条有两侧的线，所以取终点 `asLong` 最小的那条轨道的离开朝向（客户端与服务端一致）；无轨道为 `null`。
- 调用者：`RenderNode.java:78`、`NodeAngleScreen.java:76`。

### `public RailAngle getLeavingFacing()` / `public RailAngle getArrivingFacing()`
- 返回：轨道真正离开起点的方向 / 从终点沿轨道往回的方向（保存的朝向可能指向相反方向，形状只把它当直线用）。

### `private static RailAngle matchDirection(RailAngle facing, RailAngle direction)`
- 与 `direction` 同向则返回 `facing`，否则其反向。

### `public boolean turnsBack(BlockPos posStart, BlockPos posEnd)`
- 返回：沿两端连线方向，轨道是否在某处向后走（256 次取样，容差 0.5 × 端距）。

### `public boolean goodRadius()`
- 每段为直线或半径 > 2。调用者：`CompoundCreator.java:186`、`ItemRailModifier.java:89`。

### `public boolean isValid()`
- 形状参数不全为 0，且保存的两端朝向与按形状计算的一致。调用者：`CompoundCreator.java:187`、`ItemRailModifier.java:90`。

### `private double getPositionY(double value)`
- 作用：缆车（`RailSlopeStyle.CABLE`）：两端各 0.5 方块平坦，中间线性插值加抛物线下垂（下垂量随长度增大，超过 `MAX_CABLE_DIP` 时按比例缩小）；其它：前半与后半各一段二次曲线（S 形坡度）。

### `private static Vec3 getPositionXZ(double h, double k, double r, double t, double radiusOffset, boolean isStraight)`
- 按上述参数方程求平面位置（`radiusOffset` 为横向偏移，用于渲染宽度）。

### `private void renderSegment(...)`
- 一段的分块渲染（见 `render`）。

### `private RailAngle getRailAngle(boolean getEnd)`
- 返回：起点或终点处的精确切线方向（不用取样，避免超大半径时不准），四舍五入到 0.1°。

### `private static double getTBounds(double x, double h, double z, double k, double r)` / `getTBounds(..., double tStart, boolean reverse)`
- 圆上点的弧长参数；带方向版本保证与起点参数的先后关系（必要时加减 2πr）。

## private static class Biarc
- 两段相切圆弧连接任意两个位置与方向；无可用解（半径 0 或任一段超过半圈）时所有字段为 0。
- `Biarc(int xStart, int zStart, int xEnd, int zEnd, RailAngle facingStart, RailAngle facingEnd)`：按标准双圆弧公式求切线长、中点、两半径（有符号，`getSignedRadius`）。
- `getSignedRadius(double chordX, double chordZ, RailAngle facing)`：过起点、切于朝向且经过弦端点的圆的有符号半径；垂直分量过小为 0。

## public static class RailActions
沿轨道施工的动作（见 `RailwayDataRailActionsModule`）。

### 字段与常量
- `distance`（已处理到的距离）、`id`（随机）、`world`、`uuid`/`playerName`（发起玩家）、`railActionType`、`rail`、`radius`、`height`、`length`、`state`（方块）、`isSlab`。
- `blacklistedPos`：已处理的位置（隧道/墙）。
- `bridgePlacedState`：桥梁每个位置已写入的最高优先级（1 下半砖、2 上半砖、3 上方空气）。
- 圆形隧道/墙：`roundStep`、`roundDonePos`、`roundTunnelPos`（墙用：同半径隧道的方块）、`roundTunnelCollected`。
- `INCREMENT = 0.1`（沿轨道截面间距）、`SIDE_INCREMENT = 0.01`（截面内横向取样）、`ROUND_TUNNEL_INCREMENT = 0.1`、`TICK_BUDGET_NANOS = 2 ms`。

### `public RailActions(Level world, Player player, RailActionType railActionType, Rail rail, int radius, int height, BlockState state)`
- 调用者：`RailwayDataRailActionsModule.java:30-70`。

### `public boolean build()`
- 按类型推进一步；返回是否完成。调用者：`RailwayDataRailActionsModule.tick`。

### `public void writePacket(FriendlyByteBuf packet)`
- 包字段顺序：`long id, String playerName, float length(保留 1 位小数), String 方块描述 id 或 "", String 名称翻译键, int 颜色`。

### 私有施工方法
- `createTunnel()`：截面内所有位置（含中间，高度 0…height）设为空气。
- `createTunnelWall()`：只在截面边缘（和顶部）放方块。
- `createBridge()`：半砖填轨道经过的半格（上半时放下半砖于本格、清上一格；否则在下一格放整块或双层半砖、清本格），按优先级避免覆盖。
- `create(boolean includeMiddle, Consumer<Vec3> consumer)`：在 2 ms 预算内沿轨道推进，每个截面横向取样（同一方块列只处理一次），整数高度时顶层不处理；完成时显示 100%。返回是否完成。
- `createRoundTunnel()` / `createRoundTunnelWall()` / `createRound(boolean isWall)`：圆形隧道圆心在轨道上方 `radius - 1` 处（平直轨道上最低清除行就是轨道所在行）；墙先按隧道完全相同的取样收集隧道方块，再在其外侧与之共面的位置放墙（不会切入隧道或留缝），进度两遍各占一半。
- `touchesRoundTunnel(int x, int y, int z)`：六个相邻位置之一属于隧道。
- `setBlockIfChanged(BlockPos pos, BlockState newState)`：状态不同、方块不受保护且无方块实体时才设置（穿过空气或已清除的地面不产生方块更新）。
- `isProtected(Block block)`：节点、屏蔽门/安全门、站台门顶、告示牌、铁路标志及杆、线路标志，以及描述 id 含 "node"、或 MSD 模组的栏杆与接触网（按名称判断，各版本一致）。
- `showProgressMessage(float percentage)`：向发起玩家显示进度（动作栏）。

## private static class RoundSection
- 圆形隧道的一个截面：每个方块按其中心判断，横向距离与该点处轨道高度（含坡度）构成的圆（半径²）。
- 构造函数由相邻两个取样点求前进方向、横向方向、坡度；`contains(int blockX, int blockY, int blockZ)` 判断方块是否在圆内。

## public interface RenderRail
- `renderRail(x1, z1, x2, z2, x3, z3, x4, z4, y1, y2)`：四边形回调。

## public enum RailActionType
- `BRIDGE`、`TUNNEL`、`TUNNEL_WALL`、`TUNNEL_ROUND`、`TUNNEL_WALL_ROUND`，各有进度翻译键（`gui.mtr.percentage_complete_*`）、名称翻译键、颜色。

## public static final class RotatedNode
- `rails`、`backwardsRails`：按终点的新轨道（正向/反向）；失败时为已建的部分。
- `error`：失败原因翻译键，成功为 `null`。
- `private RotatedNode failed(String error)`。
