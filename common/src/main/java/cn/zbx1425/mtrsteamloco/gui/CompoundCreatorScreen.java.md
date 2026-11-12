# CompoundCreatorScreen.java（ANTE 界面）

客户端：ANTE"复合创建器"物品（`Main.COMPOUND_CREATOR`）的任务列表界面。物品 NBT 的 `tasks` 下保存一组有序任务，每个任务是"切片任务"（`SliceTask`：沿轨道按间隔放置一个横截面的方块，用于建桥、隧道断面等）或"轨道修改任务"（`RailModifierTask`：把一组轨道属性刷到轨道上）。界面可增删、复制、排序、重命名任务并进入各自的编辑界面；所有修改即时写回手中物品并发 `PacketUpdateHoldingItem` 同步到服务器。服务端在使用物品时发 `PacketScreen` `compound_creator`（`common/src/main/java/cn/zbx1425/mtrsteamloco/item/CompoundCreator.java:59`、`:71`），客户端 `createScreen(null)` 打开（`network/PacketScreen.java:70`）。本分支改动：界面上原先写死的英文/中文文字（Enter、Select Task、轨道修改器、开门方向、Select rail arguments、配置）改为翻译键 `gui.mtrsteamloco.compound_creator.*` / `gui.mtrsteamloco.brush_edit_rail.opening_direction`。

## 类 `CompoundCreatorScreen`（extends `Screen`）

### 常量与字段
- `TAG_TASKS = "tasks"`：物品 NBT 中任务表的键（子键为序号字符串 `"0"`、`"1"`…，每个任务 NBT 含 `Task.TAG_TYPE` 类型和 `order`）。
- `BACKGROUND_LOCATION`（原版选项背景）、`WHITE`（白色混凝土粉末纹理）。
- `parent`、`entries`（任务行列表）、`selectedEntry`、`scroll`（列表滚动像素，≤ 0）、`scissor`（列表裁剪区）、`draggingSlider`。
- 按钮：`btnAdd`（+，打开 `TaskSelectScreen`）、`btnRemove`（-）、`btnCopy`、`btnClear`、`btnClose`（X）。

### `static Screen createScreen(Screen parent)`
- 新建并 `load()`；手里不是复合创建器时返回 `parent`。

### 构造函数 `CompoundCreatorScreen(Screen parent)`（包级）

### `boolean load()`
- 作用：读 `getTag()` 的 `tasks`，按类型构造 `SliceTask`/`RailModifierTask`（未知类型写错误日志），按 `order` 排序生成行，选中第一行。
- 返回：手中物品不对返回 false。

### `static CompoundTag getTag()`
- 主手为复合创建器时返回其（必要时创建的）NBT，否则 null。

### `static void updateTag(Consumer<CompoundTag> modifier)`
- 修改主手复合创建器 NBT 后 `PacketUpdateHoldingItem.sendUpdateC2S()`。

### `protected void init()`
- 空（控件在 `render` 中摆放）。

### `private void update()`
- 把 `entries` 按当前顺序重新编号（`task.order = i`）写回 `tasks` 并同步。

### `private void clearEntries()` / `private void addEntry(Task)` / `private void removeEntry()` / `private void copyEntry()`
- 清空、追加并选中、删除选中（之后选中第一行）、复制选中任务（深拷贝）；都会 `update()`。

### `render(GuiGraphics|PoseStack, int mouseX, int mouseY, float partialTick)`
- 画背景、任务列表（裁剪区内按 `scroll` 偏移）、滚动条和按钮。

### `private void setScroll(int mouseY)` / `private void checkAndScroll(int temp)` / `boolean mouseScrolled(...)` / `boolean mouseClicked(...)` / `boolean mouseDragged(...)` / `boolean mouseReleased(...)` / `private boolean isMouseOverSlider(...)` / `private int[] getSliderPositionAndSize()` / `private boolean canScoll()`
- 列表滚动：拖动滚动条或滚轮；`scroll` 被夹在 `[-(行数×行高 − 裁剪高), 0]`；内容不超过裁剪区时不能滚动。

### `List<? extends GuiEventListener> children()`
- 按钮与任务行。

### 绘图辅助（静态，按 MC 版本两套）
- `drawText`、`blit`（整张纹理画到矩形）、`fill`、`drawCenteredString`、`renderDirtBackground(Screen, ...)`（画平铺背景）。

### `void onClose()`
- `update()` 后回到 `parent`。

## 内部类 `Entry`（implements `GuiEventListener`）：一行任务
- 字段：`task`；`y`；静态 `x = 20`、`height = 20`；控件：名称输入框（最长 256）、`enter`（进入编辑）、`up`/`down`（移动）。
- `Entry(Task task)`：初始化名称框，变化时 `updateName`。
- `children()`。
- `enter()`：切片任务打开 `SliceTaskScreen`，轨道修改任务打开 `setRailModifierScreen`；未知类型写日志。
- `updateName(String)`：改名并保存、选中本行。
- `moveUp()` / `moveDown()`：与相邻行交换并保存。
- `isFocused()` / `setFocused(boolean)`。
- `render(...)`：画行背景（选中高亮）、序号、控件。
- `isMouseOver(...)`、`static x()`、`y()`、`width()`、`static width(int)`、`static height()`、`isSelected()`、`mouseClicked(...)`（点击选中本行）。

## 内部类 `TaskSelectScreen`（extends `Screen`）：新建任务类型选择
- 按钮：`btnReturn`（X）、`btnNewRailModifier`（新建 `RailModifierTask` 并加入）、`btnNewSliceTask`（新建 `SliceTask` 并加入）。
- `init()` 摆放按钮；`render(...)`；`onClose()` 回到复合创建器界面。

