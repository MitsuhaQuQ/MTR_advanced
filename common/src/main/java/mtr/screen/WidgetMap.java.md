# WidgetMap.java（上游文件，本分支有较多改动）

客户端界面控件：仪表板右侧的俯视线网地图——地形底图、轨道、隔离（寻路边界与联络线占用/释放的轨道）、车厂出入库路径、线路、站台/停车轨、车站/车厂区域与名称、玩家位置；可拖动、缩放，可画区域、给线路加站台和关键节点、点站台/停车轨打开设置，以及在地图上点选线路/车站/车厂。使用者：`common/src/main/java/mtr/screen/DashboardScreen.java`（唯一）。

本分支改动：地形底图改用 `MapTerrainCache`（缓存到磁盘）；轨道与线路改用服务器发来的地图数据（`ClientData.MAP_RAILS`、`MAP_ROUTES`、`MAP_SIDINGS`、`MAP_RAIL_SHAPES`，沿曲线画）；隔离层（`MapIsolation`、寻路边界节点，L1）；车厂出入库路径；线路编辑时标出关键节点（已选为绿色）；点选线路（重叠处再点切换到下一条）、车站、车厂并高亮；悬停显示名称与车站矩形（带高度 3D 的矩形用黄框，区域编辑时锁定不能重画，右键删除）；区域编辑提示两行；线段裁剪到地图范围；右下角按钮区域不接收地图点击；鼠标坐标在顶部提示过长时移到右下角。

## 类 `WidgetMap`（implements `WidgetBase`, `SelectableMapper`, `GuiEventListener`, `IGui`）

### 字段
- 视图：`x`、`y`、`width`、`height`（屏幕像素）、`scale`（每方块像素数，`1/128`…`64`）、`centerX`、`centerY`（中心的世界 x、z）。
- `drawArea1`、`drawArea2`（正在编辑的矩形角点）、`mapState`（`DEFAULT` / `EDITING_AREA` / `EDITING_ROUTE`）、`showStations`（false 时显示车厂与停车轨）。
- 回调：`onDrawCorners`、`onDrawCornersMouseRelease`、`onClickAddPlatformToRoute`、`onClickAddKeyNodeToRoute`、`onClickEditSavedRail`、`isRestrictedMouseArea`（返回 true 的屏幕位置不算在地图内，见 `isMouseOver`）。
- 区域编辑：`areaNotice`、`areaInfo`（第二行提示，2026-11-09）、`areaLocked`（所编辑区域带高度不能重画）、`onRightClickArea`（右键位置，用于删矩形）、`drawingArea`、`drewArea`、`drawBefore1/2`（按下未拖动松开时恢复原矩形）。
- 点选：`routesToDraw`、`canPickRoute`/`onPickRoute`/`selectedLineKey`/`hoveredRoutes`/`hoveredLineKeys`、`canPickStation`/`onPickStation`/`selectedStationId`/`hoveredStations`、`canPickDepot`/`onPickDepot`/`selectedDepotId`/`hoveredDepots`；悬停缓存 `hoverMouseX/Y`、`hoverScale`、`hoverCenterX/Y`；`pressMouseX/Y`（判断是点击还是拖动）。
- `world`、`player`、`textRenderer`；`bottomButtonsWidth`（右下角按钮区宽度，默认 22 格）。
- 常量：像素尺寸（关键节点标记 3、点击半径 8、轨道线 1、站点标记 4、线路线 2、高亮 4、点选距离 5、点击最大移动 3、隔离线 2）、颜色（轨道半透明白、边界紫、联络线橙、已释放灰、区域高亮、线路透明度、关键节点黄/已选绿、玩家蓝）。

### 构造函数 `WidgetMap(TransportMode, OnDrawCorners, Runnable onDrawCornersMouseRelease, Consumer<Long> onClickAddPlatformToRoute, Consumer<BlockPos> onClickAddKeyNodeToRoute, Consumer<SavedRailBase> onClickEditSavedRail, BiFunction<Double, Double, Boolean> isRestrictedMouseArea)`
- 以玩家位置为中心，缩放 1，显示车站。

### `void render(PoseStack, int mouseX, int mouseY, float delta)`
- 依次：扫描地形（`MapTerrainCache.scan`）并画底图（颜色减半，缩小时按步长取样）；轨道、隔离、车厂路径、线路（选中或悬停线路加粗高亮）；车站模式：站台小块、各车站各矩形（带高度的加黄框，选中/悬停高亮）、悬停的站台白色；车厂模式同理；编辑线路时画站点与关键节点标记；正在画的矩形；玩家标记；顶部提示（编辑区域/编辑线路关键节点/可点选线路/车站/车厂）与区域提示两行；放大到 ≥ 8 时站台/停车轨名称；车站名与"区号"（区域足够大时）或车厂名；编辑线路时关键节点序号；悬停名称；鼠标下车站矩形信息（第几个、2D 或高度范围，最多 4 行）或车厂区域信息；鼠标世界坐标。

### `boolean mouseDragged(…)` / `boolean mouseReleased(…)` / `boolean mouseClicked(…)` / `boolean mouseScrolled(…)`
- 编辑区域时左键拖动画矩形（宽高至少 1），松开时提交；按下未拖动则恢复原矩形；其他情况拖动平移。松开时若几乎未移动，按当前可点选的对象点选（重叠处轮换）。点击（需编辑权限）：编辑区域时右键交给 `onRightClickArea`，左键（未锁定）开始画；编辑线路时点站台加入线路，否则点关键节点；默认点站台/停车轨打开设置。滚轮以鼠标位置为中心缩放。

