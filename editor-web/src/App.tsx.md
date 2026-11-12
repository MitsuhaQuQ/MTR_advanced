# App.tsx

新页面的外壳。`App` 包上 `StoreProvider`（`state/store.tsx`），`Shell` 画出顶栏、草稿/冲突提示条、左侧工作层导轨、当前层的工作区、状态栏，以及浮在上面的审阅面板、同步面板、旧代码菜单、配线图、停站编辑器、线路层各侧面板。当前层由地址栏 `#/<层>` 决定（`shell/layers.ts`）。通过 `legacy`（`legacy/engine.ts`）调用旧代码的 `openView`、`setWeekday`。由 `main.tsx` 渲染。

## 导出
### `App()`（组件）
- 无 props。返回 `<StoreProvider><Shell/></StoreProvider>`。
- 调用者：`main.tsx:6`。

## 内部组件与函数
### `Shell()`
- 状态：`layer`（当前层 `LayerId`，初值 `layerOf(location.hash)`，并随 `hashchange` 更新）；`panel`（`"review" | "sync" | null`）。
- `open(id)`：切换层，`history.pushState` 写入 `#/<id>`。
- `closePanel()`：关闭审阅/同步面板。
- `openRoute(routeId)`：从改动列表/问题列表跳到某路线的汇总时刻表：在 `legacy.v.data.routeById` 找路线，切到 `timetable` 层，调用旧代码 `legacy.fn.openView("table", r.lineKey, r.id)`。传给 `ReviewPanel` 和 `SyncPanel`。
- 键盘：不在文本框时，Ctrl/⌘+Z 撤销（加 Shift 重做）、Ctrl/⌘+Y 重做（`store.undo/redo`），Ctrl/⌘+Enter 打开审阅面板。
- 渲染：`TopBar`、`Banner`、`LayerRail`、层提示行（"第 N 层" + 层说明）、`Boundary`（以层为 `resetKey`）包裹的 `MediaLibrary`（媒体层）或 `LayerWork`，`StatusBar`、`ReviewPanel`、`SyncPanel`、`LegacyMenu`、`TrackDiagram`、`StopsEditor`、`LinePanels`。

### `TopBar({ onReview, onSync })`
- props：`onReview` 打开审阅面板；`onSync` 打开同步面板。
- 显示标志、标题、"新版预览"、`Weekday`、同步状态按钮（`useSyncProblems()` 中非 `hint` 的问题数；有 `err` 显示警告色）、草稿按钮（`draftCount(store.draft)` 项，应用中显示"正在应用…"）、"应用"按钮（可用条件：未在应用、草稿数大于冲突数 `conflictCount`、有编辑权限；点击也只是打开审阅面板）、"回到旧版"链接 `../#/timetable`。

### `WEEKDAYS`
- 常量：周一…周日的中文名，作为下拉项文本（经 `tr`）。

### `Weekday()`
- 运行日选择框；`useLegacy()` 跟随旧代码重绘。值为 `legacy.v.state.weekday`（0=周一），改动时调用旧代码 `fn.setWeekday(n)`（每天有各自的时刻表）。无数据时不显示。

### `LayerRail({ layer, open })`
- props：当前层；切换函数。按 `LAYERS` 画竖排图标链接，第 6 项前加分隔线，当前层高亮并带 `aria-current`，有 `step` 的层在角上显示序号。

### `StatusBar()`
- 每秒刷新一次。显示连接状态（`store.connection`：`ok` 已连接、`connecting` 正在连接、其他为没有连接）、"数据读取于 N 秒前"（`store.loadedAt`）、无编辑权限提示，以及"改动先进草稿，按'应用'才发给游戏"。
