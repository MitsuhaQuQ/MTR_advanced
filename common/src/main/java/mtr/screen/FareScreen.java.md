# FareScreen.java（本分支新增）

客户端界面：按距离计费（设计文档 6.13，0.68.0），从仪表板打开（`common/src/main/java/mtr/screen/DashboardScreen.java:130`）。三个标签页：
- 票价：票价规则（0.73.0），可新建、命名、删除（被删规则的线路和车站转给所选规则）；每条规则有参考线路（分档上限取其长度）、公式（起步价、单位价、起步距离、单位距离）、最长距离、封顶、超时罚款，以及在距离条上显示的分档——分档边界可拖动、增删，每档票价可设。
- 线路：每条线路（含其所有方向与服务种类，0.73.0）用哪条规则。
- 车站：车站属于哪些规则（属于多个时闸机共用）。

所有修改都在副本 `edited` 上进行，点"完成"时用 `FarePackets.setC2S` 发送；服务器检查玩家是否可编辑。打开时请求服务器各组最长距离（`FarePackets.requestGroupsC2S`），回包由 `common/src/main/java/mtr/packet/FarePackets.java:116` 调用 `setGroups`。距离单位：界面显示公里，内部为方块（`FareLevels.BLOCKS_PER_KILOMETRE`）。

## 类 `FareScreen`（extends `ScreenBase`，implements `IGui`）

### 常量与枚举
- `LIST_WIDTH = 150`、`ROW_HEIGHT = 12`、`FIELD_WIDTH = 50`、`SNAP_BLOCKS = 100`（分档边界吸附步长，方块）。
- 枚举 `Tab {FARES, ROUTES, STATIONS}`。

### 字段
- `tab`、`edited`（`NetworkSettings` 副本，从 `DATA_CACHE.networkSettings` 复制）。
- `groups`：组键 → `{名称, 最长距离(方块)}`（来自 `FareData.getAllGroups`，最长距离由服务器回包填入）。
- `listItems`、`listScroll`、`selected`（组键字符串 / `LineItem` / `Station`）。
- 标签与保存/取消按钮；公式文本框：`fieldStartFare`、`fieldUnitFare`（整数，非负）、`fieldInitial`、`fieldUnit`、`fieldMax`（公里，小数）、`fieldCap`（空为不封顶）、`checkboxOvertime`、`fieldOvertimeHours`（小时）、`fieldOvertimeFine`；`buttonRecalculate`（按公式重算分档）、`buttonKeepTiers`（保留手动分档）；`fieldTierPrice`、`buttonAddBound`、`buttonRemoveBound`；`fieldRuleName`、`buttonNewRule`、`buttonReference`（循环参考线路）、`buttonDeleteTarget`（循环删除后的去向规则）、`buttonDelete`；`deleteTarget`。
- `lines`：线路键（`FareData.getAutoGroupKey`）→ 名称（`FareData.getLines`）。
- `dynamicWidgets`：随选中项变化的按钮/复选框。
- `groupsReceived`（收到最长距离前距离条不可改，否则会把分档做到 0 距离）、`pendingChoice`（手动分档后公式又改了，等待选择重算或保留）、`loadingFields`（填充控件时不触发回调）、`selectedTier`、`draggingBound`。

### 构造函数 `FareScreen()`
- 复制设置，建组表与所有控件；文本框回调写入所选规则的 `FareLevels`（公式字段经 `formulaChanged`，其他经 `withLevels`；最长距离空为 0，封顶空为 −1，超时时长按小时转分钟）；档价修改写入对应档；最后请求最长距离。

### `void setGroups(Map<String, Object[]> serverGroups)`
- 只取服务器的最长距离（规则与名称以本界面编辑的为准），标记已收到，刷新列表。

### `protected void init()`
- 左侧列表与三个标签按钮，右侧各控件布局（未逐行核对）。

### `private static void placeField(…)` / `private static int row(int row)`
- 放置文本框；行 y。

### `private void setTab(Tab)` / `private void updateList()`
- 切换标签页；按标签列出组 / 线路（按名称，每条线路一次）/ 车站（按名称），默认选第一项。

