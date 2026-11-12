# SoundTree.java（本分支新增，0.73.1）

客户端：把可选的声音排成树，一次显示一层，放在一个 `DashboardList` 里（用户 2026-11-13）。根节点依次是 `minecraft`、`mtr`、"上传音频"（`gui.mtr.sound_tree_uploaded`），再是其他模组的命名空间（按名字）。命名空间下的游戏声音按 ID 路径的 `.` 分成文件夹（`block.anvil.land` 在 `block / anvil` 里）；上传音频下一层是上传者（`AudioCache.Entry.uploaderName`，空的记作 `?`），再下是文件。用于 `TrainAnnouncerScreen`（声音框旁的列表）和 `SoundPickerScreen`。

## 类 `SoundTree`

### 字段与常量
- `list`（public）：显示当前一层的 `DashboardList`；调用方负责摆放、`init`、事件转发和绘制。
- `preview`：试听，可为 null；`choose`：选中。
- `root`：根节点（保持插入顺序）；`current`：当前打开的文件夹。
- `search`、`searching`：搜索框文字，以及当前是否按搜索显示。
- `targets`：每一行（按对象身份）对应的东西：`Node`（文件夹）、`UP`（回上一级）或 `Sound`。
- `COLOR_FOLDER = 0xFFFFAA`、`COLOR_SOUND = 0xEEEEEE`：行颜色。

### 构造函数 `SoundTree(boolean withGameSounds, Consumer<String> preview, Consumer<String> choose)`
- `withGameSounds`：是否列出游戏声音（`SoundManager.getAvailableSounds()`）。为 false 时（音频方块只能播上传文件）只列有游戏声音位置（`getSoundLocation() != null`）的上传文件，并直接打开"上传音频"文件夹。
- `preview` 不为 null 时，声音行有试听（`onFind`）和"添加"（`onAdd`，选中）按钮；为 null 时声音行设为标题行，点击即选中。文件夹和回上一级永远是标题行。
- 选中值：上传文件是 `getReference()`（`namespace:name`），游戏声音是完整 ID。
- 建完树后调用 `Node.count` 统计各文件夹的声音数，再 `refresh`。

### `public void tick()`
- 转给 `list.tick()`。

### `public String path()`
- 当前文件夹路径，如 `minecraft / block / anvil`；在根时为空。`SoundPickerScreen` 把它接在标题后面。

### `private void setSearch(String text)`
- 列表搜索框的回调：记下文字；在"有搜索 / 无搜索"之间切换时重建行。

### `private void click(NameColorDataBase row)`
- 标题行点击：`UP` 回上一级，文件夹进入，声音（无试听时）选中。

### `private void refresh()`
- 重建行。有搜索时列出当前文件夹下所有声音（让列表自己按关键词过滤）；否则依次为回上一级（不在根时，文字带路径）、子文件夹（`> 名字 (声音数)`）、本层声音。`setData` 时只有 `preview` 不为 null 才打开试听和添加按钮（"添加"仍受 `ClientData.hasPermission()` 限制，与原来一样）。

### `private void add(List<DataConverter> rows, String label, int color, Object target)`
- 加一行并记录它对应的东西。

## 私有内部类 `Sound`
- `value`（选中后保存的值）、`label`（显示文字；上传文件后面附游戏声音位置）。

## 私有内部类 `Node`
- `name`、`parent`、`folders`（根保持插入顺序，其他层按名字不区分大小写排序）、`sounds`、`total`（含子文件夹的声音数）。
- `folder(String name)`：取或建子文件夹。
- `count()`：递归统计 `total`。
- `forEachSound(Consumer<Sound>)`：递归遍历（先子文件夹，再本层）。
