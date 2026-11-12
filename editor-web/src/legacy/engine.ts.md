# legacy/engine.ts

新页面里运行旧编辑器代码的桥。`engine.generated.js`（由 `scripts/gen-legacy.mjs` 从旧页面 `editor.js` 生成）导出 `createLegacy(env)`；本文件构造 `env`（旧代码调用的外部函数：网络请求、菜单、各种对话框），把旧对话框改接到新页面的 React 面板（`diagramHost`/`stopsHost`/`panelHost`），把"退役"的旧规则换成新代码（`core/services.ts`、`legacy/retired.ts`、`legacy/dialogs.ts`），然后导出 `legacy = { fn, v }`：`fn` 按名字取旧函数（`function f` 和 `const f = () => …` 都有），`v` 是旧代码的变量，可实时读写。新页面各视图通过 `legacy.fn.xxx` / `legacy.v.xxx` 使用旧代码。

数据流：
- 游戏数据：store 读到的数据经 `pushData` 复制一份交给旧代码 `setData`；
- 草稿：store 的草稿变化（撤销、审阅面板、应用、另一个标签页的旧页面）经 `pushDraft` 推给旧代码；旧代码改了草稿后调用 `renderDraftState`→`env.render`，`checkDraft` 发现变化就经 `onLegacyDraft` 注册的回调交回 store，作为一步撤销；
- 旧代码的 `renderAll` 画它仍负责的旧视图（`renderLegacy`），并通知 React 重绘（`notify`/`useLegacy`）；
- 旧代码的菜单由 React（`legacy/Slot.tsx` 里的菜单）按 `[label, action]` 项绘制。

文件开头 `import "./stage"`：先建立旧代码需要的 DOM 舞台（见 [stage.ts](stage.ts.md)）。

## 类型
- `MenuItem`：`[label, action]`；`action` 为函数（点击执行）、子菜单数组、或 `null/undefined`（灰掉/只显示）。
- `MenuHandler`（内部）：`{ open(x, y, items), close() }`，坐标为视口 CSS 像素。
- `DiagramTarget`：要显示的配线图：某条路线 `{kind:"route", routeId}` 或某个线路组 `{kind:"line", lineKey}`（由 `views/TrackDiagram.tsx` 显示）。
- `StopsTarget`：停站编辑目标：某路线（`stop` 为停站序号，-1 表示不定位到站）、新建线路组 `{kind:"new"}`、给线路组新建类型 `{kind:"type", lineKey, baseId}`（以某路线为底，空串表示没有）。由 `views/StopsEditor.tsx` 显示。
- `PanelTarget`：线路层/车厂层各侧面板的目标联合类型：`speed`（限速，多条路线）、`through`（直通运行）、`contingency`（应急预案，按系统 `anchor`）、`table`（某路线第 `index` 个时刻表）、`depot`、`rules`（车厂规则，`editing` 为正在编辑的规则或 null，`opened` 为打开时间戳，用于再次打开）、`vehicle`（车辆池 `poolKey`）、`groups`（运用组）、`release`（出库计划）、`moveLine`（线路换车厂）、`ask`（问答面板，见 `AskTarget`）、`serviceOrder`（某车次的出库/回库 `order` 及停站序号）、`logicalLine`（逻辑线路，`then` 为选定后的回调）、`owners`（归属，第 `index` 条）、`lineTracks`、`finishPicking`。由 `views/LinePanels.tsx` 显示。
- `AskTarget`：侧面板中的一个问题：`title`、`text`（说明行）、`list`、`hints`、`ok`（确认按钮文字）、`danger`（危险样式）、`input`（可选的单个输入：`label`、`value`、`unit`、`placeholder`、`numeric`）、`go(value)`（确认后执行，返回字符串表示错误提示；没有 `go` 则只是告知）。

## 导出的宿主变量
- `menuHost`：`{ current: MenuHandler | null }`，由 `legacy/Slot.tsx:35` 挂上；其他视图也直接用它开菜单（`views/DutyBoard.tsx:49`、`views/Layers.tsx:304`、`views/TrackDiagram.tsx:132`）。
- `diagramHost`：由 `views/TrackDiagram.tsx:21` 挂上；调用者 `views/StopsEditor.tsx:194` 和本文件 `env.routeDiagramDialog`。
- `stopsHost`：由 `views/StopsEditor.tsx:32` 挂上；本文件 `env.stopsDialog/newLineDialog/newTypeDialog` 调用。
- `panelHost`：由 `views/LinePanels.tsx:25` 挂上；本文件的各 `*Dialog`、`treeExtras`、`dialogRules` 以及 `views/DutyBoard.tsx` 等调用。

