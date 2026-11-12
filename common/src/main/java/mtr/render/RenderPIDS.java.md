# RenderPIDS.java

客户端：乘客信息显示屏（PIDS，横排/竖排/单班次型）和到站投影的方块实体渲染器，显示本站各站台的到站列车（目的地、站台号、到站时间、经停站、车厢数）及自定义消息。各 PIDS 方块类型以不同参数注册（`MTRClient`）。本分支改动：时间以服务器时钟为准（`ClientClock.INSTANCE.serverMillis()`，G2）；列车停在站台时显示发车时刻（`gui.mtr.departure_time`，0.67.0；`departureMillis` 取绝对值，在运营时区格式化）；计划（`planned`）班次显示计划时刻 `gui.mtr.arrival_planned`，已过时的计划班次不显示；晚点分钟数附加在分钟显示后（`getDelayMinutes`）；通过列车目的地显示为"通过"（`gui.mtr.passing`），只有 PIDS 打开"显示通过列车"时才列出；经停站跳过通过站（`getCallingStations`）；数据源改为 `ClientData.ALL_SCHEDULES_FOR_PLATFORM`；文字绘制改用 `GuiCompat`。

## 类 `RenderPIDS<T extends BlockEntityMapper>`（extends `BlockEntityRendererMapper<T>`，implements `IGui`）

### 常量
- `PLANNED_TIME = HH:mm`。
- `MAX_VIEW_DISTANCE = 16`（最大可视距离，格；也被升降梯显示使用）、`SWITCH_LANGUAGE_TICKS = 60`（多语言名称轮换周期）、`CAR_TEXT_COLOR = 0xFF0000`、`STATIONS_PER_PAGE = 10`、`SWITCH_PAGE_TICKS = 120`（单班次型经停站翻页周期）。

### 字段（构造时确定的版面参数）
- `scale`、`totalScaledWidth`、`destinationStart`、`destinationMaxWidth`、`platformMaxWidth`、`arrivalMaxWidth`、`callingAtMaxWidth`、`callingAtStationMaxWidth`、`maxArrivals`（显示班次数）、`linesPerArrival`（每班次行数）、`maxHeight`、`startX/Y/Z`（显示区起点，方块内像素/16）、`rotate90`、`renderArrivalNumber`、`renderType`（`PIDSType`）、`textColor`、`firstTrainColor`、`appendDotAfterMin`。

### 构造函数
- `RenderPIDS(BlockEntityRenderDispatcher, int maxArrivals, int linesPerArrival, float startX, float startY, float startZ, float maxHeight, int maxWidth, boolean rotate90, boolean renderArrivalNumber, PIDSType renderType, int textColor, int firstTrainColor, float textPadding, boolean appendDotAfterMin)`：按类型计算各列宽度。
- 较短的重载：`textPadding = 1`、`appendDotAfterMin = false`。

### `void render(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)`
- 作用：超出可视距离或玩家在背面（非 90° 旋转型）不画；双格高方块只画上半；读自定义消息和隐藏行设置；`getSchedules` 取班次；按需显示车厢数（班次车厢数不同或单班次型）；按显示页偏移逐班次、逐行绘制：目的地（轻轨带线路号；多语言名称按 60 tick 轮换；有自定义消息时与之轮换）、站台号、到站时间（见上文改动）、单班次型的"经停"与分页经停站、车厢数。任何异常被吞掉（不影响其他渲染）。

### `private static List<Route.RoutePlatform> getCallingStations(Route route, int currentStationIndex)`
- 当前站之后、不通过的各站。

### `List<ScheduleEntry> getSchedules(T entity, BlockPos pos, Map<Long, String> platformIdToName)`
- 作用：找到方块所在车站及其站台；按类型决定站台过滤（到站投影/单班次型用方块设置的站台；PIDS/竖排型设置为空时用最近站台）；从 `ALL_SCHEDULES_FOR_PLATFORM` 取班次：线路存在、计划班次未过时、（显示通过列车或不是通过车）、（类型显示终到班次或不是本线终点站）的条目；同时填 `platformIdToName`。
- 返回：按时间排序的列表。
