# RailMixin.java（ANTE mixin）

服务端与客户端共用的 mixin，作用于 `mtr.data.Rail`（一段轨道的几何与类型），优先级 1425，实现 ANTE 的 `RailExtraSupplier` 接口。为每段轨道附加 ANTE 属性：轨道模型键、渲染方向、竖曲线半径、自定义配置、滚转角表、开门方向、滚转偏移；把这些写入存档（msgpack）和网络包；用竖曲线和滚转重写 `getPositionY`；用带缓存的方式替换轨道渲染 `render`；并以完整包字节重定义 `equals/hashCode`。本分支改动：轨道全部使用 MTR 自身的形状（ANTE 以 Bézier 曲线保存的轨道在读取时由 `Rail` 转成 MTR 形状，这里只需要两端位置）；保存时仍写 `path_mode = 0` 和两端坐标/角度以保持存档键不变；渲染时用 `mtr.data.RailGeometryCache` 缓存折线点、对超长轨道（> 8192 格）不缓存；`render` 与 `onGetPositionY` 列在 `MixinLintTest` 允许项（`common/src/test/java/cn/zbx1425/mtrsteamloco/MixinLintTest.java:46`、`:47`）。

## 类 `RailMixin`（abstract，implements `RailExtraSupplier`）

### 常量
- `ACCEPT_THRESHOLD = 1E-4`、`HALF_ACCEPT_THRESHOLD`：求切向时前后取点的距离（格）。
- `NTE_PACKET_EXTRA_MAGIC = 0x25141425`：包中 ANTE 附加数据的起始标记。
- `CABLE_CURVATURE_SCALE = 1000`、`MAX_CABLE_DIP = 8`：缆车轨道下垂曲线参数（复刻 MTR）。
- `hashBuilder`（static `FriendlyByteBuf`）：算哈希时复用的缓冲（非线程安全）。

### 影子字段（`@Mutable`）
`railType`、`transportMode`、`facingStart`、`facingEnd`、两段圆弧参数 `h1,k1,r1,tStart1,tEnd1,h2,k2,r2,tStart2,tEnd2`、`yStart`、`yEnd`、`reverseT1, isStraight1, reverseT2, isStraight2`。影子方法：`getLength()`、`getPosition(double)`、`getRailAngle(boolean)`。

### ANTE 附加字段
- `mtrGeometryCache`：本段轨道的渲染折线缓存。
- `modelKey`（轨道模型，空为默认）、`isSecondaryDir`（渲染反向）、`verticalCurveRadius`（竖曲线半径，格；< 0 表示直线坡，0 表示用 MTR 默认曲线）、`customConfigs`、`customResponders`、`rollAngleMap`（`沿轨距离 → 滚转角`）、`openingDirection`（0 不开、1 左、2 右、3 双向）、`rollingOffset`（滚转中心偏移，默认 1.435/2 格，即半个标准轨距）、`posStart`/`posEnd`（两端节点坐标）、`railAngleStart`/`railAngleEnd`（声明未使用）、`vTheta`（竖曲线圆心角缓存）、`dataBytes`/`hashCode`（相等比较用）。

### 存档键（msgpack，`messagePackLength` +12）
`model_key`、`is_secondary_dir`、`vertical_curve_radius`、`custom_configs`、`roll_angle_map`、`opening_direction`、`rolling_offset`、`path_mode`（恒 0）、`pos_start`、`facing_start`（弧度）、`pos_end`、`facing_end`（弧度）。

### 包字段顺序（追加在 `Rail.writePacket` 之后，仅 `Main.enableRegistry` 时）
`int 0x25141425`、`String modelKey`、`boolean isSecondaryDir`、`float verticalCurveRadius`、`String customConfigs`、`String rollAngleMap`、`int openingDirection`、`float rollingOffset`、`int 0（path_mode）`、`BlockPos posStart`、`double facingStart 弧度`、`BlockPos posEnd`、`double facingEnd 弧度`。

### 访问器（接口实现）
- `getModelKey/setModelKey`、`getRenderReversed/setRenderReversed`、`getVerticalCurveRadius/setVerticalCurveRadius`、`getRollAngleMap/setRollAngleMap`、`getCustomConfigs/setCustomConfigs`、`getCustomResponders/setCustomResponders`、`getRollingOffset/setRollingOffset`、`setRailType`、`getPosStart/getPosEnd`：直接读写字段。
- `int getHeight()`：`yEnd - yStart`（格）。
- `setOpeningDirectionRaw(int)` / `getOpeningDirectionRaw()`：不考虑渲染方向的原值。
- `setOpeningDirection(int)` / `getOpeningDirection()`：渲染反向时左右（1↔2）互换后存取。

### `void partialCopyFrom(Rail other)`
- 作用：复制模型键、竖曲线半径、自定义配置、开门方向、滚转偏移；滚转角表的距离键按两段长度比例缩放（`length(this)/length(other)`）。渲染方向不复制。调用者：ANTE 编辑工具（例如刷子复制属性）。

### `boolean isStraightOnly()`
- 两段都为直线（或该段参数全 0）时为真。

### `boolean isBetween(double x, double y, double z, double radius)`
- 作用：点是否在两端点包围盒各向外扩 `radius` 的盒内。被 `RailwayDataMixin.simulateTrains` 用来判断轨道是否靠近玩家。