## 内部函数与对象
- `treeExtras(e)`：新页面给旧树右键菜单追加的项（旧代码冻结，0.69.2 起）。找到事件目标最近的 `#tree .node`，读其 `data-*`：车厂节点或线路节点返回一项"线路换车厂…"，动作是 `panelHost` 打开 `moveLine`；不可编辑或只有一个车厂时动作为 `null`。股道、位置、车辆池或"属于车厂"的节点返回空数组。调用者：`env.openMenuAt`。
- `api(path)`：把相对路径（去掉开头斜杠）拼成本页同源的绝对 URL。
- `env`：传给 `createLegacy` 的环境对象，成员：
  - `MTREditor`：旧代码期望的全局：`key`（编辑器密钥，来自 `api/client.ts`）、`ws:"timetable"`、`part:""`、空 `open()`、`inTimetable()` 恒真。
  - `openMenu(x, y, items)` / `openMenuAt(e, items)`：交给 `menuHost` 打开菜单；`openMenuAt` 追加 `treeExtras`，事件没有坐标（键盘触发）时贴着目标元素左下方打开。`closeMenu()` 关闭。`buildMenu()` 返回空 `div`（旧菜单 DOM 已不用）。
  - `fetchJson(path)`：带 `X-MTR-Editor-Key` 头、`no-store` 的 GET；非 2xx 抛 `Error(状态码)`；返回 JSON。
  - `sendJson(path, method, body)`：同上的写请求（JSON body）。对 `api/draft` 直接返回 `{ok:true}`，因为草稿由新页面 store 保存。
  - `setTreeOpen()`：空操作。
  - `routeDiagramDialog`、`lineDiagramDialog`：改为 `diagramHost` 打开新页面配线图。
  - `stopsDialog(r, stop)`、`newLineDialog()`、`newTypeDialog(lineKey, base)`：改为 `stopsHost`。
  - `speedLimitDialog`、`throughDialog`、`contingencyDialog`、`tableDialog`、`depotDialog`、`depotRulesDialog`、`vehicleDialog`、`dutyGroupsDialog`、`releasePlanDialog`：改为 `panelHost` 对应的 `PanelTarget`。
  - 退役第 3 步：`buildStations`、`plansFor`、`toPlans`、`gameSource`、`timesOf` 直接用 `core/services.ts`；`buildServices()` 为每条路线用 `Services.routeServices(r, fn.timetableOf(r), fn.timesSource(r), state.weekday, data.dayStartMillis)` 重算 `r.services`（副作用：改 `legacy.v.data.routes[*].services`）；`findService(id)`、`currentLine()` 包装 services 中同名函数。
  - 第 4 步起：展开 `timetableRules`、`dutyRules`、`settingsRules`、`dispatchRules`（`legacy/retired.ts`）和 `dialogRules`（`legacy/dialogs.ts`）返回的函数，覆盖旧代码同名函数。
  - `render(name)`：旧代码每次绘制时调用，转到 `onRender`。
- `created`：`createLegacy(env)` 的结果 `{ fn, v }`。
- `onRender(name)`：`name === "renderAll"` 时 `renderLegacy()`；然后 `checkDraft()`、`notify()`。
- `notify()`：把重绘合并到事件循环下一轮（用 `setTimeout` 而非动画帧，因为隐藏标签页没有动画帧），之后 `version++` 并通知所有 `useLegacy` 订阅者。
- `checkDraft()`：在旧代码当前动作结束后（下一轮）比较 `legacy.v.draft` 的 JSON 与上次推送/接收的 `pushedJson`；不同则记下并把副本交给 `draftListener`。一次改多条路线的动作因此只算一步撤销。

## 导出
- `legacy`：`{ fn, v }`。`fn` 是 Proxy：按名字依次在旧函数表、旧变量表、`env` 中查找。`v` 即旧代码变量（`data`、`draft`、`state`、`sim`、`DAY`、`FIRST_DAY_WEEKDAY`…）。模块加载时还从 `localStorage` 读 `mtr-next-old-diagram` 设置 `legacy.v.oldDiagram`（是否按旧方式画运行图；隐私窗口下忽略错误）。几乎所有视图都用它。
- `flushLegacy()`：立即 `version++` 并通知订阅者，供测试在 `act` 中使用。`src` 中未见非测试调用。
- `useLegacy()`：React Hook，基于 `useSyncExternalStore` 订阅旧代码的重绘版本号，使组件在旧代码每次绘制后重渲染。调用者：`App.tsx`、`views/DutyBoard.tsx`、`views/Layers.tsx` 等。
- `renderLegacy()`：画旧页面 `renderAll` 中新页面尚未接管的旧视图：若数据不允许模拟而模拟开着则 `fn.setSimulation(false)`；模拟开着、没有计时器且线路或星期变了则 `fn.scheduleSimulation()`；然后依次 `watchNetwork`、`drawNetwork`、`draw`、`renderTable`、`renderSimStatus`。调用者：`views/Layers.tsx:39`、`views/Layers.tsx:46`、本文件 `pushDraft`/`pushData`/`onRender`。
- `onLegacyDraft(listener)`：注册旧代码改草稿时的回调（只保留一个）。调用者：`state/store.tsx:155`（`normalizeDraft` 后作为一步编辑）。
- `pushDraft(given)`：把 store 的草稿交给旧代码。先 `normalizeDraft` 补全各部分；JSON 与上次相同则忽略；否则写入 `legacy.v.draft`（深拷贝），清 `v.coverage`、`v.terminal` 缓存；有数据时 `buildServices()`，若选中的车次已不存在就清空 `state.selected`，再 `renderLegacy()`；最后 `notify()`。调用者：`state/store.tsx:154`。
- `pushData(data)`：把游戏数据深拷贝后交给 `fn.setData`（旧代码会往上加线路、行、车次）。首次调用时还按 `dayStartMillis` 和 `v.DAY` 算出运营日"今天"，设置 `state.weekday = mod(today + FIRST_DAY_WEEKDAY, 7)`，`buildServices()`，把 `state.viewStart` 设为最早车次前 30 分钟（不早于 -30 分钟；没有车次则 2:00），`renderLegacy()`。之后 `checkDraft()`、`notify()`。调用者：`state/store.tsx:77`。
