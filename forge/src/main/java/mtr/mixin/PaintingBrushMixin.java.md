# PaintingBrushMixin.java（forge）

客户端 mixin，注入原版 `MultiPlayerGameMode.interact`（玩家右键实体时客户端的处理）。玩家手持本模组的刷子物品（`mtr.Items.BRUSH`）右键原版画时，打开 `ImportedPaintingScreen` 来设置这幅画显示的导入图片，并吞掉这次交互。登记于 `forge/src/main/resources/mtr.mixins.json:12`；Fabric 侧有逐字相同的 `fabric/src/main/java/mtr/mixin/PaintingBrushMixin.java`。

## 类 `PaintingBrushMixin`（abstract）

### `private void mtr$brush(Player player, Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir)`
- 注入点：`interact` 方法 `HEAD`，可取消。
- 作用：`target` 是 `Painting` 且 `hand` 手上的物品是刷子时，`Minecraft.setScreen(new ImportedPaintingScreen(painting))`，并把返回值设为 `InteractionResult.SUCCESS`（原版交互不再执行，也不会发原版交互包）。
- 参数：`player` 本地玩家；`target` 被右键的实体；`hand` 使用的手。
- 副作用：打开界面。
