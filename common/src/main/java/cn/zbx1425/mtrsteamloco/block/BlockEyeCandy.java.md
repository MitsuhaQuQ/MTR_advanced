# BlockEyeCandy.java（ANTE）

ANTE 的"装饰物"方块（eye candy）及其方块实体：一个方块位置上显示资源包定义的任意模型（可平移、旋转、缩放），可由脚本驱动；还能设定形状/碰撞箱、发光等级，以及充当站台（列车开门时同步"站台门"动画）或检票闸机。服务端与客户端共用。在 `cn/zbx1425/mtrsteamloco/Main.java:62` 注册；渲染见 `render/block/BlockEntityEyeCandyRenderer`；列车扫描车门时识别它（`mixin/TrainMixin.java:215`）。本分支相对合并时的改动（`c53099f8`）：方块实体增加 `doorTrainId`、`syncTrainDoors`、`clearTrainDoors`，记住是哪列车在驱动站台门，只由该列车清除。

## 类 `BlockEyeCandy`（extends `BlockDirectionalMapper`，implements `EntityBlockMapper`）

### 常量
- `OPEN`：复用 MTR 闸机的开闭状态属性（`BlockTicketBarrier.OPEN`）。
- `LEVEL`：原版 `level` 属性（0–15），作为发光等级。
- `LIGHT_EMISSION`：取方块状态 `LEVEL` 作亮度的函数。

### 方块状态属性
`FACING`（水平朝向）、`LEVEL`、`OPEN`。

### 构造函数 `BlockEyeCandy()`
- 强度 2、发光按 `LEVEL`、动态形状；1.20 前为金属灰色材质。

### `BlockState getStateForPlacement(BlockPlaceContext ctx)`
- 朝向为玩家水平朝向，`LEVEL = 0`，`OPEN = CLOSED`。

### `void createBlockStateDefinition(StateDefinition.Builder)`
- 登记三个属性。

### `InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)`
- 作用：主手拿 MTR 刷子时，服务端发 `PacketScreen.sendScreenBlockS2C(player, "eye_candy", pos)` 让客户端打开编辑界面（客户端返回 PASS）；否则客户端调用方块实体的脚本 `use` 函数（找不到方块实体写警告），服务端 PASS。
- 返回：交互结果。

### `BlockEntityMapper createBlockEntity(BlockPos, BlockState)`
- 新建 `BlockEntityEyeCandy`。

### `RenderShape getRenderShape(BlockState)`
- `ENTITYBLOCK_ANIMATED`（只由方块实体渲染器画）。

### `VoxelShape getShape(...)` / `VoxelShape getCollisionShape(...)`
- 作用：按方块实体的形状字符串和朝向（`ShapeSerializer.getShape`）生成轮廓/碰撞形状；出错或无方块实体时，轮廓回退为整块，碰撞回退为 1×2 格高的盒子。

### `VoxelShape getOcclusionShape(...)`
- 空（不遮挡光线和相邻面）。

### `void entityInside(BlockState state, Level world, BlockPos pos, Entity entity)`
- 作用：仅当方块实体设为检票闸机时生效；服务端、玩家进入时，把玩家位置转到方块朝向坐标：闸机开着且玩家已走到前方（z > 0）则关闭；关着且玩家在后方（z < 0）则调用 `TicketSystem.passThrough(world, pos, player, 是否进站, 是否出站, 声音…, null, false)` 计算新状态，开闸后安排 40 tick 后的方块刻来关闭。
- 副作用：改方块状态、扣费（`TicketSystem`）、安排方块刻。

### `void tick(BlockState state, ServerLevel world, BlockPos pos)`
- 方块刻：把闸机关上。

## 内部类 `BlockEyeCandy.BlockEntityEyeCandy`（extends `BlockEntityClientSerializableMapper`）

