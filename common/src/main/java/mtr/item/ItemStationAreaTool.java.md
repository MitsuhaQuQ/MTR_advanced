# ItemStationAreaTool.java（本分支新增）

车站区域工具物品：先点一个方块、再点另一个方块，两点构成一个三维长方体，交给某个车站作为主区域或附加矩形之一，并记录它覆盖的高度范围，这样上下重叠的车站（例如干线下方的地下线）可以区分开。注册于 `common/src/main/java/mtr/Items.java:18`。第二次点击后服务端打开 `StationAreaToolScreen`（经 `PacketTrainDataGuiServer.openStationAreaToolScreenS2C`）。

## 类 `ItemStationAreaTool`（extends `ItemBlockClickingBase`）

### 构造函数 `ItemStationAreaTool()`
- 放入 `CreativeModeTabs.CORE`，堆叠上限 1。

### `protected void onStartClick(UseOnContext context, CompoundTag compoundTag)`
- 在动作栏提示 `gui.mtr.station_area_tool_first`（带第一个点坐标）。第一个点由父类存入物品 NBT。

### `protected void onEndClick(UseOnContext context, BlockPos posEnd, CompoundTag compoundTag)`
- 服务端：`PacketTrainDataGuiServer.openStationAreaToolScreenS2C(player, posEnd, 当前点击位置)`，让客户端打开车站区域设置界面（两角坐标）。

### `protected boolean clickCondition(UseOnContext context)`
- 任意方块都可点（返回 true）。
