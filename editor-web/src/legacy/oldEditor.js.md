# legacy/oldEditor.js

剩余的旧编辑器代码（约 3100 行），JavaScript 源码（`// @ts-nocheck`，类型声明在 `oldEditor.d.ts`）。到 2026-11-13 为止它由 `scripts/gen-legacy.mjs` 从旧页面 `assets/mtr/editor/editor.js` 生成（`engine.generated.js`）；退役旧代码第 9 步删除旧页面时转成源码，同时删掉了：旧运行图的绘制（`drawBefore` 及画布上的指针处理）、旧汇总表（`renderLineTable`、`timeCell` 等及 `#view-table` 上的处理）、旧调度台面板（`renderConsole` 的 HTML）、旧对话框（`openDialog`、`#dialog`）、旧页面顶栏和标签的按钮处理，以及此后不再有人引用的全部声明（一次性脚本按引用关系剪掉，共 108 个）。旧代码最初的分节和注释保留。

它在新页面里以 `createLegacy(env)` 运行（[engine.ts](engine.ts.md)）：一个闭包，里面是旧页面的状态（`data`、`draft`、`state`、`net`、`sim`、`consoleState` …），返回 `fn`（函数）和 `v`（变量的 getter/setter，`let` 的可写）。`env` 第一行解构：旧代码里由新模块实现的名字（`core/*` 经 `retired.ts`、`dialogs.ts`）和新页面的面板入口。

**仍由它负责的**：
- 全网实际图（"轨道编辑"，`#view-network`）：`drawNetwork` 及其点选、右键菜单、寻路边界、归属画笔的点轨道、出入库选口和新建线路选站的点选、调度台列车在实际图上的位置（`drawConsole`、`consoleRail`）。用户决定以后整体重新构想（`MTR-代码说明与流程.md`"全网实际图：保留说明"），之前只修真正的错误。
- `#tree`、`#inspector` 上的事件处理（新页面 `views/Navigator.tsx`、`views/Inspector.tsx` 用 portal 画进这两个元素，行保留旧类名和 `data-*`）。
- 新视图仍调用的数据和规则帮手：`setData`（加工游戏数据）、车厂与运用的说明文字、限速、`cellMenu`、`selectService`、`centerOn`、`zoom` 等。
- 只剩计算、不再绘制的：`draw()`（算 `layout` 并 `env.render("draw")`）、`renderTable()`（算 `deadheadIndex`）、`renderConsole()`（`env.render`）、`renderSimStatus()`（写 `#sim-status`）。

**约定**：查找元素用 `$`（`document.getElementById`），元素来自 [markup.ts](markup.ts.md)；`tr`、`I18N`（`i18n.js`）、`TerminalPairs`（`terminal.js`）、`window.TimetableBatch`（`batch.js`）是页面全局。`test/style.test.ts` 检查这里没有写死的颜色和字体。`test/legacy.test.ts` 是它在新页面中的冒烟测试。

下面按原来的分节列出剩下的顶层声明：名字（当前行号，种类）：作用；新页面的调用者照旧标出（经 `legacy.fn` / `legacy.v`）。说明取自删除前的 `editor.js.md`，行号已改为本文件的；原文件内的调用行号已去掉。

## 文件开头：常量、工具与状态

- `DAY`（L20，常量）：一天的毫秒数。
- `POLL_MILLIS`、`POLL_HIDDEN_MILLIS`（L22，常量）：轮询游戏数据的间隔：页面可见时 1500 ms，隐藏时 `POLL_HIDDEN_MILLIS` 5000 ms。
- `LABEL_W`、`AXIS_H`、`MIN_ROW_GAP`、`PAD_BOTTOM`（L24，常量）：运行图几何常量：左侧站名栏宽 `LABEL_W` 120、顶部时间轴高 `AXIS_H` 28、最小行距 `MIN_ROW_GAP` 22、底部留白 `PAD_BOTTOM` 30（像素，留给接续到其他路线的车次标签）。
- `DEPOT_ROW_ROOM`（L25，常量）：运行图上下为车厂行留出的高度（像素）。
- `ZOOMS`（L27，常量）：运行图缩放档位（每分钟像素），从一眼看全天到约每秒一像素。
- `MAX_SERVICES`、`MAX_TABLES`（L29，常量）：与游戏 `RouteTimetable` 相同的上限：一张表最多 `MAX_SERVICES` 4096 班，一条路线最多 `MAX_TABLES` 16 张表。
- `MAX_LAYOVER_MILLIS`（L31，常量）：环线列车等下一圈的最长时间（30 分钟，超过则回库），同游戏 `RouteTimetable.MAX_LAYOVER_MILLIS`。
- `FIRST_DAY_WEEKDAY`（L33，常量）：纪元第一天（1970-01-01）是星期四，即星期序号 3（0=周一）。
- `$`（L35，常量）：`document.getElementById` 的简写。
- `css`（L36，常量）：读取根元素上的 CSS 变量值（如 `--panel`）。 新页面调用：`editor-web/src/views/RunningDiagram.tsx:289`（经 `legacy.fn`）。
- `escapeHtml`（L37，常量）：HTML 转义 `& < > " '`。
- `colorOf`（L38，常量）：RGB 整数 → `#rrggbb`。
- `mod`（L39，常量）：非负取模。 新页面调用：`editor-web/src/legacy/engine.ts:233`（经 `legacy.fn`）。
- `shortName`（L41，常量）：名字写成"中文|English"时取第一部分；空时"（未命名）"。
- `key`（L45，常量）：编辑器密钥，取自 `env.MTREditor.key`（新页面 `api/client.ts` 的 `key`；旧页面时取自 `shell.js`）。
- `inTimetable`（L46，常量）：当前是否显示时刻表工作区（媒体库显示时这里不绘制、少问游戏、Esc 不清选择）。
- `data`、`revision`、`lastLoaded`、`connection`（L48，变量）：页面状态变量：`data` 游戏数据（`setData` 加工后）、`revision` 已读的数据版本号、`lastLoaded` 最近读取时间、`connection` 连接状态（`connecting`/`ok`/`key`/`world`/`offline`）。
- `coverage`（L53，变量）：当天哪些车次没有车跑的缓存（`coverageNow` 使用；数据、草稿或星期变化时清空）。
- `terminal`（L55，变量）：当天终点接续配对的缓存（`terminalNow` 使用）。
- `draft`、`draftLoaded`、`draftTimer`、`applyResult`、`applying`（L56，变量）：草稿相关变量：`draft` 草稿对象（`routes`、`created`、`duties`、`boundaries`、`depots`、`depotRules`、`platformStations`、`platformSpeeds`、`platformStops`、`networkSettings`、`contingencies`）、`draftLoaded`、`draftTimer`（保存防抖计时器）、`applyResult`（最近应用结果）、`applying`（正在应用）。
- `state`（L57，常量）：界面状态对象：`view`（diagram/table/network）、`line`、`route`、`selected`（`路线@车次`）、`selectedRoute`（双击选中整条路线）、`pendingExit`（直通时先选的接出站）、`slot`（正在编辑运用的库位 `{depot, siding, index}`）、`weekday`、`split`、`treeOpen`、`openNodes`、`pxPerMin`、`viewStart`、`scrollY`、`lineWay`（线路组表的方向）；`range` 等在运行时加入。

