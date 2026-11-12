# PIDSConfigScreen.java（上游文件，本分支有改动）

客户端界面：乘客信息显示屏（PIDS）设置——自动检测附近站台或手选站台、显示第几页、每行自定义消息与"隐藏到站"，本分支加"显示通过列车"。关闭时发送设置。打开：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:430`。`getPlatformFilterButton` 也被到站投影设置界面使用（`common/src/main/java/mtr/screen/ArrivalProjectorConfigScreen.java:54`）。

本分支改动：新增"显示通过列车"复选框（读自方块实体 `getShowPassingTrains`，随 `sendPIDSConfigC2S` 发送）；继承 `ScreenBase`。

## 类 `PIDSConfigScreen`（extends `ScreenBase`，implements `IGui`, `IPacket`）

### 字段与常量
- `pos1`、`pos2`：显示屏两端方块位置。
- `messages`（`maxArrivals × linesPerArrival` 条）、`hideArrival`（每个到站一项）。
- 控件：`textFieldMessages`、`buttonsHideArrival`、`displayPageInput`（正整数，最多 3 位，显示从 1 起）、`selectAllCheckbox`（自动检测附近站台 = 不过滤）、`checkboxShowPassingTrains`、`filterButton`、`buttonPrevPage`、`buttonNextPage`。
- `filterPlatformIds`（方块实体的集合）、`displayPage`（从 0 起）、`showPassingTrains`、`maxArrivals`、`linesPerArrival`、`page`（消息分页的当前页）。
- `MAX_MESSAGE_LENGTH = 2048`、`TEXT_FIELDS_Y_OFFSET`。

### 构造函数 `PIDSConfigScreen(BlockPos pos1, BlockPos pos2, int maxArrivals, int linesPerArrival)`
- 从 `pos1` 的方块实体读取站台过滤、每行消息、隐藏到站、显示页、显示通过列车。

### `protected void init()`
- 布局：自动检测、过滤按钮、显示页、显示通过列车、翻页按钮、每行消息文本框（每个到站的第一行右侧有"隐藏到站"）；显示第 0 页。

### `private void setPage(int newPage)`
- 限制页码，只显示本页的文本框与复选框，更新翻页按钮可见性。

### `void tick()`
- 文本框 tick。

### `void onClose()`
- 收集消息与隐藏到站；勾选自动检测时清空过滤；显示页 = 输入 − 1（解析失败为 0）；`PacketTrainDataGuiClient.sendPIDSConfigC2S(pos1, pos2, messages, hideArrival, filterPlatformIds, displayPage, 显示通过列车)`。

### `void render(…)`
- 标签：显示页、过滤站台数（自动检测时为 0）、消息标题与页码。

### `boolean isPauseScreen()`
- false。

### `private int getMaxArrivalsPerPage()` / `private int getMaxPages()`
- 按窗口高度每页可放的行数（至少 1）；页数。

### `static Button getPlatformFilterButton(BlockPos pos, WidgetBetterCheckbox selectAllCheckbox, Set<Long> filterPlatformIds, ScreenMapper thisScreen)`
- 返回按钮：找到 `pos` 所在车站，列出该站站台（名称 + 经过线路的终点合并），勾选自动检测时先清空过滤，打开 `DashboardListSelectorScreen`；关闭时回到本界面并按过滤是否为空更新自动检测复选框。车站不存在时无动作。
