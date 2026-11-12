# Items.java（上游文件，本分支有改动）

服务器与客户端共用：MTR 所有独立物品（不含方块物品）的注册对象表（接口中的常量，共 119 个 `RegistryObject<Item>`）。实际注册在 `common/src/main/java/mtr/MTR.java` 的 `init` 中按名称逐个进行（如 `MTR.java:110` `station_area_tool`、`:123` `rail_connector_35`、`:196` `round_tunnel_creator_4`）。

本分支新增：
- `PLATFORM_END_PSD_2`、`PLATFORM_END_PSD_1`、`PLATFORM_END_APG`：站台端门物品（`ItemPSDAPGBase`，类型 `PLATFORM_END_*`）。
- `STATION_AREA_TOOL`：车站区域工具（`ItemStationAreaTool`，框选长方体后打开 `StationAreaToolScreen`）。
- `RAIL_CONNECTOR_35`、`RAIL_CONNECTOR_35_ONE_WAY`（`RailType.SANDSTONE`）、`RAIL_CONNECTOR_45`、`RAIL_CONNECTOR_45_ONE_WAY`（`RailType.COPPER`）：新的限速轨道连接器（数字为限速 km/h）。
- `ROUND_TUNNEL_CREATOR_4/6/8`、`ROUND_TUNNEL_WALL_CREATOR_4/6/8`：圆形隧道挖掘器与隧道墙生成器（参数为直径）。

## 接口 `Items`

### 常量分组
- 屏蔽门/安全门物品：`APG_*`、`PSD_*`、`PLATFORM_END_*`。
- 工具：`BRUSH`（刷子）、`STATION_AREA_TOOL`、各交通方式仪表板（`RAILWAY_DASHBOARD`、`BOAT_DASHBOARD`、`CABLE_CAR_DASHBOARD`、`AIRPLANE_DASHBOARD`）、`DRIVER_KEY`、`BOAT_NODE`、`ESCALATOR`、电梯按钮连接/移除器、`LIFT_REFRESHER`、`RESOURCE_PACK_CREATOR`。
- 轨道连接器：`RAIL_CONNECTOR_<限速>`（及 `_ONE_WAY` 单向）、`RAIL_CONNECTOR_PLATFORM`、`_SIDING`、`_TURN_BACK`、`_CABLE_CAR`、`_RUNWAY`、`RAIL_REMOVER`（`ItemRailModifier(…, RailType)`）。
- 信号闭塞连接器与移除器：`SIGNAL_CONNECTOR_<颜色>`、`SIGNAL_REMOVER_<颜色>`（16 种染料颜色；颜色决定闭塞，品红默认为联锁区、棕色默认为留置闭塞，见 `SecurityConfig`）。
- 建造工具：`BRIDGE_CREATOR_*`、`TUNNEL_CREATOR_<高>_<宽>`、`ROUND_TUNNEL_CREATOR_*`、`TUNNEL_WALL_CREATOR_*`、`ROUND_TUNNEL_WALL_CREATOR_*`。
- 各物品行为见 `mtr/item/*.java.md`。

无方法。