## Data

- `saveDraft`（L83，函数）：清空 `coverage`、`terminal` 缓存，400 ms 防抖后 PUT `api/draft`（失败忽略），更新草稿状态显示，并安排重新模拟。新页面中草稿由 store 保存（`env.sendJson` 对 `api/draft` 直接返回）。 新页面调用：`editor-web/src/legacy/dialogs.ts:105`、`editor-web/src/legacy/dialogs.ts:151` 等 16 处（经 `legacy.fn`）。
- `dutyNotice`（L93，变量）：显示在顶部提示条里的、关于最近一次运用操作的消息。
- `pendingOpen`（L95，变量）：游戏建好新路线后要打开显示的路线 ID。
- `EDITOR_PARTS`（L97，常量）：编辑器改动会发送的路线部分：时刻表、站台、路线设置。
- `draftOutOfDate`（L99，常量）：草稿建立后游戏是否改动了草稿会发送的部分（据此判冲突）。
- `isManaged`（L100，常量）：路线是否由高级编辑器管理（草稿优先）。 新页面调用：`editor-web/src/views/SummaryTable.tsx:239`（经 `legacy.fn`）。
- `isConnecting`（L101，常量）：路线是否为联络线（草稿优先）。 新页面调用：`editor-web/src/views/Navigator.tsx:132`（经 `legacy.fn`）。
- `speedLimitOf`（L103，常量）：路线限速 km/h（0 为不限，草稿优先）。 新页面调用：`editor-web/src/views/LinePanels.tsx:57`、`editor-web/src/views/SummaryTable.tsx:272`（经 `legacy.fn`）。
- `isDeleted`（L105，常量）：种别是否在草稿里标记为删除。 新页面调用：`editor-web/src/views/Layers.tsx:337`、`editor-web/src/views/Layers.tsx:338` 等 4 处（经 `legacy.fn`）。
- `boundaryErrors`（L108，常量）：游戏没接受的寻路边界改动的原因，按轨道键保存，直到再次修改或应用。
- `railKey`（L109，常量）：两个节点键组成的轨道键（较小的在前，按 BigInt 比较）。
- `releasedOf`（L111，常量）：联络线放行给其他路线的轨道（草稿优先）。
- `toggleReleased`（L114，函数）：在草稿中放行或收回联络线的一段轨道。
- `railMenu`（L128，函数）：配线图中轨道的右键菜单：寻路边界（只缩小寻路范围）或联络线轨道放行。 新页面调用：`editor-web/src/views/TrackDiagram.tsx:133`（经 `legacy.fn`）。
- `shortfallText`（L146，函数）：缺车说明（按服务器按线路排运用的结果）。 新页面调用：`editor-web/src/views/DutyBoard.tsx:74`、`editor-web/src/views/Inspector.tsx:147` 等 3 处（经 `legacy.fn`）。
- `missingTrains`（L168，函数）：缺几列车（各车型分开计，未知为 -1）。 新页面调用：`editor-web/src/views/Navigator.tsx:282`（经 `legacy.fn`）。
- `replanText`（L173，函数）：解释运用是按原设置排、为何未自动重排（换端时间、实测余量、轨道变化）。 新页面调用：`editor-web/src/legacy/dialogs.ts:117`、`editor-web/src/views/DutyBoard.tsx:74` 等 3 处（经 `legacy.fn`）。
- `shortfallsOf`（L183，函数）：跑某路线的各车厂的缺车说明（每个规划单元一次）。 新页面调用：`editor-web/src/views/SummaryTable.tsx:269`（经 `legacy.fn`）。
- `setSpeedLimit`（L192，函数）：在草稿中设置路线限速（0 为不限）。 新页面调用：`editor-web/src/views/LinePanels.tsx:68`（经 `legacy.fn`）。
- `setConnecting`（L201，函数）：在草稿中设置/取消联络线（其他线路寻路避开其轨道，但车次可载客直通）。
- `setManaged`（L211，函数）：在草稿中设置是否由高级编辑器管理（与游戏相同则删字段），保存并重绘。 新页面调用：`editor-web/src/views/SummaryTable.tsx:271`（经 `legacy.fn`）。
- `draftOperations`（L224，函数）：把草稿转成发给游戏的操作列表及每条操作对应的对象（用于放回错误）。新页面的同名实现是 `core/draft.ts` 的 `draftOperations`。 新页面调用：`editor-web/src/legacy/retired.ts:277`（经 `legacy.fn`）。
- `renderDraftState`（L317，函数）：更新顶栏草稿标签（项数/正在应用）、媒体工作区标签上的草稿点、应用按钮状态等。
- `poll`（L319，异步函数）：轮询：GET `api/revision`，版本变化时 GET `api/data` 并 `setData`；记连接状态；按页面是否隐藏安排下一次（`once` 时只读一次）。 新页面调用：`editor-web/src/legacy/retired.ts:176`（经 `legacy.fn`）。
- `setData`（L339，函数）：接收游戏数据：设置语言（`I18N.setLanguage`），建 `routeById`、线路组、各线路组车站行（`buildStations`）、拓扑（`buildTopology`），重建车次，清缓存，处理待打开的新路线，然后重绘。 新页面调用：`editor-web/src/legacy/engine.ts:227`（经 `legacy.fn`）。