### `private void loadFields()`
- 只在票价页且选中规则时显示公式等控件，并以当前值填充（不触发回调）；参考线路、删除去向（只有一个其他规则时不可切换）。

### `private void loadTierPrice()`
- 填所选分档价格；收到最长距离且选中分档时才可编辑价格、加边界（未达 `FareLevels.MAX_TIERS − 1`）、删边界（不是最后一档）。

### `private void updateDynamicWidgets()`
- 线路页：每条规则一个按钮，点击把该线路所有线路（`routesOf`）设为该规则（`edited.fareRouteGroups`）。车站页："自动"按钮（按经过线路的规则，清除手动设置）和每条规则的复选框（勾选结果为空时恢复自动）。

### `private static List<Route> routesOf(String lineKey)`
- 未隐藏、至少两站、线路键相同的线路。

### `private String lineRule(String lineKey)`
- 线路按其第一条线路的规则（`FareData.getGroupKey`），无线路时为默认规则。

### `private List<String> otherRules()`
- 除所选外的规则键。

### `private void newRule()`
- 新建 `rule:N`，名为"规则 N"（`gui.mtr.fare_rule_new_name`），无参考线路；上限 `NetworkSettings.MAX_FARE_GROUPS`。

### `private void nextReference()`
- 参考线路在"无"与各线路之间循环。

### `private void deleteRule()`
- 删除所选规则（至少保留一条）：按它计费的线路改为去向规则，设为它的车站换成去向规则；`keepFareRulesKnown()` 后选中去向规则。

### `private String groupName(String group)`
- 组显示名称。

### `private FareLevels getLevels()` / `private FareLevels editLevels()`
- 所选组的票价：查看时没有就用默认（不保存，只看不改的组不会被保存）；修改时创建并保留。

### `private long getLongest()`
- 所选组最长距离（方块）。

### `private void withLevels(Consumer<FareLevels>)` / `private void formulaChanged(Consumer<FareLevels>)`
- 在非填充状态下修改并刷新档价控件；公式改动且分档为手动时进入"等待选择"。

### `private void addBound()` / `private void removeBound()`
- 在所选档中点（吸附到 100 方块）加边界并复制该档价格；删除所选档右侧的边界（合并下一档）。

### `void render(…)`
- 左侧列表（选中高亮）；票价页：标题（规则名与最长距离）、各标签、公式变化提示、距离条、档价与名称标签；线路页：线路及其规则；车站页：车站及其规则（多个时提示共用闸机）。

### `private void drawLabel(…)` / `private String listText(Object item)`
- 标签；列表行文字（线路行为"名称 → 规则"）。

### `private long getBarBlocks(FareLevels.Tiers)` / `private int barX(long blocks, long barBlocks, int left, int right)`
- 距离条总长：max(最长距离或设定的最长距离, 最后边界 × 1.1, 1 公里)；距离 → x。

### `private void drawTiers(PoseStack, int left, int right)`
- 画距离条：所选档高亮；每档价格写在档内（与前一档同价则不重复写）；边界手柄（两侧同价为灰色）与上方公里数（重叠时省略）；右下角总长。

### `boolean mouseClicked(…)`
- 点列表选择项；票价页且已收到距离时，点边界手柄开始拖动，点条内选择所在档。

### `boolean mouseDragged(…)` / `boolean mouseReleased(…)`
- 拖动边界：吸附到 100 方块，限制在相邻边界之间（至少隔 100 方块）。

### `boolean mouseScrolled(…)` / `boolean isPauseScreen()`
- 列表每次滚 3 行；不暂停。

### `private static WidgetBetterTextField numberField()` / `decimalField()`
- 非负整数框（票价、封顶、罚款不会为负）；小数框。

### `private static long snap(long)` / `private static String kilometres(long)` / `parseInt` / `parseDouble` / `parseBlocks`
- 吸附到 100 方块；方块 → 一位小数公里；解析（失败用后备值）；公里文本 → 方块（负数或无效用后备值）。

## 私有内部类 `LineItem`
- 线路页的一行：`key`（线路键）、`name`。
