# LineRows.java（本分支新增）

客户端界面辅助：在列表中把同一线路（`Route.getLineKey()`）的多条线路折叠成一行"线路标题行"，点击展开显示其中每条线路。使用者：`common/src/main/java/mtr/screen/DashboardScreen.java:75`、`common/src/main/java/mtr/screen/DashboardListSelectorScreen.java:40`。

## 类 `LineRows`

### 字段
- `expandedLines`：已展开的线路键。
- `headerRows`：标题行 id → 线路键（每次 `getRows` 重建）。
- `childRows`：已展开线路中各线路的 id。

### `List<NameColorDataBase> getRows(List<Route> routes)`
- 作用：按线路键分组（保持传入顺序）；只有一条线路的线路直接作为一行；多条的生成标题行 `DataConverter(标题 id, "[+|-] <线路名> (<条数>)", 第一条线路颜色)`，展开时后面跟各条线路。
- 参数：`routes` 显示顺序的线路。返回：列表行。

### `void clear()`
- 清空标题行与子行记录。

### `boolean isHeader(NameColorDataBase data)` / `boolean hasHeader(long headerId)`
- 是否为当前的标题行 / 是否存在此标题 id。

### `boolean isChild(NameColorDataBase data)`
- 是否为已展开线路下的线路行。

### `int getColor(NameColorDataBase data)`
- 行颜色：展开线路下的线路显示自己的颜色（`Route.getOwnColor()`），否则为 `data.color`（线路颜色）。

### `boolean toggle(NameColorDataBase data)`
- 若为标题行则切换展开状态并返回 true，否则 false。

### `static long getHeaderId(String lineKey)`
- 标题行 id：`lineKey.hashCode()` 的低 32 位 | `1L << 40`（避免与真实 id 冲突的约定，非严格保证）。调用者：`DashboardScreen.java:151`。