## Editing

- `servedStops`（L381，常量）：车次实际载客的首末站（出/回库指令可缩小范围）。 新页面调用：`editor-web/src/views/SummaryTable.tsx:80`、`editor-web/src/views/SummaryTable.tsx:88`（经 `legacy.fn`）。
- `PATTERN_HINT`（L383，常量）：添加班次输入格式的提示文字。
- `tableMessage`（L385，变量）：汇总时刻表下方显示的提示消息。

## Menus and dialogs

- `menu`（L388，变量）：当前打开的菜单元素。
- `subMenu`（L389，变量）：当前展开的二级菜单元素。
- `browserMenuFields`（L394，常量）：右键行为统一（2026-11-09）：页面有菜单处由该处处理；文本框和选中文字保留浏览器菜单（复制粘贴）；其他地方不弹任何菜单。此常量是保留浏览器菜单的输入框选择器，文档级右键监听使用。
- `setWeekday`（L407，函数）：切换显示的星期：同步下拉框，重建车次，选中车次不存在则清空，重绘。 新页面调用：`editor-web/src/App.tsx:126`、`editor-web/src/views/LinePanels.tsx:378` 等 3 处（经 `legacy.fn`）。

## What the draft changes

- `clock`（L417，常量）：运营日时刻 → 当地钟点 "HH:MM"（`seconds` 时有秒则加秒）。
- `routeTitle`（L423，常量）：路线短名 + " → 终点站"。

## Tree

- `renderTree`（L429，函数）：画左侧树（按存档分层：物理线路、区段归属/逻辑线路、运营线路及其停站、时刻表和运行图、车厂及车型/运用/存车线），每个节点为带 `data-*` 的 `.node`。新页面改由 `views/Navigator.tsx` 画进同一 `#tree`，本函数经 `RENDERS` 改为通知 React。
- `onTreeClick`（L431，函数）：树节点点击：按节点的 `data-*`（`slot`、`layer`、`connecting`、`logical`、`newLogical`、`generateLogical`、`line`、`route`、`view`、`depot`、`pool` 等）选中运用位、切换路网图层、打开逻辑线路对话框、展开/折叠、打开线路或路线的视图等。新页面中仍由它处理 `#tree` 上的点击。

## Views

- `canSplit`（L511，常量）：窗口足够大（宽 ≥1100、高 ≥820）时允许运行图与汇总时刻表上下同时显示。 新页面调用：`editor-web/src/views/Layers.tsx:291`、`editor-web/src/views/Layers.tsx:311`（经 `legacy.fn`）。
- `showsDiagram`（L512，常量）：当前是否显示运行图（时刻工作区内，运行图视图或分屏）。 新页面调用：`editor-web/src/views/Layers.tsx:242`（经 `legacy.fn`）。
- `showsTable`（L513，常量）：当前是否显示汇总时刻表（表视图或分屏且不在路网视图）。 新页面调用：`editor-web/src/views/Layers.tsx:242`、`editor-web/src/views/SummaryTable.tsx:34`（经 `legacy.fn`）。
- `openView`（L515，函数）：打开某线路组/路线的某个视图（`diagram`/`table`/`network`）：换线路时清选中，竖屏时收起树，`renderAll`。 新页面调用：`editor-web/src/App.tsx:40`、`editor-web/src/views/Inspector.tsx:86`（经 `legacy.fn`）。

## Whole network track view

