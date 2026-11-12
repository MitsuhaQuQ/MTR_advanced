# IPIDS.java

乘客信息显示屏（PIDS）方块的公共接口及其方块实体基类。本分支在原 MTR 基础上加了"显示通过列车"（`showPassingTrains`）选项。具体的横向/纵向 PIDS 方块实体（`BlockPIDSBaseHorizontal.TileEntityBlockPIDSBaseHorizontal`、`BlockPIDSBaseVertical.TileEntityBlockPIDSBaseVertical`）继承 `TileEntityPIDS`；渲染在 `RenderPIDS`，配置界面 `PIDSConfigScreen`，服务器端设置经 `PacketTrainDataGuiServer`。

## interface IPIDS extends IBlock
无自身方法，仅作为标记与内部类容器。

## abstract class TileEntityPIDS extends BlockEntityClientSerializableMapper implements IPIDSRenderChild

### 字段与 NBT 键
- `cachedRefreshTime`、`cachedPlatformId`：最近站台 id 的缓存及其时间。
- `messages[]`（public）：自定义信息，长度 `getMaxArrivals() * getLinesPerArrival()`；NBT 键 `message0`、`message1`…。
- `hideArrival[]`：每行到站信息是否隐藏；键 `hide_arrival<i>`。
- `platformIds`：手动选择的站台 id 集合；键 `platform_ids`（long 数组）。
- `displayPage`：显示页；键 `display_page`。
- `showPassingTrains`：是否显示通过（不停站）列车；键 `show_passing_trains`（本分支新增）。

### `public TileEntityPIDS(BlockEntityType<?> type, BlockPos pos, BlockState state)`
- 作用：调用基类构造。由子类构造。

### `public void readCompoundTag(CompoundTag compoundTag)` / `public void writeCompoundTag(CompoundTag compoundTag)`
- 作用：按上表读/写 NBT。写入时 `null` 信息写为 `""`。
- 主要调用者：方块实体同步框架；`PacketTrainDataGuiServer.java:1420`、`:1434`（write）。

### `public AABB getRenderBoundingBox()`
- 返回：无限大包围盒（总是渲染）。

### `public void setData(String[] messages, boolean[] hideArrival, Set<Long> platformIds, int displayPage, boolean showPassingTrains)`
- 作用：设置 `showPassingTrains` 后调用下面的四参数版本。
- 主要调用者：`PacketTrainDataGuiServer.java:1157`、`:1182`。

### `public void setData(String[] messages, boolean[] hideArrival, Set<Long> platformIds, int displayPage)`
- 作用：拷贝信息与隐藏标记（按较短长度）、替换站台集合、设置显示页；`setChanged()` 并 `syncData()` 同步到客户端。
- 主要调用者：`PacketTrainDataGuiServer.java:540`。

### `public long getPlatformId(Set<Platform> platforms, DataCache dataCache)`
- 作用：返回离本方块最近的站台 id（`RailwayData.getClosePlatformId`），数据缓存需要刷新时重算，否则用缓存。
- 主要调用者：`RenderPIDS.java:505`、`RenderTrainDebug.java:125`。

### 取值方法
- `getDisplayPage()`、`getShowPassingTrains()`、`getPlatformIds()`：直接返回字段（集合为内部引用）。
- `getMessage(int index)`：越界返回 `""`；`null` 时置为 `""` 再返回。
- `getHideArrival(int index)`：越界返回 `false`。
- 主要调用者：`RenderPIDS.java:127/134/171/485/511`、`PIDSConfigScreen.java:96-102`。

### `public abstract int getMaxArrivals()` / `public abstract int getLinesPerArrival()`
- 子类给出最多显示的到站条数和每条的行数。
