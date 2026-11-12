# TrainMixin.java（ANTE mixin）

服务端与客户端共用的 mixin，作用于 `mtr.data.Train`（列车基类），实现 ANTE 的 `TrainExtraSupplier` 接口。提供：列车自定义配置（脚本用的键值对）的保存/同步；按轨道位置取滚转角；扫描车门时把 ANTE 装饰物（设为站台的 eye candy）和 Metropolis 的站台方块也当作站台；以及 ANTE 的 P 速度轨道类型下的人工驾驶限速。本分支的改动：自定义配置不再挂在 `Train` 字段上，而是放在 `TrainIdentity` 附件 `TrainAnteState` 中（R1，列车换停车轨也带着走）；`getSyncKey` 把配置的哈希算进去（G2，配置变化时重新发完整包）；`scanDoors` 和 `convertMaxManualSpeed` 的替换只在 `SecurityConfig.anteRunHooks` 打开时生效（默认隔离，只用 MTR 自己的规则）。

## 类 `TrainMixin`（abstract，implements `TrainExtraSupplier`）

### 影子成员
- `path`（`List<PathData>`）、`distances`（`List<Double>`，各路径段末端累计距离）、`identity`（`TrainIdentity`）。
- `skipScanBlocks(...)`、`openDoors(...)`、`getIndex(double, boolean)`：`Train` 的受保护方法。

### 存档/包字段
- msgpack 键 `custom_configs`：`StringMapSerializer` 序列化后的字符串；本 mixin 让 `messagePackLength()` +1。
- NBT 键 `custom_configs`（旧格式构造函数读取）。
- 完整同步包：在 `Train.writePacket` 末尾追加一个 UTF 字符串（序列化后的配置）；`Train(FriendlyByteBuf)` 构造末尾读回。

### `private TrainAnteState mtrsteamloco$state()`
- 取（或新建）`identity` 上键为 `TrainAnteState.KEY` 的附件。

### `getCustomConfigs()` / `setCustomConfigs(Map)` / `isConfigsChanged()` / `isConfigsChanged(boolean)` / `setConfigResponders(Map)` / `getConfigResponders()`
- 作用：读写 `TrainAnteState` 中对应字段（接口实现）。调用者：ANTE 的配置包 `PacketUpdateTrainCustomConfigs`、脚本、`TrainClientMixin.copyFromTrainTail` 等。

### `float getRollAngleAt(double value)`
- 作用：`value` 为沿路径的进度（格）；找到所在路径段（`getIndex(value, true)`），换算为段内距离，返回 `RailExtraSupplier.getRollAngle(该段轨道, 段内距离)`。
- 返回：滚转角（单位按 `RailExtraSupplier`，推测为弧度）。

### `private void fromMassagePack(long sidingId, float railLength, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime, Map<String, Value> map, CallbackInfo ci)`
- 注入点：msgpack 构造函数 `TAIL`。作用：读 `custom_configs`，解析失败置空 Map。

### `private void fromCompoundTag(..., CompoundTag compoundTag, CallbackInfo ci)`
- 注入点：NBT 构造函数 `TAIL`。作用同上，从 NBT 读。

### `private void fromFriendlyByteBuf(FriendlyByteBuf buffer, CallbackInfo ci)`
- 注入点：包构造函数 `TAIL`。作用：读一个 UTF 字符串解析为配置。

### `private void toMessagePack(MessagePacker messagePacker, CallbackInfo ci)`
- 注入点：`toMessagePack` 的 `TAIL`。作用：写键 `custom_configs` 和序列化字符串（失败写空串）。

### `private void messagePackLength(CallbackInfoReturnable<Integer> cir)`
- 注入点：`messagePackLength` 的 `TAIL`。作用：返回值 +1。

### `private void getSyncKey(CallbackInfoReturnable<Integer> cir)`
- 注入点：`getSyncKey` 的 `RETURN`。作用：`原值 * 31 + hashCode(customConfigs)`，配置改变即同步键改变，客户端会收到完整包（G2）。

### `private void toPacket(FriendlyByteBuf packet, CallbackInfo ci)`
- 注入点：`writePacket` 的 `TAIL`。作用：追加序列化的配置字符串。

### 静态字段 `IBlockPlatformClass`
- 静态初始化时尝试加载 Metropolis 的 `team.dovecotmc.metropolis.block.interfaces.IBlockPlatform`，失败则为私有空接口 `Void`（这样 `isInstance` 永远 false），并写 info 日志。

### `private void onScanDoors(Level world, double trainX, double trainY, double trainZ, float checkYaw, float pitch, double halfSpacing, int dwellTicks, CallbackInfoReturnable<Boolean> ci)`
- 注入点：`scanDoors` 的 `HEAD`，可取消（`MixinLintTest` 允许项，`common/src/test/java/cn/zbx1425/mtrsteamloco/MixinLintTest.java:57`）。
- 作用：`anteRunHooks` 关闭时直接返回，走 MTR 原逻辑。打开时整体替换：`skipScanBlocks` 为真返回 false；否则在列车侧向 1–3 格、竖向 -2…3、沿车长 ±`halfSpacing` 的范围内逐格检查：
  - MTR 站台、屏蔽门/安全门、Metropolis 站台：`openDoors`，记有站台；
  - ANTE 装饰物：从该格沿 x、y、z 正负方向各最多 40 格扩展，只要连着的装饰物 `isPlatform()` 就记有站台（用 `OKPos` 去重），客户端上同时同步站台门动画。
  与 MTR 不同，开门后仍继续扫描。
- 返回（经 `ci`）：是否找到站台。

### `private boolean checkEyeCandy(Level world, BlockPos pos, boolean isClientSide)`
- 作用：该位置的方块实体是装饰物且 `isPlatform()` 时返回 true；客户端且本列车是 `TrainClient` 时调用 `syncPlatformDoor(e)` 同步门。

### 私有接口 `Void`
- 占位类型，Metropolis 不存在时让 `IBlockPlatformClass.isInstance` 恒为 false。

### `private static void convertMaxManualSpeed(int maxManualSpeed, CallbackInfoReturnable<RailType> ci)`
- 注入点：`Train.convertMaxManualSpeed` 的 `HEAD`，可取消（`MixinLintTest` 允许项，`:49`）。
- 作用：`anteRunHooks` 关闭时不处理（MTR 自己的速度，最高到钻石轨道）；打开时把 `maxManualSpeed` 当作 `RailType.values()` 下标（截断到最大值）直接返回，使 ANTE 新增的 P 速度轨道类型可作为人工驾驶上限。