- `NET_CELL`、`NET_HIT_PX`、`NET_STATION_SCALE`、`NET_POLL_MS`（L531，常量）：全网配线图常量：命中网格 `NET_CELL` 64 格、命中距离 `NET_HIT_PX` 6 像素、车站名显示比例 `NET_STATION_SCALE`、轮询 `NET_POLL_MS` 3000 ms。
- `NET_EXACT_SCALE`、`NET_SIMPLIFY_PX`、`NET_ALIGNED`（L534，常量）：缩放低于 `NET_EXACT_SCALE`（像素/格）时画成示意图：道岔之间的轨道简化（容差 `NET_SIMPLIFY_PX`）并把直段对齐横/竖/45°（`NET_ALIGNED` 为对齐阈值）。
- `netExact`（L539，常量）：地图是否按实际轨道画：放大到足够程度，或正在为车厂选出入口（其路线按实际轨道画）。
- `net`（L540，常量）：全网配线图状态：`data`（`api/network` 的回答）、`version`、`mode`、`scale`、`cx`/`cz`（中心世界坐标）、`grid`（命中索引）、`hover`、`error`、计时器、`layer`（physical/owners）、`ownerOf`、`sectionRails`、`chains`、`railChain`、`byPair`、`focusLogical` 等。
- `netCanvas`、`netWrap`、`netCtx`（L543，常量）：全网配线图的 `<canvas>`、容器和 2D 上下文。
- `netGeometryCache`、`netDetailCache`、`netRouteCache`（L546，常量）：几何、简化细节和路线路径的缓存（键为不可变的点数组，换数据时自然失效）。
- `netFramePending`（L547，变量）：是否已安排下一帧绘制。
- `netGeometry`（L548，函数）：一段轨道点数组的包围盒、起点，以及（`withPath` 时）Path2D，带缓存。
- `netDrawingPoints`（L567，函数）：一段轨道在当前缩放级别下要画的点（实际画法或按级别简化，缓存最多 6 级）。
- `netStyles`（L579，函数）：每帧一次的样式快照（颜色、线宽等）与区段解析结果，避免每段轨道扫描整个区段。
- `setNetLayer`（L620，函数）：在全网图上切换物理线路/区段归属图层，并记住选择。 新页面调用：`editor-web/src/views/Layers.tsx:33`（经 `legacy.fn`）。
- `loadNetwork`（L627，异步函数）：POST `api/network`（`mode`、`fresh`、`version`）；有新数据时保存、建索引（`indexNetwork`），首次或换运输方式时 `fitNetwork`；然后重画、更新工具栏。 新页面调用：`editor-web/src/views/NetworkSchematic.tsx:70`（经 `legacy.fn`）。
- `watchNetwork`（L647，函数）：路网视图打开时轮询地图数据（服务器在请求后一段时间才给出）。 新页面调用：`editor-web/src/legacy/engine.ts:169`（经 `legacy.fn`）。
- `indexNetwork`（L659，函数）：为每段轨道整理点序列、在网格中登记包围盒（找鼠标下的轨道）、按节点对索引轨道。
- `netChains`（L720，函数）：示意图用的轨道链：经过度为 2 的节点首尾相连、种类和隔离状态相同的轨道合成一条，使站台、存车线、隔离轨道各自成链。
- `netLevel`（L789，常量）：示意图计算的缩放级别（以半个 2 的幂为一级）。
- `chainShape`（L792，函数）：一条链在当前缩放下的画法：放大时为实际形状，否则简化并对齐 0°/45°/90°。
- `simplifyPoints`（L800，函数）：对 `[x, z, x, z, …]` 做 Douglas–Peucker 简化。
- `octilinear`（L830，函数）：把相邻两点间的每段对齐到横、竖或 45°（接近者保留，否则直—斜—直）。
- `cutSteps`（L862，函数）：把相邻两段形成的直角台阶斜切掉。
- `fitNetwork`（L900，函数）：把视野适配到全部节点（设 `cx`、`cz`、`scale`）。
- `netX`（L911，常量）：世界 x → 画布像素 x。
- `netZ`（L912，常量）：世界 z → 画布像素 y。
- `netWorldX`（L913，常量）：画布像素 x → 世界 x。
- `netWorldZ`（L914，常量）：画布像素 y → 世界 z。

## Section owners (web layers step 2)

- `netRailKey`（L917，常量）：全网图中轨道序号 → 轨道键。
- `sectionOf`（L922，常量）：轨道所在区段的所有轨道（页面所知范围；无线路归属的区段不发送，则为它自己）。
- `ownerText`（L928，函数）：归属者的可读名称：线路名或联络线名。 新页面调用：`editor-web/src/legacy/dialogs.ts:208`（经 `legacy.fn`）。
- `ownerColor`（L937，函数）：归属者的颜色：联络线用 `--connecting`，线路用其颜色，否则 `--muted`。 新页面调用：`editor-web/src/views/NetworkSchematic.tsx:179`（经 `legacy.fn`）。

## Logical lines (2026-11-09)

- `logicalMenu`（L947，函数）：有逻辑线路时归属图层上轨道的右键菜单：区段的逻辑线路、经过的联络线。
- `ownersMenu`（L963，函数）：归属图层上轨道的右键菜单：区段归属、经过的联络线。
- `netStatus`（L985，函数）：轨道隔离状态（叠加草稿边界）：0 普通、1 联络线独占、2 已放行、3 寻路边界。
- `drawNetwork`（L994，函数）：在下一动画帧画全网配线图（只在路网视图且在时刻工作区时）。 新页面调用：`editor-web/src/legacy/dialogs.ts:105`、`editor-web/src/legacy/engine.ts:170` 等 17 处（经 `legacy.fn`）。
- `drawNetworkFrame`（L1000，函数）：画一帧全网配线图：网格、轨道（按图层、隔离、归属/逻辑线路着色，示意或实际形状）、车站、所选线路组的路线、点选状态（`drawPicking`/`drawWayPick`）、调度台（`drawConsole`）等。
- `netRailAt`（L1177，函数）：鼠标附近几像素内最近的轨道序号（缩小时为鼠标下链中最近的一段），无则 -1。 新页面调用：`editor-web/src/views/OwnershipPanel.tsx:81`（经 `legacy.fn`）。
- `netSegmentDistance`（L1228，函数）：点到线段距离（世界坐标）。
- `netRailText`（L1234，函数）：轨道的悬停说明：种类（轨道/站台/存车线/留置位）、隔离状态、链长、归属或逻辑线路等。
- `consoleState`（L1257，常量）：调度台状态（D6）：`open`、`data`（`api/console`）、`timer`、`system`（所选路网）、`selected`（所选列车）。
- `consoleRail`（L1260，函数）：调度台给出的两节点 → 全网图轨道序号，无则 -1。 新页面调用：`editor-web/src/views/Layers.tsx:155`（经 `legacy.fn`）。
- `drawConsole`（L1269，函数）：在地图上画调度台信息：被占用闭塞分区红色、本路网列车所在分区琥珀色、车头处写车名。
- `renderConsole`（L1300，函数）：画调度台面板（路网下拉、每列车一张卡片及命令按钮）。新页面为 `views/DispatchConsole.tsx`。 新页面调用：`editor-web/src/legacy/retired.ts:304`、`editor-web/src/legacy/retired.ts:343` 等 5 处（经 `legacy.fn`）。
- `renderNetworkTools`（L1306，函数）：更新路网视图工具栏：运输方式下拉、轨道/车站数、图层下拉等。 新页面调用：`editor-web/src/legacy/dialogs.ts:105`、`editor-web/src/legacy/retired.ts:190`（经 `legacy.fn`）。

