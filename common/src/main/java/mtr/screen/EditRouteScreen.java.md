# EditRouteScreen.java（上游文件，本分支有改动）

客户端界面：编辑一条线路的名称、颜色及其他属性（线路类型、隐藏、关闭下一站广播、轻铁线路及其编号、顺/逆时针环线），以及本分支加的：线路名、服务种类、联络线、时刻表入口、复制线路。打开：`common/src/main/java/mtr/screen/DashboardScreen.java:514`。

本分支改动：右侧一列新增"线路名"文本框（默认 `Route.getAutoLineName(名称)`）、"服务种类"文本框（默认 `LineWords.findServiceWord(名称)`）、"时刻表"按钮（打开 `TimetableScreen`，只有支持时刻表的交通方式）、"复制线路"按钮、"联络线"复选框（只对沿轨道寻路的交通方式：非飞机、非连续运动）；颜色编辑改为线路自己的颜色（`getOwnColor`/`setOwnColor`）。

## 类 `EditRouteScreen`（extends `EditNameColorScreenBase<Route>`，implements `IGui`, `IPacket`）

### 字段与常量
- `routeType`：正在编辑的线路类型。
- 文字：`lightRailRouteNumberText`、`lineNameText`、`serviceTypeText`。
- 控件：`textFieldLightRailRouteNumber`、`buttonRouteType`、`buttonIsLightRailRoute`、`buttonIsRouteHidden`、`buttonDisableNextStationAnnouncements`、`buttonConnectingLine`、`buttonIsClockwiseRoute`、`buttonIsAntiClockwiseRoute`、`buttonTimetable`、`buttonDuplicate`、`textFieldLineName`、`textFieldServiceType`。
- `isCircular`：首末站台属于同一车站（才显示顺/逆时针选项）。
- `CHECKBOX_WIDTH = 160`、`RIGHT_COLUMN_X = SQUARE_SIZE*2 + 160`。

### 构造函数 `EditRouteScreen(Route route, DashboardScreen dashboardScreen)`
- 创建控件。"时刻表"：先保存再打开 `TimetableScreen(data, this)`。"复制线路"：先保存；`data.duplicate()` 加入客户端集合；依次发送名称颜色、附加数据、站台列表、时刻表、（有自定义路径时）自定义路径的 `PACKET_UPDATE_ROUTE`；请求新线路的地图（`requestMapRouteC2S`）；打开新线路的编辑界面。

### `protected void init()`
- 名称列至 3/4 宽处、颜色列至右边；左列：线路类型（交通方式有类型变化时）、隐藏、关闭下一站广播、轻铁线路、轻铁编号、顺/逆时针（环线时）；右列（小窗口时左列会超出底部）：线路名、服务种类、时刻表、复制、联络线。按数据设置初值。

### `void render(…)`
- 名称/颜色标题与其他编辑者提示；右列标签（按列宽截断）；轻铁编号可见时其标签。

### `protected void saveData()`
- 基类保存名称与颜色后：写入线路类型、轻铁、编号、隐藏、关闭广播、联络线、线路名与服务种类（去空格）、环线状态（非环线为 NONE）；发 `setExtraData` 的 `PACKET_UPDATE_ROUTE`；`DATA_CACHE.sync()`。

### `protected int getEditedColor()` / `protected void setEditedColor(int color)`
- 读写线路自己的颜色（`Route.getOwnColor`/`setOwnColor`）。

### `private int getRightColumnWidth()`
- `max(2*SQUARE_SIZE, min(160, 剩余宽度))`。

### `private void setRouteTypeText(TransportMode, RouteType)`
- 记下类型并更新按钮文字 `gui.mtr.route_type_<交通方式>_<类型>`。

### `private void setIsLightRailRoute(boolean)` / `setIsRouteHidden(boolean)` / `setDisableNextStationAnnouncements(boolean)`
- 同步复选框（轻铁时显示编号文本框）。

### `private void setIsClockwise(boolean)` / `setIsAntiClockwise(boolean)`
- 两者互斥。
