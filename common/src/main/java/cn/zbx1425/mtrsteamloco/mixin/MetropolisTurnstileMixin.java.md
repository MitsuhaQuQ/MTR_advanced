# MetropolisTurnstileMixin.java（mixin，兼容 Metropolis）

服务端 mixin（`@Pseudo`，目标类不存在时忽略），注入附属模组 Metropolis（0.4.0-beta for MTR 3.2.2，Fabric）的闸机方块 `team.dovecotmc.metropolis.block.BlockTurnstile`。Metropolis 闸机按车站区（zone）从它自己的卡/单程票（钱存在物品 NBT 里）扣费；本 mixin 改为按距离计费（设计文档 6.13，0.68.0），仍从卡或票扣：进站时在物品里另记闸机位置和分钟数，出站时用 `TicketSystem.getTripFare` 算距离票价。没有这些记录的卡/票（本改动前进站、或在不属于任何车站的闸机进站）仍按区计费。Metropolis 的售票机和补票机不变，所以按目的地买的单程票余额可能多于或少于按距离的票价。

目标方法 `use` 是 Minecraft 的方法，因此用 Fabric intermediary 名 `method_9534` 定位；局部变量 `cost` 按名字定位。没有 Metropolis、在 Forge 上、或版本方法不同时（`require = 0`）不改变任何行为。

## 类 `MetropolisTurnstileMixin`

### 常量（物品 NBT 键）
- `mtr$ENTERED_GATE = "mtm_entered_gate"`：进站闸机坐标（`BlockPos.asLong()`）。
- `mtr$ENTERED_MINUTE = "mtm_entered_minute"`：进站时刻（`TicketSystem.getMinute()`）。
- `mtr$ENTERED_STATION = "entered_station"`：Metropolis 自己记录进站车站名的键。

### `private void mtr$recordEntry(CompoundTag tag, String key, String value, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)`
- 注入：重定向 `use` 中 `CompoundTag.putString(String, String)`（`class_2487.method_10582`）调用。
- 作用：照常写入；若写的是 `entered_station`，同时写 `mtm_entered_gate`、`mtm_entered_minute`。
- 副作用：修改玩家手中物品 NBT。

### `private void mtr$clearEntry(CompoundTag tag, String key, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)`
- 注入：重定向 `use` 中 `CompoundTag.remove(String)`（`method_10551`）调用。
- 作用：照常删除；删 `entered_station` 时一并删除本模组两个键（卡出站时）。

### `private int mtr$fareByDistance(int cost, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)`
- 注入：`@ModifyVariable` 在局部变量 `cost` 被赋值（STORE）时修改其值。
- 作用：读主手物品 NBT；没有进站闸机记录则保持原 `cost`；否则 `TicketSystem.getTripFare(world, 进站闸机, 当前闸机 pos, 当前分钟 − 进站分钟)`，结果 < 0（算不出）时保持原 `cost`。
- 返回：要扣的金额（Metropolis 的货币单位）。
- 注意：只看主手物品，与 Metropolis 实际使用的手是否一致未确定。