## 轨道修改任务界面

### `Screen newRailModifierScreen(RailModifierTask task0)`
- 作用：在 `task0` 的副本上编辑，保存时 `task0.copyFrom(副本)` 并 `update()`。条目：当前轨道模型按钮（打开内部 `SelectScreen` 选择模型）、轨道类型文本框（非 NONE 的枚举名）、`isOneWay`（只修改单向）、`isReversed`（反向）开关、滚转角条目 `RollAnglesListEntry`、开门方向滑块 0–3、轨道自定义配置条目。

### `void setRailModifierScreen(RailModifierTask task)`
- 打开上述界面。

### 私有内部类 `SelectScreen`（extends `SelectListScreen`）：轨道模型选择
- 常量 `INSTRUCTION_LINK`（ANTE 文档链接）；`lblInstruction` 资源包提示标签。
- `SelectScreen(RailModifierTask task, Runnable returnParent)`；`init()`、`loadPage()`（按树结构列出 `RailModelRegistry`）、`onBtnClick(String btnKey)`（设任务轨道的模型键）、`getRegistryEntries()`、`render(...)`、`onClose()`（执行 `returnParent`）、`isPauseScreen()`（false）。

## 内部类 `SliceTaskScreen`（extends `Screen`）：切片任务编辑
- 字段：`task`、`canvas`（横截面网格，每格一个 `Square`）、`inventory`（右侧方块选择栏）、`scissor`、`tx`/`ty`（画布平移）、`now`（当前"笔刷"方块与是否替换）、各按钮：宽/高 ±2、画布平移 ◁▷▲▼ 与居中、`btnEnterConfig`（任务参数）、`btnReturn`。
- `SliceTaskScreen(SliceTask task)`。
- `setWidthAndHeight(int, int)`：调整截面尺寸（`task.setWidthAndHeight` 拒绝则不变），保存并 `reload`。
- `updateTask()`：把画布每格的方块状态和"替换"标志写回 `task.lumps` 并保存。
- `reload()`：按 `task.lumps` 重建画布；左键格子时把 `now` 画上去；只显示裁剪区内的格子。
- `init()`：名称框绑定任务名；摆放控件。
- `children()`、`render(...)`：画画布、方块栏、悬停格子的提示（`Square.renderTooltip`）。
- `setConfigScreen()`：任务参数界面（Cloth Config）：起点位置 `start`（≥ 0）、长度 `length`（≤ 0 表示到轨道末端，存 null）、间隔 `interval`（≤ 0 存 null）、步长 `increment`（> 0，默认 0.1）、是否跟随偏航 `useYaw`（默认 true）、俯仰 `usePitch`（默认 true）、滚转 `useRoll`（默认 false）；保存时 `updateTask()`。
- `updateWidgetPosition()`：按窗口尺寸摆放控件。
- `setTX(int)` / `setTY(int)`：画布大于裁剪区时把平移夹在 ±(超出量/2)，否则归零。
- `onClose()`：回到复合创建器界面。
- `setNowBlock(BlockState)`：设当前笔刷方块。
- `renderBlockState(...)`：在 GUI 中以物品形式画一个方块状态。
- `newPropertyScreen(Square square, boolean fixed)`：方块状态属性编辑界面：`replacement` 开关和每个方块属性的循环按钮；`fixed`（来自方块栏）时编辑副本；保存时把结果设为当前笔刷。
- `newButtonCycleListEntry(Square, Property<T>, ConfigEntryBuilder)`：生成单个属性的循环按钮条目，默认值为方块默认状态的值。

### 内部类 `Square`（implements `GuiEventListener`）：一个格子
- 常量：`length`（格子像素边长）、`PURPLE_CIRCLE`（替换模式标记）、`BLUE_CIRCLE`（非替换）、`MID_CIRCLE`（方块栏固定格标记）纹理。
- 字段：`x`、`y`、`state`（null 为空）、`consumer`（左键回调）、`highlight`、`visible`、`fixed`、`replacement`。
- `Square(Square other)`（复制）；`Square(int x, int y, BlockState state, Consumer<Square> consumer, Function<Square,Boolean> highlight, Function<Square,Boolean> visible, boolean fixed, boolean replacement)`。
- `render(..., int tx, int ty, ...)`：位置设为 `(tx, ty)` 后画边框、方块、高亮蒙版和标记，记录悬停格子。
- `renderTooltip(...)`：在屏幕左下画"Replacement、方块名、描述 id、全部属性"文字（未翻译的英文提示）。
- `isMouseOver(...)`、`isVisible()`、`mouseClicked(...)`：左键执行 `consumer`；右键打开属性编辑；中键把该格设为当前笔刷。
- `isFocused()` / `setFocused(boolean)`。

### 内部类 `Inventory`（implements `GuiEventListener`）：方块选择栏
- 常量 `width = 100`、`col = 5`（每行 5 格）；字段 `scroll`、`blocksList`（注册表中所有方块的默认状态）、`searchedList`、`searchField`、`scissor`。
- `Inventory()`：遍历方块注册表建格子，左键设为当前笔刷；搜索框变化时 `search`。
- `markSquare(Square)`：加入列表。
- `search(String)`：按方块名、描述 id 包含关键字（小写），或方块名的拼音全拼/首字母包含关键字（`PinyinUtils`）筛选；空串显示全部。
- `render(...)`、`children()`、`mouseClicked`、`isFocused/setFocused`、`mouseDragged`、`mouseScrolled`、`isMouseOver`、`mouseReleased`、`isMouseOverSlider`、`getSliderPositionAndSize`：网格绘制与滚动。
