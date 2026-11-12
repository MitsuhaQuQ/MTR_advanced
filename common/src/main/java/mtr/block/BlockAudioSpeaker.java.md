# BlockAudioSpeaker.java（本分支新增）

"音箱"方块及其方块实体：播放玩家上传到服务器的广播音频（一组片段依次播放），有三种模式：循环、红石上升沿触发一次、玩家进入区域触发一次。方块本身不可见（`RenderShape.INVISIBLE`），客户端按设置的"外观"方块绘制（`RenderAudioSpeaker`）。服务端只负责判断何时播放、谁能听到，并发包（`AudioSpeakerPackets`）；实际播放在客户端 `SpeakerPlayback`。注册于 `common/src/main/java/mtr/Blocks.java:205`，方块实体类型 `common/src/main/java/mtr/BlockEntityTypes.java:68`。

## 类 `BlockAudioSpeaker`（final，extends `BlockMapper`，implements `EntityBlockMapper`）

### 构造函数 `BlockAudioSpeaker()`
- 属性复制平滑石，不遮挡。

### `BlockEntityMapper createBlockEntity(BlockPos, BlockState)` / `BlockEntityType<?> getType()`
- 新建 `TileEntityAudioSpeaker`；类型为 `AUDIO_SPEAKER_TILE_ENTITY`。

### `InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)`
- 作用：手持刷子时（`IBlock.checkHoldingBrush`），服务端对有编辑权限（非 `RailwayData.hasNoPermission`）的玩家发 `AudioSpeakerPackets.open` 打开设置界面。

### `<T> void tick(Level world, BlockPos pos, T blockEntity)`
- 服务端每 tick 调 `tickSpeaker`。

### `void neighborChanged(...)`
- 服务端邻居变化时 `samplePower`（立即响应红石）。

### `RenderShape getRenderShape(BlockState)`
- `INVISIBLE`。

### `static BlockState appearance(ResourceLocation id)`
- 按方块 id 取默认状态，只有普通模型方块（`RenderShape.MODEL`）可用作外观，否则 null。调用者：`common/src/main/java/mtr/packet/AudioSpeakerPackets.java:42`、`screen/AudioSpeakerScreen.java:143`。

## 内部类 `TileEntityAudioSpeaker`（final，extends `BlockEntityClientSerializableMapper`）

### 字段
- `trigger`（`SpeakerSettings.Trigger`，上升沿检测）、`listeners`（`SpeakerListeners`，循环听众）、`settings`（`SpeakerSettings`）、`loopTicks`（循环计时）、`playbackGate`（`transitsim.sim.PlaybackGate`：两次触发的最小间隔与"播完再触发"判定，在仿真组件中实现）。

### 构造函数 `TileEntityAudioSpeaker(BlockPos pos, BlockState state)`
- 设置为 `SpeakerSettings.defaults(pos)`。

### `void readCompoundTag(CompoundTag)` / `void writeCompoundTag(CompoundTag)`
- 读：先停止循环，`SpeakerSettings.read`，重置触发器、播放闸门与计时。写：`settings.write`（NBT 键见 `SpeakerSettings`）。

### `void applySettings(SpeakerSettings settings)`
- 作用：停止旧循环，换设置并重置；服务端上用当前红石/区域状态作为触发基准（编辑后不会立即误触发）；标记保存并同步客户端。调用者：`AudioSpeakerPackets` 的保存处理。

### `BlockState appearance()`
- 设置的外观方块，不可用时为唱片机。

### `private void tickSpeaker(ServerLevel world)`
- 无声音则不做。`LOOP`：有红石信号或有片段未上传时停止循环；否则每 5 tick 更新一次听众，每 20 tick 对全部听众刷新一次（`refresh`）。`REDSTONE`：`samplePower`。`REGION`：区域内有人（非旁观者）的上升沿时 `playOnce`。

### `private void samplePower(ServerLevel world)`
- 循环模式通电即停；红石模式在上升沿 `playOnce`。

### `private boolean regionOccupied(ServerLevel world)`
- 触发区域（中心 `triggerCenter`、半径 `triggerRadius`）内是否有非旁观玩家。

### `private List<String> hashes()`
- 各片段的上传文件哈希；任一未上传返回 null。

### `private void playOnce(ServerLevel world)`
- 作用：总时长 = 各片段时长与间隔之和（`transitsim.sim.ClipSequence.totalTicks`）；`playbackGate.accept(游戏时间, 总时长, finishBeforeRepeat, 间隔 tick)` 拒绝则不播；否则对能听到的每个玩家发 `Action.ONCE`。

### `private void updateLoop(ServerLevel world, List<String> hashes, boolean refresh)`
- 作用：统计世界内玩家与可听玩家，`listeners.update` 得到需通知者，对仍在本世界的发 `Action.LOOP`。离开可听范围只会让客户端静音，回来时不重新开始播放。

### `private void stopLoop()`
- 对之前所有仍在本世界的听众发 `AudioSpeakerPackets.stop`。

### `void setRemoved()`
- 方块被移除时停止循环。

### `private static boolean inside(ServerPlayer, BlockPos center, int radius)` / `private boolean canHear(ServerPlayer)`
- 区域判定（全方向）；可听判定（半径 + 听众方向掩码，`SpeakerSettings.audibleFrom`）。
