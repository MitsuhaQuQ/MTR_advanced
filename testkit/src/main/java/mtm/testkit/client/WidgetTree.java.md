# WidgetTree.java

MTM_TestKit 客户端：把当前打开界面的控件整理成脚本可读的树（类似网页的无障碍树）。控件 id 用"持有它的字段名"表示：`界面类名.字段名`，在 `DashboardList` 里为 `界面类名.列表字段.字段名`，列表/数组元素加 `[i]`；没有字段持有的控件记为 `类型#序号`。全部靠反射，MTR 代码无需改动。被 `ClientDriver` 的 `/state`、`/click`、`/type`、`/screenshot` 使用（`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:198`、`:200`、`:205`）。

## 类 `WidgetTree`（final，私有构造）

### 常量 `MAX_DEPTH = 3`
- 反射向下递归进 MTR 界面辅助对象的最大层数。

## 内部类 `WidgetTree.Found`
- 字段：`id`（控件 id）、`widget`（`AbstractWidget`）。私有构造。

### `static List<Found> widgets(Screen screen)`
- 作用：先 `walk` 收集"对象 → id"，再按 `screen.children()` 的顺序列出所有 `AbstractWidget`，没有 id 的用 `typeName(child)#index`（index 为在 children 中的位置）。
- 返回：控件列表（顺序与界面登记顺序一致）。
- 调用者：`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:198`、`:205`。

### `static JsonObject describe(Found found)`
- 作用：输出 `{"id","type","text","x","y","width","height","visible","active","focused"}`，并按类型加 `value`：`EditBox` 的文字、`Checkbox` 是否勾选、滑块的 0–1 值。坐标为 GUI 缩放后坐标。
- 调用者：`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:198`。

### `static JsonArray lists(Screen screen)`
- 作用：找出界面（含递归辅助对象）持有的所有 `DashboardList`，逐个 `describeList`。
- 调用者：`testkit/src/main/java/mtm/testkit/client/ClientDriver.java:200`。

### `private static JsonObject describeList(String id, DashboardList list)`
- 作用：反射读取 `DashboardList` 的私有字段 `dataSorted`、`dataFiltered`、`scroll`、`TOP_OFFSET`、`rowLabel`、`isHeaderRow`，调用私有方法 `itemsToShow()`，输出列表位置尺寸、`scroll`、`total`（过滤后条数）、`rowsShown`，以及当前可见每行的 `index/dataId/name/label/header/type/x/y/height`；行高为 `IGui.SQUARE_SIZE`，行 y = `list.y + TOP_OFFSET + 行高 × i`。
- 副作用：无；反射失败时在结果里写 `error`。
- 注意：依赖 `DashboardList` 的私有字段名，那边改名会导致这里报错。

### `private static Field field(Class<?> owner, String name)`
- 取声明字段并 `setAccessible(true)`。

### `private static void walk(Object object, String prefix, Class<?> fromClass, Map<Object, String> ids, int depth)`
- 作用：从 `fromClass` 沿父类向上（到 `Screen`/`Object` 为止）遍历非静态、非基本类型、非合成字段：
  - 字段值是控件或 `DashboardList`：登记 id（`putIfAbsent`，先登记者优先），`DashboardList` 还要递归；
  - `List`、对象数组：其中的控件登记为 `id[i]`；
  - MTR 的界面辅助对象（`isScreenHelper`）：留待递归。
  直接持有的字段先命名，之后才递归进辅助对象，所以控件以其直接所有者命名。递归深度 < `MAX_DEPTH`。

### `private static boolean isScreenHelper(Object value)`
- 类名以 `mtr.screen.` 开头、非合成类、非 lambda、且不是 `Screen` 时为真。

### `static String typeName(Object widget)`
- 作用：非 `net.minecraft.` 类返回简单类名（MTR 类在发布 jar 中不混淆）；原版类按最近的已知类型返回 `ImageButton`/`EditBox`/`Checkbox`/`Slider`/`Button`/`Widget`/`Listener`。
