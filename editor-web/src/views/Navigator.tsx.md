# views/Navigator.tsx

各层左侧的导航：旧树（`editor.js` 的 `renderTree`）的新设计，每次只显示一层对应的部分。由 React 通过 Portal 画进旧的 `#tree` 元素，每一行保留旧节点的类名 `node` 和 `data-*` 属性，所以旧页面在 `#tree` 上的处理函数（`onTreeClick`、树的右键菜单、键盘）照旧响应点击、按键和右键；新的只是外观和布局。由 `views/Layers.tsx` 按 `TREE_PART` 显示。

## 类型
- `Part`（导出）：`1 | 2 | 3 | 4 | 5`，对应配线、归属、线路、时刻、车厂。

## 导出
### `Navigator({ part })`（组件）
- 把 `#tree` 的类名改为 `nav-tree`（脱离旧树样式）；用 `LegacySlot id="tree"` 借进布局，并 `createPortal(<Part part={part} />, #tree)`。
- 调用者：`views/Layers.tsx:53`。

## 内部组件与函数
- `Heading({ children, action })`：小节标题。
- `Swatch({ color, wide })`：颜色块。`Count({ children, title, tone })`：右侧计数。`Dot({ title, error })`：草稿/错误小圆点。
- `Raw({ html })`：直接显示旧代码已转义的 HTML（`problemDot`、`lockMark`）。
- `Row({ attrs, depth, caret, active, title, children, muted })`：一行（旧 `.node`）：`attrs` 转为 `data-*` 属性；`depth` 缩进；`caret` 展开状态；`active` 高亮当前项；可聚焦。
- `Part({ part })`：按部分渲染 `Physical`、`Owners`、`OperatingLines`、`Timetables`、`Depots`；无数据时"正在读取…"。
- `onNetwork(layer)`：当前是否在网络视图的某图层。
- `RouteLabel({ r })`：路线标签：色条、标题（将删除的种别加删除线）、删除/草稿标记（`fn.isDeleted`、`fn.draftOf`）、`ProblemDot`。
- `ProblemDot({ r })`：股道问题标记（旧 `problemDot`）：`topologyOf(...).routeProblems(r, fn.platformsOf(r))`，有错误红色、只有警告琥珀色，提示列出问题。
- `Created({ made, title, depth })`：草稿中新建的种别/线路一行（`data-created`），有错误时红点。
- `Physical()`：第 1 部分："全网配线"一行（`data-layer="physical"`）及说明。
- `Owners()`：第 2 部分："新建逻辑线路"（`data-new-logical`）、"归属图"（`data-layer="owners"`）、各逻辑线路（`data-logical`，草稿标记、联络线标记，当前聚焦高亮 `v.net.focusLogical`）；没有逻辑线路时提示、"按现在的归属生成初稿…"（`data-generate-logical`）和联络线列表（`fn.isConnecting`，`data-connecting`）。
- `OperatingLines()`：第 3 部分："＋ 新建线路"（`data-new-line`）、各线路组（`data-line`、`data-section="lines"`，展开状态来自 `state.openNodes` 的 `oline:<key>`）及其种别（`data-route`，站数）和草稿新建种别；最后是只在草稿中的新线路组。
- `Timetables()`：第 4 部分：各线路组（展开 `line:<key>` 或为当前线路，显示当天班次数），展开后 `ViewChips`（运行图/汇总时刻表）和各路线（展开 `route:<id>`，可再显示 `ViewChips`）。
- `ViewChips({ depth, attrs, table, diagram })`：一行里的"运行图"和"汇总时刻表"两个选择块（也是 `.node`，带 `data-view`）。
- `Depots()`：第 5 部分：各车厂（`data-depot`；锁标记 `fn.lockMark`、运用草稿标记、方式及缺车/建议重排计数，提示用 `fn.shortfallText`/`fn.replanText`/`fn.missingTrains`）；展开（`depot:<id>`）后按车辆池列出（`data-pool`、`data-of-depot`；草稿标记按 `fn.sameVehicle` 判断；`fn.poolFleetText` 配属与运用数）、池内各运用（`data-slot`、`data-siding`；`fn.startText`、问题标记 `fn.dutyProblems`、当天班数/留/休；选中高亮），以及存车线（`data-siding`；最多停几列、只跑哪些线路，优先显示草稿值）。
