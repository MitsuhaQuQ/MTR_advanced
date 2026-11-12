# BrushEditRailScreen.java（ANTE 界面）

客户端：MTR 刷子在轨道上的"轨道刷"编辑界面（Cloth Config 构建）。刷子物品 NBT 中的 `NTERailBrush` 标签保存要刷到轨道上的属性（轨道类型、轨道模型、竖曲线半径、滚转角表）；界面同时显示当前选中轨道（`RailPicker.pickedRail`）的属性，可直接修改并发包。刷子右键轨道节点时由 `ItemWithCreativeTabBaseMixin` 调用 `applyBrushToPickedRail`（`common/src/main/java/cn/zbx1425/mtrsteamloco/mixin/ItemWithCreativeTabBaseMixin.java:48`）；潜行右键时服务端发 `PacketScreen` `brush_edit_rail`，客户端 `createScreen(null)` 打开本界面（`network/PacketScreen.java:67`）。本分支改动：去掉了节点角度调整和"切换为直接节点"按钮（节点角度现在由 MTR 自己的节点与刷子处理）；开门方向标题改为翻译键 `gui.mtrsteamloco.brush_edit_rail.opening_direction`。

## 刷子 NBT（`NTERailBrush` 下）
- `RailType`（字符串，`RailType` 枚举名）、`ModelKey`（字符串）、`VerticalCurveRadius`（float，格）、`RollAngleMap`（字符串，`DoubleFloatMapSerializer`；键为沿轨道的相对位置 0–1，值为滚转角）。

## 类 `BrushEditRailScreen`

### 字段
- `pickedRail`、`supplier`（同一对象的 `RailExtraSupplier` 视图）、`pickedPosStart`、`pickedPosEnd`：被编辑的轨道及两端；`screen`：构建好的界面；`parent`：返回的上级界面。

### 私有构造函数 `BrushEditRailScreen(Rail pickedRail, BlockPos pickedPosStart, BlockPos pickedPosEnd, Screen parent)`
- 作用：若选中的是 `RailType.NONE`（反向的那条记录），改取 `ClientData.RAILS` 中反方向的轨道并交换两端；然后 `init()`。注意：反向记录不存在时 `pickedRail` 为 null，`init` 会直接返回上级界面。

### 私有构造函数 `BrushEditRailScreen(Screen parent)`
- 使用 `RailPicker` 当前选中的轨道。

### `private void init()`
- 作用：无轨道时 `screen = parent`。否则构建界面（标题 `gui.mtrsteamloco.brush_edit_rail.title`，不确认保存，透明背景），条目依次为：
  1. 刷子用法提示；
  2. "启用轨道类型"开关（切换时写/删刷子 `RailType` 并重开界面）；启用时为轨道类型文本框（必须是非 NONE 的枚举名，否则显示错误），保存写入刷子；
  3. "启用轨道模型"开关；启用时显示当前模型名按钮，点击打开 `SelectScreen` 从 `RailModelRegistry.TREE` 选择：写刷子、设置轨道模型并 `PacketUpdateRail.sendUpdateC2S`；
  4. "启用竖曲线半径"开关；启用时加 `VertCurveRadiusListEntry`；
  5. "启用滚转角"开关（开启时写空表）；启用时加 `RollAnglesListEntry`（方向标志为轨道 0 点是否更靠近 `pickedPosStart`）；
  6. 开门方向滑块 0–3（0 不开、1 左、2 右、3 双向），保存时设置并发包；
  7. 轨道自定义配置条目（`ConfigResponder.getEntrysFromMaps`）。
- 副作用：切换开关会修改手中刷子 NBT 并发包（`updateBrushTag`）。

### `void updateRailAngle(Map<Double, Float> res)`
- 作用：发送轨道更新包；把滚转角表的距离键除以轨道长度存为相对位置，写入刷子 `RollAngleMap`。调用者：`RollAnglesListEntry`（构造时传入）。

### `void updateRadius(float newRadius, boolean send)`
- `send` 为真时把半径写入刷子 `VerticalCurveRadius`（不直接改轨道；写刷子后 `updateBrushTag` 会把刷子应用到选中轨道）。

### `Component[] getVerticalValueText(float verticalRadius)`
- 作用：竖曲线提示文字：最大可行半径 `(H²+L²)/(4H)`（格）和坡度（‰）：半径 < 0 为直线坡 `H/L×1000`；为 0 或超过最大值按最大半径；否则按给定半径，坡度由 `RailExtraSupplier.getVTheta` 求。

### `Screen getScreen()`

### `static Screen createScreen(Screen parent)` / `static Screen createScreen(Rail rail, BlockPos posStart, BlockPos posEnd, Screen parent)`
- 构建并返回界面（无轨道时返回 `parent`）。

### `static void applyBrushToPickedRail(BlockPos posStart, BlockPos posEnd, Rail pickedRail, CompoundTag railBrushProp, boolean isBatchApply)`
- 作用：参数为空则返回；选中反向记录时换成正向轨道并交换两端（写 info 日志）。按刷子 NBT：模型键不同则设置（反向记录也设）；竖曲线半径不同则设置；有滚转角表则按轨道长度换算回距离并设置；有轨道类型则设置（写日志）。`isBatchApply` 且没有任何属性变化时，把渲染方向取反（再次右键即可反转方向）。最后 `PacketUpdateRail.sendUpdateC2S`。
- 异常：`RailType.valueOf` 遇非法名抛 `IllegalArgumentException`。

### `static void applyBrushToPickedRail(CompoundTag railBrushProp, boolean isBatchApply)`
- 对 `RailPicker` 当前选中轨道应用。调用者：`mixin/ItemWithCreativeTabBaseMixin.java:48`、本类 `updateBrushTag`。

### `static CompoundTag getBrushTag()`
- 返回主手（优先）或副手刷子的 `NTERailBrush` 标签，没有返回 null。

### `static void updateBrushTag(Consumer<CompoundTag> modifier)`
- 作用：取（或创建）刷子标签，交给 `modifier` 修改，立即 `applyBrushToPickedRail(tag, false)`，再 `PacketUpdateHoldingItem.sendUpdateC2S(hand)` 把手中物品 NBT 同步给服务器。手里没有刷子时不做任何事。

## 私有内部类 `VertCurveRadiusListEntry`（extends `TooltipListEntry<String>`，implements `ContainerEventHandler`，仅客户端）
- 控件：半径输入框（最长 8 字符）、"设为最大"按钮（半径 0）、"设为无曲线"按钮（半径 −1）。
- 构造函数 `VertCurveRadiusListEntry(float v, BrushEditRailScreen screen)`：提示文字来自 `getVerticalValueText`；输入框内容变化时解析半径，小于最大可行半径为灰白色、否则黄色、解析失败红色，并 `updateRadius(newRadius, true)`。
- `updateRadius(float newRadius, boolean send)`：按值启用/禁用两个按钮，同步输入框文字（≤ 0 时为空），转调外部类 `updateRadius`。
- `isEdited()` false、`getValue()` ""、`getDefaultValue()` 空、`save()` 空：本条目不参与 Cloth Config 的保存机制。
- `render(...)`：把三个控件放在条目行固定 x 位置（80、290、355）并绘制。
- `children()` / `narratables()`：三个控件。