## A new line chosen on the whole network map

- `picking`（L1410，变量）：在全网图上点选新线路的状态：`mode`、`stops`（站台、车站、是否通过、途经节点）、`legs`（服务器 `api/route-preview` 找到的各段路）、`activeLeg`、`color`、`requestId`、`error`。
- `startPicking`（L1412，函数）：开始在全网图上点选新线路：建 `picking`，切到路网视图，提示操作。 新页面调用：`editor-web/src/views/Layers.tsx:298`、`editor-web/src/views/StopsEditor.tsx:418`（经 `legacy.fn`）。
- `stopPicking`（L1419，函数）：取消点选。 新页面调用：`editor-web/src/views/Layers.tsx:109`、`editor-web/src/views/PickPanels.tsx:62`（经 `legacy.fn`）。
- `stationPlatforms`（L1425，常量）：某车站（地图当前运输方式）的站台。
- `addPickedStop`（L1427，函数）：把选中的站台加为下一站并刷新预览。
- `refreshPreview`（L1435，异步函数）：POST `api/route-preview` 请求经过所选各站的路，回来后绘制。 新页面调用：`editor-web/src/views/PickPanels.tsx:25`（经 `legacy.fn`）。
- `netStationAt`（L1459，函数）：地图上某点的车站（站名/圆点，或其站台轨道），无则 null。
- `netNodeAt`（L1486，函数）：地图上某点的轨道节点 ID，无则 null。
- `pickAt`（L1499，函数）：点选时的地图点击：点车站加站，点节点作为所选段的途经点（再点去掉）。
- `drawPicking`（L1535，函数）：点选时在地图上画：经过所选车站的已有路线突出显示，再画新线路各段和各站。
- `pickingRouteIds`（L1612，常量）：在已选车站停靠的路线 ID。
- `onWayPickPanelClick`（L1618，函数）：选出入口面板内的点击：用这个/取消/选目标站台/清空等。

## A depot's rules for going out and back (运用补完 step 2)

- `depotLines`（L1680，函数）：车厂负责的线路（键与显示名）。 新页面调用：`editor-web/src/views/DepotPanel.tsx:235`（经 `legacy.fn`）。
- `rulesEditing`（L1690，变量）：正在编辑的规则（关闭对话框去地图上选择时保留）。
- `wayPick`（L1695，变量）：在全网图上为规则选车站或画回送路径的状态：车厂、存车线、目标、服务器的回答等。
- `rulePlatform`（L1701，函数）：规则的出库终点/回库起点站台：车厂所跑该线路某路线的首站（或末站）。
- `ruleTargets`（L1714，函数）：规则列车在当天运行图上出库到达/回库出发的站台（按站台给出最早/最晚时刻和班数）。
- `startWayPick`（L1750，函数）：开始在地图上为规则或回送路径选择：取存车线（没有则提示并回到对话框），建 `wayPick`，切到路网视图，请求服务器。 新页面调用：`editor-web/src/views/DepotPanel.tsx:248`（经 `legacy.fn`）。
- `refreshWayPick`（L1776，异步函数）：POST 请求存车线可达的车站与所选路线，回来后绘制。 新页面调用：`editor-web/src/views/PickPanels.tsx:89`、`editor-web/src/views/PickPanels.tsx:95`（经 `legacy.fn`）。
- `wayPickAt`（L1792，函数）：选择时的地图点击：可达车站、路径节点或另一端。
- `toggleWayRail`（L1833，函数）：规则路线经过某段轨道（2026-11-09）：首次点按列车方向加入，再点反向，第三次去掉。
- `drawWayPick`（L1862，函数）：选择时在地图上画：存车线可达的车站（圈出）和所选路线。
- `finishWayPick`（L1902，函数）：把选择结果放进正在编辑的规则（`keep` 为假时放弃），回到规则对话框。 新页面调用：`editor-web/src/views/Layers.tsx:109`、`editor-web/src/views/PickPanels.tsx:100` 等 3 处（经 `legacy.fn`）。

## Running diagram

- `wrap`（L1945，常量）：运行图的 `<canvas>`、容器 `#canvas-wrap` 和 2D 上下文。新页面中容器仍借入布局，画布隐藏，新画布叠在其上。
- `layout`、`drawn`、`handles`（L1946，变量）：运行图布局（`computeLayout` 结果）、已画车次 `drawn`、停站拖柄 `handles`。

## Other lines on this line's track (web layers step 3)