### `void setBottomButtonsWidth(int)` / `boolean isMouseOver(double, double)` / `void setFocused(boolean)` / `boolean isFocused()`
- 右下角按钮区（最下一行）与 `isRestrictedMouseArea` 指定的位置不算在地图内；不获取焦点。

### `void setPositionAndSize(int x, int y, int width, int height)` / `void scale(double amount)`
- 位置尺寸；缩放 `× 2^amount` 并限制。

### `void find(double x1, double z1, double x2, double z2)` / `void find(BlockPos pos)`
- 定位到区域中心（缩放至少 2）/ 到位置（至少 8）。

### `void startEditingArea(AreaBase)` / `void setDrawArea(Tuple, Tuple)` / `void setAreaNotice(String)` / `void setAreaInfo(String)` / `void setAreaEditing(BooleanSupplier areaLocked, BiConsumer<Double, Double> onRightClickArea)` / `void startEditingRoute()` / `void setRoutesToDraw(Supplier<Collection<Route>>)` / `void stopEditing()`
- 编辑状态与回调设置。

### `void setRoutePicking(BooleanSupplier, Consumer<Route>)` / `void setSelectedLine(String)` / `void setStationPicking(…)` / `void setSelectedStation(long)` / `void setDepotPicking(…)` / `void setSelectedDepot(long)` / `void setShowStations(boolean)`
- 点选设置与选中项。

### `private void mouseOnSavedRail(Tuple<Double, Double> mouseWorldPos, MouseOnSavedRailCallback, boolean isPlatform)`
- 同一位置的多个站台/停车轨把该方块纵向均分，找到鼠标下的那个回调（并发修改异常忽略）。

### `private Tuple<Integer, Integer> coordsToWorldPos(int, int)` / `private Tuple<Double, Double> coordsToWorldPos(double, double)`
- 地图内像素 → 世界坐标（取整或小数）。

### `private void drawRails(BufferBuilder)` / `private void drawIsolation(BufferBuilder)` / `private void drawIsolatedRail(…)` / `private void drawRailShape(BufferBuilder, long node1, long node2, double pixels, int color)`
- 画本交通方式的地图轨道；隔离状态的轨道（颜色按 `MapIsolation` 状态）与边界节点；沿 `MAP_RAIL_SHAPES` 中的曲线点从较低节点画到较高节点（无形状则直线）。

### `private void drawRouteHighlights(BufferBuilder, Route)` / `private void drawMarker(BufferBuilder, BlockPos, double radiusPixels, int color)`
- 线路站点标记（线路色）与所有关键节点（已作为途经点的为绿色）；方形标记。

### `private void drawDepotPaths(BufferBuilder)` / `private void drawNodes(BufferBuilder, long[] nodes, int color)`
- 本交通方式车厂的停车轨路径（编辑线路时只画服务该线路的），颜色取车厂第一条线路颜色或车厂颜色。

### `private void drawRoute(…)` / `private void drawRouteNodes(…)` / `private void drawRouteSegment(…)` / `private static List<BlockPos> getRouteNodes(Route)`
- 线路节点来自 `MAP_ROUTES`；没有路径时退回为各站台中点与途经点之间的直线。

### `private void updateHoveredRoutes(int mouseX, int mouseY, Tuple<Double, Double> mouseWorldPos)`
- 视图或鼠标变化时重算悬停的线路（线路键）、车站、车厂。

### `private static List<Station> getStationsAt(Tuple)` / `private List<Depot> getDepotsAt(Tuple)` / `private static <T> void pickArea(List<T>, long selectedId, Consumer<T>)`
- 位置处的车站（任一矩形）/ 车厂，按 id 排序；选择当前选中项之后的一个（重叠处反复点击可轮换）。

### `private List<Route> getRoutesAt(Tuple)` / `private static double getDistanceToSegment(…)` / `private void pickRoute(Tuple)`
- 距离 ≤ 5 像素的线路，每个线路键一条，按距离排序；点到线段距离；选择当前选中线路键之后的一条。

### `private void drawLineFromWorldCoords(BufferBuilder, double x1, double z1, double x2, double z2, double thickness, int color)`
- 画有宽度的线段（四边形，与 `IDrawing.drawRectangle` 同绕向以免被剔除），先裁剪到地图范围，避免长轨道画到两侧面板上。

### `private void clickKeyNode(Tuple)`
- 8 像素内最近的关键节点（距离相同按坐标长整数）交给 `onClickAddKeyNodeToRoute`。

### `private void drawFromWorldCoords(double, double, BiConsumer)` / `drawRectangleFromWorldCoords(…)` / `private void drawRectangle(…)` / `private void drawOutlineFromWorldCoords(BufferBuilder, int[] rectangle, int color)`
- 世界坐标 → 屏幕并在范围内回调；画矩形（裁剪左边界）；画 1 像素边框。

### `private boolean canDrawAreaText(AreaBase)` / `private void drawSavedRail(PoseStack, BlockPos, List)` / `private static int divideColorRGB(int color, int amount)`
- 区域在屏幕上足够大（约 80 像素）才写名称；站台/停车轨名称；颜色各分量除以 amount。

## 函数式接口
- `OnDrawCorners.onDrawCorners(Tuple corner1, Tuple corner2)`（public）。
- `MouseOnSavedRailCallback.mouseOnSavedRailCallback(SavedRailBase, double x1, double z1, double x2, double z2)`（私有）。

## 私有枚举 `MapState`
- `DEFAULT`、`EDITING_AREA`、`EDITING_ROUTE`。
