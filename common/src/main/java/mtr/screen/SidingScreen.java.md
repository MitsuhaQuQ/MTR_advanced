# SidingScreen.java（上游文件，本分支有较多改动）

客户端界面：游戏中的停车轨设置——编号、颜色和车辆。停车轨容纳多少列车以及列车如何运行（加减速、最高速度、手动驾驶、服务种类）在这里只做显示，按运行时所用的值；这些都在网页编辑器的车厂对话框中设置（5c），所以关闭本界面时不会把网页设置的值写回。打开：`common/src/main/java/mtr/screen/DashboardScreen.java:531`。

本分支改动：去掉了原来的列车数、加速度、手动驾驶等编辑控件，改为只读显示（`renderSettings`）；容量文字对按时刻表运行的交通方式改为"容纳列车数"（与网页编辑器一致，2026-11-07 审查）；车辆设置按车厂/车辆池取（`getVehicle`）；关闭时只发送名称与颜色。

## 类 `SidingScreen`（extends `SavedRailScreenBase<Siding>`，implements `Icons`）

### 字段与常量
- `isSelectingTrain`：是否在选车辆列表中；`transportMode`；`buttonSelectTrain`；`availableTrainsList`（搜索文字 `ClientData.TRAINS_SEARCH`）；`depot`：所属车厂（`DATA_CACHE.sidingIdToDepot`）。
- 文字常量：已选车辆、最大列车数、容纳列车数、加速度、手动转自动时间、最高手动速度、服务种类。
- `DESCRIPTION_WIDTH = 160`；`ACCELERATION_UNIT_CONVERSION_1 = 400`（m/tick² → m/s²）、`_2 = 400 × 3.6`（→ km/h/s）。
- `WIKIPEDIA_ARTICLES`：维基摘要缓存。

### 构造函数 `SidingScreen(Siding siding, TransportMode transportMode, DashboardScreen dashboardScreen)`
- 传给基类的标签列表决定左侧文字宽度；找所属车厂；建车辆按钮与列表。

### `protected void init()`
- 车辆按钮、车辆列表（初始在屏幕外），进入非选择状态。

### `private static Component capacityText(TransportMode)`
- 支持时刻表时为"容纳列车数"，否则"最大列车数"。

### `private VehicleSettings getVehicle()`
- 返回：有车厂时为车厂给该停车轨（其车辆池）的设置 `depot.getVehicleSettings(siding)`，否则停车轨自己的设置。

### `void tick()`
- 列表 tick。

### `void render(…)`
- 非选择状态：车辆标签，按时刻表时显示设置。选择状态：悬停的车辆显示名称、车长（间距 − 1）、可生成的车厢数（不足一节时加警告，上限为交通方式最大长度）、描述与维基摘要（右侧换行，超出显示 "..."）。

### `private void renderSettings(PoseStack)`
- 只读显示：容量（−1 为不限）、加速度与减速度、服务种类（按时刻表且有车厂时，`depot.vehicleServiceTypes`）、是否允许手动驾驶与最高速度（0 为无）、允许手动时的最高手动速度与手动转自动时间（半秒 / 2 秒）；底部提示"在网页设置"。

### `private void drawSetting(PoseStack, Component label, String value, int row, int valueX)` / `private static int rowY(int row)`
- 一行标签与值；行 y。

### `void onClose()`
- 只发送名称与颜色（`setNameColor`），再调用基类。

### `void mouseMoved(…)` / `boolean mouseScrolled(…)` / `protected boolean shouldRenderExtra()` / `protected void renderExtra(…)`
- 转发给车辆列表；选择状态时绘制列表。

### `protected String getNumberStringKey()` / `protected ResourceLocation getPacketIdentifier()`
- `gui.mtr.siding_number`；`PACKET_UPDATE_SIDING`。

### `private void onSelectingTrain()`
- 列出该交通方式的所有车辆，可用的在前（不可用的加警告、无颜色），进入选择状态。

### `private void setIsSelectingTrain(boolean)`
- 切换状态，隐藏停站时间滑块（手动转自动时间也在网页设置），更新车辆按钮文字，移动列表。

### `private void onAdd(NameColorDataBase data, int index)`
- 车辆可用（`isValidVehicle(间距)`）时发送 `setTrainIdAndBaseType` 并退出选择。

### `private static String accelerationText(float)` / `private static String speedText(int)`
- 加速度 "x m/s² (y km/h/s)"；最高手动速度（`Train.convertMaxManualSpeed` 对应轨道类型的限速，无则不限）。

### `private int drawWrappedText(PoseStack, Component, int y, int color)`
- 右侧按宽度换行绘制，返回下一行 y；超出高度时画 "..." 并返回屏幕高度。

### `private static String fetchWikipediaArticle(String)`
- 首次异步请求英文维基摘要（`Patreon.openConnectionSafeJson`）并先缓存空串；之后返回缓存。

## 私有内部类 `TrainForList`（extends `NameColorDataBase`）
- `trainId`、`trainProperties`、`isAvailable`（长度适合本停车轨）；名称（不可用加警告）与颜色。`hasTransportMode()` 为 false。