- `showAccess`（L1954，变量）：是否在运行图上画其他线路走本线轨道的车次（只读）。
- `access`（L1956，常量）：接入车次计算缓存：`key`、`legs`、`askedMillis`。
- `ensureNetworkFor`（L1959，函数）：需要时（最多每 5 秒一次）为某运输方式读取全网图数据（其中有路线路径）。 新页面调用：`editor-web/src/legacy/dialogs.ts:180`（经 `legacy.fn`）。
- `accessLegs`（L1972，函数）：本线路组运行图上要画的其他线路车次段（按数据版本缓存）。 新页面调用：`editor-web/src/views/RunningDiagram.tsx:387`（经 `legacy.fn`）。
- `findAccessLegs`（L1985，函数）：找出其他路线在本线两相邻车站之间、走本线轨道的区段。
- `computeLayout`（L2023，函数）：运行图布局：各车站行的 y（按行距权重铺满高度，上下留车厂行空间）、内容高度。新页面为 `core/diagram.ts` 的 `layoutRows`。
- `xOf`（L2036，常量）：运营日时刻 → 运行图 x。
- `millisAt`（L2037，常量）：运行图 x → 运营日时刻。
- `clampView`（L2039，函数）：把时间轴视野和纵向滚动限制在合理范围。
- `draw`（L2051，函数）：画运行图（时间轴、当前时刻、车站行、接入车次、车次、指令、直通、环线、拖柄、所选运用、终点接续、车厂回送、模拟、站名）。新页面中经 `NOTIFY` 包装：运行后调用 `env.render("draw")`，由 `views/RunningDiagram.tsx` 重画。 新页面调用：`editor-web/src/legacy/engine.ts:171`、`editor-web/src/legacy/retired.ts:286` 等 4 处（经 `legacy.fn`）。
- `stopsHtml`（L2059，函数）：车次各站到发时刻的 HTML（提示框用）。
- `renderInspector`（L2068，函数）：画检查器：点选/选出入口面板、运用详情、选中车次、模拟问题、环线运行、线路与股道问题、操作提示。新页面改由 `views/Inspector.tsx` 画，本函数经 `RENDERS` 改为通知 React。 新页面调用：`editor-web/src/legacy/retired.ts:287`、`editor-web/src/legacy/retired.ts:318`（经 `legacy.fn`）。
- `tooltip`（L2070，常量）：提示框元素。
- `showTooltip`（L2071，函数）：显示车次提示（路线、时刻、各站、模拟结论；接入车次标明只读），或隐藏。 新页面调用：`editor-web/src/views/RunningDiagram.tsx:168`、`editor-web/src/views/RunningDiagram.tsx:176` 等 3 处（经 `legacy.fn`）。
- `showDragTip`（L2086，函数）：拖动时在指针旁显示移动量。 新页面调用：`editor-web/src/views/RunningDiagram.tsx:144`、`editor-web/src/views/RunningDiagram.tsx:156` 等 4 处（经 `legacy.fn`）。
- `centerOn`（L2098，函数）：把车次滚动到视野中（横向居中，必要时纵向）。 新页面调用：`editor-web/src/views/Inspector.tsx:202`、`editor-web/src/views/SummaryTable.tsx:155`（经 `legacy.fn`）。
- `zoom`（L2116，函数）：按档位缩放时间轴（以某 x 或中间为锚点），重画。 新页面调用：`editor-web/src/views/Layers.tsx:332`、`editor-web/src/views/Layers.tsx:333`（经 `legacy.fn`）。

## Simulated day

- `SIM_LATE_MILLIS`、`SIM_MAX_LISTED`（L2129，常量）：模拟：晚点阈值 `SIM_LATE_MILLIS` 30 s，问题最多列出 `SIM_MAX_LISTED` 300 条。
- `sim`（L2131，常量）：模拟状态（`on`、`request`、`state`、`result`、`trips`、`notRun`、`listed`、`checkDepot`（正在验证运用的车厂）等）。
- `renderSimStatus`（L2133，函数）：模拟状态文字（`#sim-status`）。 新页面调用：`editor-web/src/legacy/engine.ts:173`、`editor-web/src/legacy/retired.ts:278` 等 5 处（经 `legacy.fn`）。
- `formatDuration`（L2145，常量）：毫秒 → "X 分 Y 秒" 或 "Y 秒"。

## Summary timetable

- `showDeadheads`（L2152，变量）：汇总时刻表是否显示运用的回送（运用补完第 3 步）。
- `deadheadsByService`（L2158，函数）：当天每个车次接入/离开的出库/回库回送 `{out, back, depot}`。
- `deadheadIndex`（L2176，变量）：`deadheadsByService` 的结果（`renderTable` 时更新）。新页面 `SummaryTable` 读取它。
- `renderTable`（L2182，函数）：画汇总时刻表（线路组或单路线；工具行、表按钮、最低性能、添加班次、各站行）。新页面为 `views/SummaryTable.tsx`（旧画法开关时仍显示本函数的结果）。 新页面调用：`editor-web/src/legacy/engine.ts:172`、`editor-web/src/views/RunningDiagram.tsx:232` 等 3 处（经 `legacy.fn`）。
- `Batch`（L2187，常量）：`window.TimetableBatch`（`batch.js`）。
- `tableRun`（L2188，常量）：与车次同表的所有车次。
- `durationText`（L2191，函数）：时长 → "1 分 30 秒"、"45 秒"、"5 分钟"。 新页面调用：`editor-web/src/legacy/dialogs.ts:265`（经 `legacy.fn`）。
- `addBeside`（L2197，函数）：在某车次旁新建一班（时刻由 `TimetableBatch.besideTime` 算出）。
- `nextServiceItems`（L2214，函数）：设定"接下一班"的菜单项：到达后从同站出发的车次（同站台优先，最近 8 个），已被别的车次接的注明。
- `stationNameOf`（L2236，函数）：车站名，`"0"` 为自动选择说明。 新页面调用：`editor-web/src/legacy/retired.ts:244`、`editor-web/src/views/DepotPanel.tsx:243` 等 3 处（经 `legacy.fn`）。
- `stopPositionText`（L2243，函数）：站台停车位置文字（第 4 轮）。 新页面调用：`editor-web/src/legacy/retired.ts:230`（经 `legacy.fn`）。
- `cellMenu`（L2247，函数）：汇总时刻表单元格/表头右键菜单：接下一班、调整/删除/停运/恢复选中班次、在左/右新建一列、删除这一班、清除例外、同步时刻改动到之后或选中的班次、清除直通、批量新建直通、把时刻改成整表的/恢复按表、出车车厂/取消出车/取消回送/出库改回首站接入、站台归属车站/过站限速/停车位置等。 新页面调用：`editor-web/src/views/SummaryTable.tsx:164`（经 `legacy.fn`）。

