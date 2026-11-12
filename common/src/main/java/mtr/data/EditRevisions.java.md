# EditRevisions.java

多人编辑冲突检测：服务器记录玩家可编辑的铁路数据各部分的版本号，一个基于旧副本做的修改（例如两人同时编辑同一对象）会被拒绝，而不是覆盖对方。每类数据（车站、线路……）按对象和"一个界面一次发送的部分"（如名称与颜色、或时刻表）分别计数，所以两人编辑同一线路的不同部分不会互相阻挡。服务器运行期间删除的对象会被记住，防止仍开着的界面把它改回来。由 `RailwayData.editRevisions` 持有，`EditGuard` 检查和记录，`RailwayDataFileSaveModule` 保存，客户端 `ClientEditing` 用同一键格式。

## final class EditRevisions

### 字段与常量
- `revisions`：键 → 版本号（缺省 0）。
- `editors`：键 → 最近修改者玩家名。
- `deleted`：被删除对象的 `kind:id:*` 键 → 删除者。
- `unsaved`（`LinkedHashSet`）：自上次保存以来变化的键。
- `WHOLE = "*"`：删除操作对应的"部分"（整个对象）。
- `UNCHECKED = -1`：不做检查的修改所带的版本（来自不知道版本的代码）。
- 键格式：`kind:id:part`（`getKey`）。

### `public int get(String kind, long id, String part)`
- 返回：当前版本，缺省 0。
- 主要调用者：未在服务端代码中直接找到（客户端 `ClientEditing.java:73` 等调用的是同名的其它方法，未确定）。

### `public Conflict check(String kind, long id, String part, int base, boolean isDelete)`
- 参数：`base` 修改基于的版本，或 `UNCHECKED`；`isDelete` 是否删除操作。
- 返回：可以修改返回 `null`；对象已被删除返回 `Conflict(deleted=true, 删除者)`；删除操作或 `UNCHECKED` 返回 `null`；否则 `base` 与当前版本不同返回 `Conflict(false, 最近修改者或 "")`。
- 主要调用者：`EditGuard.java:154`、`EditGuard.java:349`、`EditGuard.java:359`。

### `public int record(String kind, long id, String part, String playerName, boolean isDelete)`
- 作用：记录一次修改：删除时登记到 `deleted` 并把 part 改为 `WHOLE`；版本 +1、记录修改者、加入 `unsaved`。
- 返回：新版本号。
- 主要调用者：`EditGuard.java:229`。

### `public void restore(String key, int revision, String editor, boolean isDeleted)`
- 作用：从存档恢复一条版本记录（不加入 `unsaved`）。
- 主要调用者：`EditGuard.java:212`。

### `public List<Object[]> takeUnsaved()`
- 返回：自上次调用以来变化的条目，每项 `[key, revision, editor, isDeleted]`（`isDeleted` 为该键是否在 `deleted` 中，即只有 `*` 键可能为真）；随后清空 `unsaved`。
- 主要调用者：`RailwayDataFileSaveModule.java:543`。

### `public void keepUnsaved(List<Object[]> entries)`
- 作用：保存失败时把这些键放回 `unsaved`，下次再存。
- 主要调用者：`RailwayDataFileSaveModule.java:624`。

### `public Map<String, Integer> getAll()`
- 返回：所有非 0 版本的拷贝（`kind:id:part` → 版本），发给新加入的玩家。
- 主要调用者：`EditGuard.java:464`。

### `public static String getKey(String kind, long id, String part)`
- 返回：`kind + ":" + id + ":" + part`。
- 主要调用者：`ClientEditing.java:75`、`EditGuard.java:451`。

## public static final class Conflict
- `deleted`：冲突原因是对象已被删除（而非该部分被改）。
- `playerName`：造成冲突的修改者，可能为空。
- 构造函数私有，只在 `check` 中创建。
