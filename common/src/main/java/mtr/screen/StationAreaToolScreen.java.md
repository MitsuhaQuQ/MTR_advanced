# StationAreaToolScreen.java（本分支新增）

客户端界面：把车站区域工具框出的长方体交给一个车站或车厂。先选车站或车厂（区域包含框中心的优先，其余按距离，最多 10 个车站、5 个车厂）；选车站后再选它的哪个矩形——主区域、某个附加区域或新建一个——同时带上框的高度。车厂的区域直接变成这个框并带上高度，使上下叠放的车厂各自包含自己的停车轨（2026-11-09）。打开：`common/src/main/java/mtr/packet/PacketTrainDataGuiClient.java:166`。

## 类 `StationAreaToolScreen`（extends `ScreenBase`，implements `IGui`, `IPacket`）

### 字段与常量
- `rectangle`：`{minX, minZ, maxX, maxZ}`；`height`：`{minY, maxY}`。
- `stations`、`depots`：候选；`buttons`；`chosen`：已选车站（null 表示在第一步）。
- `MAX_STATIONS = 10`、`MAX_DEPOTS = 5`。

### 构造函数 `StationAreaToolScreen(BlockPos pos1, BlockPos pos2)`
- 计算矩形与高度；在有角点的车站/车厂中排序（包含框中心者在前，再按区域中心到框中心的距离，再按 id）取前若干个。

### `protected void init()`
- 第一步：每个车站一个按钮（选中后重建界面），每个车厂一个按钮（`gui.mtr.station_area_tool_depot`，直接 `applyDepot`）。第二步：主区域、每个附加区域（编号从 2 起），未满 `Station.MAX_EXTRA_AREAS` 时"新建"。最后"取消"。

### `void render(PoseStack, int, int, float)`
- 标题（选车站 / 选区域）与框的坐标和高度（`gui.mtr.station_area_tool_box`）。

### `private void apply(int index)`
- 参数：0 主区域，1… 各附加区域，`附加区域数 + 1` 为新建。
- 作用：替换主区域时先删除旧主矩形的高度（`setHeight(旧矩形, null)`），设置角点并发 `PACKET_UPDATE_STATION`；替换附加区域时先删除旧矩形高度再替换，或追加，发附加区域包；然后为新矩形设置高度并发高度包；`DATA_CACHE.sync()` 并关闭。

### `private void applyDepot(Depot depot)`
- 设置车厂角点与高度（`setAreaHeights(minY, maxY)`），分别发 `PACKET_UPDATE_DEPOT`；同步并关闭。

### `boolean isPauseScreen()`
- false。