## Loops

- `trackTitle`（L2350，函数）：停站所用站台及同站同轨道上其他股道的说明。 新页面调用：`editor-web/src/views/SummaryTable.tsx:308`（经 `legacy.fn`）。
- `isBoundary`（L2357，常量）：车次能否在该站（首末站以外）接出或接入（时刻边界）。 新页面调用：`editor-web/src/views/LinePanels.tsx:125`（经 `legacy.fn`）。
- `stopName`（L2358，常量）：第 `stop` 站的短名。

## Duties (运用)

- `depotById`（L2365，常量）：按 ID 找车厂。 新页面调用：`editor-web/src/legacy/dialogs.ts:236`、`editor-web/src/legacy/retired.ts:275` 等 15 处（经 `legacy.fn`）。
- `startText`（L2367，常量）：出车方式文字（如"出车：存车线名"）。 新页面调用：`editor-web/src/views/DutyBoard.tsx:179`、`editor-web/src/views/Navigator.tsx:307`（经 `legacy.fn`）。
- `platformById`（L2374，常量）：按 ID 取拓扑中的站台，无则 null。
- `platformText`（L2375，常量）：站台的"车站名 站台名"。 新页面调用：`editor-web/src/views/RunningDiagram.tsx:650`、`editor-web/src/views/StopsEditor.tsx:265`（经 `legacy.fn`）。
- `terminalText`（L2380，函数）：列车在路线终点之后如何继续的一句话。 新页面调用：`editor-web/src/views/DutyDetail.tsx:113`、`editor-web/src/views/SummaryTable.tsx:83`（经 `legacy.fn`）。
- `showNoTrain`（L2391，变量）：是否把没有车跑的车次标红（有车的变淡）。
- `showDepots`（L2394，变量）：运行图是否显示车厂行和出入库回送。
- `selectSlot`（L2397，函数）：选中/取消选中一个运用位，并切到其第一班的线路组。 新页面调用：`editor-web/src/views/DutyBoard.tsx:172`（经 `legacy.fn`）。
- `lastSlotService`（L2411，变量）：选中运用时上次点击的车次（Shift 连选用）。
- `chainBetween`（L2417，函数）：当天一列车从一个车次连续跑到另一个车次的车次序列（每次取到站后最早从该站发车的），到不了为空。
- `addRangeToSlot`（L2435，函数）：把两个车次之间的一串车次加入所选运用（两端都已在其中则移出）。 新页面调用：`editor-web/src/views/RunningDiagram.tsx:218`（经 `legacy.fn`）。
- `toggleInSlot`（L2472，函数）：把车次加入或移出所选运用（别的车跑的会移过来；中途接入/离开按直通处理）。 新页面调用：`editor-web/src/views/DutyBoard.tsx:146`、`editor-web/src/views/RunningDiagram.tsx:219`（经 `legacy.fn`）。
- `plannedReturnText`（L2507，函数）：生成的运用预计回到哪条存车线（不是自己的时）。 新页面调用：`editor-web/src/views/DutyDetail.tsx:87`（经 `legacy.fn`）。
- `legTimes`（L2525，函数）：运用列车离开存车线和回到存车线的时刻（按出入库用时），未知为 "--:--"。 新页面调用：`editor-web/src/views/DepotExtras.tsx:198`（经 `legacy.fn`）。
- `EXCEPTION`（L2535，常量）：运用可单独设置的例外位：`OUT_LEAD` 1、`OUT_PATH` 2、`BACK_SIDING` 4、`BACK_PATH` 8、`WAITS` 16、`ENTRANCES` 32（同 `DepotDuties.DayDuty.EXCEPTION_*`）。
- `hhmm`（L2536，常量）：时刻 → "HHMM"。
- `deadheadTimes`（L2544，函数）：运用的出库/回库回送（运用补完第 3 步）：编号、离开与到达时刻、提前量、出库路线用时、是否例外等。 新页面调用：`editor-web/src/legacy/dialogs.ts:255`、`editor-web/src/views/DutyBoard.tsx:111` 等 5 处（经 `legacy.fn`）。
- `editDutyException`（L2570，函数）：把运用自己的回送或等候改为例外（写草稿运用）。 新页面调用：`editor-web/src/legacy/dialogs.ts:243`、`editor-web/src/views/RunningDiagram.tsx:191`（经 `legacy.fn`）。
- `isOrderIssue`（L2579，常量）：排运用问题是否为出/回库车厂指令问题。
- `orderIssueText`（L2584，函数）：把 `DEPLOY_DEPOT`/`RETURN_DEPOT` 问题写成一句话。 新页面调用：`editor-web/src/legacy/dialogs.ts:50`（经 `legacy.fn`）。
- `shortTurnaroundText`（L2593，函数）：把 `SHORT_TURNAROUND` 问题写成一句话（含站名）。 新页面调用：`editor-web/src/legacy/dialogs.ts:49`（经 `legacy.fn`）。
- `poolFleetText`（L2601，函数）：车辆池配属说明（按给定或存车线相加）。 新页面调用：`editor-web/src/views/Navigator.tsx:298`（经 `legacy.fn`）。
- `vehicleSummary`（L2607，函数）：车型设置一行摘要（加减速、最高速度、手动、种别）。 新页面调用：`editor-web/src/views/DepotPanel.tsx:130`（经 `legacy.fn`）。
- `plannedReturns`（L2620，常量）：按车厂记录最近生成的运用把列车送回哪条存车线（预测）。
- `setDutyMode`（L2622，函数）：切换车厂运用方式（`diagram` 按运用图 / `online` 自动排班），写草稿。 新页面调用：`editor-web/src/views/DutyBoard.tsx:70`（经 `legacy.fn`）。
- `changeSlots`（L2628，函数）：修改某存车线的运用位列表（写草稿），选中位越界时清除，重绘。 新页面调用：`editor-web/src/views/DutyBoard.tsx:141`、`editor-web/src/views/DutyBoard.tsx:142`（经 `legacy.fn`）。

