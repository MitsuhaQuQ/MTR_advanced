# DwellTimeBveTrainSound.java（ANTE）

客户端列车声音：在 ANTE 的 BVE 声音（`BveTrainSound`）基础上，按停站时间播放开门声和关门提示音。由 ANTE 的 `CustomResources` 为 DK3、D51 等内置车型创建（`common/src/main/java/cn/zbx1425/mtrsteamloco/CustomResources.java:84`、`:106`）。本分支改动（0.65.30）：关门提示音的结束时刻对齐服务器告知的"车门关完"时刻（`train.getDoorsCloseTicksLeft()`），而不是按站台轨道的停站时间推算——时刻表把发车推迟时，后者会导致提示音响完车门却仍开着；服务器也不知道时（列车被调度员扣住或等前方进路）就等到车门开始关时再播。

## 类 `DwellTimeBveTrainSound`（extends `TrainSoundBase`）

### 字段
- `bveTrainSound`：内部的 BVE 声音实例（运行、刹车等声音由它负责）。
- `train`：所属客户端列车（模板实例为 null）。
- `oldStopTicks`（public）：上次记录的已停站 tick 数。
- `elapsedDwellTicks`：本次停站已过 tick 数（按帧时长累加）。
- `totalDwellTicks`：本次停站总 tick 数（开门时从列车读取）。
- `lastRenderedTick`：上次处理时的游戏 tick，用于同一 tick 内不重复累加。
- `closePlayed`：本次停站是否已播关门提示音。

### 构造函数 `DwellTimeBveTrainSound(BveTrainSoundConfig config)`
- 作用：以声音配置创建模板实例（内部新建 `BveTrainSound`）。

### 私有构造函数 `DwellTimeBveTrainSound()`
- 供 `createTrainInstance` 使用。

### `TrainSoundBase createTrainInstance(TrainClient train)`（@Override）
- 作用：为具体列车创建实例：复制内部 BVE 声音的列车实例并记下 `train`。
- 调用者：MTR 为每列客户端列车创建声音时。

### `void playNearestCar(Level level, BlockPos blockPos, int i)` / `void playAllCars(Level level, BlockPos blockPos, int i)`（@Override）
- 作用：直接转给内部 BVE 声音。`i` 为车厢序号。调用者：`TrainClientMixin.handlePositions` / `simulateCar`。

### `void playAllCarsDoorOpening(Level world, BlockPos pos, int carIndex)`（@Override）
- 作用：车门打开期间每帧被调用（`TrainClientMixin.simulateCar` 中门开着时）。非客户端世界或无列车时返回。按帧时长累加 `elapsedDwellTicks`（暂停或同一 tick 不累加）；列车刚开门时重置计数、读 `getTotalDwellTicks()`、清 `closePlayed`。判断是否该播关门音：服务器给出剩余关门 tick（≥ 0）时，在 `剩余 ≤ max(0, 提示音长度×20 − DOOR_MOVE_TIME)` 时播（提示音在车门关完时结束；比车门动作还短的提示音则在开始关门时播）；否则在 `train.justClosing(1)` 时播。刚开门且配置有开门声则播开门声；否则满足上述条件、尚未播过、门仍开着时播关门声并记 `closePlayed`。
- 副作用：在 `pos` 播放本地声音（`SoundSource.BLOCKS`，音量 1，音调 1）。

### `private static void playLocalSound(Level world, SoundEvent event, BlockPos pos)`
- `event` 非空时 `ClientLevel.playLocalSound`。
