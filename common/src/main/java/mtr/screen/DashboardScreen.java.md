# DashboardScreen.java（上游文件，本分支有较多改动）

客户端界面：仪表板——左侧面板为车站/线路/车厂三个标签页的列表（`DashboardList`），右侧为线网地图（`WidgetMap`）。可新建/编辑车站和车厂区域、编辑线路经过的站台与关键节点、打开各种编辑界面，地图右下角有缩放、设置、票价、轨道操作、高级（网页）编辑器、区域过滤按钮。打开：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:156`。

本分支改动：打开时启动地图缓存（`MapDataCache.open`），单人游戏时重新加载本地线路词与时刻表配置（服务器上它们来自服务器）；线路列表按线路折叠（`LineRows`，搜索时不折叠），在地图上点线路/车站/车厂会选中并定位到列表中；网页编辑器管理的线路在游戏中不能改站（画区域按钮被锁定）；线路可加入关键节点；线路站点可设停站时间覆盖（开门秒数）；新增票价、高级编辑器、区域过滤按钮（设计文档 5.13：按所选车站所在的寻路区域过滤列表，并显示网络数、区域数与边界问题），按钮按可用宽度收缩/隐藏；车站可有多个矩形（附加区域），带高度的矩形（3D，由车站区域工具设置）在这里只能删除不能重画（2026-11-09），右键删除鼠标下的矩形；带高度的车厂区域同样锁定；编辑车站区域时提示被其他车站重叠的站台数。

## 类 `DashboardScreen`（extends `ScreenBase`，implements `IGui`, `IPacket`）

### 字段
- 编辑状态：`selectedTab`、`editingArea`（正在画的车站/车厂）、`editingRoute`、`editingRoutePlatformIndex`（正在编辑终点等的站序，−1 无）、`routeEntries`（线路条目：`RoutePlatform` 或关键节点 `BlockPos`）、`isNew`、`editingAreaIndex`（0 为主区域，1… 附加区域，超出为新建）。
- `transportMode`、`widgetMap`、`dashboardList`、`lineRows`。
- 按钮：三个标签、新建车站/线路/车厂、三种"完成"、缩放、轨道操作（`RailActionsScreen`）、设置（`ConfigScreen(useTimeAndWindSync)`）、票价（`FareScreen`）、高级编辑器（`EditorServer.open()` 返回网址后用系统浏览器打开）、区域过滤、车站矩形切换/新增/删除。
- `regionFilter`（过滤的区域号数组，null 为全部）、`compactRegionFilter`、`pickedStationId`（最近在地图或列表中选的车站）。
- 文本框与控件：`textFieldName`、`textFieldCustomDestination`、`textFieldDwellOverride`（停站覆盖，开门秒数）、`checkboxPassStation`、`colorSelector`。
- 常量：`MAX_COLOR_ZONE_LENGTH = 6`（public）、`COLOR_WIDTH = 48`、`ARGB_KEY_NODE`（关键节点行颜色，黄）。

### 构造函数 `DashboardScreen(TransportMode transportMode, boolean useTimeAndWindSync)`
- 如上创建控件；地图回调：画角点、松开鼠标、点站台加入线路、点节点加入关键节点、点站台/停车轨编辑；设置线路绘制来源与区域编辑（有高度时锁定、右键删除）；地图点选线路（选中线路键并在列表中选中其标题行或线路）、车站、车厂；列表行样式与锁定行；进入车站页。

### `protected void init()`
- 地图占右侧，布局地图按钮（`layoutMapButtons`）与左侧控件。

### `void render(…)`
- 先画地图，再在 z=500 层画左侧面板、列表与控件。

### `void mouseMoved(…)` / `boolean mouseClicked(…)` / `boolean mouseScrolled(…)`
- 转发列表（左键先交给列表处理线路标题行）。

### `void tick()`
- 文本框与列表 tick、更新区域按钮；按标签页与编辑状态刷新列表：车站（按区域过滤）或正在编辑车站的区域内站台（含重叠车站的站台，并提示 `overlappingPlatforms` 中的数量）；线路（`updateRouteRows`）或正在编辑线路的条目；车厂（按区域过滤）或车厂的停车轨。

### `boolean isPauseScreen()`
- false。

### `private void updateRouteRows()`
- 该交通方式的线路（按区域过滤：有站台在过滤区域内），无搜索时按线路折叠。

### `private void toggleRegionFilter()`
- 已过滤则取消；否则取所选车站各站台所在区域作为过滤（区域无名称不保存，网络变化后再选会得到新结果）。

### `private void layoutMapButtons(int bottomRowY)`
- 从右往左：缩放、设置、票价、轨道操作、高级编辑器、区域过滤；宽度不够时先压缩区域过滤，再从左压缩其他按钮（最小 2 格），仍不够则从区域过滤开始隐藏；告诉地图底部按钮占用宽度。

### `private void updateRegionFilterButton()`
- 文字：过滤中显示所选区域；否则显示网络数（`platformGraph.getNetworkCount()`）、区域数（含站台或停车轨的区域）及问题（未切开的边界数、缺失的边界数）；没有已选车站且未过滤时禁用。

### `private <T, U> Set<T> filterByRegion(Set<T> data, Function<T, Map<Long, U>> getSavedRails, boolean isPlatform)`
- 保留有站台/停车轨在过滤区域内的车站/车厂；还没有站台/停车轨的总是保留。

### `private void onClickLineHeader(NameColorDataBase)` / `private String getRowLabel(NameColorDataBase)`
- 展开/收起线路；展开后的子行显示缩进的方向文字。

### `private void onSelectTab(SelectedTab)`
- 切换标签，清除选中，停止编辑；车厂页时地图不显示车站。

### `private void onFind(NameColorDataBase, int)`
- 地图定位：线路到首站台；车站/车厂到其区域（并记为所选车站）；编辑车站时到所选站台。

### `private void onDrawArea(NameColorDataBase, int)`
- 开始编辑车站/车厂区域或线路（锁定线路不可）；清空搜索。

### `private void onEdit(NameColorDataBase, int)`
- 打开 `EditStationScreen` / `PlatformScreen` / `EditRouteScreen` / `EditDepotScreen` / `SidingScreen`；编辑线路时点关键节点定位，点站台开始编辑该站终点等。

### `private void onSort()` / `private void onDelete(NameColorDataBase, int)`
- 线路条目排序后保存；删除车站/线路/车厂（`DeleteConfirmationScreen` 确认后 `sendDeleteData` 并从客户端集合移除），编辑线路时删除条目并保存。

### `private List<Object> getList()` / `private DataConverter getRouteEntryRow(Object)` / `private void saveRouteEntries()`
- 列表排序作用于 `routeEntries`；条目行：关键节点（坐标，黄色）或"[终点前缀]站名 (站台)"（车站颜色）；保存条目（`setEntries` → `setPlatformIds` 包）并请求地图线路。

### `private void startEditingArea(AreaBase, boolean isNew)` / `private void startEditingRoute(Route, boolean isNew)` / `private void startEditingRouteDestination(int index)`
- 进入编辑区域 / 线路（颜色为线路自己的颜色）/ 某站的终点、停站覆盖、通过设置。

### `private void onDrawCorners(Tuple, Tuple)` / `private void onDrawCornersMouseRelease()`
- 画矩形（带高度的矩形锁定不画）写入主区域或附加区域；松开时发送，并清除所画车站矩形原有的高度（画出的矩形包含所有高度）。

### `private int[] getEditingHeights()`
- 正在编辑的车站矩形或车厂区域的高度，无高度（2D）或不是车站/车厂为 null。

### `private void removeStationAreaAt(double worldX, double worldZ)` / `private void removeStationArea(int index)` / `private void selectStationArea(int index)` / `private void removeStationArea()`
- 右键删除鼠标下的矩形；删除矩形及其高度（主区域由下一个顶替，唯一的矩形保留并提示），发三个包并同步；选择要编辑的矩形；删除当前附加矩形。

### `private void onClickAddPlatformToRoute(long platformId)` / `private void onClickAddKeyNodeToRoute(BlockPos pos)`
- 线路末尾加站台 / 加关键节点（线路至少有一个站台时）并保存。

### `private void onClickEditSavedRail(SavedRailBase)`
- 打开站台或停车轨界面。

### `private void onDoneEditingArea()` / `private void onDoneEditingRoute()` / `private void onDoneEditingRouteDestination()`
- 新建时加入客户端集合，写名称颜色并发包；线路另发附加数据并同步；站点设置：自定义终点、通过（仅中间站）、停站覆盖（开门秒数 → `Route.getDwellFromDoorOpenSeconds`，空为 0），发站台列表包后回到线路编辑。

### `private Collection<Route> getRoutesToDraw()`
- 车厂页不画线路；编辑线路时只画它。

### `private void stopEditing()` / `private boolean isValidRoutePlatformIndex()` / `private void toggleButtons()`
- 结束编辑；站序有效；按状态与权限显示各控件、矩形按钮文字（第 n/总数，带高度时附高度范围并在地图上提示锁定）、调整列表高度。

## 私有枚举 `SelectedTab`
- `STATIONS`、`ROUTES`、`DEPOTS`。
