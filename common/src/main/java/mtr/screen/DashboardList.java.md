# DashboardList.java（上游文件，本分支有较多改动）

客户端界面控件：仪表板及各选择界面中使用的可搜索列表——每行一个颜色方块和名称，鼠标悬停时在行右侧出现操作按钮（查找、画区域、编辑、上移、下移、添加、删除），上方有翻页按钮、位置显示和搜索框。使用者：`DashboardScreen`、`DashboardListSelectorScreen`、`SidingScreen`、`TrainAnnouncerScreen`、`common/src/main/java/mtr/screen/AudioSpeakerScreen.java:469` 等；界面自动测试读取其内部（`testkit/src/main/java/mtm/testkit/client/WidgetTree.java:114`）。

本分支改动：滚动改为逐行（滚轮每次 3 行，按住 Shift 一页），右侧可拖动的滚动条；搜索按空格分词、每个词都须出现；可选中高亮某项并滚到中间（`selectItem`）；行样式可定制（`setRowStyle`：标签、标题行、颜色、点击标题行）；可锁定行的画区域按钮（`setLockedRows`）；"查找"按钮可不发声（`WidgetSilentImageButton`，构造参数 `playSound`）；`setData` 时立即重新过滤，避免显示行指向旧数据。

## 类 `DashboardList`（implements `IGui`）

### 公开字段
- `x`、`y`、`width`、`height`：位置与尺寸（由界面设置）。

### 私有字段与常量
- 控件：`textFieldSearch`、`buttonPrevPage`、`buttonNextPage`、各操作按钮。
- `getSearch`/`setSearch`：搜索文字的读写（通常存到 `ClientData` 的某个静态字段，界面之间保留）。
- `dataSorted`（显示顺序的数据）、`dataFiltered`（通过搜索的行在 `dataSorted` 中的下标）、`lastSearch`、`hoverIndex`（悬停的可见行号）、`scroll`（第一行的下标）、`isDraggingScrollbar`、`selectedItemId`（高亮项，`NO_SELECTION = -1`）。
- 行样式：`rowLabel`（默认格式化名称）、`isHeaderRow`、`isLockedRow`、`rowColor`、`onClickHeaderRow`。
- 能力：`hasFind`、`hasDrawArea`、`hasEdit`、`hasSort`、`hasAdd`、`hasDelete`（除查找外均需 `ClientData.hasPermission()`）。
- `TOP_OFFSET = SQUARE_SIZE + TEXT_FIELD_PADDING`、`SCROLLBAR_WIDTH = 4`、`MIN_SCROLLBAR_HEIGHT = 16`、`ROWS_PER_WHEEL_STEP = 3`、滚动条与选中颜色。

### 构造函数
- `DashboardList(onFind, onDrawArea, onEdit, Runnable onSort, onAdd, onDelete, Supplier<List<T>> getList, Supplier<String> getSearch, Consumer<String> setSearch)`：查找按钮发声。
- `DashboardList(…, boolean playSound)`：各回调为 `(数据, 在 dataSorted 中的下标)`；上/下移按钮直接修改 `getList` 返回的列表后调用 `onSort`。

### `void init(Consumer<AbstractWidget> addDrawableChild)`
- 布局翻页按钮与搜索框，恢复搜索文字，操作按钮先隐藏，全部加入界面。

### `void tick()`
- 搜索框 tick；按 `x` 更新位置（列表可被移入移出屏幕）；重新过滤；搜索变化时滚回顶部。

### `void setRowStyle(Function<NameColorDataBase, String> rowLabel, Predicate<NameColorDataBase> isHeaderRow, ToIntFunction<NameColorDataBase> rowColor, Consumer<NameColorDataBase> onClickHeaderRow)`
- 标题行（代表一组）没有操作按钮，点击时调用 `onClickHeaderRow`。调用者：`DashboardListSelectorScreen`、`DashboardScreen`。

### `void setLockedRows(Predicate<NameColorDataBase> isLockedRow)`
- 被锁定行的"画区域"按钮显示但不可用。

### `void setData(Set<…> dataSet, boolean hasFind, hasDrawArea, hasEdit, hasSort, hasAdd, hasDelete)`
- 排序后转调列表版本。

### `void setData(List<…> dataList, …)`
- 替换数据与能力，立即过滤并限制滚动。

### `private void filterData()`
- 按搜索分词过滤（`matchesKeywords`）。

### `void render(PoseStack, Font)`
- 位置文字 "最后可见/总数"（过宽时压缩）；有滚动条时画轨道与滑块；每行：选中高亮、颜色方块（不透明的 `rowColor`）、标签（过宽时横向压缩）。

### `void mouseMoved(double mouseX, double mouseY)`
- 先隐藏操作按钮；左键按在滚动条上（或已在拖动）时按鼠标位置设置滚动；否则若悬停在某行（非标题行）上，按能力显示操作按钮并从右向左排列；上/下移只在无搜索过滤时显示，首行不能上移、末行不能下移。

### `boolean mouseClicked(double mouseX, double mouseY)`
- 点在标题行上时调用 `onClickHeaderRow` 并返回 true；否则 false（界面需主动转发点击）。

### `void mouseScrolled(double mouseX, double mouseY, double amount)`
- 鼠标在列表内时滚动 3 行（Shift 一页）。

### `void clearSearch()`
- 清空搜索。

### `void selectItem(long id)`
- 高亮该项、清空搜索并把它滚到列表中间（不让搜索把它过滤掉）。

### `void clearSelectedItem()`
- 取消高亮。

### `int getHoverItemIndex()`
- 返回：悬停行在 `dataSorted` 中的下标，没有为 −1。调用者：`SidingScreen` 等。

### `private void setScroll(int)` / `private int getMaxScroll()` / `private boolean hasScrollbar()` / `private int getRowWidth()` / `private int getScrollbarHandleHeight()` / `private int itemsToShow()`
- 限制滚动并显示/隐藏翻页按钮；最大滚动；是否需要滚动条；行宽（有滚动条时减 4）；滑块高度（不小于 16）；可见行数。

### `private static boolean matchesKeywords(String name, String[] keywords)`
- 每个词（小写）都出现在名称中（任意顺序）。

### `private void onClick(BiConsumer<NameColorDataBase, Integer>)`
- 以悬停项调用回调。

### `private <T> void onUp(Supplier<List<T>>)` / `private <T> void onDown(Supplier<List<T>>)`
- 无搜索时把悬停项与上/下一项交换；按住 Shift 移到最前/最后。