### 字段
- `prefabId`：装饰物种类 id（null 为默认）。
- `translateX/Y/Z`（格）、`rotateX/Y/Z`（弧度）、`scaleX/Y/Z`：模型相对方块的变换。
- `fullLight`：是否全亮渲染。
- `customConfigs`（`Map<String,String>`）、`customResponders`（`Map<String, ConfigResponder>`）：脚本配置及其界面响应器。
- `scriptContext`：脚本上下文（种类有脚本时创建）。
- `asPlatform`：是否作为站台；`doorValue`（0–1 门开度）、`doorTarget`（目标开/关）、`doorTrainId`（当前驱动门的列车 id）。
- `shape`、`collisionShape`：形状字符串（默认 `"0, 0, 0, 16, 16, 16"` 和 `"0, 0, 0, 0, 0, 0"`）。
- `fixedMatrix`、`lightLevel`（0–15）、`isTicketBarrier`、`isEntrance`（作为闸机时是进站闸机还是出站闸机）。

### NBT 键
`prefabId`（空串表示 null）、`fullLight`、`customConfigs`（字节数组；旧键 `data` 读兼容）、`translateX/Y/Z`、`rotateX/Y/Z`、`scaleX/Y/Z`、`asPlatform`、`shape`、`collisionShape`、`fixedMatrix`、`lightLevel`、`isTicketBarrier`、`isEntrance`。门状态不存。

### 构造函数 `BlockEntityEyeCandy(BlockPos pos, BlockState state)`

### `void readCompoundTag(CompoundTag)` / `void writeCompoundTag(CompoundTag)`（@Override）
- 读写上述键；读时先 `setPrefabId`（种类改变会 `restore()` 重置为种类默认值），再读其余字段覆盖。配置反序列化失败静默忽略。

### `Matrix4f getBaseMatrix()`
- 作用：世界变换矩阵：平移到方块中心底部加偏移，按朝向转 `180° − facing`，再依次 X、Y、Z 旋转与缩放。

### `AABB getRenderBoundingBox()`
- 无限大的包围盒（永不按包围盒剔除）。

### `void setPrefabId(String new1)`
- 作用：种类改变时 `restore()`；未变但种类有脚本而还没有上下文时创建上下文。

### `void restore()`
- 作用：清空配置与响应器；旧脚本上下文标记 `disposeForReload`；按种类属性重设形状、碰撞形状、`fixedMatrix`、发光、闸机、进出站、站台；种类有脚本则新建上下文。

### `void setLightLevel(int)` / `int getLightLevel()`
- 0–15 才接受，否则写错误日志。

### `boolean setShape(String)` / `boolean setCollisionShape(String)`
- 作用：`ShapeSerializer.isValid` 通过且与当前不是同一字符串对象（用 `!=` 比较引用）时设置并返回 true；无效写错误日志返回 false。

### `String getShape()` / `String getCollisionShape()`
### `BlockPos getWorldPos()` / `Vector3f getWorldPosVector3f()` / `Vector3f getTransformPosVector3f()`
- 方块坐标；后者加上平移偏移。

### `void sendUpdateC2S()`
- `PacketUpdateBlockEntity.sendUpdateC2S(this)`：客户端编辑后发给服务器。

### `float getBlockYRot()`
- 朝向的 Y 角度（度），出错返回 0。

### `void setDoorValue(float)` / `void setDoorTarget(boolean)`

### `void syncTrainDoors(long trainId, boolean target, float value)`
- 记下列车 id 并设置门目标与开度。调用者：`common/src/main/java/mtr/data/TrainClient.java:277`（`syncPlatformDoor`，由 `TrainMixin.checkEyeCandy` 触发）。

### `void clearTrainDoors(long trainId)`
- 只有当前驱动门的就是该列车时才把门关上（目标 false、开度 0），避免另一列车误关。调用者：`common/src/main/java/mtr/data/TrainClient.java:287`。

### `boolean isPlatform()` / `boolean isTicketBarrier()`
### `boolean isOpened()`
- 闸机当前是否打开；读取失败写 info 日志返回 false。

### `EyeCandyProperties getProperties()`
- 按 `prefabId` 查 `EyeCandyRegistry`，找不到用 `EyeCandyProperties.DEFAULT`。

### `void tryCallUseFunctionAsync(Player player)`
- 有脚本上下文且种类有脚本时调用其 `use` 函数。

### `getCustomConfigs()` / `getCustomResponders()` / `getCustomConfig(String)` / `registerCustomConfig(ConfigResponder)` / `removeCustomConfig(String)` / `putCustomConfig(String, String)`
- 脚本配置的读写；`registerCustomConfig` 先让响应器用当前配置初始化再登记。
