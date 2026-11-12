# AreaBase.java

有平面范围的数据对象（车站 `Station`、车厂 `Depot`）的基类，在 `NameColorDataBase`（id、名称、颜色、交通方式）之上加两个角点。本分支加了多矩形与高度的扩展点（`getRectangles`、`getHeight`、三参数 `inArea`），由子类（例如用车站区域工具设置的车站与车厂）覆盖；`intersecting` 改为逐矩形比较。被 `DataCache`（按位置查车站/车厂、重叠检查）、`ClientCache`、仪表盘与地图界面使用。

## abstract class AreaBase extends NameColorDataBase

### 字段与存档键
- `corner1`、`corner2`（`Tuple<Integer,Integer>`，x,z）：两个角；值为 (0,0) 视为未设置（`null`）。
- 存档键：`x_min`、`z_min`（corner1）、`x_max`、`z_max`（corner2）——注意名为 min/max，但并不保证排序。
- 增量更新键：`KEY_CORNERS = "corners"`。

### 构造函数
- `AreaBase()`、`AreaBase(long id)`、`AreaBase(TransportMode)`、`AreaBase(long id, TransportMode)`：委托基类。
- `AreaBase(Map<String, Value> map)`：读 msgpack 后 `setCorners`。
- `AreaBase(CompoundTag)`（已弃用）：读旧 NBT。
- `AreaBase(FriendlyByteBuf packet)`：读基类字段后读 4 个 int 角点。

### `toMessagePack(MessagePacker)` / `messagePackLength()`
- 在基类之后写 4 个角点键（未设置写 0）；长度 = 基类 + 4。
- 主要调用者：`Station.java:118`、`Depot.java:509`（子类 super 调用）。

### `writePacket(FriendlyByteBuf)`
- 包字段顺序：基类字段，然后 `int corner1.x, int corner1.z, int corner2.x, int corner2.z`。

### `public void update(String key, FriendlyByteBuf packet)`
- 作用：`key == "corners"` 时读 4 个 int 设置角点，否则交给基类。
- 主要调用者：`Depot.java:925`（子类 super）及数据同步路径。

### `public void setCorners(Consumer<FriendlyByteBuf> sendPacket)`
- 作用：构造增量更新包并交给 `sendPacket` 发送：`long id, String transportMode, String "corners", int×4`。
- 主要调用者：`DashboardScreen.java:676`、`DashboardScreen.java:728`、`StationAreaToolScreen.java:114`。

### `public boolean inArea(int x, int z)`
- 返回：两个角都设置且 x、z 都在两角之间（`RailwayData.isBetween`，闭区间、与顺序无关）。
- 主要调用者：`DataCache.java:347`、`ClientCache.java:499`。

### `public boolean intersecting(AreaBase areaBase)`
- 返回：两者都有角点，且任一对矩形在 x、z 上都重叠（含边界）。
- 主要调用者：`DataCache.java:210`；测试 `StationAreaTest.java:65`。

### `public boolean inArea(int x, int y, int z)`
- 作用：考虑高度的区域判断；基类忽略 y，子类可覆盖（有高度的矩形只在其高度内算）。

### `public int[] getHeight(int[] rectangle)`
- 返回：某矩形占的高度 `{最低, 最高}`；基类返回 `null` 表示所有高度。
- 主要调用者：`DataCache.java:470`、`ClientCache.java:588`、`DashboardScreen.java:682`。

### `public List<int[]> getRectangles()`
- 返回：所有矩形 `{x1, z1, x2, z2}`；基类为两角构成的一个（未设置时空列表）。
- 主要调用者：`DataCache.java:436`、`:470`、`:489`。

### `public BlockPos getCenter()`
- 返回：两角中点（y=0）；未设置为 `null`。
- 主要调用者：`IUpdateWebMap.java:48`、`WidgetMap.java:296`。

### `private void setCorners(int corner1a, int corner1b, int corner2a, int corner2b)`
- 作用：设置角点，(0,0) 置为 `null`。

### `private static boolean rangesOverlap(int a1, int a2, int b1, int b2)`
- 返回：两个闭区间（端点顺序任意）是否重叠。

### `public static boolean nonNullCorners(AreaBase station)`
- 返回：对象非空且两角都已设置。
- 主要调用者：`DataCache.java:197`、`Station.java:368`、`DashboardScreen.java:471`。
