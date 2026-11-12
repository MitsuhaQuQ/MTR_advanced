# ItemPSDAPGBase.java

屏蔽门（PSD）、安全门（APG）、升降梯门、站台端门物品（服务端放置逻辑）。右键地面时按玩家朝向一次放置一整组方块：门为 2 格宽（奇数型 3 格）、玻璃为 1 格宽，高 2 格（PSD 再在顶上放 `BlockPSDTop`）。物品在 `common/src/main/java/mtr/Items.java:10`、`:11` 等注册。本分支改动：新增三种站台端门类型 `PLATFORM_END_APG`、`PLATFORM_END_PSD_1`、`PLATFORM_END_PSD_2`（放置对应的 `Blocks.PLATFORM_END_*`，提示文字 `tooltip.mtr.platform_end_door`，见 `BlockPlatformEndDoor`）；可替换判断改用 `Compat.isReplaceable`（1.20 无 Material）。

## 类 `ItemPSDAPGBase`（extends `ItemWithCreativeTabBase`，implements `IBlock`）

### 字段
- `item`（`EnumPSDAPGItem`：门/玻璃/端玻璃）、`type`（`EnumPSDAPGType`）。

### 构造函数 `ItemPSDAPGBase(EnumPSDAPGItem item, EnumPSDAPGType type)`
- 升降梯门放入"扶梯与升降梯"标签页，其他放入"铁路设施"标签页。

### `InteractionResult useOn(UseOnContext context)`
- 作用：宽度 = 门（奇数型 3，否则 2）或 1；高度 PSD 3、其他 2；`blocksNotReplaceable` 为真则 FAIL。否则从点击面相邻格开始、沿玩家朝向的顺时针方向逐列放置：下/上半方块（门设左右侧 `SIDE`，奇数型中间列设 `ODD`；玻璃设 `SIDE_EXTENDED = SINGLE`），PSD 在第 3 格放顶盖（`BlockPSDTop.getActualState`）。消耗 1 个物品。
- 返回：SUCCESS/FAIL。
- 副作用：放置方块。

### `void appendHoverText(...)`
- 站台端门类型显示 `tooltip.mtr.platform_end_door`；升降梯门显示奇偶提示；其他显示 `tooltip.mtr.<item 名>`。

### `private BlockState getBlockStateFromItem()`
- 按类型与物品返回要放的方块默认状态；未覆盖的组合返回空气。注意：`switch` 的 `PSD_1`/`PSD_2`/`APG` 分支内层 switch 没有 break/return 时会落入下一个 case（由于三种 item 都有返回，实际不会发生）。

### `static boolean blocksNotReplaceable(UseOnContext context, int width, int height, Block blacklistBlock)`
- 作用：检查放置区域（宽 × 高）内是否有不可替换方块，或区域正下方/正上方紧邻处有与 `blacklistBlock` 相同的方块（防止叠放）。
- 返回：true 表示不能放。调用者：本类、`common/src/main/java/mtr/item/ItemEscalator.java:24`。

## 枚举 `EnumPSDAPGType`
- `PSD_1`、`PSD_2`（屏蔽门）、`APG`（安全门）、`LIFT_DOOR_1`、`LIFT_DOOR_ODD_1`（升降梯门，后者奇数宽）、`PLATFORM_END_APG`、`PLATFORM_END_PSD_1`、`PLATFORM_END_PSD_2`（站台端门）。
- 字段 `isPSD`（高 3 格并放顶盖）、`isOdd`（3 格宽门）、`isLift`。

## 枚举 `EnumPSDAPGItem`（implements `StringRepresentable`）
- `PSD_APG_DOOR("psd_apg_door", 门)`、`PSD_APG_GLASS("psd_apg_glass")`、`PSD_APG_GLASS_END("psd_apg_glass_end")`。
- `getSerializedName()`：返回名字。