### `Rail getTransposition(RailType railType)`
- 作用：返回反向的同一段轨道（起终点互换），附加属性复制，`isSecondaryDir` 取反。传入 `RailType.NONE` 时新轨道类型为 NONE，否则沿用本轨道类型（传入值本身被忽略）。

### `void sendUpdateC2S()`
- 作用：`PacketUpdateRail.sendUpdateC2S(this, posStart, posEnd)`，把客户端改过的轨道属性发给服务器。

### `private Vec3 max(Vec3, Vec3)` / `private Vec3 min(Vec3, Vec3)`
- 分量取最大/最小。

### `private void onCreate(BlockPos posStart, RailAngle facingStart, BlockPos posEnd, RailAngle facingEnd, RailType railType, TransportMode transportMode, CallbackInfo ci)`
- 注入点：几何构造函数 `TAIL`。作用：记下两端坐标。

### `private BlockPos round(Vec3 pos)`
- 各分量向下取整。

### `private void fromMessagePack(Map<String, Value> map, CallbackInfo ci)`
- 注入点：`Rail(Map)` 构造 `TAIL`。作用：读上述存档键（缺省值见字段），解析失败的 Map 置空；没有 `pos_start`/`pos_end` 时用轨道 0 处和末端位置取整得到两端。

### `private void toMessagePack(MessagePacker messagePacker, CallbackInfo ci)`
- 注入点：`toMessagePack` 的 `TAIL`。作用：写 12 个键（见上）。

### `private void messagePackLength(CallbackInfoReturnable<Integer> cir)`
- 返回值 +12。

### `private void fromPacket(FriendlyByteBuf packet, CallbackInfo ci)`
- 注入点：`Rail(FriendlyByteBuf)` 构造 `TAIL`。作用：`enableRegistry` 为假或剩余字节 ≤ 4 时不读；读一个 int，不等于魔数就回退 4 字节；否则按上面的顺序读，`path_mode` 与两端角度读出后丢弃。

### `private void toPacket(FriendlyByteBuf packet, CallbackInfo ci)`
- 注入点：`writePacket` 的 `TAIL`。作用：按上面顺序写。

### `private double _getPositionY(double value)`
- 作用：复刻 MTR 原版的高度函数：缆车轨道（`CABLE`）两端各 0.5 格为端点高度，中间线性插值加下垂（最大下垂限制为 `MAX_CABLE_DIP`）；其他类型为前后两半各一段抛物线的 S 形坡。
- 参数：`value` 沿轨距离（格）。返回：y 坐标。

### `private void render(RenderRail callback, float offsetRadius1, float offsetRadius2, CallbackInfo ci)`
- 注入点：`Rail.render` 的 `HEAD`，总是取消原方法（整体替换）。
- 作用：长度非正或非有限直接返回。按约 1 格步长沿轨道取点，每一步用 `getLookAtMat`（含滚转）把横向偏移 `offsetRadius1/2` 变换为四个角点，回调 `callback.renderRail(x1,z1,x2,z2,x3,z3,x4,z4,y1,y2)`。长度 > 8192 时逐步计算不缓存；否则以几何参数、竖曲线、滚转偏移、方向、类型、长度、偏移量组成的 key 和 `rollAngleMap` 在 `mtrGeometryCache` 中缓存每步 10 个 float。
- 参数：`offsetRadius1/2` 横向偏移（格，左右边界）。

### `private Matrix4f getLookAtMat(Rail rail, double value)`
- 作用：在 `value` 处前后各取半个阈值的两点求切向和中点，结合 `RailExtraSupplier.getRollAngle` 的滚转角，返回 `BakedRail.getLookAtMat(mid, from, to, roll, 0, isSecondaryDir)`。`value` 被夹到 `[阈值/2, 长度-阈值/2]`。

### `private void onGetPositionY(double rawValue, CallbackInfoReturnable<Double> cir)`
- 注入点：`getPositionY` 的 `HEAD`，非缆车轨道时取消原方法（整体替换）。
- 作用：设高差 H、长度 L，最大可行半径 `(H²+L²)/(4H)`：
  - `verticalCurveRadius < 0`：直线坡；
  - `= 0` 或大于最大可行半径：`_getPositionY`（MTR 默认曲线）；
  - 否则：两端各一段半径为 `verticalCurveRadius` 的圆弧竖曲线，中间直线；`vTheta` 由 `RailExtraSupplier.getVTheta` 计算并缓存，非有限值时回退到默认曲线。
  最后加上 `rollingYOffset`（滚转引起的抬高）。
- 返回：y 坐标。

### `private double rollingYOffset(double rawValue)`
- 作用：该处滚转角 r 不为 0 时，返回 `|rollingOffset × sin(r)|`（由 `Vec2.rotateRad` 求得），即外轨抬高后轨道中心线的升高量。

### `boolean equals(Object o)` / `int hashCode()`
- 作用：以本段轨道完整 `writePacket` 的字节（含 ANTE 附加数据）判断相等与求哈希，首次需要时由 `createDataBytes` 计算并缓存。注意：缓存后字段再变（例如 `setModelKey`）不会更新 `dataBytes`。

### `private void createDataBytes()`
- 用共享的 `hashBuilder` 写包、拷贝字节、算 `Arrays.hashCode`。
