# DashboardListSelectorScreen.java（上游文件，本分支有改动）

客户端界面：左右两个列表的选择界面——左边可选项，右边已选项（可删除、可排序）。用于车厂的线路、传感器的线路过滤、PIDS 的平台等。结果直接写入传入的 `selectedIds` 集合。打开者：`common/src/main/java/mtr/screen/EditDepotScreen.java:128`、`common/src/main/java/mtr/screen/PIDSConfigScreen.java:244`、`TrainSensorScreenBase`。

本分支改动：`canSort` 与 `canRepeat` 分开（按时刻表运行的车厂，其线路无顺序也不重复）；全是线路时像仪表板线路页那样显示——同一线路折叠成一行（`LineRows`，搜索时不折叠以便找到每条线路），标签用方向文字（`TimetableScreen.getDirectionText`），已选列表显示线路自己的颜色；继承 `ScreenBase`。

## 类 `DashboardListSelectorScreen`（extends `ScreenBase`，implements `IGui`）

### 字段
- `availableList`、`selectedList`（`DashboardList`）、`buttonDone`。
- `previousScreen`（关闭后返回）、`onClose`（关闭回调）、`allData`、`selectedIds`。
- `isSingleSelect`（只选一个）、`canRepeat`（可重复选同一项）、`canSort`（已选项可排序）、`isRouteList`、`lineRows`、`lastSearch`。

### 构造函数
- `(Runnable onClose, allData, selectedIds, isSingleSelect, canRepeat)`：关闭时回调，不返回上一界面。
- `(ScreenMapper previousScreen, allData, selectedIds, isSingleSelect, canRepeat)`：`canSort = canRepeat`。
- `(ScreenMapper previousScreen, allData, selectedIds, isSingleSelect, canRepeat, boolean canSort)`。
- 私有完整构造：建两个列表（搜索文字共用 `ClientData.ROUTES_PLATFORMS_SEARCH`；已选列表的排序直接作用于 `selectedIds`（是 `ArrayList` 时））；全为线路时设置行样式（子行缩进、标题行可点击展开）。

### `protected void init()`
- 两列表左右并排，底部"完成"按钮，刷新列表。

### `void tick()`
- 列表 tick；线路列表的搜索文字变化时刷新。

### `void render(…)` / `void mouseMoved(…)` / `boolean mouseScrolled(…)`
- 绘制两个列表与"可选""已选"标题；转发鼠标事件。

### `boolean mouseClicked(double, double, int)`
- 左键先交给可选列表（点击线路标题行展开/收起，列表本身只有转发才知道点击）。

### `void onClose()`
- 运行回调；返回上一界面。

### `boolean isPauseScreen()`
- false。

### `private void updateList()`
- 可选项：`canRepeat` 或未选的项；已选项：按 `selectedIds` 顺序；线路列表且无搜索时按线路折叠。可选列表只有"添加"，已选列表有排序（`canSort`）与删除。

### `private String getRouteLabel(NameColorDataBase data)`
- 线路用方向文字，其他用格式化名称。

### `private void onAdd(NameColorDataBase data, int index)`
- 单选时先清空；加入 id 并刷新。

### `private void onDelete(NameColorDataBase data, int index)`
- 可重复且为列表时按位置删除，否则按 id 删除；刷新。