## Topology: how trains run between platforms

- `topo`（L2645，变量）：服务器的站台图（PlatformGraph）：每个站台两个方向状态，边连到不掉头能到的下一批站台。新页面为 `core/topology.ts` 的 `Topology`。
- `buildTopology`（L2647，函数）：由 `data.topology` 建 `topo`（索引、按车站分组）。
- `TRACK_DIRECTIONS`（L2664，常量）：股道方向名：上行/下行/双向（L2，StationTracks）。
- `trackInfo`（L2665，函数）：股道的方向和种类（手设待避优先）。
- `trackLabel`（L2672，函数）："上行 3道 正线" 式名称。
- `lockOffText`（L2687，函数）：车厂列车为何不锁咽喉（不按运用跑，或与这样的车厂共用轨道）。 新页面调用：`editor-web/src/views/StationTracks.tsx:160`（经 `legacy.fn`）。
- `notConfiguredText`（L2699，常量）：车厂为何不发车（线路时刻表全关、设了运用却不按运用图，或什么都没设）。
- `lockMark`（L2706，常量）：树中车厂的提示标记 HTML（未配置、不锁咽喉等）。 新页面调用：`editor-web/src/views/Navigator.tsx:278`（经 `legacy.fn`）。
- `counterpartOf`（L2708，函数）：同线路组中反方向（首末站互换、站数相同）的路线，优先同种别。 新页面调用：`editor-web/src/views/StopsEditor.tsx:548`、`editor-web/src/views/StopsEditor.tsx:563` 等 3 处（经 `legacy.fn`）。
- `withSuffix`（L2717，常量）：在名字第一种语言部分后加词（`|` 后的其他语言保留）。 新页面调用：`editor-web/src/views/StopsEditor.tsx:564`（经 `legacy.fn`）。
- `typedName`（L2720，函数）：新种别名：把路线名中的种别词替换，或在后面加上。 新页面调用：`editor-web/src/views/StopsEditor.tsx:564`、`editor-web/src/views/StopsEditor.tsx:615`（经 `legacy.fn`）。
- `platformsOf`（L2726，常量）：各停站所用站台（含草稿中的换股）。 新页面调用：`editor-web/src/views/Inspector.tsx:130`、`editor-web/src/views/Navigator.tsx:97` 等 4 处（经 `legacy.fn`）。
- `keepType`（L2728，函数）：取消种别的删除标记。 新页面调用：`editor-web/src/views/Layers.tsx:337`（经 `legacy.fn`）。
- `removeCreated`（L2738，函数）：从草稿中删除一个新建项。
- `addInputs`（L2745，常量）：添加班次输入框的内容（按浏览者保存在 `localStorage`）。
- `selectService`（L2753，函数）：选中车次（必要时切到其路线），居中显示，重画。 新页面调用：`editor-web/src/views/RunningDiagram.tsx:221`、`editor-web/src/views/RunningDiagram.tsx:238`（经 `legacy.fn`）。

## Banner and status

- `renderBanner`（L2764，函数）：顶部提示条：连接问题、缺时刻、冲突、应用结果、运用消息等。新页面为 `shell/Banner.tsx`，本函数经 `RENDERS` 改为通知 React。 新页面调用：`editor-web/src/legacy/dialogs.ts:20`、`editor-web/src/legacy/dialogs.ts:31` 等 5 处（经 `legacy.fn`）。
- `renderSyncButton`（L2766，函数）：同步状态按钮（问题数、颜色）。
- `systemOfRoute`（L2768，常量）：路线所在路网。 新页面调用：`editor-web/src/views/DispatchConsole.tsx:66`（经 `legacy.fn`）。
- `systemOfLine`（L2769，常量）：线路组所在路网（取其路线中第一个有路网的）。
- `renderStatus`（L2774，函数）：底部状态栏（选中车次、线路数、本线班次、同步时间或未连接）。
- `renderAll`（L2776，函数）：全部重画：模拟按钮与重新模拟、树、视图、路网、运行图、检查器、汇总时刻表、提示条、状态栏、草稿状态。新页面中经 `RENDERS` 改为 `env.render("renderAll")`，由 `legacy/engine.ts` 的 `renderLegacy` 画旧视图并通知 React。 新页面调用：`editor-web/src/legacy/dialogs.ts:56`、`editor-web/src/legacy/dialogs.ts:105` 等 47 处（经 `legacy.fn`）。

## Tree folding

- `portrait`（L2779，常量）：竖屏且宽度 ≤1100 的媒体查询。

## Events

- `perfText`（L2925，函数）：表的最低性能显示值：加减速 m/s²、最高速度 km/h，未设为空。 新页面调用：`editor-web/src/views/SummaryTable.tsx:264`、`editor-web/src/views/SummaryTable.tsx:283` 等 4 处（经 `legacy.fn`）。
- `drag`、`lastClick`（L2931，变量）：运行图拖动状态 `drag`、上次点击 `lastClick`（判双击）。

## 没有说明的声明

以下声明在删除前的说明里没有单独条目（多为几行的小帮手或状态变量），作用见源码注释：`nowDay`（运营日序号，按现在的时间）。
