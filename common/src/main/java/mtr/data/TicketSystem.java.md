# TicketSystem.java

检票闸机（设计文档 6.13，0.68.0）。一次行程从在某个闸机进站到在另一个闸机出站，按计费路径的距离收费（组件 `FareTrip`）；闸机代表其所在车站的票务规则（`FareData#getGate`）。进站记录闸机位置与时间。余额与记录保存在计分板上，以玩家 UUID 为持有者。以玩家名保存的余额（0.68.0 之前，以及 `/scoreboard players add <name> mtr_balance` 等命令仍按名字）在每次使用时并入。

与其它模组的兼容：保留公共成员，因为 YJCM 的查询机与补贴机调用它们。YJCM 的 `MixinTicketBarrier` 以 `defaultRequire: 1` 注入 `onExit(Station, Player, Score, Score, boolean)`，所以必须保留这个名字和签名的方法（永不调用）；其"省钱卡"（fare saver）折扣改在这里按距离票价给出（`applyFareSaver`）。Metropolis 的闸机经 `MetropolisTurnstileMixin` 调用 `getTripFare`/`getMinute`。调用者：闸机方块 `BlockTicketBarrier.java:47`、`BlockTicketProcessor.java:45`、`BlockEyeCandy.java:197`；充值 `PacketTrainDataGuiServer.java:1237-1239`。

## class TicketSystem

### 常量
- `BALANCE_OBJECTIVE = "mtr_balance"`（public）：余额。
- `ADD_BALANCE_OPTIONS = 10`（public）：售票机充值档位数。
- `EMERALD_TO_DOLLAR = 10`。
- `ENTRY_ZONE_OBJECTIVE = "mtr_entry_zone"`：在站内时非 0（与旧版相同）。
- `ENTRY_GATE_HIGH_OBJECTIVE = "mtr_entry_gate_hi"`、`ENTRY_GATE_LOW_OBJECTIVE = "mtr_entry_gate_lo"`：进站闸机位置 `BlockPos.asLong()` 的高/低 32 位（0.68.0）。
- `ENTRY_MINUTE_OBJECTIVE = "mtr_entry_minute"`：进站的分钟数（`currentTimeMillis / 60000`，截断为 int）。
- `EVASION_FINE = 500`：逃票罚款。

### `public static int getAddAmount(int index)`
- 返回：第 index 档充值金额 `ceil(2^index × (10 + index))`。
- 调用者：`PacketTrainDataGuiServer.java:1239`、`TicketMachineScreen.java:95`。

### `public static EnumTicketBarrierOpen passThrough(Level world, BlockPos pos, Player player, boolean isEntrance, boolean isExit, SoundEvent entrySound, SoundEvent entrySoundConcessionary, SoundEvent exitSound, SoundEvent exitSoundConcessionary, SoundEvent failSound, boolean remindIfNoRecord)`
- 作用：玩家通过闸机。无铁路数据或闸机不在车站内时 `CLOSED`。进出双向闸机按"是否在站内"决定进还是出。进站 `onEnter`，出站 `chargeOnExit`；成功播放进/出站音效，失败播放 `failSound`。优惠音效参数不再使用（0.68.0 起创造模式玩家同价）。
- 参数：`remindIfNoRecord` 为真时，重复进站/无记录出站会提示并拒绝；为假时按逃票罚款处理。
- 返回：`OPEN` 或 `CLOSED`。
- 副作用：修改计分板、发送提示消息、播放声音。

### `public static void addObjectivesIfMissing(Level world)`
- 作用：创建上述 5 个计分项（如不存在）。调用者：`PacketTrainDataGuiServer.java:1237`、本类。

### `private static void addObjective(Level world, String name, String displayName)`
- 作用：不存在时以 DUMMY 准则创建，忽略异常。

### `public static Score getPlayerScore(Level world, Player player, String objectiveName)`
- 作用：取以 UUID 为持有者的分数。若该计分项中存在以玩家名为持有者的分数：余额项总是并入（相加，限制在 int 范围）并删除名字分数；其它项仅在 UUID 尚无分数时迁移过去。
- 调用者：`BlockTicketProcessorEnquiry.java:24`、`PacketTrainDataGuiServer.java:141`、`PacketTrainDataGuiServer.java:1238`。

