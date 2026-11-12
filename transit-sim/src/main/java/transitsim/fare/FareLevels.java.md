# FareLevels.java

一个路线组（即一家铁路公司，设计文档 6.13）的票价规则：计价公式、由公式生成（或手工改过）的票价档位、可选的单程封顶价和"站内滞留超时"罚款。距离单位是方块（block），1000 方块 = 1 公里。纯数据 + 计算类，不依赖其他类；`FareTrip.charge` 用它算每段票价，`FareNetwork.getLongestBlocks` 给它提供"组内最长票价距离"。游戏侧 `NetworkSettings`（保存/包读写）和客户端 `FareScreen`（票价编辑界面）读写它的字段。

公式：票价 = 起步价 + ⌈max(0, 距离 − 起步距离) ÷ 单位距离⌉ × 单位票价。档位上界从起步距离开始每隔一个单位距离一个，一直到最长距离；某档覆盖"上一个上界之后到本档上界（含）"，超过最后上界按最后一档收费。

## 类 FareLevels（final）

### 常量
- `BLOCKS_PER_KILOMETRE = 1000`：每公里方块数。
- `MAX_TIERS = 200`：由公式生成的档位数上限；超过时最后一档即作为其余距离的封顶。

### 字段
- `startFare`（int，默认 2）：起步价。
- `initialBlocks`（long，默认 4000）：起步距离（方块）。
- `unitBlocks`（long，默认 4000）：每加价一次的单位距离（方块），`clamp` 后 ≥ 1。
- `unitFare`（int，默认 1）：每个单位距离加的票价。
- `manualTiers`（boolean）：档位是否手工修改过；为真时按 `bounds`/`prices` 原样使用，不再由公式生成。
- `bounds`（long[]）：除最后一档外每档的上界，升序；仅 `manualTiers` 时有效。
- `prices`（int[]）：每档票价，长度 = `bounds.length + 1`；仅 `manualTiers` 时有效。
- `maxBlocks`（long）：生成档位的最长距离；0 表示使用 `FareNetwork#getLongestBlocks` 算出的组内两站间最长票价距离。
- `cap`（int）：一段行程在本组内最多收多少，-1 表示不封顶。
- `overtime`（boolean）：是否启用同站进出超时罚款。
- `overtimeMinutes`（int，默认 120）：允许在站内停留的分钟数。
- `overtimeFine`（int，默认 20）：超时罚款金额。

### `FareLevels copy()`
- 作用：深拷贝所有字段（`bounds`/`prices` 数组 clone）。
- 参数：无。返回：新的 `FareLevels`。副作用：无。
- 主要调用者：`NetworkSettings.java:519`。

### `FareLevels clamp()`
- 作用：把从存档或网络包读入的值收回合法范围：票价、罚款、距离、时间都不小于 0（负票价会倒贴玩家），单位距离 ≥ 1，`cap` ≥ -1，`bounds` 每项 ≥ 0 后排序，`prices` 每项 ≥ 0。
- 返回：`this`（便于链式调用）。
- 副作用：就地改写本对象所有数值字段；`bounds`、`prices` 换成新数组。
- 主要调用者：`NetworkSettings.java:206`、`NetworkSettings.java:345`、`NetworkSettings.java:379`。

### `Tiers getTiers(long longestBlocks)`
- 作用：取当前生效的档位。若 `manualTiers` 且 `prices.length == bounds.length + 1` 且非空，返回手工档位的拷贝；否则用公式生成，最长距离取 `maxBlocks`（>0 时）或参数 `longestBlocks`。
- 参数：`longestBlocks` —— 当 `maxBlocks == 0` 时用于生成档位的最长距离（方块）。
- 返回：`Tiers{bounds, prices}`（新数组，可随意修改）。副作用：无。
- 主要调用者：`FareScreen.java:171`、`FareScreen.java:321`；本类 `getFare`（第 153 行）。

### `boolean needsLongest()`
- 作用：判断票价是否依赖算出的组内最长距离。只有"非手工档位且 `maxBlocks <= 0`"时才需要；否则调用方可以不去计算最长距离。
- 返回：需要则 true。副作用：无。
- 主要调用者：`FareTrip.java:101`。

### `Tiers formulaTiers(long longestBlocks)`
- 作用：按公式生成到 `longestBlocks` 为止的档位。档数 `count = ⌈(longest − initial) / unit⌉`（longest ≤ initial 时为 0），最多 `MAX_TIERS - 1`；第 i 档价格 `startFare + i × unitFare`，第 i 个上界 `initial + i × unit`。内部对 `unitBlocks`、`initialBlocks` 再做一次下限保护。
- 参数：`longestBlocks` —— 最长距离（方块）。
- 返回：新的 `Tiers`（bounds 长 count，prices 长 count+1）。副作用：无。
- 主要调用者：`FareTest.java:27`；本类 `getTiers`（第 106 行）。

### `void setTiers(long[] newBounds, int[] newPrices)`
- 作用：把给定档位存为手工档位：上界拷贝后排序，价格拷贝，置 `manualTiers = true`。
- 参数：`newBounds` 上界（方块，任意顺序）；`newPrices` 价格，长度必须为 `newBounds.length + 1`。
- 返回：无。
- 副作用：改写 `bounds`、`prices`、`manualTiers`；长度不符时抛 `IllegalArgumentException("a tier more than bounds")`。
- 主要调用者：`NetworkSettings.java:342`、`NetworkSettings.java:455`、`FareScreen.java:175`。

### `int getFare(long blocks, long longestBlocks)`
- 作用：计算一段行程（在本组内的那一部分）的票价：找到第一个 `blocks <= bound` 的档位（距离恰好等于上界算本档），取其价格，再按 `cap` 封顶。
- 参数：`blocks` 本段距离（方块）；`longestBlocks` 传给 `getTiers` 的最长距离。
- 返回：票价（≥ 0，若数据已 clamp）。副作用：无。
- 主要调用者：`FareTrip.java:101`、`FareTest.java:31`、`NetworkSettingsTest.java:109`。

### `int getSameStationFare(long minutesInside)`
- 作用：同站进出的收费：若启用超时且停留分钟数 > `overtimeMinutes`，收 `overtimeFine`（不受封顶影响）；否则收第一档价格（手工档位取 `prices[0]`，否则 `startFare`），再按 `cap` 封顶。
- 参数：`minutesInside` 在站内（进闸到出闸）的分钟数。
- 返回：应收金额。副作用：无。
- 主要调用者：`TicketSystem.java:287`、`FareTrip.java:141`、`FareTest.java:56`。

### `boolean equals(Object)` / `int hashCode()`
- 作用：按值比较/求哈希。手工档位的 `bounds`/`prices` 只在 `manualTiers` 为真时参与比较（否则它们不保存也不发送，不应影响相等）；超时参数在 hashCode 中只在 `overtime` 为真时参与，但 equals 中始终比较。
- 主要调用者：`NetworkSettings.java:115`、`FareData.java:53`（判断设置是否变化）。

## 内部类 Tiers（public static final）
档位的不可变容器（数组本身未做防御拷贝）。
- 字段 `bounds`（long[]）：上界，升序，单位方块；`prices`（int[]）：各档票价，比 `bounds` 多一项。
- 构造函数 `Tiers(long[] bounds, int[] prices)`：直接保存两个数组，不校验。调用者：本类 `getTiers`（第 104 行）、`formulaTiers`（第 132 行）。
