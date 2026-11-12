# Station.java

车站：地图上的一个区域，带票价区和出口。站台属于其区域包含它的车站（见 `DataCache.platformIdToStation`）。本分支加了：除两个角点外的额外矩形（`extraAreas`，最多 15 个，旧数据与其它模组仍读角点）；每个矩形的高度范围（`areaHeights`，用车站区域工具设置，用来区分上下叠放的车站，如主线下面的地铁）；矩形的增删与查询。数据三写：msgpack 存档、整包、`update(key, packet)` 增量。`Depot` 复用其区域高度读写（`Depot.java:409/485/635/670/685/890`）。界面：`DashboardScreen`、`StationAreaToolScreen`、`EditStationScreen`、`WidgetMap`。

## final class Station extends AreaBase

### 字段与常量
- `zone`：票价区。
- `exits`：出口名（如 "A1"）→ 目的地列表。
- `extraAreas`：额外矩形 `{x1, z1, x2, z2}`。
- `areaHeights`：矩形键（`rectangleKey`）→ `{最低 y, 最高 y}`；没有条目的矩形包含所有高度（与以前相同）。
- `MAX_EXTRA_AREAS = 15`（public）。
- 存档键：`zone`、`exits`（map: 出口 → 字符串数组）、`extra_areas`（扁平 int 数组，每 4 个一组）、`area_heights`（扁平 int 数组，每 6 个一组 `x1,z1,x2,z2,low,high`）。
- 增量键：`exit_edit_parent`、`exit_delete_parent`、`exit_destinations`、`extra_areas`、`area_heights`、`zone`。

### 构造函数
- `Station()`、`Station(long id)`：新建。
- `Station(Map<String, Value> map)`：读 msgpack（额外矩形最多 15；高度最多 16 组）。
- `Station(CompoundTag)`（已弃用）：读旧 NBT（只有 zone 与 exits）。
- `Station(FriendlyByteBuf packet)`：见包格式。

### `toMessagePack` / `messagePackLength`（基类 + 4） / `writePacket`
- 包字段顺序：基类（`AreaBase`）字段，`int zone, int 出口数`，每个出口 `String 名, int n, String×n`，然后额外矩形（`int n, int×4×n`），然后区域高度（`int n, int×6×n`，只写当前仍存在的矩形）。

### `public void update(String key, FriendlyByteBuf packet)`
- `exit_edit_parent`：读旧名、新名，重命名出口（不存在则新建空出口）；`exit_delete_parent`：删出口；`exit_destinations`：读出口名、`int n`、n 个目的地（出口存在时替换，注意出口不存在时后续字段不会被读取）；`extra_areas` / `area_heights`：整体替换；`zone`：读 `name, color, zone`；其它交给基类。

### `protected boolean hasTransportMode()` → `false`。

### `public boolean inArea(int x, int z)`
- 角点矩形或任一额外矩形水平包含。

### `public boolean inArea(int x, int y, int z)`
- 某个矩形水平包含且（该矩形无高度或 y 在高度范围内）。调用者：`DataCache.java:347` 等。

### `public boolean hasHeights()`
- 是否有任一矩形设置了高度。

### `public int[] getHeight(int[] rectangle)`
- 矩形的高度范围，或 `null`（所有高度）。调用者：`ClientCache.java:588`、`RouteMapGenerator.java:910`。

### `public void setHeight(int[] rectangle, int[] height)`
- 设置或（`null`）清除矩形高度。调用者：`StationAreaToolScreen.java:110`。

### `public void moveHeight(int[] oldRectangle, int[] newRectangle)`
- 重画矩形时保留原高度。脚本未找到调用者。

### `public void setAreaHeights(Consumer<FriendlyByteBuf> sendPacket)`
- 发送增量包：`long id, String transportMode, "area_heights", 高度列表`。调用者：`DashboardScreen.java:684`。

### `public static String rectangleKey(int[] rectangle)`
- 返回：规范化的 `"minX,minZ,maxX,maxZ"`（与角点顺序无关）。

### `private void putHeight(int[] rectangle, int y1, int y2)`
- 存入排序后的高度。

### `private List<int[]> getHeightEntries()`
- 当前矩形（去重）中有高度的，每项 `{x1, z1, x2, z2, low, high}`；已不存在的矩形的高度被略去。

### `private void readAreaHeights(FriendlyByteBuf)` / `private void writeAreaHeights(FriendlyByteBuf)`
- 读（最多保留 16 组）/写高度列表。

### `public List<int[]> getRectangles()`
- 角点矩形（如有）+ 额外矩形。

### `public int rectangleAt(double x, double z)`
- 返回：包含该点的矩形下标（0 为主区域，额外矩形从 1 起），重叠时取面积最小者；无为 −1。调用者：`DashboardScreen.java:711`、`WidgetMap.java:334`。

### `public int[] getRectangle(int index)`
- 返回：下标对应的矩形，或 `null`。

### `public boolean removeRectangle(int index)`
- 作用（车站区域编辑，2026-11-09）：删除矩形及其高度（除非另一矩形与它相同）；删主区域时由第一个额外矩形接替。车站唯一的矩形不能删。
- 返回：是否删除。调用者：`DashboardScreen.java:723`。

### `public void clearHeight(int[] rectangle)`
- 作用：在仪表盘地图上画的矩形包含所有高度（高度只能由车站区域工具设置）；仅当没有其它相同矩形时清除。调用者：`DashboardScreen.java:683`。

### `public void setExtraAreas(Consumer<FriendlyByteBuf> sendPacket)`
- 发送增量包 `extra_areas`。调用者：`DashboardScreen.java:674`、`StationAreaToolScreen.java:122`。

### `private void readExtraAreas(FriendlyByteBuf)` / `private void writeExtraAreas(FriendlyByteBuf)`
- `int n, int×4×n`；读取最多保留 15 个。

### `public void setZone(Consumer<FriendlyByteBuf> sendPacket)`
- 发送增量包 `zone`：`name, color, zone`。调用者：`EditStationScreen.java:176`。

### 出口
- `setExitParent(String oldParent, String newParent, Consumer)`：本地重命名并发 `exit_edit_parent`。调用者：`EditStationScreen.java:211`。
- `deleteExitParent(String parent, Consumer)`：本地删除并发 `exit_delete_parent`。调用者：`EditStationScreen.java:238`。
- `setExitDestinations(String parent, Consumer)`：出口存在时发 `exit_destinations`（当前目的地列表）。调用者：`EditStationScreen.java:228`。
- `getGeneratedExits()`：按出口名排序，返回每个出口名 → 目的地，并按首字母（如 "A"）汇总所有同字母出口的目的地。调用者：`RenderRailwaySign.java:154`、`RailwaySignScreen.java:75`。
- 私有 `setExitParent(String, String)`、`parentExists(String)`。
- `serializeExit(String)`：每字符 8 位拼成 long；`deserializeExit(long)`：反向。调用者：`RailwaySignScreen.java:80`（serialize）。