### `static int mergeScore(int byHolder, int byName, boolean add)`
- 返回：`add` 时两者之和（夹在 int 范围内），否则 `byName`。测试：`TicketSystemCompatTest.java:46`。

### `private static boolean onEnter(...)`
- 作用：已在站内时：`remindIfNoRecord` 则提示 `gui.mtr.already_entered` 并拒绝；否则清除记录并罚款 500。余额 ≥ 0 时记录在站内、闸机位置、进站分钟，提示 `gui.mtr.enter_barrier`（车站名与规则、余额），放行；余额为负提示 `gui.mtr.insufficient_balance` 并拒绝。

### `private static boolean onExit(Station station, Player player, Score balanceScore, Score entryZoneScore, boolean remindIfNoRecord)`
- 作用：仅为 YJCM 的 mixin 保留签名，返回 `false`，从不调用。

### `private static int applyFareSaver(Player player, int fare)`
- 作用：YJCM（1.2.x）省钱卡：若 `Joban.discountMap` 中有该玩家 UUID 的折扣，取出并用掉，从票价中扣除 `min(discount, fare)`（负折扣即 YJCM 的"讽刺"设置，会加价），在聊天栏提示（`gui.jsblock.faresaver.saved` 或 `..._sarcasm`）。没有 YJCM 时不做任何事。
- 返回：折后票价。

### `static int fareSaverSaving(int discount, int fare)`
- 返回：`min(discount, fare)`。测试：`TicketSystemCompatTest.java:55`。

### `private static Object2IntMap<UUID> getFareSaverDiscounts()`
- 作用：首次调用时用反射依次尝试 `fabric.com.jsblock.Joban`、`forge.com.jsblock.Joban`、`com.jsblock.Joban` 的静态字段 `discountMap`，缓存结果（可能为 `null`）。

### `private static boolean chargeOnExit(...)`
- 作用：无进站记录时：`remindIfNoRecord` 则提示 `gui.mtr.already_exited` 并拒绝；否则罚款 500 并放行。有记录时：读出进站闸机位置与分钟，清除全部记录，计算票价（`getTripFare`）并应用省钱卡，扣款，提示 `gui.mtr.exit_barrier`（车站、距离 km、票价、余额），放行。

### `public static int getTripFare(Level world, BlockPos entryPos, BlockPos exitPos, long minutesInside)`
- 作用：两闸机之间行程的按距离票价，供带独立余额的其它模组闸机使用（Metropolis）。
- 返回：票价；出站闸机不在车站内返回 −1。
- 调用者：`MetropolisTurnstileMixin.java:73`。

### `public static long getMinute()`
- 返回：当前分钟数（与进站记录一致）。调用者：`MetropolisTurnstileMixin.java:48`。

### `private static int getTripFare(RailwayData railwayData, BlockPos entryPos, FareTrip.Gate gate, long minutesInside, long[] blocks)`
- 作用：按进站闸机所在车站（以当前设置为准）计算到出站闸机的票价：`FareTrip.charge(计费网络, 规则→档位, 进站闸机, 出站闸机, 站内分钟)`，结果 ≥0 时返回并把计费路径长度写入 `blocks[0]`。否则（进站位置未知——0.68.0 之前的记录——或不连通）返回出站规则（取第一个）的同站票价 `getSameStationFare(0)`。

### `private static String kilometresText(long blocks)`
- 返回：`" %.1f km"`（按 `FareLevels.BLOCKS_PER_KILOMETRE` 换算）；0 时为空。

### `private static String stationText(RailwayData railwayData, Station station, FareTrip.Gate gate)`
- 返回：格式化车站名，后附 `" (规则名, ...)"`。

## public enum EnumTicketBarrierOpen implements StringRepresentable
- `CLOSED("closed")`、`OPEN("open")`、`OPEN_CONCESSIONARY("open_concessionary")`：后者 0.68.0 起不再使用，为其它模组与已存的方块状态保留。
- `getSerializedName()`：返回名字。
- `isOpen()`：`this != CLOSED`。调用者：`BlockEyeCandy.java:194`。
