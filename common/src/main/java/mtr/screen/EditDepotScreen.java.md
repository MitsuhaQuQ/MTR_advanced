# EditDepotScreen.java（上游文件，本分支有较多改动）

客户端界面：车厂设置——左侧按小时的发车频率（或按真实时间的发车列表），右侧线路指令、寻路、清除列车、各选项、飞机巡航高度、发车最小间隔，以及状态（停车轨数、下一班发车、寻路结果、线路缺车）。打开：`common/src/main/java/mtr/screen/DashboardScreen.java:527`。静态工具 `applyDepartures`、`formatTime`、`getDepartureList` 也被时刻表界面使用（`common/src/main/java/mtr/screen/TimetableScreen.java:118`、`:538`、`:894`）。

本分支改动：列车车厂（支持时刻表的交通方式）只按运用（duties）发车（R2）：滑块改为真实时间每小时的间隔分钟（`FrequencyTimetable.MAX_HEADWAY_MINUTES`），"填入时刻表"按钮把频率或发车列表转为线路的服务；按时刻表运行时线路无顺序、不重复，"循环线路"变为"留在线上"；列车车厂总是确定性寻路（L1，2026-11-07 审查）且总用速度曲线（R4），这两个选项不再显示；新增发车最小间隔（秒，解耦文档 D 步）；显示寻路失败原因与过期提示、线路缺车（`Depot.Shortfall`，按车辆池分别显示）；发车列表按运行顺序排序（午夜后的末班车排在最后）。

## 类 `EditDepotScreen`（extends `EditNameColorScreenBase<Depot>`）

### 字段与常量
- 布局：`sliderX`、`sliderWidthWithText`、`rightPanelsX`。
- `showScheduleControls`（非连续运动）、`showCruisingAltitude`（飞机）、`byHeadway`（R2：按间隔，即支持时刻表）。
- `sidingsInDepot`：车厂内停车轨。
- 按钮：`buttonUseRealTime`（切换按真实时间发车列表）、`buttonReset`（清零频率与发车列表）、`buttonFillTimetable`（填入时刻表：`setFrequenciesToMigrate` 发包后 `generatePathC2S`）、`buttonAddDeparture`、`buttonEditInstructions`（打开线路选择）、`buttonGenerateRoute`（重新寻路）、`buttonClearTrains`（清除停车轨列车，`clearTrainsC2S`）。
- `sliders`：每小时一个（`Depot.HOURS_IN_DAY` 个），拖动时按住 Shift 设所有小时为同值。
- 复选框：`checkboxRepeatIndefinitely`（无限重复，首末线路首尾相接时）、`checkboxDeterministicPath`、`checkboxCycleRoutes`（循环线路 / 留在线上）、`checkboxSpeedCurves`；改变后都会保存并重新寻路。
- 文本框：`textFieldDeparture`（如 `07:10:00 + 10 * 00:03:00`）、`textFieldCruisingAltitude`、`textFieldReleaseInterval`（发车最小间隔，秒）。
- `departuresList`（可删除）。
- `PANELS_START`、`SLIDER_WIDTH = 64`、`MAX_TRAINS_PER_HOUR = 5`（频率滑块最大值 = 5 × 2，单位为 `1 / TRAIN_FREQUENCY_MULTIPLIER` 列/小时）、`SECONDS_PER_MC_HOUR = Depot.TICKS_PER_HOUR / 20`。

### 构造函数 `EditDepotScreen(Depot depot, TransportMode transportMode, DashboardScreen dashboardScreen)`
- 计算布局，创建各控件及回调。线路指令：按时刻表运行时选择界面不可重复、不可排序，否则可以。

### `protected void init()`
- 名称列从右面板开始；右侧依次：线路指令、重新寻路 + 清除列车、确定性寻路（仅非列车非飞机）、巡航高度（飞机）、无限重复/循环线路（同一行，只显示其一）、速度曲线（仅非列车车厂）与发车最小间隔；左侧：真实时间切换、重置、填入时刻表（列车车厂）、滑块或发车列表与输入框。

### `void tick()`
- 重新寻路按钮在寻路中禁用；把滑块值写回车厂（间隔或频率）；按线路决定显示"无限重复"（首条线路首站 = 末条线路末站）还是"循环线路"，按时刻表运行时只显示"留在线上"；更新行位置。

### `void render(…)`
- 分隔线、名称颜色、发车列表或每小时标签；右侧状态：停车轨数、按时刻表运行时显示"时刻表生效"否则下一班发车倒计时、寻路结果（`getSuccessfulSegmentsText`，`|` 分行）、寻路状态（`getPathStatusText`）、线路缺车（非图表模式且有未覆盖服务时：多个车辆池时逐池显示，否则显示需要/现有/每日未覆盖，附不能运行数与建议停车轨）。

### `void mouseMoved(…)` / `boolean mouseScrolled(…)`
- 转发给发车列表。

### `private boolean runsByTimetable()`
- 车厂是否按运用运行（自身设置或其线路之一有时刻表，`Depot.runsByDutiesWith`）。

### `private String getPathStatusText()`
- 寻路失败说明（`PathFailureText.get(clientPathFailure)`）；无失败但在 `ClientData.STALE_DEPOTS` 中时为 `gui.mtr.path_stale`。

### `private boolean showSpeedCurves()` / `private int getReleaseIntervalRowY()` / `private int getSpeedCurvesRowY()` / `private boolean showDeterministicPath()`
- 速度曲线只用于非连续运动；行位置；确定性寻路选项只给非列车、非飞机的车厂。

### `protected void saveData()`
- 写回选项（隐藏的选项为 false；不显示确定性寻路时为 true）、巡航高度（失败用默认）、发车最小间隔（0…`Depot.MAX_RELEASE_INTERVAL`），发 `setData` 的 `PACKET_UPDATE_DEPOT`。

### `private void toggleRealTime()`
- 在滑块与发车列表之间切换并更新按钮文字。

### `private void onDeleteDeparture(NameColorDataBase, int)` / `private void updateList()` / `private boolean checkDeparture(String text, boolean addToList, boolean removeFromList)`
- 删除发车；刷新列表并生成临时发车；校验/添加/删除输入的发车时刻（返回是否有效）。

### `static List<NameColorDataBase> getDepartureList(List<Integer> departures)`
- 把一天内的毫秒（UTC）按运行顺序（`TimetableConfig.getTimeInDay`）排序并在配置时区下格式化为 `HH:MM:SS`。

### `static String formatTime(long millis)`
- 以 UTC 保存的一天内时间 → 配置时区的 `HH:MM:SS`。

### `static boolean applyDepartures(String text, IntConsumer consumer)`
- 解析 `HH:MM:SS` 或 `HH:MM:SS + N * HH:MM:SS`（配置时区），依次给出 N+1 个时刻（一天内毫秒）；格式错误返回 false。

### `private Component getSuccessfulSegmentsText()`
- 按 `clientPathGenerationSuccessfulSegments`：<0 寻路中；0 未生成；1 车厂到第一站失败；≥ 总站数 + 2 成功；否则指出在哪两站之间（或最后一站到车厂）失败。

### `private static String getSliderString(int value)` / `private static String getHeadwayString(int minutes)` / `private static String getTimeString(int hour)`
- 频率滑块文字（列/小时及间隔秒）；间隔滑块文字（R2，0 为无）；小时标签 `HH:00-HH:59`。
