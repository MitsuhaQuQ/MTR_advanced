# PlatformScreen.java（上游文件，本分支有较多改动）

客户端界面：站台设置——编号、颜色（基类）、停站时间滑块，本分支加：所属车站、时刻表分界、正线/侧线、通过速度、停车位置，以及经过此站台的线路一览（每条线路在此停多久、来自站台还是线路设置）。打开：`common/src/main/java/mtr/screen/DashboardScreen.java:508`、`:768`。关闭时只发送有变化的项。

## 类 `PlatformScreen`（extends `SavedRailScreenBase<Platform>`）

### 字段与常量
- `stationsHere`：可选车站（R6：区域包含站台的车站，再加最近的 8 个其他车站，再加当前设定的车站）。
- `buttonStation`、`selectedStationId`（0 = 自动按区域）。
- `buttonBoundary`、`timetableBoundary`：时刻表分界（服务可在线路中途加入或离开，见 `TimetableBoundaries`）。
- `buttonTrackType`、`passingTrack`：正线/侧线；`workedOutTrack`：按线路推算出的方向与种类（`ClientStationTracks.compute()`，与网页编辑器显示一致）。
- `buttonPassSpeed`、`passSpeed`（km/h），循环 `PASS_SPEEDS = {80, 100, 120, 160, 200, 300}`（第 3b 轮）。
- `buttonStopPosition`、`stopPosition`，循环 `STOP_POSITIONS = {出口端 EXIT_END, 居中 CENTRED, 2, 5, 10, 20}`（数字为距出口端的方块数，第 4 轮）。
- `firstVisibleRow`、`rowRoutes`、`rowIndices`：线路一览（一条线路多次经过时有多行）及滚动位置。
- `DWELL_TIME_TEXT`、`ROW_HEIGHT`。

### 构造函数 `PlatformScreen(Platform savedRailBase, TransportMode transportMode, DashboardScreen dashboardScreen)`
- 初始化各值与按钮回调（通过速度取下一个更大的档位，超过最大回到第一档；停车位置循环到下一个）；收集该交通方式下经过此站台的线路（按名称排序）。

### `private static List<Station> getStationChoices(Platform platform)`
- 见 `stationsHere`；按区域中心到站台中点的距离排序。

### `private void cycleStation()`
- 依次切换到下一个候选车站，最后回到 0（自动）。

### `private void updateBoundaryButton()` / `updateTrackTypeButton()` / `updatePassSpeedButton()` / `updateStopPositionButton()` / `updateStationButton()`
- 更新按钮文字。正线/侧线按钮后附推算结果（方向 `gui.mtr.track_<方向>`，种类只在未勾选侧线且原本不是侧线时显示）。车站按钮在所选车站区域不包含站台时附加提示。

### `protected void init()`
- 停站时间滑块位置；有候选车站时显示车站按钮；`showScheduleControls`（基类：交通方式按时刻表运行）时一行放四个按钮：分界、正线/侧线、通过速度、停车位置。

### `void tick()`
- 非连续运动方式下，停站时间不得低于 `Route.MIN_STOP_DWELL`（分钟为 0 时把秒滑块抬到最小值）。

### `void render(…)` / `boolean mouseScrolled(…)`
- 显示停站时间标签与线路一览；滚轮滚动一览。

### `private int getRowsTop()` / `private int getVisibleRows()`
- 一览起始 y 与可见行数（至少 1）。

### `private void renderRows(PoseStack)`
- 标题（多于可见行时显示位置），无线路时提示；每行按宽度横向压缩。停站时间按当前滑块计算（单位半秒：`分 × 60 × 2 + 秒滑块`）。

### `private String getRowText(Route route, int index, int platformDwell)`
- 该线路在此的停站时间 `RoutePlatform.getStopDwell(是否中间站, 站台停站, false)`：0 时为"通过"（`gui.mtr.platform_schedule_pass`），否则显示开门秒数（`Route.getDoorOpenSeconds`）、停站秒数（半秒 / 2）及来源（线路覆盖 `dwellOverride > 0` 或站台）。

### `void onClose()`
- 发送停站时间（半秒单位）；车站、分界、停车位置、通过速度（等于默认 `PlatformSpeeds.DEFAULT_KMH` 时存 0）、侧线有变化时各自发 `PACKET_UPDATE_PLATFORM`；再调用基类。

### `protected String getNumberStringKey()` / `protected ResourceLocation getPacketIdentifier()`
- `gui.mtr.platform_number`；`PACKET_UPDATE_PLATFORM`。
