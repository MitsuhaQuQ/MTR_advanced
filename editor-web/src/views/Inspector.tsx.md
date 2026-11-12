# views/Inspector.tsx

运行图旁的检查器：旧 `renderInspector` 的新设计，由 React 通过 Portal 画进旧页面的 `#inspector` 元素。选中的车次、线路及其股道问题、操作提示是新组件；旧处理函数依赖的表单类面板（正在编辑的运用、点选车站、选车厂出入口、模拟问题、环线运行）按旧代码的样子保留，使 `#inspector` 上的旧处理函数仍能找到它们的数据属性（运用详情已改为 `DutyDetail`）。由 `views/Layers.tsx` 在车厂层和时刻层显示。

## 导出
### `Inspector({ className })`（组件）
- props：`className` 外层类名。
- 用 `LegacySlot id="inspector"` 把旧元素借进布局，并用 `createPortal(<Content />, #inspector)` 往里画。
- 调用者：`views/Layers.tsx:231`、`views/Layers.tsx:260`。

## 内部组件
- `Section({ title, children, aside })`：小节（标题 + 右侧附加内容）。
- `Card({ children })`：卡片容器。
- `Swatch({ color })`：路线色条。
- `Tag({ tone, children, title })`：小标签（`warn`/`sel`/`plain`/`caution`）。

### `Content()`
- `useLegacy()`。每次渲染把旧的 `#net-waypick` 面板隐藏并清空（选出入口改在 `views/PickPanels.tsx` 显示）。
- 依次显示：选中运用位时 `DutyDetail`；选中车次时 `Selected`；模拟开着时 `SimProblems`；选中车次或当前路线是环线时 `LoopRuns`（`fn.isLoop`）；有当前线路组时 `Line`；最后 `Hints`。

### `Selected({ s })`
- 选中车次：路线标题，标签（停运、没有车跑这一班 `fn.hasNoTrain`、有单班例外、有未应用草稿 `fn.draftOf`），班次时刻、所属表、直通接续、回库，以及各站到发时刻（通过站显示 ↓，例外时刻用选择色）。
- "在时刻表里看"：`fn.openView("table", lineKey, routeId)`。

### `Line({ line })`
- 当前线路组：每条路线的车次数、今天有车/没车数（路线不在任何按运用跑的车厂时显示"看不出哪班有车"，`fn.coverageNow().routes`）、停运数；缺车车厂的说明作为提示（`fn.shortfallText`）。
- 股道问题：`topologyOf(data.topology).routeProblems(r, fn.platformsOf(r))` 有问题的路线逐条列出。

### `Hints()`
- 可折叠的"怎么操作"说明（运行图缩放平移、拖动、多选、停站方块、按运用着色、时刻表输入快捷键）。

### `SimProblems({ line })`
- 模拟问题（旧 `simPanel`）：`simProblems(sim, data, line, 正在验证的车厂)`（`core/dispatch.ts`），写入 `sim.listed`；最多列 `SIM_MAX_LISTED` 条，每条显示时刻、种类（`SIM_KINDS`）、路线与班次、站名、持续时间；点击选中该车次并 `fn.centerOn(s)`、`fn.renderAll()`。附未模拟说明和模拟起点说明。

### `LoopRuns({ r })`
- 环线运行（旧 `loopPanel`）：`fn.loopRuns(r)`、`fn.loopEntryStop(r)`：列车数与班次数、出库接入站、等下一圈上限（8 天或 30 分钟，按 `v.MAX_LAYOVER_MILLIS` 比较）、每列车各圈发车时刻（最多 40 列）及最后去向（直通、留在线上、回库）。
