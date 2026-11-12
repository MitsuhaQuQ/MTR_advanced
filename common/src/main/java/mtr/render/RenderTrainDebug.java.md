# RenderTrainDebug.java（本分支新增）

客户端调试显示（0.65.31）：玩家手持刷子（或配置打开了调试信息）看向一列列车时，在屏幕右上角显示服务器给出的该列车信息（计划与预计时间、晚点、已停时间与离关门还有多久、被扣原因、允许速度等，经 `TrainDebugPackets`）。0.66.4 起看向乘客信息屏（PIDS）、到站投影或时刻表传感器时，显示它读到的站台、本客户端的到站条目（被显示屏略去的打 `×`）以及传感器的设置与是否会触发。由 `RenderDrivingOverlay.render` 调用（`common/src/main/java/mtr/render/RenderDrivingOverlay.java:34`），所以两个加载器都会绘制。

## 类 `RenderTrainDebug`（final，私有构造）

### 常量与静态字段
- `MAX_DISTANCE = 96`（找列车的最大距离，格）、`MAX_AIM_DISTANCE = 3`（车厢到视线的最大距离）、`STALE_MILLIS = 3000`（回答超过 3 秒不显示）、`FIND_EVERY_FRAMES = 4`、`PADDING = 4`、`LINE_HEIGHT = 10`。
- `frames`（帧计数）、`aimedTrain`（当前看向的列车 id，0 为无）。

### `static void render(PoseStack matrices)`
- 作用：无玩家/窗口、隐藏界面、或既没拿刷子又没开 `Config.showDebugInfo()` 时清空并返回。每 4 帧重新找看向的列车；若准星对着 PIDS/投影/传感器方块且（没有列车或方块距离 < 4 格），显示方块信息并返回；否则有列车时 `TrainDebugPackets.sendRequestC2S`（内部限每秒一次），回答属于该列车且未过期时显示标题 `gui.mtr.train_debug_title` 与各行 `gui.mtr.train_debug_<键>: 值`（无行时 `gui.mtr.train_debug_value_gone`）。

### `private static void draw(PoseStack, Minecraft, Window, List<String> texts)`
- 右上角半透明黑底，第一行黄色，其余白色。

### `private static List<String> blockLines(BlockEntity entity, BlockPos pos)`
- 作用：PIDS：其站台（为空时取最近站台）与是否显示通过列车；到站投影：其站台；时刻表传感器：4 格内最近站台、触发方式、秒数、线路过滤。然后列出站台名、按时间排序的前 8 条到站（线路名、多少秒后到/已到；被显示屏略去的——不显示通过列车时的通过车、已过时的计划条目——前缀 `× `），每条下一行显示到达、离开（站着的列车用其 `departureMillis` 绝对值，否则到达 + 站台停站时间 × 500 毫秒）、计划时间、列车 id 尾部；`LEGACY` 触发的传感器显示是否会触发，其他触发方式显示由服务器判断。不是这三类方块返回 null。
- 注意：停站时间按 `× 500` 毫秒换算，即站台停站时间单位为 0.5 秒（据此推断）。

### `private static String clock(long millis)`
- 本机时区 `HH:mm:ss`。

### `private static String platformName(long platformId)`
- `车站名 站台名`，找不到站台时为 id。

### `static String value(String value)`（包级）
- 服务器发来的值中以 `#` 开头的词翻译为 `gui.mtr.train_debug_value_<词>`。

### `private static long findAimedTrain(Vec3 eye, Vec3 look)`
- 在客户端所有列车的每节车厢位置（抬高 1.5 格）中，找到离视线 < 3 格、沿视线最近（≤ 96 格）的列车 id；没有返回 0。
