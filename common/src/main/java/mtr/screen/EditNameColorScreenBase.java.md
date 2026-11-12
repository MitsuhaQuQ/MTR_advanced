# EditNameColorScreenBase.java（上游文件，本分支有改动）

客户端界面基类：编辑一个有名称和颜色的对象（线路、车厂等）的界面——名称文本框和颜色选择器，关闭时保存并回到仪表板。子类：`common/src/main/java/mtr/screen/EditRouteScreen.java:17`、`common/src/main/java/mtr/screen/EditDepotScreen.java:23`。

本分支改动：继承 `mtr.compat.ScreenBase`；打开/关闭时通知 `ClientEditing.beginEdit/endEdit`（多人编辑冲突）；显示其他正在编辑者（`renderEditors`）；颜色读写改为可覆盖的 `getEditedColor`/`setEditedColor`。

## 抽象类 `EditNameColorScreenBase<T extends NameColorDataBase>`（extends `ScreenBase`，implements `IGui`, `IPacket`）

### 字段
- `nameStart`、`colorStart`、`colorEnd`：名称列和颜色列的横向边界（像素）。
- `data`：被编辑对象；`dashboardScreen`：关闭后返回的仪表板。
- `nameText`、`colorText`：列标题；`textFieldName`、`colorSelector`：控件。

### 构造函数 `EditNameColorScreenBase(T data, DashboardScreen dashboardScreen, String nameKey, String colorKey)`
- 记下对象与标题翻译键，`ClientEditing.beginEdit(data)`，创建控件。

### `void tick()`
- 文本框光标闪烁。

### `void onClose()`
- 回到仪表板，`saveData()`，`ClientEditing.endEdit(data)`。

### `boolean isPauseScreen()`
- false（不暂停游戏）。

### `protected void setPositionsAndInit(int nameStart, int colorStart, int colorEnd)`
- 子类在 `init` 中调用：设置边界、布局文本框与颜色选择器（第二行，`SQUARE_SIZE` 起）、填入当前名称与颜色、加入控件。

### `protected void renderTextFields(PoseStack matrices)`
- 绘制其他编辑者提示与两列标题。

### `protected void saveData()`
- 名称（空则"未命名"，`IGui.textOrUntitled`）与颜色写回对象。子类覆盖以保存更多字段并发包。

### `protected int getEditedColor()` / `protected void setEditedColor(int color)`
- 默认读写 `data.color`；子类可改为编辑其他颜色字段（如线路自己的服务颜色）。

### `static void renderEditors(PoseStack, Font, NameColorDataBase data, int height)`
- 有其他玩家在编辑同一对象时，在左下角用黄色显示 `gui.mtr.others_editing`（玩家名逗号分隔）。调用者：本类、`common/src/main/java/mtr/screen/SavedRailScreenBase.java:106`。
