# editor.js

旧页面（`assets/mtr/editor/index.html`）高级编辑器的主脚本（约 9300 行）：每条线路的运行图、每条路线排成印刷式的汇总时刻表，两者都可编辑；改动作为草稿保存（`api/draft`），直到"应用"才发给游戏（`api/apply`）。游戏只向本机提供此页面，数据需要地址中的密钥。整个文件是一个 `(() => { … })()`，没有导出；依赖 `i18n.js`（`tr`、`trc`、`translatedList`、`I18N`）、`shell.js`（`MTREditor`）、`batch.js`（`TimetableBatch`）、`terminal.js`（`TerminalPairs`），这些都在 `index.html` 中先加载。

**与新页面的关系**：新页面 `editor-web` 通过 `editor-web/scripts/gen-legacy.mjs` 把本文件主体（到 `// ---------- Start ----------` 为止）生成为 `editor-web/src/legacy/engine.generated.js` 的 `createLegacy(env)`，再经 `editor-web/src/legacy/engine.ts` 以 `legacy.fn.<名字>`（函数）和 `legacy.v.<名字>`（变量）暴露给新页面。其中：
- 列在 `gen-legacy.mjs` 的 `FROM_ENV` 里的名字（下文标"新页面中由 `env` 提供"）在新页面中不使用本文件的实现，而改用 `editor-web/src/core/*`（经 `legacy/retired.ts`、`legacy/dialogs.ts`）或新页面的 React 面板；本文件的原实现仍在旧页面运行，旧页面已冻结。
- `RENDERS` 中的 `renderAll`、`renderTree`、`renderInspector`、`renderViews`、`renderBanner`、`renderStatus`、`renderSyncButton`、`renderDraftState` 在新页面中换成 `env.render(name)`（通知 React）；`draw` 在原实现后追加 `env.render("draw")`。
- 启动代码不生成，由新页面的 store（`state/store.tsx`）和 `legacy/engine.ts` 的 `pushData`/`pushDraft` 完成。

下面按文件中的 `// ----------` 分节，逐个列出顶层声明（函数、常量、变量）：名字（行号，种类）：作用；调用：本文件内前几处引用的行号与总数，以及新页面中经 `legacy.fn` 的引用（由脚本按名字匹配统计，可能包含同名的局部变量或注释外的字符串，个别不准）。函数参数和返回值写在说明中；涉及的端点有 `api/data`、`api/revision`、`api/draft`、`api/apply`、`api/times`、`api/network`、`api/route-preview`、`api/route-layout`、`api/layout`、`api/simulate`、`api/console`、`api/plan-duties`、`api/replan-duties`、`api/place-trains`。行号以 7dcccd22 为准。


## 文件开头：常量、工具与状态（第 1 行起）

- `DAY`（L9，常量）：一天的毫秒数。 调用：editor.js L30、L986、L1180 等 31 处。
- `POLL_MILLIS`、`POLL_HIDDEN_MILLIS`（L11，常量）：轮询游戏数据的间隔：页面可见时 1500 ms，隐藏时 `POLL_HIDDEN_MILLIS` 5000 ms。 调用：editor.js L679。
- `LABEL_W`、`AXIS_H`、`MIN_ROW_GAP`、`PAD_BOTTOM`（L13，常量）：运行图几何常量：左侧站名栏宽 `LABEL_W` 120、顶部时间轴高 `AXIS_H` 28、最小行距 `MIN_ROW_GAP` 22、底部留白 `PAD_BOTTOM` 30（像素，留给接续到其他路线的车次标签）。 调用：editor.js L3910、L3959、L3969 等 23 处。
- `DEPOT_ROW_ROOM`（L14，常量）：运行图上下为车厂行留出的高度（像素）。 调用：editor.js L3952、L4379。
- `SECTION_GAP`（L16，常量）：线路组车站行中，第一段以外各段上方的间距（相对一行的倍数）。 调用：editor.js L897。
- `LINE_DIAGRAM_HALF_WIDTH`（L18，常量）：线路组配线图在路径两侧显示的宽度（格）。 调用：editor.js L7723、L7762。
- `LABEL_ROW_PX`、`RAIL_WOBBLE_BLOCKS`（L21，常量）：线路组配线布局用：站名行间距 `LABEL_ROW_PX`（像素）和轨道偏离常规位置仍画直的容差 `RAIL_WOBBLE_BLOCKS`（格）。 调用：editor.js L7818、L7888、L7901。
- `ZOOMS`（L23，常量）：运行图缩放档位（每分钟像素），从一眼看全天到约每秒一像素。 调用：editor.js L4568、L4569。
- `WEEKDAY_NAMES`（L24，常量）：星期全名（周一…周日），读取时翻译（`translatedList`）。 调用：editor.js L4859、L4862、L4927 等 9 处。
- `WEEKDAY_SHORT`（L25，常量）：星期短名（一…日），读取时翻译。 调用：editor.js L1006、L1344、L1348 等 6 处。
- `MAX_SERVICES`、`MAX_TABLES`（L27，常量）：与游戏 `RouteTimetable` 相同的上限：一张表最多 `MAX_SERVICES` 4096 班，一条路线最多 `MAX_TABLES` 16 张表。 调用：editor.js L5159、L8205、L8216。
- `MAX_LAYOVER_MILLIS`（L29，常量）：环线列车等下一圈的最长时间（30 分钟，超过则回库），同游戏 `RouteTimetable.MAX_LAYOVER_MILLIS`。 调用：editor.js L5358、L5396、L5400。
- `MAX_STAY_MILLIS`（L30，常量）：车厂设"允许长时间停在线上"时环线可等的最长时间（8 天）。 调用：editor.js L5358。
- `FIRST_DAY_WEEKDAY`（L32，常量）：纪元第一天（1970-01-01）是星期四，即星期序号 3（0=周一）。 调用：editor.js L4036、L4585、L4674 等 4 处。
- `$`（L34，常量）：`document.getElementById` 的简写。 调用：editor.js L211、L212、L213 等 660 处。
- `css`（L35，常量）：读取根元素上的 CSS 变量值（如 `--panel`）。 调用：editor.js L2314、L2316、L2590 等 42 处；新页面 `editor-web/src/views/RunningDiagram.tsx:289`（经 `legacy.fn`）。
- `escapeHtml`（L36，常量）：HTML 转义 `& < > " '`。 调用：editor.js L212、L213、L220 等 207 处。
- `colorOf`（L37，常量）：RGB 整数 → `#rrggbb`。 调用：editor.js L1629、L1630、L1631 等 22 处。
- `mod`（L38，常量）：非负取模。 调用：editor.js L986、L1180、L1181 等 34 处；新页面 `editor-web/src/legacy/engine.ts:233`（经 `legacy.fn`）。
- `shortName`（L40，常量）：名字写成"中文|English"时取第一部分；空时"（未命名）"。 调用：editor.js L292、L296、L339 等 127 处。
- `key`（L44，常量）：编辑器密钥，取自 `shell.js` 的 `MTREditor.key`。 调用：editor.js L6、L81、L87 等 153 处。
- `inTimetable`（L45，常量）：当前是否显示时刻表工作区（媒体库显示时这里不绘制、少问游戏、Esc 不清选择）。 调用：editor.js L679、L1841、L2000 等 9 处。
- `data`、`revision`、`lastLoaded`、`connection`（L47，变量）：页面状态变量：`data` 游戏数据（`setData` 加工后）、`revision` 已读的数据版本号、`lastLoaded` 最近读取时间、`connection` 连接状态（`connecting`/`ok`/`key`/`world`/`offline`）。 调用：editor.js L6、L53、L115 等 454 处。
- `coverage`（L52，变量）：当天哪些车次没有车跑的缓存（`coverageNow` 使用；数据、草稿或星期变化时清空）。 调用：editor.js L127、L686、L5564 等 6 处。
- `terminal`（L54，变量）：当天终点接续配对的缓存（`terminalNow` 使用）。 调用：editor.js L128、L516、L687 等 7 处。
- `draft`、`draftLoaded`、`draftTimer`、`applyResult`、`applying`（L55，变量）：草稿相关变量：`draft` 草稿对象（`routes`、`created`、`duties`、`boundaries`、`depots`、`depotRules`、`platformStations`、`platformSpeeds`、`platformStops`、`networkSettings`、`contingencies`）、`draftLoaded`、`draftTimer`（保存防抖计时器）、`applyResult`（最近应用结果）、`applying`（正在应用）。 调用：editor.js L5、L53、L94 等 225 处。
- `state`（L56，常量）：界面状态对象：`view`（diagram/table/network）、`line`、`route`、`selected`（`路线@车次`）、`selectedRoute`（双击选中整条路线）、`pendingExit`（直通时先选的接出站）、`slot`（正在编辑运用的库位 `{depot, siding, index}`）、`weekday`、`split`、`treeOpen`、`openNodes`、`pxPerMin`、`viewStart`、`scrollY`、`lineWay`（线路组表的方向）；`range` 等在运行时加入。 调用：editor.js L656、L706、L707 等 288 处。

## Data（第 79 行起）

读写游戏数据和草稿：请求、草稿的载入/保存/计数、路线草稿条目的各种取值与设定（管理、联络线、限速、删除、放行、折返站台、寻路边界）、缺车与运用组、生成并应用草稿操作、冲突处理、轮询与 `setData`、车站行。

- `fetchJson`（L80，异步函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：带密钥头的 GET JSON，非 2xx 抛 `Error(状态码)`。 调用：editor.js L94、L665、L667 等 5 处；新页面 `editor-web/src/legacy/retired.ts:267`、`editor-web/src/legacy/retired.ts:300`（经 `legacy.fn`）。
- `sendJson`（L86，异步函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：带密钥头的写请求（`method` + JSON body），非 2xx 抛错。 调用：editor.js L130、L295、L352 等 19 处；新页面 `editor-web/src/legacy/dialogs.ts:22`、`editor-web/src/legacy/dialogs.ts:33` 等 8 处（经 `legacy.fn`）。
- `loadDraft`（L92，异步函数）：启动时 GET `api/draft` 读草稿（失败则空草稿），冲突条目补错误说明，补齐各部分（`created`、`duties`、`boundaries`、`depots`、`depotRules`、`platformStations`、`platformSpeeds`、`platformStops`、`networkSettings`、`contingencies`），置 `draftLoaded`。新页面同名实现为 `core/draft.ts` 的 `normalizeDraft`。 调用：editor.js L9313。
- `clearDraft`（L116，函数）：丢弃整个草稿：恢复显示游戏数据，并保存空草稿。 调用：editor.js L1567。
- `saveDraft`（L126，函数）：清空 `coverage`、`terminal` 缓存，400 ms 防抖后 PUT `api/draft`（失败忽略），更新草稿状态显示，并安排重新模拟。新页面中草稿由 store 保存（`env.sendJson` 对 `api/draft` 直接返回）。 调用：editor.js L120、L179、L192 等 31 处；新页面 `editor-web/src/legacy/dialogs.ts:105`、`editor-web/src/legacy/dialogs.ts:151` 等 16 处（经 `legacy.fn`）。
- `draftCount`（L136，常量）：草稿改动项数（各部分键数之和，新建项也算）。 调用：editor.js L568、L645、L1468 等 6 处。
- `dutyNotice`（L138，变量）：显示在顶部提示条里的、关于最近一次运用操作的消息。 调用：editor.js L119、L296、L299 等 26 处。
- `pendingOpen`（L140，变量）：游戏建好新路线后要打开显示的路线 ID。 调用：editor.js L574、L710、L711 等 4 处。
- `draftOf`（L141，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线的草稿条目或 undefined。 调用：editor.js L153、L154、L156 等 19 处；新页面 `editor-web/src/views/Inspector.tsx:93`、`editor-web/src/views/LinePanels.tsx:79` 等 5 处（经 `legacy.fn`）。
- `draftEntry`（L143，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：取得或新建路线草稿条目（记下各部分的版本 `base`/`parts`，游戏按部分检查）。 调用：editor.js L171、L184、L415 等 8 处；新页面 `editor-web/src/views/StopsEditor.tsx:172`（经 `legacy.fn`）。
- `EDITOR_PARTS`（L145，常量）：编辑器改动会发送的路线部分：时刻表、站台、路线设置。 调用：editor.js L147。
- `draftOutOfDate`（L147，常量）：草稿建立后游戏是否改动了草稿会发送的部分（据此判冲突）。 调用：editor.js L701。
- `tidyDraft`（L149，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线条目只剩来源信息（`base/force/error/conflict`）时删除。 调用：editor.js L178、L191、L419 等 8 处；新页面 `editor-web/src/views/StationTracks.tsx:184`、`editor-web/src/views/StopsEditor.tsx:175`（经 `legacy.fn`）。
- `isManaged`（L153，常量）：路线是否由高级编辑器管理（草稿优先）。 调用：editor.js L4925；新页面 `editor-web/src/views/SummaryTable.tsx:239`（经 `legacy.fn`）。
- `isConnecting`（L154，常量）：路线是否为联络线（草稿优先）。 调用：editor.js L247、L1652、L2556 等 4 处；新页面 `editor-web/src/views/Navigator.tsx:132`（经 `legacy.fn`）。
- `speedLimitOf`（L156，常量）：路线限速 km/h（0 为不限，草稿优先）。 调用：editor.js L429、L4930；新页面 `editor-web/src/views/LinePanels.tsx:57`、`editor-web/src/views/SummaryTable.tsx:272`（经 `legacy.fn`）。
- `isDeleted`（L158，常量）：种别是否在草稿里标记为删除。 调用：editor.js L658、L660、L1630 等 5 处；新页面 `editor-web/src/views/Layers.tsx:337`、`editor-web/src/views/Layers.tsx:338` 等 4 处（经 `legacy.fn`）。
- `boundaryErrors`（L161，常量）：游戏没接受的寻路边界改动的原因，按轨道键保存，直到再次修改或应用。 调用：editor.js L230、L586、L588 等 5 处。
- `railKey`（L162，常量）：两个节点键组成的轨道键（较小的在前，按 BigInt 比较）。 调用：editor.js L184、L185、L187 等 16 处。
- `releasedOf`（L164，常量）：联络线放行给其他路线的轨道（草稿优先）。 调用：editor.js L185、L186、L202 等 8 处。
- `turnOf`（L167，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线的轮用折返站台（站前折返）或优先出发站台（种别优先），草稿优先。 调用：editor.js L172、L173、L6831。
- `toggleTurnPlatform`（L170，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：在上面列表末尾加入或移出一个站台（写草稿，与游戏相同则删字段）。 调用：editor.js L6944。
- `toggleReleased`（L183，函数）：在草稿中放行或收回联络线的一段轨道。 调用：editor.js L243、L2535、L2553 等 4 处。
- `railMarker`（L200，函数）：配线图中一段轨道的画法：联络线独占、已放行或普通；联络线自己的图显示其草稿状态。 调用：editor.js L7835、L7992。
- `setBoundary`（L226，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：在草稿中设置或取消一段轨道的寻路边界（应用时随游戏内边界包发送），然后保存、重绘。 调用：editor.js L240。
- `railMenu`（L236，函数）：配线图中轨道的右键菜单：寻路边界（只缩小寻路范围）或联络线轨道放行。 调用：editor.js L3101、L7951；新页面 `editor-web/src/views/TrackDiagram.tsx:133`（经 `legacy.fn`）。
- `shortfallText`（L254，函数）：缺车说明（按服务器按线路排运用的结果）。 调用：editor.js L408、L1718、L5638 等 4 处；新页面 `editor-web/src/views/DutyBoard.tsx:74`、`editor-web/src/views/Inspector.tsx:147` 等 3 处（经 `legacy.fn`）。
- `missingTrains`（L276，函数）：缺几列车（各车型分开计，未知为 -1）。 调用：editor.js L1718；新页面 `editor-web/src/views/Navigator.tsx:282`（经 `legacy.fn`）。
- `replanText`（L281，函数）：解释运用是按原设置排、为何未自动重排（换端时间、实测余量、轨道变化）。 调用：editor.js L291、L1718、L8368；新页面 `editor-web/src/legacy/dialogs.ts:117`、`editor-web/src/views/DutyBoard.tsx:74` 等 3 处（经 `legacy.fn`）。
- `replanDialog`（L290，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）："重新排运用"确认对话框，确认后 POST `api/replan-duties`，结果写入 `dutyNotice`。 调用：editor.js L8690；新页面 `editor-web/src/views/DutyBoard.tsx:69`（经 `legacy.fn`）。
- `dutyGroupsOf`（L309，函数）：网页按"车型|星期"统计的车厂运用组（运用组第 4 步）。 调用：editor.js 内无。
- `dutyGroupsDialog`（L325，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：运用组对话框：本规划单元各组（可单独重排）和其他车厂的运用组（可作为模板套用）。 调用：editor.js L8691；新页面 `editor-web/src/views/DutyBoard.tsx:44`（经 `legacy.fn`）。
- `applyDutyGroup`（L372，函数）：把另一车厂某车型某些星期的运用放进本车厂草稿（同车型存车线按顺序对应，最后一条接收其余）；本车厂这些天的运用被替换，改为按运用图。 调用：editor.js L363。
- `shortfallsOf`（L405，函数）：跑某路线的各车厂的缺车说明（每个规划单元一次）。 调用：editor.js L4927；新页面 `editor-web/src/views/SummaryTable.tsx:269`（经 `legacy.fn`）。
- `setSpeedLimit`（L414，函数）：在草稿中设置路线限速（0 为不限）。 调用：editor.js L443；新页面 `editor-web/src/views/LinePanels.tsx:68`（经 `legacy.fn`）。
- `speedLimitDialog`（L426，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：多条路线的限速对话框（不高于沿途最快轨道限速）。 调用：editor.js L8696、L8697。
- `setConnecting`（L451，函数）：在草稿中设置/取消联络线（其他线路寻路避开其轨道，但车次可载客直通）。 调用：editor.js L248、L2557、L8678 等 4 处。
- `setManaged`（L461，函数）：在草稿中设置是否由高级编辑器管理（与游戏相同则删字段），保存并重绘。 调用：editor.js L8873；新页面 `editor-web/src/views/SummaryTable.tsx:271`（经 `legacy.fn`）。
- `draftOperations`（L474，函数）：把草稿转成发给游戏的操作列表及每条操作对应的对象（用于放回错误）。新页面的同名实现是 `core/draft.ts` 的 `draftOperations`。 调用：editor.js L569、L4620；新页面 `editor-web/src/legacy/retired.ts:277`（经 `legacy.fn`）。
- `applyDraft`（L567，异步函数）：应用草稿：`draftOperations` 后 POST `api/apply`；成功的部分从草稿删除，失败的留在草稿并记错误/冲突，记下新建路线待打开；然后保存草稿、重读数据、重绘。 调用：editor.js L8716。
- `resolveConflict`（L626，函数）：冲突处理：保留草稿（以游戏最新版本为基础、`force`）或丢弃草稿用游戏的。 调用：editor.js L1601、L8741、L8742。
- `renderDraftState`（L644，函数）：更新顶栏草稿标签（项数/正在应用）、媒体工作区标签上的草稿点、应用按钮状态等。 调用：editor.js L131、L571、L677 等 4 处。
- `poll`（L663，异步函数）：轮询：GET `api/revision`，版本变化时 GET `api/data` 并 `setData`；记连接状态；按页面是否隐藏安排下一次（`once` 时只读一次）。 调用：editor.js L621、L7639、L9298 等 4 处；新页面 `editor-web/src/legacy/retired.ts:176`（经 `legacy.fn`）。
- `setData`（L683，函数）：接收游戏数据：设置语言（`I18N.setLanguage`），建 `routeById`、线路组、各线路组车站行（`buildStations`）、拓扑（`buildTopology`），重建车次，清缓存，处理待打开的新路线，然后重绘。 调用：editor.js L669；新页面 `editor-web/src/legacy/engine.ts:227`（经 `legacy.fn`）。
- `revisits`（L726，函数）：路线回到已停过车站的情况（原路折回或从别处绕回，同站台或不同站台）。新页面同名实现在 `core/services.ts`。 调用：editor.js L762、L6746。
- `buildStations`（L751，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：线路组运行图左侧的车站行（分段、行间距），并设置各路线的 `rows`/`leaveRows`。新页面同名实现在 `core/services.ts`。 调用：editor.js L696。

## Times（第 901 行起）

车次与时刻：页面不自己算时刻，各站时刻由游戏按 StopRules 算出；草稿时刻表的时刻向游戏请求（`api/times`）。

- `toPlans`（L904，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：游戏给的各站 `[到, 发, 待避, 通过, 过快]` → 计划对象数组（页面不自己算时刻，时刻由游戏按 StopRules 算出）。 调用：editor.js L958。
- `gameSource`（L905，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线在游戏中各表的时刻，作为时刻来源（缓存在 `r.gameSource`）。 调用：editor.js L914、L923。
- `draftTimes`（L907，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：各路线草稿时刻表由游戏算出的时刻及对应的草稿。 调用：editor.js L916、L928、L939。
- `requestedTimes`（L908，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：已向游戏请求时刻的草稿指纹。 调用：editor.js L914、L917、L918 等 5 处。
- `timesTimer`（L909，变量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：批量请求草稿时刻的防抖计时器。 调用：editor.js L919、L920。
- `timesSource`（L912，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线时刻来源：游戏自己的时刻表，或游戏对草稿的回答（未回答前用游戏原有的），必要时安排 `sendTimes`。 调用：editor.js L967；新页面 `editor-web/src/legacy/engine.ts:109`（经 `legacy.fn`）。
- `sendTimes`（L927，异步函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：把等待时刻的草稿一起 POST `api/times`，回答后重建车次并重绘。 调用：editor.js L920。
- `plansFor`（L955，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：某表各车次（或某车次带例外时）的各站计划时刻；游戏尚未回答时用表的或全 0。 调用：editor.js L971、L987。
- `buildServices`（L963，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：为每条路线生成当天车次（每班取当天运行的第一张包含它的表），附直通、回库、停运、出/回库指令、运营日内时刻和计划时刻。 调用：editor.js L121、L709、L946 等 8 处；新页面 `editor-web/src/legacy/engine.ts:198`、`editor-web/src/legacy/engine.ts:234` 等 4 处（经 `legacy.fn`）。
- `findService`（L996，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：按 `路线@车次` 查车次。 调用：editor.js L708、L947、L1065 等 24 处；新页面 `editor-web/src/legacy/engine.ts:199`、`editor-web/src/views/AskPanels.tsx:61`（经 `legacy.fn`）。
- `timesOf`（L1003，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车次在各站的 `[到, 发]`（运营日毫秒）。 调用：editor.js L3586、L3595、L3907 等 29 处。

## Editing（第 1005 行起）

时刻表编辑规则（改草稿）与时刻输入解析。

- `daysText`（L1006，常量）：星期位掩码 → "一二三…"，0 为"不运行"。 调用：editor.js L328、L339、L353 等 10 处。
- `tableName`（L1007，常量）：表名，空时"表 N"。 调用：editor.js L1321、L1351、L1407 等 10 处。
- `normalize`（L1010，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：把时刻表各列表排成固定顺序，使改回原样的草稿能与游戏版本相等。 调用：editor.js L1026、L1059、L7558。
- `fromGame`（L1025，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线在游戏中的时刻表，转成编辑器发送的格式。 调用：editor.js L1040。
- `gameTimetable`（L1040，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：`fromGame` 的缓存版本（`r.gameTimetable`）。 调用：editor.js L1041、L1049、L1060 等 8 处；新页面 `editor-web/src/views/StopsEditor.tsx:164`（经 `legacy.fn`）。
- `timetableOf`（L1041，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线时刻表：草稿中有则用草稿，否则游戏的。 调用：editor.js L966、L1316、L1327 等 23 处；新页面 `editor-web/src/legacy/engine.ts:109`、`editor-web/src/views/LinePanels.tsx:341` 等 8 处（经 `legacy.fn`）。
- `editTimetable`（L1047，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：在草稿中修改路线时刻表（从游戏副本开始；改回原样则删除），保存、重建车次并重绘，表下方显示改动返回的消息。 调用：editor.js L1146、L1163、L1328 等 24 处；新页面 `editor-web/src/legacy/dialogs.ts:25`、`editor-web/src/views/AskPanels.tsx:71` 等 9 处（经 `legacy.fn`）。
- `setStopException`（L1071，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：改一个车次在一站的运行/停站/通过/待避（-1 为跟表）。 调用：editor.js L5026、L5029、L5303 等 8 处；新页面 `editor-web/src/views/RunningDiagram.tsx:99`（经 `legacy.fn`）。
- `syncExceptions`（L1089，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：让多个车次与某一班的各站改动相同（按站复制，替换它们自己的改动）。 调用：editor.js L5295、L5297。
- `retimeService`（L1098，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车次改时刻，连同直通、回库、停运、指令、例外一起改；目标时刻已有车次时返回原因。 调用：editor.js L5021、L5101、L5143 等 5 处；新页面 `editor-web/src/views/SummaryTable.tsx:366`（经 `legacy.fn`）。
- `setSuspended`（L1110，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：设置车次停运或恢复（时刻等保留）。 调用：editor.js L5282、L5283、L5288。
- `removeService`（L1117，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：从表中删除车次及其直通、回库、停运、指令、例外。 调用：editor.js L5277、L5287、L8200；新页面 `editor-web/src/views/SummaryTable.tsx:365`（经 `legacy.fn`）。
- `servedStops`（L1127，常量）：车次实际载客的首末站（出/回库指令可缩小范围）。 调用：editor.js L4097、L4810、L4832 等 5 处；新页面 `editor-web/src/views/SummaryTable.tsx:80`、`editor-web/src/views/SummaryTable.tsx:88`（经 `legacy.fn`）。
- `orderDepotName`（L1128，常量）：出/回库指令指定的车厂名，`"0"` 为"自动选的车厂"。 调用：editor.js L1443、L4161、L4802 等 5 处。
- `setServiceOrder`（L1134，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：设置或清除车次在某站由车厂出车/转回送的指令（`depot` 为 ID、`"0"` 任意、null 清除）。 调用：editor.js L1146、L1163、L5320 等 4 处；新页面 `editor-web/src/views/AskPanels.tsx:71`（经 `legacy.fn`）。
- `orderDepotItems`（L1141，函数）：某站可设的出/回库车厂菜单项（当前设定打勾）。 调用：editor.js 内无。
- `serviceOrderDialog`（L1154，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：出/回库车厂选择对话框（跑本线的车厂在前），确定后改草稿时刻表。 调用：editor.js L5319。
- `parseClock`（L1169，函数）：解析输入的钟点（7:10、07:10:30、0710 等，支持全角）→ 当天毫秒，非法为 null。 调用：editor.js L1178、L1189、L8081 等 5 处。
- `typedTime`（L1177，函数）：输入的当地钟点 → 离原时刻最近（午夜哪一侧）的运营日时刻。 调用：editor.js L4985。
- `PATTERN_HINT`（L1184，常量）：添加班次输入格式的提示文字。 调用：editor.js L7156、L8186。
- `parsePattern`（L1186，函数）："07:10 + 10 * 00:03" 一类模式 → 车次时刻（UTC 当天毫秒），非法为 null。 调用：editor.js L7155、L8148。
- `tableMessage`（L1194，变量）：汇总时刻表下方显示的提示消息。 调用：editor.js L1066、L4934、L5086 等 6 处。

## Menus and dialogs（第 1196 行起）

旧页面自己的菜单与 `<dialog>` 表单，以及表设置对话框和星期切换。新页面中菜单函数由 `env` 提供（React 绘制）。

- `menu`（L1197，变量）：当前打开的菜单元素。 调用：editor.js L235、L1140、L1199 等 23 处。
- `openMenu`（L1202，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：在某点打开 `[label, action]` 菜单（子菜单在旁展开，无动作的项灰显）。 调用：editor.js L1215、L1218、L8725。
- `openMenuAt`（L1213，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：在右键位置打开菜单；由菜单键或 Shift+F10 触发时贴着元素打开。 调用：editor.js L250、L2537、L2559 等 15 处；新页面 `editor-web/src/legacy/dialogs.ts:240`、`editor-web/src/legacy/dialogs.ts:290` 等 4 处（经 `legacy.fn`）。
- `menuHost`（L1225，常量）：菜单挂载处：顶层打开的 `<dialog>`（否则菜单会被对话框挡住）或 `body`。 调用：editor.js L1206、L1241。
- `subMenu`（L1226，变量）：当前展开的二级菜单元素。 调用：editor.js L1237、L1238、L1239 等 14 处。
- `buildMenu`（L1227，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：构建菜单 DOM（按钮；数组动作为带 ▸ 的子菜单）。 调用：editor.js L1205。
- `closeMenu`（L1265，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：关闭菜单和子菜单。 调用：editor.js L1203、L1254、L1271 等 9 处。
- `browserMenuFields`（L1275，常量）：右键行为统一（2026-11-09）：页面有菜单处由该处处理；文本框和选中文字保留浏览器菜单（复制粘贴）；其他地方不弹任何菜单。此常量是保留浏览器菜单的输入框选择器，文档级右键监听使用。 调用：editor.js L1280。
- `dialog`（L1288，常量）：页面唯一的 `<dialog>` 元素。 调用：editor.js L349、L351、L360 等 58 处。
- `openDialog`（L1290，函数）：打开表单对话框：确定按钮执行 `onOk`，返回错误则显示并保持打开；`onInput` 在输入时调用。 调用：editor.js L292、L341、L433 等 32 处。
- `tableDialog`（L1315，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：新建时刻表或修改表名与星期（一天只用一张表，勾选的星期从其他表移走）。 调用：editor.js L8697、L8729、L8888 等 4 处；新页面 `editor-web/src/views/Layers.tsx:301`、`editor-web/src/views/SummaryTable.tsx:243` 等 3 处（经 `legacy.fn`）。
- `setWeekday`（L1356，函数）：切换显示的星期：同步下拉框，重建车次，选中车次不存在则清空，重绘。 调用：editor.js L1344、L8861、L8884；新页面 `editor-web/src/App.tsx:126`、`editor-web/src/views/LinePanels.tsx:378` 等 3 处（经 `legacy.fn`）。

## What the draft changes（第 1364 行起）

把草稿改动写成可读文字，以及"草稿的改动"对话框。

- `setTimeText`（L1366，常量）：站的设定时间（自首站起秒数）文字，-1 为"跟前一站"。 调用：editor.js L1426、L1427。
- `serviceClock`（L1367，常量）：车次时刻（游戏日毫秒）→ 带秒钟点。 调用：editor.js L1370、L1431、L1443 等 5 处。
- `someServices`（L1369，常量）：前若干个车次时刻，并说明还有多少班。 调用：editor.js L1421、L1422、L1437 等 6 处。
- `routeChanges`（L1375，函数）：路线草稿相对游戏版本的改动说明（逐行）。新页面同名实现在 `core/draft.ts`。 调用：editor.js L1476。
- `draftChangesDialog`（L1467，函数）："草稿的改动"对话框：逐路线列出改动，可跳转、放弃，处理冲突，应用。新页面对应 `shell/Review.tsx`。 调用：editor.js L1552、L1583、L1603。
- `clock`（L1608，常量）：运营日时刻 → 当地钟点 "HH:MM"（`seconds` 时有秒则加秒）。 调用：editor.js L1160、L1367、L3765 等 49 处。
- `routeTitle`（L1614，常量）：路线短名 + " → 终点站"。 调用：editor.js L248、L430、L440 等 52 处。

## Tree（第 1619 行起）

左侧树的绘制与点击处理。

- `renderTree`（L1620，函数）：画左侧树（按存档分层：物理线路、区段归属/逻辑线路、运营线路及其停站、时刻表和运行图、车厂及车型/运用/存车线），每个节点为带 `data-*` 的 `.node`。新页面改由 `views/Navigator.tsx` 画进同一 `#tree`，本函数经 `RENDERS` 改为通知 React。 调用：editor.js L1808、L1831、L1835 等 4 处。
- `onTreeClick`（L1760，函数）：树节点点击：按节点的 `data-*`（`slot`、`layer`、`connecting`、`logical`、`newLogical`、`generateLogical`、`line`、`route`、`view`、`depot`、`pool` 等）选中运用位、切换路网图层、打开逻辑线路对话框、展开/折叠、打开线路或路线的视图等。新页面中仍由它处理 `#tree` 上的点击。 调用：editor.js L8660、L8706。

## Views（第 1839 行起）

运行图、汇总时刻表、全网配线三个视图的切换。

- `canSplit`（L1840，常量）：窗口足够大（宽 ≥1100、高 ≥820）时允许运行图与汇总时刻表上下同时显示。 调用：editor.js L1841、L1842、L1855 等 4 处；新页面 `editor-web/src/views/Layers.tsx:291`、`editor-web/src/views/Layers.tsx:311`（经 `legacy.fn`）。
- `showsDiagram`（L1841，常量）：当前是否显示运行图（时刻工作区内，运行图视图或分屏）。 调用：editor.js L1859、L3988、L5781；新页面 `editor-web/src/views/Layers.tsx:242`（经 `legacy.fn`）。
- `showsTable`（L1842，常量）：当前是否显示汇总时刻表（表视图或分屏且不在路网视图）。 调用：editor.js L1860、L4914、L5781；新页面 `editor-web/src/views/Layers.tsx:242`、`editor-web/src/views/SummaryTable.tsx:34`（经 `legacy.fn`）。
- `openView`（L1844，函数）：打开某线路组/路线的某个视图（`diagram`/`table`/`network`）：换线路时清选中，竖屏时收起树，`renderAll`。 调用：editor.js L1592、L1772、L1778 等 11 处；新页面 `editor-web/src/App.tsx:40`、`editor-web/src/views/Inspector.tsx:86`（经 `legacy.fn`）。
- `renderViews`（L1854，函数）：按状态显示/隐藏三个视图和分屏按钮，切换 `body` 的 `split`、`network-view` 类等。 调用：editor.js L8633。
- `currentLine`（L1870，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：当前显示的线路组。新页面同名实现在 `core/services.ts`。 调用：editor.js L1142、L1155、L1866 等 13 处；新页面 `editor-web/src/legacy/retired.ts:275`、`editor-web/src/views/AskPanels.tsx:65`（经 `legacy.fn`）。

## Whole network track view（第 1873 行起）

全网配线图（`api/network`，即服务器 `EditorNetwork`）：所有轨道、站台、存车线和隔离层，放大后显示站名，并叠加树中所选线路组的路线；缩小时画成示意图。还有坐标换算、索引与简化。

- `NET_CELL`、`NET_HIT_PX`、`NET_STATION_SCALE`、`NET_POLL_MS`（L1879，常量）：全网配线图常量：命中网格 `NET_CELL` 64 格、命中距离 `NET_HIT_PX` 6 像素、车站名显示比例 `NET_STATION_SCALE`、轮询 `NET_POLL_MS` 3000 ms。 调用：editor.js L2028、L2029、L2787 等 4 处。
- `NET_EXACT_SCALE`、`NET_SIMPLIFY_PX`、`NET_ALIGNED`（L1882，常量）：缩放低于 `NET_EXACT_SCALE`（像素/格）时画成示意图：道岔之间的轨道简化（容差 `NET_SIMPLIFY_PX`）并把直段对齐横/竖/45°（`NET_ALIGNED` 为对齐阈值）。 调用：editor.js L1887。
- `netExact`（L1887，常量）：地图是否按实际轨道画：放大到足够程度，或正在为车厂选出入口（其路线按实际轨道画）。 调用：editor.js L1916、L2143、L2703 等 7 处。
- `net`（L1888，常量）：全网配线图状态：`data`（`api/network` 的回答）、`version`、`mode`、`scale`、`cx`/`cz`（中心世界坐标）、`grid`（命中索引）、`hover`、`error`、计时器、`layer`（physical/owners）、`ownerOf`、`sectionRails`、`chains`、`railChain`、`byPair`、`focusLogical` 等。 调用：editor.js L1632、L1644、L1648 等 176 处。
- `netCanvas`、`netWrap`、`netCtx`（L1891，常量）：全网配线图的 `<canvas>`、容器和 2D 上下文。 调用：editor.js L2582、L2583、L2584 等 16 处。
- `netGeometryCache`、`netDetailCache`、`netRouteCache`（L1894，常量）：几何、简化细节和路线路径的缓存（键为不可变的点数组，换数据时自然失效）。 调用：editor.js L1897、L1905。
- `netFramePending`（L1895，变量）：是否已安排下一帧绘制。 调用：editor.js L2573、L2574、L2575。
- `netGeometry`（L1896，函数）：一段轨道点数组的包围盒、起点，以及（`withPath` 时）Path2D，带缓存。 调用：editor.js L2601、L2610、L2761。
- `netDrawingPoints`（L1915，函数）：一段轨道在当前缩放级别下要画的点（实际画法或按级别简化，缓存最多 6 级）。 调用：editor.js L2609。
- `netStyles`（L1927，函数）：每帧一次的样式快照（颜色、线宽等）与区段解析结果，避免每段轨道扫描整个区段。 调用：editor.js L2633。
- `setNetLayer`（L1969，函数）：在全网图上切换物理线路/区段归属图层，并记住选择。 调用：editor.js L1771、L1777、L1792 等 4 处；新页面 `editor-web/src/views/Layers.tsx:33`（经 `legacy.fn`）。
- `loadNetwork`（L1976，异步函数）：POST `api/network`（`mode`、`fresh`、`version`）；有新数据时保存、建索引（`indexNetwork`），首次或换运输方式时 `fitNetwork`；然后重画、更新工具栏。 调用：editor.js L1999、L2000、L3024 等 5 处；新页面 `editor-web/src/views/NetworkSchematic.tsx:70`（经 `legacy.fn`）。
- `watchNetwork`（L1996，函数）：路网视图打开时轮询地图数据（服务器在请求后一段时间才给出）。 调用：editor.js L8634；新页面 `editor-web/src/legacy/engine.ts:169`（经 `legacy.fn`）。
- `indexNetwork`（L2008，函数）：为每段轨道整理点序列、在网格中登记包围盒（找鼠标下的轨道）、按节点对索引轨道。 调用：editor.js L1985。
- `netChains`（L2070，函数）：示意图用的轨道链：经过度为 2 的节点首尾相连、种类和隔离状态相同的轨道合成一条，使站台、存车线、隔离轨道各自成链。 调用：editor.js L2635、L2760。
- `netLevel`（L2139，常量）：示意图计算的缩放级别（以半个 2 的幂为一级）。 调用：editor.js L1919、L2144。
- `chainShape`（L2142，函数）：一条链在当前缩放下的画法：放大时为实际形状，否则简化并对齐 0°/45°/90°。 调用：editor.js L2637、L2685、L2710 等 6 处。
- `simplifyPoints`（L2150，函数）：对 `[x, z, x, z, …]` 做 Douglas–Peucker 简化。 调用：editor.js L1921、L2145。
- `octilinear`（L2180，函数）：把相邻两点间的每段对齐到横、竖或 45°（接近者保留，否则直—斜—直）。 调用：editor.js L2145。
- `cutSteps`（L2212，函数）：把相邻两段形成的直角台阶斜切掉。 调用：editor.js L2204。
- `fitNetwork`（L2250，函数）：把视野适配到全部节点（设 `cx`、`cz`、`scale`）。 调用：editor.js L1986、L3029。
- `netX`（L2261，常量）：世界 x → 画布像素 x。 调用：editor.js L2699、L2741、L2902 等 18 处。
- `netZ`（L2262，常量）：世界 z → 画布像素 y。 调用：editor.js L2699、L2741、L2902 等 18 处。
- `netWorldX`（L2263，常量）：画布像素 x → 世界 x。 调用：editor.js L2599、L2757、L3036。
- `netWorldZ`（L2264，常量）：画布像素 y → 世界 z。 调用：editor.js L2599、L2757、L3036。

## Section owners (web layers step 2)（第 2266 行起）

区段归属（web 分层第 2 步）：服务器算出或草稿手设的区段归属线路。

- `draftOwnerOverrides`（L2267，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：草稿中手工设定的区段归属（轨道键 → 线路列表或 null）。新页面同名实现在 `core/settings.ts`。 调用：editor.js L1929、L2287、L2325 等 4 处。
- `savedOwnerOverrides`（L2268，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：游戏中手工设定的区段归属。 调用：editor.js L1929、L2287、L2324 等 4 处。
- `netRailKey`（L2269，常量）：全网图中轨道序号 → 轨道键。 调用：editor.js L1935、L2288、L2293 等 11 处。
- `sectionOf`（L2274，常量）：轨道所在区段的所有轨道（页面所知范围；无线路归属的区段不发送，则为它自己）。 调用：editor.js L1935、L2288、L2293 等 8 处。
- `railOwnersShown`（L2285，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：轨道显示的归属：服务器算的，或草稿手设的（区段内各轨道手设的并集）。新页面实现为 `core/settings.ts` 的 `sectionOwnersShown`。 调用：editor.js L2349、L2544、L2824；新页面 `editor-web/src/views/AskPanels.tsx:140`、`editor-web/src/views/NetworkSchematic.tsx:179`（经 `legacy.fn`）。
- `ownerText`（L2304，函数）：归属者的可读名称：线路名或联络线名。 调用：editor.js L1537、L2511、L2821 等 4 处；新页面 `editor-web/src/legacy/dialogs.ts:208`（经 `legacy.fn`）。
- `ownerColor`（L2313，函数）：归属者的颜色：联络线用 `--connecting`，线路用其颜色，否则 `--muted`。 调用：editor.js L2672；新页面 `editor-web/src/views/NetworkSchematic.tsx:179`（经 `legacy.fn`）。
- `setOwnerOverride`（L2323，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：在草稿中手工设定轨道所在区段的归属（线路列表 / 空表示对所有线路开放 / null 恢复自动），保存在所选轨道上并从区段其他轨道去掉。 调用：editor.js L2357、L2547、L2548；新页面 `editor-web/src/views/AskPanels.tsx:146`、`editor-web/src/views/OwnershipPanel.tsx:31`（经 `legacy.fn`）。
- `ownersDialog`（L2348，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：选择区段归属线路的对话框（地图当前运输方式的线路）。新页面为 `views/AskPanels.tsx` 的 `OwnersPanel`。 调用：editor.js L2547。

## Logical lines (2026-11-09)（第 2361 行起）

逻辑线路：轨道本身的线路，与运营线路分开（transitsim `LogicalLines`）；一旦存在就决定运营线路能走哪些区段。此外包含归属图层的菜单、隔离状态、路网绘制与调度台（D6）。

- `draftSettings`（L2367，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：草稿全网设置或 `{}`。逻辑线路说明：轨道本身的线路，与运营线路分开；每个区段至多属于一条，记在区段的某段轨道上；运营线路走为它勾选的逻辑线路，否则走其站台所在的。 调用：editor.js L1647、L1928、L2377 等 7 处；新页面 `editor-web/src/views/Navigator.tsx:131`、`editor-web/src/views/OwnershipPanel.tsx:56` 等 5 处（经 `legacy.fn`）。
- `savedSettings`（L2368，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：游戏全网设置或 `{}`。 调用：editor.js L1526、L2376、L2386 等 5 处；新页面 `editor-web/src/views/OwnershipPanel.tsx:56`、`editor-web/src/views/OwnershipPanel.tsx:179`（经 `legacy.fn`）。
- `compareRailKeys`（L2369，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：按服务器顺序比较两个轨道键（BigInt）。 调用：editor.js L1939、L2391。
- `logicalLinesShown`（L2375，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：草稿下的逻辑线路（ID → `{id, name, color, connecting}`）。 调用：editor.js L1534、L1928、L2383 等 5 处；新页面 `editor-web/src/views/Navigator.tsx:130`、`editor-web/src/views/OwnershipPanel.tsx:94` 等 3 处（经 `legacy.fn`）。
- `usesLogicalLines`（L2383，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：是否有逻辑线路。 调用：editor.js L2819、L3017、L3095 等 4 处；新页面 `editor-web/src/views/NetworkSchematic.tsx:174`、`editor-web/src/views/OwnershipPanel.tsx:72`（经 `legacy.fn`）。
- `railLinesShown`（L2386，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：草稿下各区段（按轨道键）所属的逻辑线路。 调用：editor.js L1928、L2390、L2450；新页面 `editor-web/src/views/OwnershipPanel.tsx:38`（经 `legacy.fn`）。
- `railLogicalLine`（L2389，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：轨道所在区段的逻辑线路（多段都设了取键最小的，同服务器），无则 null。 调用：editor.js L2526、L2820；新页面 `editor-web/src/views/NetworkSchematic.tsx:175`、`editor-web/src/views/OwnershipPanel.tsx:102`（经 `legacy.fn`）。
- `railLogicalDrafted`（L2394，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：轨道所在区段的逻辑线路是否在草稿里改过。 调用：editor.js L2821、L2822。
- `draftSettingsPart`（L2397，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：修改草稿全网设置的一部分（与游戏相同的项去掉，空则删除），保存并重绘路网。 调用：editor.js L2417、L2441、L2449 等 10 处；新页面 `editor-web/src/legacy/dialogs.ts:215`、`editor-web/src/legacy/dialogs.ts:216` 等 9 处（经 `legacy.fn`）。
- `setRailLogicalLine`（L2415，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：把轨道所在区段放进某逻辑线路（null 为移出），记在所选轨道上。 调用：editor.js L2528、L2529、L2530；新页面 `editor-web/src/views/OwnershipPanel.tsx:30`（经 `legacy.fn`）。
- `newLogicalId`（L2427，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：新逻辑线路 ID。 调用：editor.js L2439。
- `logicalLineDialog`（L2430，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：新建或修改逻辑线路（名称、颜色、联络线），然后对其 ID 执行 `then`。新页面为 `LogicalLinePanel`。 调用：editor.js L1782、L2529、L8674；新页面 `editor-web/src/views/OwnershipPanel.tsx:146`、`editor-web/src/views/OwnershipPanel.tsx:159`（经 `legacy.fn`）。
- `deleteLogicalLine`（L2447，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：删除逻辑线路，连同其区段和各运营线路对它的勾选。 调用：editor.js L8676。
- `lineTracksDialog`（L2465，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：运营线路走哪些逻辑线路的对话框。新页面为 `LineTracksPanel`。 调用：editor.js L8695。
- `logicalDraftDialog`（L2484，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：按当前归属生成逻辑线路初稿的对话框。 调用：editor.js L1786、L2547；新页面 `editor-web/src/views/OwnershipPanel.tsx:160`（经 `legacy.fn`）。
- `logicalMenu`（L2525，函数）：有逻辑线路时归属图层上轨道的右键菜单：区段的逻辑线路、经过的联络线。 调用：editor.js L3095。
- `ownersMenu`（L2541，函数）：归属图层上轨道的右键菜单：区段归属、经过的联络线。 调用：editor.js L3096。
- `netStatus`（L2563，函数）：轨道隔离状态（叠加草稿边界）：0 普通、1 联络线独占、2 已放行、3 寻路边界。 调用：editor.js L2084、L2813。
- `drawNetwork`（L2572，函数）：在下一动画帧画全网配线图（只在路网视图且在时刻工作区时）。 调用：editor.js L1992、L2344、L2411 等 24 处；新页面 `editor-web/src/legacy/dialogs.ts:105`、`editor-web/src/legacy/engine.ts:170` 等 17 处（经 `legacy.fn`）。
- `drawNetworkFrame`（L2578，函数）：画一帧全网配线图：网格、轨道（按图层、隔离、归属/逻辑线路着色，示意或实际形状）、车站、所选线路组的路线、点选状态（`drawPicking`/`drawWayPick`）、调度台（`drawConsole`）等。 调用：editor.js L2575。
- `netRailAt`（L2755，函数）：鼠标附近几像素内最近的轨道序号（缩小时为鼠标下链中最近的一段），无则 -1。 调用：editor.js L3072、L3081、L3171 等 4 处；新页面 `editor-web/src/views/OwnershipPanel.tsx:81`（经 `legacy.fn`）。
- `netSegmentDistance`（L2806，函数）：点到线段距离（世界坐标）。 调用：editor.js L2160、L2764、L2776 等 4 处。
- `netRailText`（L2812，函数）：轨道的悬停说明：种类（轨道/站台/存车线/留置位）、隔离状态、链长、归属或逻辑线路等。 调用：editor.js L3075。
- `consoleState`（L2835，常量）：调度台状态（D6）：`open`、`data`（`api/console`）、`timer`、`system`（所选路网）、`selected`（所选列车）。 调用：editor.js L2840、L2842、L2849 等 20 处。
- `CONSOLE_POLL_MS`（L2836，常量）：调度台刷新间隔 2000 ms。 调用：editor.js L2854。
- `loadConsole`（L2838，异步函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：GET `api/console`，然后 `renderConsole`、`drawNetwork`。 调用：editor.js L2853、L2854、L2999。
- `toggleConsole`（L2848，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：打开/关闭调度台面板及定时刷新。 调用：editor.js L3005；新页面 `editor-web/src/views/Layers.tsx:34`、`editor-web/src/views/Layers.tsx:35`（经 `legacy.fn`）。
- `consoleTrains`（L2864，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：所选路网（或全部）的列车。 调用：editor.js L2884、L2940；新页面 `editor-web/src/views/DispatchConsole.tsx:25`、`editor-web/src/views/Layers.tsx:154`（经 `legacy.fn`）。
- `consoleRail`（L2871，函数）：调度台给出的两节点 → 全网图轨道序号，无则 -1。 调用：editor.js L2890、L2897；新页面 `editor-web/src/views/Layers.tsx:155`（经 `legacy.fn`）。
- `drawConsole`（L2880，函数）：在地图上画调度台信息：被占用闭塞分区红色、本路网列车所在分区琥珀色、车头处写车名。 调用：editor.js L2746。
- `consoleTrainName`（L2910，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：列车名："存车线 车 N" 或 "存车线的车"。 调用：editor.js L2903、L2953；新页面 `editor-web/src/views/DispatchConsole.tsx:74`、`editor-web/src/views/Layers.tsx:155`（经 `legacy.fn`）。
- `consoleTrainState`（L2913，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：列车状况文字（不在运用中、晚点、等进路、扣停、延长停站、指定站折返、清客、回库、跳站、提前折返、交给备车、不载客）。 调用：editor.js L2954；新页面 `editor-web/src/views/DispatchConsole.tsx:62`（经 `legacy.fn`）。
- `renderConsole`（L2931，函数）：画调度台面板（路网下拉、每列车一张卡片及命令按钮）。新页面为 `views/DispatchConsole.tsx`。 调用：editor.js L2844、L2859；新页面 `editor-web/src/legacy/retired.ts:304`、`editor-web/src/legacy/retired.ts:343` 等 5 处（经 `legacy.fn`）。
- `consoleCommand`（L2994，异步函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：发送调度命令：禁用按钮，POST `api/apply`（`dispatch` 操作），成功按钮显示"已发送"并稍后刷新。 调用：editor.js L2967、L2970、L2971 等 5 处；新页面 `editor-web/src/views/DispatchConsole.tsx:59`（经 `legacy.fn`）。
- `renderNetworkTools`（L3007，函数）：更新路网视图工具栏：运输方式下拉、轨道/车站数、图层下拉等。 调用：editor.js L1973、L1991、L2410；新页面 `editor-web/src/legacy/dialogs.ts:105`、`editor-web/src/legacy/retired.ts:190`（经 `legacy.fn`）。

## A new line chosen on the whole network map（第 3105 行起）

在全网配线图上依次点车站新建线路（服务器 `api/route-preview` 找各段路），以及选出入口面板的点击处理。

- `picking`（L3111，变量）：在全网图上点选新线路的状态：`mode`、`stops`（站台、车站、是否通过、途经节点）、`legs`（服务器 `api/route-preview` 找到的各段路）、`activeLeg`、`color`、`requestId`、`error`。 调用：editor.js L3054、L3114、L3121 等 49 处。
- `startPicking`（L3113，函数）：开始在全网图上点选新线路：建 `picking`，切到路网视图，提示操作。 调用：editor.js L8726；新页面 `editor-web/src/views/Layers.tsx:298`、`editor-web/src/views/StopsEditor.tsx:418`（经 `legacy.fn`）。
- `stopPicking`（L3120，函数）：取消点选。 调用：editor.js L3403；新页面 `editor-web/src/views/Layers.tsx:109`、`editor-web/src/views/PickPanels.tsx:62`（经 `legacy.fn`）。
- `stationPlatforms`（L3126，常量）：某车站（地图当前运输方式）的站台。 调用：editor.js L3219、L7461、L7462 等 4 处。
- `addPickedStop`（L3128，函数）：把选中的站台加为下一站并刷新预览。 调用：editor.js L3226、L3231。
- `refreshPreview`（L3136，异步函数）：POST `api/route-preview` 请求经过所选各站的路，回来后绘制。 调用：editor.js L3132、L3208、L3410；新页面 `editor-web/src/views/PickPanels.tsx:25`（经 `legacy.fn`）。
- `netStationAt`（L3160，函数）：地图上某点的车站（站名/圆点，或其站台轨道），无则 null。 调用：editor.js L3082、L3212、L3660。
- `netNodeAt`（L3187，函数）：地图上某点的轨道节点 ID，无则 null。 调用：editor.js L3202、L3651。
- `pickAt`（L3200，函数）：点选时的地图点击：点车站加站，点节点作为所选段的途经点（再点去掉）。 调用：editor.js L3056。
- `drawPicking`（L3236，函数）：点选时在地图上画：经过所选车站的已有路线突出显示，再画新线路各段和各站。 调用：editor.js L2747。
- `pickingRouteIds`（L3313，常量）：在已选车站停靠的路线 ID。 调用：editor.js L2604。
- `pickingPanel`（L3320，函数）：点选时地图旁面板的 HTML（各站与各段、完成按钮）。新页面为 `views/PickPanels.tsx`。 调用：editor.js L4490。
- `finishPicking`（L3337，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：最后一步：填线名、种别和颜色，路线作为新线路进草稿。新页面改为打开 `FinishPickingPanel`。 调用：editor.js L3400；新页面 `editor-web/src/views/PickPanels.tsx:61`（经 `legacy.fn`）。
- `onWayPickPanelClick`（L3360，函数）：选出入口面板内的点击：用这个/取消/选目标站台/清空等。 调用：editor.js L3376、L3379。

## A depot's rules for going out and back (运用补完 step 2)（第 3419 行起）

车厂出入库规则（运用补完第 2 步）：按线路和星期定出入库车站/路径、提前量、回哪条存车线、等候分界；出入口和回送路径在全网配线图上点选。

- `rulesOf`（L3423，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车厂出入库规则与命名回送路径（草稿优先）。 调用：editor.js L3451、L6015；新页面 `editor-web/src/views/DepotPanel.tsx:230`（经 `legacy.fn`）。
- `depotLines`（L3430，函数）：车厂负责的线路（键与显示名）。 调用：editor.js L3453、L8365；新页面 `editor-web/src/views/DepotPanel.tsx:235`（经 `legacy.fn`）。
- `secondsText`（L3440，常量）：毫秒 → 输入框中的秒数文字（负数为空）。 调用：editor.js L3468、L3472。
- `rulesEditing`（L3443，变量）：正在编辑的规则（关闭对话框去地图上选择时保留）。 调用：editor.js L3452、L3490、L3603 等 4 处。
- `wayPick`（L3448，变量）：在全网图上为规则选车站或画回送路径的状态：车厂、存车线、目标、服务器的回答等。 调用：editor.js L1887、L3049、L3361 等 60 处。
- `depotRulesDialog`（L3450，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车厂出入库规则对话框（规则卡片、命名路径、在图上点轨道）。新页面为 `views/DepotPanel.tsx` 的 `DepotRules`。 调用：editor.js L3607、L3811、L8687；新页面 `editor-web/src/views/DutyBoard.tsx:42`（经 `legacy.fn`）。
- `readRulesForm`（L3527，函数）：把对话框字段读回正在编辑的规则。 调用：editor.js L3486、L3498。
- `rulePlatform`（L3553，函数）：规则的出库终点/回库起点站台：车厂所跑该线路某路线的首站（或末站）。 调用：editor.js L3618、L3621。
- `ruleTargets`（L3566，函数）：规则列车在当天运行图上出库到达/回库出发的站台（按站台给出最早/最晚时刻和班数）。 调用：editor.js L3614。
- `startWayPick`（L3602，函数）：开始在地图上为规则或回送路径选择：取存车线（没有则提示并回到对话框），建 `wayPick`，切到路网视图，请求服务器。 调用：editor.js L3513、L3518；新页面 `editor-web/src/views/DepotPanel.tsx:248`（经 `legacy.fn`）。
- `refreshWayPick`（L3628，异步函数）：POST 请求存车线可达的车站与所选路线，回来后绘制。 调用：editor.js L3367、L3371、L3624 等 7 处；新页面 `editor-web/src/views/PickPanels.tsx:89`、`editor-web/src/views/PickPanels.tsx:95`（经 `legacy.fn`）。
- `wayPickAt`（L3644，函数）：选择时的地图点击：可达车站、路径节点或另一端。 调用：editor.js L3051。
- `toggleWayRail`（L3685，函数）：规则路线经过某段轨道（2026-11-09）：首次点按列车方向加入，再点反向，第三次去掉。 调用：editor.js L3647。
- `drawWayPick`（L3714，函数）：选择时在地图上画：存车线可达的车站（圈出）和所选路线。 调用：editor.js L2748。
- `wayPickPanel`（L3754，函数）：选择时地图旁面板的 HTML（所选、找到的路和用时、完成）。 调用：editor.js L4491、L4497。
- `finishWayPick`（L3773，函数）：把选择结果放进正在编辑的规则（`keep` 为假时放弃），回到规则对话框。 调用：editor.js L3363、L3364；新页面 `editor-web/src/views/Layers.tsx:109`、`editor-web/src/views/PickPanels.tsx:100` 等 3 处（经 `legacy.fn`）。

## Running diagram（第 3814 行起）

运行图画布与布局变量。

- `canvas`、`wrap`、`ctx`（L3815，常量）：运行图的 `<canvas>`、容器 `#canvas-wrap` 和 2D 上下文。新页面中容器仍借入布局，画布隐藏，新画布叠在其上。 调用：editor.js L1891、L3973、L3974 等 33 处。
- `layout`、`drawn`、`handles`（L3816，变量）：运行图布局（`computeLayout` 结果）、已画车次 `drawn`、停站拖柄 `handles`。 调用：editor.js L3983、L3990、L4000 等 26 处。

## Other lines on this line's track (web layers step 3)（第 3818 行起）

运行图上其他线路走本线轨道的车次（web 分层第 3 步），以及运行图的布局、绘制、命中、检查器、提示、缩放。

- `showAccess`（L3824，变量）：是否在运行图上画其他线路走本线轨道的车次（只读）。 调用：editor.js L3825、L3897、L8842 等 5 处。
- `accessDrawn`（L3826，变量）：已画的接入车次（用于命中）。 调用：editor.js L3896、L3911、L3934。
- `access`（L3827，常量）：接入车次计算缓存：`key`、`legs`、`askedMillis`。 调用：editor.js L3825、L3832、L3833 等 11 处。
- `ensureNetworkFor`（L3830，函数）：需要时（最多每 5 秒一次）为某运输方式读取全网图数据（其中有路线路径）。 调用：editor.js L2486、L3845；新页面 `editor-web/src/legacy/dialogs.ts:180`（经 `legacy.fn`）。
- `accessLegs`（L3843，函数）：本线路组运行图上要画的其他线路车次段（按数据版本缓存）。 调用：editor.js L3898；新页面 `editor-web/src/views/RunningDiagram.tsx:387`（经 `legacy.fn`）。
- `findAccessLegs`（L3856，函数）：找出其他路线在本线两相邻车站之间、走本线轨道的区段。 调用：editor.js L3851。
- `drawAccess`（L3895，函数）：在本线车次下方画其他线路的接入车次，并记下位置供提示。 调用：editor.js L4080。
- `hitAccess`（L3932，函数）：5 像素内最近的接入车次。 调用：editor.js L9156。
- `computeLayout`（L3946，函数）：运行图布局：各车站行的 y（按行距权重铺满高度，上下留车厂行空间）、内容高度。新页面为 `core/diagram.ts` 的 `layoutRows`。 调用：editor.js L3990。
- `xOf`（L3959，常量）：运营日时刻 → 运行图 x。 调用：editor.js L3919、L3920、L3922 等 33 处。
- `axisSteps`（L3963，函数）：当前缩放下时间轴网格与标签的步长。 调用：editor.js L4010。
- `millisAt`（L3969，常量）：运行图 x → 运营日时刻。 调用：editor.js L4571、L9017。
- `resizeCanvas`（L3971，函数）：按容器尺寸和设备像素比设置画布。 调用：editor.js L3989。
- `clampView`（L3980，函数）：把时间轴视野和纵向滚动限制在合理范围。 调用：editor.js L3991。
- `draw`（L3987，函数）：画运行图（时间轴、当前时刻、车站行、接入车次、车次、指令、直通、环线、拖柄、所选运用、终点接续、车厂回送、模拟、站名）。新页面中经 `NOTIFY` 包装：运行后调用 `env.render("draw")`，由 `views/RunningDiagram.tsx` 重画。 调用：editor.js L3839、L4575、L4599 等 21 处；新页面 `editor-web/src/legacy/engine.ts:171`、`editor-web/src/legacy/retired.ts:286` 等 4 处（经 `legacy.fn`）。
- `polyline`（L4254，函数）：画经过各点的折线，跨段处的跳转画成竖虚线。 调用：editor.js L3912、L4115、L4123 等 10 处。
- `fitText`（L4272，函数）：截断文字到给定宽度（加 …）。 调用：editor.js L4236。
- `legsDrawn`（L4281，变量）：最后画出的车厂出库/回库回送（用于点中运用）。 调用：editor.js L4207、L4356、L4401 等 5 处。
- `drawTerminalLinks`（L4294，函数）：画每列车在路线终点之后的去向：站台上停留、换站台虚线、或去折返线再回来；玩家设定的更粗。 调用：editor.js L4204。
- `drawDepotLegs`（L4355，函数）：画车厂行和每份运用的出库/回库回送线。 调用：editor.js L4206。
- `hitDepotLeg`（L4445，函数）：点中的出库/回库回送所属运用，无则 null。 调用：editor.js L9099、L9210、L9247。
- `hitService`（L4457，函数）：6 像素内最近的车次。 调用：editor.js L9094、L9154、L9199 等 4 处。
- `segmentDistance`（L4471，函数）：点到线段距离（像素）。 调用：editor.js L3936、L4448、L4461。
- `stopsHtml`（L4477，函数）：车次各站到发时刻的 HTML（提示框用）。 调用：editor.js L4505、L4528、L4529。
- `renderInspector`（L4486，函数）：画检查器：点选/选出入口面板、运用详情、选中车次、模拟问题、环线运行、线路与股道问题、操作提示。新页面改由 `views/Inspector.tsx` 画，本函数经 `RENDERS` 改为通知 React。 调用：editor.js L3137、L3152、L3396 等 8 处；新页面 `editor-web/src/legacy/retired.ts:287`、`editor-web/src/legacy/retired.ts:318`（经 `legacy.fn`）。
- `tooltip`（L4521，常量）：提示框元素。 调用：editor.js L3894、L4524、L4527 等 14 处。
- `showTooltip`（L4522，函数）：显示车次提示（路线、时刻、各站、模拟结论；接入车次标明只读），或隐藏。 调用：editor.js L9135、L9149、L9157 等 4 处；新页面 `editor-web/src/views/RunningDiagram.tsx:168`、`editor-web/src/views/RunningDiagram.tsx:176` 等 3 处（经 `legacy.fn`）。
- `showDragTip`（L4537，函数）：拖动时在指针旁显示移动量。 调用：editor.js L9124、L9137、L9164 等 4 处；新页面 `editor-web/src/views/RunningDiagram.tsx:144`、`editor-web/src/views/RunningDiagram.tsx:156` 等 4 处（经 `legacy.fn`）。
- `centerOn`（L4549，函数）：把车次滚动到视野中（横向居中，必要时纵向）。 调用：editor.js L8230、L8752、L8924；新页面 `editor-web/src/views/Inspector.tsx:202`、`editor-web/src/views/SummaryTable.tsx:155`（经 `legacy.fn`）。
- `zoom`（L4567，函数）：按档位缩放时间轴（以某 x 或中间为锚点），重画。 调用：editor.js L3030、L3031、L3961 等 17 处；新页面 `editor-web/src/views/Layers.tsx:332`、`editor-web/src/views/Layers.tsx:333`（经 `legacy.fn`）。

## Simulated day（第 4578 行起）

服务器按草稿模拟一天（S5）：每个车次怎么跑、出了什么问题。服务器需开启 `editorSimulation`。

- `SIM_LATE_MILLIS`、`SIM_MAX_LISTED`（L4580，常量）：模拟：晚点阈值 `SIM_LATE_MILLIS` 30 s，问题最多列出 `SIM_MAX_LISTED` 300 条。 调用：editor.js L4694、L4718。
- `SIM_KINDS`（L4581，常量）：模拟问题种类 → 中文名。 调用：editor.js L4749。
- `sim`（L4583，常量）：模拟状态（`on`、`request`、`state`、`result`、`trips`、`notRun`、`listed`、`checkDepot`（正在验证运用的车厂）等）。 调用：editor.js L4132、L4507、L4585 等 68 处。
- `simFromNow`（L4585，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：是否从游戏当前列车位置接着模拟（只有今天可以）。 调用：editor.js L4620。
- `setSimulation`（L4587，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：开/关模拟：更新按钮，开时 `simulate()`，关时清结果，重画。 调用：editor.js L6381、L8629、L8831；新页面 `editor-web/src/legacy/dialogs.ts:42`、`editor-web/src/legacy/engine.ts:166` 等 3 处（经 `legacy.fn`）。
- `scheduleSimulation`（L4604，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：草稿、星期或线路变化后 2 秒再模拟（连续改动只模拟一次）。 调用：editor.js L132、L8631；新页面 `editor-web/src/legacy/engine.ts:167`（经 `legacy.fn`）。
- `simulate`（L4610，异步函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：POST `api/simulate`（星期、是否从现在、路线、草稿操作），然后轮询结果。 调用：editor.js L4589、L4594、L4607 等 9 处；新页面 `editor-web/src/legacy/dialogs.ts:42`、`editor-web/src/views/Layers.tsx:326` 等 3 处（经 `legacy.fn`）。
- `pollSimulation`（L4634，异步函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：每 0.5 秒 GET `api/simulate` 直到本请求完成，保存结果并重画。 调用：editor.js L4631。
- `setSimResult`（L4657，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：保存模拟结果，建 `trips`、`notRun`。 调用：editor.js L4645。
- `renderSimStatus`（L4664，函数）：模拟状态文字（`#sim-status`）。 调用：editor.js L4598、L4630、L4651 等 4 处；新页面 `editor-web/src/legacy/engine.ts:173`、`editor-web/src/legacy/retired.ts:278` 等 5 处（经 `legacy.fn`）。
- `simLate`（L4679，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：一站的晚点（`[站, 到, 发, 计划到, 计划发, 通过]`）。 调用：editor.js L4694、L4717；新页面 `editor-web/src/views/RunningDiagram.tsx:741`（经 `legacy.fn`）。
- `formatDuration`（L4680，常量）：毫秒 → "X 分 Y 秒" 或 "Y 秒"。 调用：editor.js L4718、L4748、L5179 等 10 处。
- `drawSimulated`（L4683，函数）：在计划之上画模拟行程虚线，晚点处红色。 调用：editor.js L4208。
- `simTooltip`（L4713，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车次提示中的模拟结论 HTML。 调用：editor.js L4529。
- `dutyCheckText`（L4722，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：正在验证某车厂运用时的一行结论。 调用：editor.js L5920、L8272；新页面 `editor-web/src/views/DutyDetail.tsx:137`（经 `legacy.fn`）。
- `simPanel`（L4738，函数）：检查器中的模拟问题面板 HTML（点击跳到车次）。 调用：editor.js L4507。

## Summary timetable（第 4760 行起）

汇总时刻表的绘制、单元格编辑、多选、平移/排开/插入车次、站台设置菜单、右键菜单。

- `tableRoute`（L4761，变量）：汇总时刻表上次画的是哪条路线（或 `line:<线路组键>`）；换了时 `renderLineTable`/`renderTable` 据此做一次切换处理（细节见源码）。 调用：editor.js L4903、L4905、L4957 等 4 处。
- `showDeadheads`（L4766，变量）：汇总时刻表是否显示运用的回送（运用补完第 3 步）。 调用：editor.js L4767、L4793、L4859 等 7 处。
- `deadheadsByService`（L4772，函数）：当天每个车次接入/离开的出库/回库回送 `{out, back, depot}`。 调用：editor.js L4912。
- `deadheadIndex`（L4790，变量）：`deadheadsByService` 的结果（`renderTable` 时更新）。新页面 `SummaryTable` 读取它。 调用：editor.js L4793、L4912。
- `deadheadNotes`（L4792，函数）：表头里的回送注记 HTML。 调用：editor.js L4802。
- `serviceHead`（L4800，函数）：汇总时刻表一列的表头 HTML（时刻、无车/停运样式、注记）。 调用：editor.js L4879、L4941。
- `timeCell`（L4806，函数）：一个车次在一站的到/发/通过时刻单元格 HTML（可点击输入）。 调用：editor.js L4889、L4950。
- `terminalCell`（L4830，函数）：首站到达/末站出发单元格：跟随前/后一班（`terminalNow`），不可输入。 调用：editor.js L4807。
- `renderLineTable`（L4845，函数）：线路组的各路线按同一方向合成一张表（跨段处标 ↳ 与 ‖）。 调用：editor.js L4917。
- `renderTable`（L4911，函数）：画汇总时刻表（线路组或单路线；工具行、表按钮、最低性能、添加班次、各站行）。新页面为 `views/SummaryTable.tsx`（旧画法开关时仍显示本函数的结果）。 调用：editor.js L4993、L5002、L8187 等 8 处；新页面 `editor-web/src/legacy/engine.ts:172`、`editor-web/src/views/RunningDiagram.tsx:232` 等 3 处（经 `legacy.fn`）。
- `tableRouteOf`（L4965，常量）：汇总时刻表显示的路线：所选路线或线路组第一条。 调用：editor.js L8178、L8870、L8935 等 4 处。
- `editCell`（L4971，函数）：在汇总时刻表单元格中输入（Enter 保存、Esc 放弃、Tab 下一格）。 调用：editor.js L8931、L9001。
- `changeTime`（L5011，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：输入一个车次的时刻：首站出发移动整班；到达/通过为自前一站的运行时间、出发为停站时间，作为本班例外。 调用：editor.js L4992；新页面 `editor-web/src/views/SummaryTable.tsx:54`（经 `legacy.fn`）。
- `laterServices`（L5038，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：同表中晚于该车次的车次。 调用：editor.js L5094、L5269、L9255；新页面 `editor-web/src/views/RunningDiagram.tsx:252`（经 `legacy.fn`）。
- `Batch`（L5041，常量）：`window.TimetableBatch`（`batch.js`）。 调用：editor.js L5053、L5070、L5136 等 6 处。
- `tableRun`（L5042，常量）：与车次同表的所有车次。 调用：editor.js L5053、L5065、L5153。
- `inRange`（L5043，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车次是否在多选范围内。 调用：editor.js L4108、L4865、L4940 等 7 处；新页面 `editor-web/src/views/RunningDiagram.tsx:120`、`editor-web/src/views/RunningDiagram.tsx:439` 等 3 处（经 `legacy.fn`）。
- `rangeServices`（L5044，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：多选范围内的车次。 调用：editor.js L5132、L5276、L5281 等 5 处。
- `chooseRange`（L5047，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：Shift 选择：从上次选中的车次到这一班（同路线同表）。 调用：editor.js L8911、L9225；新页面 `editor-web/src/views/RunningDiagram.tsx:232`、`editor-web/src/views/SummaryTable.tsx:146`（经 `legacy.fn`）。
- `toggleChosen`（L5064，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：Ctrl（⌘）点击：加入或移出一个车次；加 Shift 时整段加入或移出。 调用：editor.js L8903、L9219；新页面 `editor-web/src/views/RunningDiagram.tsx:231`、`editor-web/src/views/SummaryTable.tsx:141`（经 `legacy.fn`）。
- `chooseLater`（L5081，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：选中该车次及同表之后的全部。 调用：editor.js L5292、L9258；新页面 `editor-web/src/views/RunningDiagram.tsx:255`（经 `legacy.fn`）。
- `shiftLaterDialog`（L5093，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：询问平移量，把该车次及之后的同表车次一起平移。 调用：editor.js L5291、L9259；新页面 `editor-web/src/views/RunningDiagram.tsx:256`（经 `legacy.fn`）。
- `parseShift`（L5112，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：解析平移量文字（分钟、秒、m:ss、1m30s 等）→ 毫秒，非法为 null。 调用：editor.js L5095。
- `durationText`（L5125，函数）：时长 → "1 分 30 秒"、"45 秒"、"5 分钟"。 调用：editor.js L3760、L5104、L6034 等 7 处；新页面 `editor-web/src/legacy/dialogs.ts:265`（经 `legacy.fn`）。
- `respaceRange`（L5131，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：把选中的一串车次从第一班起等间隔排开（询问间隔）。 调用：editor.js L5274。
- `addBeside`（L5152，函数）：在某车次旁新建一班（时刻由 `TimetableBatch.besideTime` 算出）。 调用：editor.js L5285、L5286。
- `nextServiceItems`（L5169，函数）：设定"接下一班"的菜单项：到达后从同站出发的车次（同站台优先，最近 8 个），已被别的车次接的注明。 调用：editor.js L5264、L5289。
- `stationNameOf`（L5191，函数）：车站名，`"0"` 为自动选择说明。 调用：editor.js L1523、L1546、L3455 等 7 处；新页面 `editor-web/src/legacy/retired.ts:244`、`editor-web/src/views/DepotPanel.tsx:243` 等 3 处（经 `legacy.fn`）。
- `platformSpeedItems`（L5200，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：站台过站限速菜单（第 3b 轮），写草稿。 调用：editor.js L5349。
- `stopPositionText`（L5214，函数）：站台停车位置文字（第 4 轮）。 调用：editor.js L1542、L5226；新页面 `editor-web/src/legacy/retired.ts:230`（经 `legacy.fn`）。
- `platformStopItems`（L5221，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：站台停车位置菜单（第 4 轮），写草稿。 调用：editor.js L5351。
- `platformStationItems`（L5238，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：站台所属车站菜单（R6）：自动或最近的若干车站，写草稿。 调用：editor.js L5337。
- `cellMenu`（L5256，函数）：汇总时刻表单元格/表头右键菜单：接下一班、调整/删除/停运/恢复选中班次、在左/右新建一列、删除这一班、清除例外、同步时刻改动到之后或选中的班次、清除直通、批量新建直通、把时刻改成整表的/恢复按表、出车车厂/取消出车/取消回送/出库改回首站接入、站台归属车站/过站限速/停车位置等。 调用：editor.js L8952；新页面 `editor-web/src/views/SummaryTable.tsx:164`（经 `legacy.fn`）。

## Loops（第 5356 行起）

环线与直通。

- `isLoop`（L5357，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：首末站同一站台且多于两站即环线。 调用：editor.js L4508、L4952、L5322 等 6 处；新页面 `editor-web/src/views/Inspector.tsx:58`、`editor-web/src/views/SummaryTable.tsx:312`（经 `legacy.fn`）。
- `loopWaitMillis`（L5358，常量）：环线等下一圈的上限（允许长时间停在线上的车厂为 8 天，否则 30 分钟）。 调用：editor.js L5391、L5590。
- `trackTitle`（L5361，函数）：停站所用站台及同站同轨道上其他股道的说明。 调用：editor.js L4952；新页面 `editor-web/src/views/SummaryTable.tsx:308`（经 `legacy.fn`）。
- `loopEntryStop`（L5368，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车厂列车接入环线的停站（未另设时为首站）。 调用：editor.js L4952、L5322、L5323 等 4 处；新页面 `editor-web/src/views/Inspector.tsx:236`、`editor-web/src/views/SummaryTable.tsx:312`（经 `legacy.fn`）。
- `loopRuns`（L5377，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：把运行图的有效接续按列车分成各圈。 调用：editor.js L5395；新页面 `editor-web/src/views/Inspector.tsx:236`（经 `legacy.fn`）。
- `loopPanel`（L5394，函数）：检查器中环线运行面板 HTML。 调用：editor.js L4509。
- `isBoundary`（L5406，常量）：车次能否在该站（首末站以外）接出或接入（时刻边界）。 调用：editor.js L5325、L5330、L8073 等 4 处；新页面 `editor-web/src/views/LinePanels.tsx:125`（经 `legacy.fn`）。
- `stopName`（L5407，常量）：第 `stop` 站的短名。 调用：editor.js L1160、L1400、L1429 等 12 处。
- `setLink`（L5410，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：设置车次直通到另一路线的某车次（替换原有直通；`fromStop` -1 为末站）。 调用：editor.js L5183、L5331、L8108；新页面 `editor-web/src/views/LinePanels.tsx:156`（经 `legacy.fn`）。

## Duties (运用)（第 5424 行起）

运用：取值、编号、编辑、运行日类型、覆盖、终点接续、问题检查、在运行图上分配车次、出入库回送、车厂/车型/运用组对话框与一键生成。

- `sortedObject`（L5425，常量）：按键排序的对象副本。 调用：editor.js L5427。
- `canonDuties`（L5427，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：运用的规范 JSON（固定顺序，用于判断是否改回原样）。 调用：editor.js L5511。
- `dutiesOf`（L5432，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车厂运用：草稿的、设定的，或按时刻表跑且未设定时游戏自动排出的（标 `auto`）。 调用：editor.js L310、L331、L375 等 34 处；新页面 `editor-web/src/views/DepotExtras.tsx:103`、`editor-web/src/views/DepotExtras.tsx:112` 等 15 处（经 `legacy.fn`）。
- `routeIsLoop`（L5439，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：环线：标为顺/逆时针，或首末同站台（2026-11-09）。 调用：editor.js 内无。
- `dutyOn`（L5440，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：运用位在某星期的条目。 调用：editor.js L1723、L3580、L4087 等 21 处；新页面 `editor-web/src/views/DepotExtras.tsx:190`、`editor-web/src/views/DutyBoard.tsx:96` 等 9 处（经 `legacy.fn`）。
- `depotById`（L5441，常量）：按 ID 找车厂。 调用：editor.js L1129、L1489、L1504 等 26 处；新页面 `editor-web/src/legacy/dialogs.ts:236`、`editor-web/src/legacy/retired.ts:275` 等 15 处（经 `legacy.fn`）。
- `segmentKey`（L5442，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：班段键 `路线@车次`。 调用：editor.js L4088、L5607、L5655 等 10 处；新页面 `editor-web/src/views/DutyDetail.tsx:102`、`editor-web/src/views/RunningDiagram.tsx:425`（经 `legacy.fn`）。
- `startOf`（L5446，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：运用的出车方式（S8）："prefer"/"only"/"any"。 调用：editor.js L396、L5449、L5537 等 5 处；新页面 `editor-web/src/views/DutyDetail.tsx:32`（经 `legacy.fn`）。
- `startText`（L5447，常量）：出车方式文字（如"出车：存车线名"）。 调用：editor.js L1726、L8147、L8148 等 4 处；新页面 `editor-web/src/views/DutyBoard.tsx:179`、`editor-web/src/views/Navigator.tsx:307`（经 `legacy.fn`）。
- `newDutyId`（L5454，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：新运用号（与游戏相同的长整数十进制字符串）。 调用：editor.js L395、L5537、L8788；新页面 `editor-web/src/views/DutyDetail.tsx:43`（经 `legacy.fn`）。
- `dutyIdOf`（L5456，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：运用位自己的运用号，无则 null。 调用：editor.js L395、L5477、L5537 等 6 处；新页面 `editor-web/src/views/DutyDetail.tsx:43`、`editor-web/src/views/DutyDetail.tsx:45` 等 3 处（经 `legacy.fn`）。
- `dutyNumber`（L5462，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：玩家看到的运用编号（在车辆池内按存车线顺序连续编号，运用补完第 5 步）。 调用：editor.js L4213、L5706、L5707 等 10 处；新页面 `editor-web/src/views/DepotExtras.tsx:228`、`editor-web/src/views/DutyDetail.tsx:73` 等 4 处（经 `legacy.fn`）。
- `findDutySlot`（L5474，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：按运用号找运用位 `{siding, index, slot}`。 调用：editor.js L5703。
- `relinkOvernight`（L5486，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：重算由前一天过夜车接续的条目（`fromOvernight`）。 调用：editor.js L5509。
- `poolSidings`（L5495，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：同车型的存车线。 调用：editor.js L1731、L1732、L1737 等 7 处；新页面 `editor-web/src/views/DutyDetail.tsx:31`（经 `legacy.fn`）。
- `editDuties`（L5500，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：在草稿中改车厂运用（起点为当前运用副本；排序班段、重连过夜；与游戏相同则删除），保存并重绘。 调用：editor.js L377、L5819、L5857 等 16 处；新页面 `editor-web/src/legacy/dialogs.ts:37`、`editor-web/src/views/DepotExtras.tsx:113` 等 8 处（经 `legacy.fn`）。
- `dayGroup`（L5521，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：与给定星期运行相同表的星期（运行日类型）掩码。 调用：editor.js L5532、L5894；新页面 `editor-web/src/views/DutyBoard.tsx:60`、`editor-web/src/views/DutyDetail.tsx:26`（经 `legacy.fn`）。
- `dutyForEditing`（L5531，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：取当天所属运行日类型的条目用于编辑（必要时从共用条目中拆出）。 调用：editor.js L5822、L5833、L5860 等 12 处；新页面 `editor-web/src/views/DutyBoard.tsx:140`、`editor-web/src/views/DutyDetail.tsx:29`（经 `legacy.fn`）。
- `segmentService`（L5548，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：班段在当天对应的车次。 调用：editor.js L3583、L4372、L4373 等 12 处；新页面 `editor-web/src/views/DepotExtras.tsx:195`、`editor-web/src/views/DutyBoard.tsx:110` 等 6 处（经 `legacy.fn`）。
- `lastStopOf`（L5552，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：班段的末站序号。 调用：editor.js L3585、L5559、L5683 等 7 处；新页面 `editor-web/src/views/DepotExtras.tsx:196`、`editor-web/src/views/DepotExtras.tsx:200` 等 3 处（经 `legacy.fn`）。
- `segmentStart`（L5553，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：班段在起站的到达时刻（无车次为 Infinity）。 调用：editor.js L5508、L5686、L5838 等 5 处；新页面 `editor-web/src/views/DepotExtras.tsx:198`、`editor-web/src/views/DutyBoard.tsx:110` 等 5 处（经 `legacy.fn`）。
- `segmentEnd`（L5557，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：班段在末站的出发时刻（无车次为 -Infinity）。 调用：editor.js L5686、L5911；新页面 `editor-web/src/views/DutyBoard.tsx:110`、`editor-web/src/views/DutyBoard.tsx:185` 等 4 处（经 `legacy.fn`）。
- `coverageNow`（L5566，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：当天按运用跑的车厂负责的路线、以及没有车跑的车次（缓存于 `coverage`）。 调用：editor.js L5577、L5634；新页面 `editor-web/src/views/Inspector.tsx:128`（经 `legacy.fn`）。
- `hasNoTrain`（L5577，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车次当天是否没有车跑。 调用：editor.js L4801、L5636；新页面 `editor-web/src/views/DutyBoard.tsx:104`、`editor-web/src/views/Inspector.tsx:78` 等 4 处（经 `legacy.fn`）。
- `terminalNow`（L5584，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：当天每列车在路线终点之后接哪一班、期间停在哪（`TerminalPairs.pair`，缓存于 `terminal`）。 调用：editor.js L53、L4190、L4828 等 8 处；新页面 `editor-web/src/legacy/retired.ts:361`、`editor-web/src/views/DutyDetail.tsx:102` 等 4 处（经 `legacy.fn`）。
- `platformById`（L5615，常量）：按 ID 取拓扑中的站台，无则 null。 调用：editor.js L5201、L5222、L5239 等 5 处。
- `platformText`（L5616，常量）：站台的"车站名 站台名"。 调用：editor.js L1517、L1542、L1546 等 19 处；新页面 `editor-web/src/views/RunningDiagram.tsx:650`、`editor-web/src/views/StopsEditor.tsx:265`（经 `legacy.fn`）。
- `terminalText`（L5621，函数）：列车在路线终点之后如何继续的一句话。 调用：editor.js L4835、L5911；新页面 `editor-web/src/views/DutyDetail.tsx:113`、`editor-web/src/views/SummaryTable.tsx:83`（经 `legacy.fn`）。
- `coverageText`（L5633，函数）：路线当天有车/没车的车次数，或为何不知道。 调用：editor.js L4512。
- `showNoTrain`（L5642，变量）：是否把没有车跑的车次标红（有车的变淡）。 调用：editor.js L4132、L4142、L4143 等 8 处。
- `showDepots`（L5645，变量）：运行图是否显示车厂行和出入库回送。 调用：editor.js L3952、L4206、L5646 等 6 处。
- `dutyAssignments`（L5649，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：当天每个车次由哪个运用跑（`路线@车次` → `{depot, siding, index}`）。 调用：editor.js L4085、L5568、L5667；新页面 `editor-web/src/views/RunningDiagram.tsx:421`（经 `legacy.fn`）。
- `dutyProblems`（L5664，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：某运用在某天的问题（赶不上、站台对不上、折返/换站台时间不够、停运、过夜接续等）。 调用：editor.js L1725、L5895；新页面 `editor-web/src/views/DutyBoard.tsx:166`、`editor-web/src/views/DutyDetail.tsx:27` 等 3 处（经 `legacy.fn`）。
- `nextSlotOf`（L5719，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：过夜车第二天跑的运用位。 调用：editor.js L5709、L5713、L5927；新页面 `editor-web/src/views/DutyDetail.tsx:66`（经 `legacy.fn`）。
- `dutyEndPlatform`（L5720，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：条目最后一班的终到站台。 调用：editor.js L5707、L5712、L5924；新页面 `editor-web/src/views/DutyDetail.tsx:62`（经 `legacy.fn`）。
- `dutyStartPlatform`（L5724，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：条目第一班的起始站台。 调用：editor.js L5707、L5712、L5924；新页面 `editor-web/src/views/DutyDetail.tsx:62`（经 `legacy.fn`）。
- `waitThresholdMillis`（L5731，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：全网等候分界（草稿优先，运用补完第 2 步）。 调用：editor.js L5743、L6190；新页面 `editor-web/src/views/DepotPanel.tsx:42`（经 `legacy.fn`）。
- `stationWaitDialog`（L5738，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车站自己的等候分界对话框（写草稿）。 调用：editor.js L3085。
- `minReverseMillis`（L5755，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：换端最短时间（草稿优先，缺省 30 s）。 调用：editor.js L5175、L5611、L5691 等 6 处；新页面 `editor-web/src/views/DepotPanel.tsx:41`（经 `legacy.fn`）。
- `turnTimeOf`（L5764，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：实测同站换站台用时（毫秒；null 无路；undefined 未测）。 调用：editor.js L5694、L8420。
- `stopLabel`（L5770，常量；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）："车站短名 站台名"。 调用：editor.js L5695、L5697、L8347 等 4 处。
- `selectSlot`（L5772，函数）：选中/取消选中一个运用位，并切到其第一班的线路组。 调用：editor.js L1765；新页面 `editor-web/src/views/DutyBoard.tsx:172`（经 `legacy.fn`）。
- `lastSlotService`（L5786，变量）：选中运用时上次点击的车次（Shift 连选用）。 调用：editor.js L9202、L9204。
- `chainBetween`（L5792，函数）：当天一列车从一个车次连续跑到另一个车次的车次序列（每次取到站后最早从该站发车的），到不了为空。 调用：editor.js L5813。
- `addRangeToSlot`（L5810，函数）：把两个车次之间的一串车次加入所选运用（两端都已在其中则移出）。 调用：editor.js L9202；新页面 `editor-web/src/views/RunningDiagram.tsx:218`（经 `legacy.fn`）。
- `toggleInSlot`（L5847，函数）：把车次加入或移出所选运用（别的车跑的会移过来；中途接入/离开按直通处理）。 调用：editor.js L9203；新页面 `editor-web/src/views/DutyBoard.tsx:146`、`editor-web/src/views/RunningDiagram.tsx:219`（经 `legacy.fn`）。
- `plannedReturnText`（L5882，函数）：生成的运用预计回到哪条存车线（不是自己的时）。 调用：editor.js L5916；新页面 `editor-web/src/views/DutyDetail.tsx:87`（经 `legacy.fn`）。
- `dutyPanel`（L5889，函数）：检查器中的运用详情面板 HTML。新页面为 `views/DutyDetail.tsx`。 调用：editor.js L4499。
- `legTimes`（L5950，函数）：运用列车离开存车线和回到存车线的时刻（按出入库用时），未知为 "--:--"。 调用：editor.js 内无；新页面 `editor-web/src/views/DepotExtras.tsx:198`（经 `legacy.fn`）。
- `EXCEPTION`（L5960，常量）：运用可单独设置的例外位：`OUT_LEAD` 1、`OUT_PATH` 2、`BACK_SIDING` 4、`BACK_PATH` 8、`WAITS` 16、`ENTRANCES` 32（同 `DepotDuties.DayDuty.EXCEPTION_*`）。 调用：editor.js L5909、L5979、L5986 等 14 处。
- `hhmm`（L5961，常量）：时刻 → "HHMM"。 调用：editor.js L5979、L5986。
- `deadheadTimes`（L5969，函数）：运用的出库/回库回送（运用补完第 3 步）：编号、离开与到达时刻、提前量、出库路线用时、是否例外等。 调用：editor.js L4384、L4781、L5951 等 5 处；新页面 `editor-web/src/legacy/dialogs.ts:255`、`editor-web/src/views/DutyBoard.tsx:111` 等 5 处（经 `legacy.fn`）。
- `editDutyException`（L5995，函数）：把运用自己的回送或等候改为例外（写草稿运用）。 调用：editor.js L6016、L6019、L6031 等 7 处；新页面 `editor-web/src/legacy/dialogs.ts:243`、`editor-web/src/views/RunningDiagram.tsx:191`（经 `legacy.fn`）。
- `deadheadMenu`（L6005，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：运行图上回送线的右键菜单：时间、路径或存车线，或恢复按规则。 调用：editor.js L9250；新页面 `editor-web/src/views/RunningDiagram.tsx:249`（经 `legacy.fn`）。
- `releasePlanDialog`（L6060，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：出入库计划对话框（当天每列车出库、首班、末班、到库）。新页面为 `ReleasePlanPanel`。 调用：editor.js L8692；新页面 `editor-web/src/views/DutyBoard.tsx:46`（经 `legacy.fn`）。
- `placeTrainsDialog`（L6093，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）："按时刻表摆车"确认，确认后 POST `api/place-trains`。 调用：editor.js L8689；新页面 `editor-web/src/views/DutyBoard.tsx:43`（经 `legacy.fn`）。
- `isOrderIssue`（L6106，常量）：排运用问题是否为出/回库车厂指令问题。 调用：editor.js L6388、L6389。
- `orderIssueText`（L6111，函数）：把 `DEPLOY_DEPOT`/`RETURN_DEPOT` 问题写成一句话。 调用：editor.js L6388；新页面 `editor-web/src/legacy/dialogs.ts:50`（经 `legacy.fn`）。
- `shortTurnaroundText`（L6120，函数）：把 `SHORT_TURNAROUND` 问题写成一句话（含站名）。 调用：editor.js L6387；新页面 `editor-web/src/legacy/dialogs.ts:49`（经 `legacy.fn`）。
- `sameVehicle`（L6132，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：草稿车型设置是否等于游戏的。 调用：editor.js L1733、L6290；新页面 `editor-web/src/views/Navigator.tsx:291`（经 `legacy.fn`）。
- `poolFleetText`（L6139，函数）：车辆池配属说明（按给定或存车线相加）。 调用：editor.js L1498、L1734；新页面 `editor-web/src/views/Navigator.tsx:298`（经 `legacy.fn`）。
- `depotDialog`（L6144，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车厂设置对话框（负责线路、长时间停留、配属、存车线、实测余量、全网设置）。新页面为 `views/DepotPanel.tsx`。 调用：editor.js L8686；新页面 `editor-web/src/views/DutyBoard.tsx:41`（经 `legacy.fn`）。
- `depotEntryOf`（L6261，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车厂条目（草稿优先，否则由游戏车厂生成），供修改后 `storeDepotEntry`。 调用：editor.js L6312；新页面 `editor-web/src/views/DepotExtras.tsx:25`、`editor-web/src/views/MoveLinePanel.tsx:26` 等 4 处（经 `legacy.fn`）。
- `storeDepotEntry`（L6285，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：把车厂条目存进草稿，不改变任何东西时删除。 调用：editor.js L6253、L6259、L6354；新页面 `editor-web/src/views/DepotExtras.tsx:55`、`editor-web/src/views/DepotPanel.tsx:93` 等 4 处（经 `legacy.fn`）。
- `vehicleSummary`（L6298，函数）：车型设置一行摘要（加减速、最高速度、手动、种别）。 调用：editor.js L6159；新页面 `editor-web/src/views/DepotPanel.tsx:130`（经 `legacy.fn`）。
- `vehicleDialog`（L6311，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：车厂中某车型的设置对话框（web 分层第 4 步）。新页面为 `VehiclePanel`。 调用：editor.js L8677。
- `plannedReturns`（L6362，常量）：按车厂记录最近生成的运用把列车送回哪条存车线（预测）。 调用：editor.js L118、L5883、L6377。
- `generateDuties`（L6364，异步函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：一键生成运用：确认替换后 POST `api/plan-duties`，写草稿运用，必要时开启模拟验证，汇总提示。 调用：editor.js L8688；新页面 `editor-web/src/views/DutyBoard.tsx:68`（经 `legacy.fn`）。
- `setDutyMode`（L6397，函数）：切换车厂运用方式（`diagram` 按运用图 / `online` 自动排班），写草稿。 调用：editor.js L8693；新页面 `editor-web/src/views/DutyBoard.tsx:70`（经 `legacy.fn`）。
- `changeSlots`（L6403，函数）：修改某存车线的运用位列表（写草稿），选中位越界时清除，重绘。 调用：editor.js L8681、L8683、L8684；新页面 `editor-web/src/views/DutyBoard.tsx:141`、`editor-web/src/views/DutyBoard.tsx:142`（经 `legacy.fn`）。

## New types（第 6412 行起）

（本节标题下没有声明，新种别相关函数在后面的拓扑一节。）


## Topology: how trains run between platforms（第 6414 行起）

站台图（服务器 PlatformGraph）上的股道选择与通路计算，车站配线图，新建种别/线路、停站编辑、停站增删与重编号，线路组/路线配线图，删除种别、直通关系、添加班次。

- `topo`（L6420，变量）：服务器的站台图（PlatformGraph）：每个站台两个方向状态，边连到不掉头能到的下一批站台。新页面为 `core/topology.ts` 的 `Topology`。 调用：editor.js L3126、L3674、L5193 等 57 处。
- `problemDot`（L6423，函数）：树中路线股道问题标记 HTML。 调用：editor.js L1630。
- `buildTopology`（L6430，函数）：由 `data.topology` 建 `topo`（索引、按车站分组）。 调用：editor.js L697。
- `isUsable`（L6442，常量）：状态是否可用。 调用：editor.js L6510、L6529、L6550 等 6 处。
- `TRACK_DIRECTIONS`（L6448，常量）：股道方向名：上行/下行/双向（L2，StationTracks）。 调用：editor.js L6459、L7293。
- `TRACK_SHORT`（L6449，常量）：股道方向短名：上/下/双。 调用：editor.js L6464。
- `trackInfo`（L6450，函数）：股道的方向和种类（手设待避优先）。 调用：editor.js L6458、L6463。
- `trackLabel`（L6457，函数）："上行 3道 正线" 式名称。 调用：editor.js L3231、L5362、L6726 等 9 处。
- `trackShort`（L6462，函数）："上 3 待避" 式短名。 调用：editor.js L6727、L6901。
- `platformLabel`（L6466，常量）：站台名或 "?"。 调用：editor.js L6472、L6726、L6727 等 6 处。
- `stationLabel`（L6467，常量）：站台所属车站短名或"（不属于车站）"。 调用：editor.js L6721、L6851、L6908 等 8 处。
- `stationTracks`（L6469，函数）：同站同轨道网的股道（按名称排序）。 调用：editor.js L6673、L6990、L7072 等 4 处。
- `reachFrom`（L6476，函数）：从一个状态不掉头可达的所有状态及代价（Dijkstra）。 调用：editor.js L6530、L6554、L6578 等 5 处。
- `legCost`（L6527，函数）：两个站台之间的最小代价，不通为 Infinity。 调用：editor.js L6667、L6673、L6703。
- `compareTuples`（L6536，常量）：按 `[换股数, 掉头数, 代价]` 比较。 调用：editor.js L6560、L6568、L6585。
- `solveStops`（L6545，函数）：为一串车站选最佳股道（固定站不变；尽量少换股、少掉头、代价最小）。 调用：editor.js L6664、L6692、L6976 等 5 处。
- `findCorridor`（L6611，函数）：沿轨道从起点经途经站到终点的路（途中掉头只在无路时），返回经过各站的股道。 调用：editor.js L7093、L7269。
- `routeProblems`（L6649，函数）：路线股道问题（到不了、中途掉头、绕远）。 调用：editor.js L4514、L6424、L7406 等 4 处。
- `topologyHtml`（L6689，函数）：站台图上的股道选择器 HTML（每站一列，相邻列间可通行的连线，所选路线加粗）。 调用：editor.js L7129、L7306、L7411。
- `foldOf`（L6743，函数）：连通图中各站的位置（一行；Q 形线回到分出站后的部分在第二行）。 调用：editor.js L6697。
- `loadDiagram`（L6755，异步函数）：POST `api/layout` 读车站周围轨道，游戏未就绪时重试几次。 调用：editor.js L6954、L6962、L7437。
- `lockOffText`（L6783，函数）：车厂列车为何不锁咽喉（不按运用跑，或与这样的车厂共用轨道）。 调用：editor.js L6802、L6817；新页面 `editor-web/src/views/StationTracks.tsx:160`（经 `legacy.fn`）。
- `notConfiguredText`（L6795，常量）：车厂为何不发车（线路时刻表全关、设了运用却不按运用图，或什么都没设）。 调用：editor.js L6802。
- `lockMark`（L6802，常量）：树中车厂的提示标记 HTML（未配置、不锁咽喉等）。 调用：editor.js L1718；新页面 `editor-web/src/views/Navigator.tsx:278`（经 `legacy.fn`）。
- `throatHtml`（L6804，函数）：停站咽喉说明 HTML（进出站道岔组数、联锁区、交叉进路）。 调用：editor.js L6910。
- `turnHtml`（L6825，函数）：首/末站优先股道或轮用折返站台勾选框 HTML。 调用：editor.js L6910。
- `stationDiagramHtml`（L6849，函数）：车站配线图 HTML（旋转后的轨道、站台，可点选股道、缩放）。 调用：editor.js L6734。
- `enableDiagramPanning`（L6914，函数）：配线图拖动平移（位置记在选择器模型中）。 调用：editor.js L7198、L7368、L7434。
- `topologyClick`（L6941，函数）：选择器中的点击：固定/取消股道，切换停车/通过。 调用：editor.js L7213、L7383、L7446。
- `topologyModel`（L6989，函数）：由各站股道建选择器模型。 调用：editor.js L7095、L7271、L7402。
- `orderByLayout`（L6998，函数）：各站股道按左右位置排序。 调用：editor.js L6990。
- `stopsSummaryOf`（L7027，函数）："6 站，停 5 站…" 摘要。 调用：editor.js L7130、L7307、L7412。
- `counterpartOf`（L7033，函数）：同线路组中反方向（首末站互换、站数相同）的路线，优先同种别。 调用：editor.js L7114、L7160；新页面 `editor-web/src/views/StopsEditor.tsx:548`、`editor-web/src/views/StopsEditor.tsx:563` 等 3 处（经 `legacy.fn`）。
- `withSuffix`（L7042，常量）：在名字第一种语言部分后加词（`|` 后的其他语言保留）。 调用：editor.js L7162；新页面 `editor-web/src/views/StopsEditor.tsx:564`（经 `legacy.fn`）。
- `typedName`（L7045，函数）：新种别名：把路线名中的种别词替换，或在后面加上。 调用：editor.js L7162、L7194；新页面 `editor-web/src/views/StopsEditor.tsx:564`、`editor-web/src/views/StopsEditor.tsx:615`（经 `legacy.fn`）。
- `newTypeDialog`（L7055，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：新建种别对话框（沿轨道选起终点与途经站；以某路线为颜色、时刻表、车厂来源）。新页面为 `views/StopsEditor.tsx` 的 `NewType`。 调用：editor.js L8695、L8697、L8728；新页面 `editor-web/src/views/Layers.tsx:300`（经 `legacy.fn`）。
- `newLineDialog`（L7223，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：新建线路对话框（选运输方式、命名、沿轨道选站）。新页面为 `NewLine`。 调用：editor.js L3407、L8657、L8727；新页面 `editor-web/src/views/Layers.tsx:299`、`editor-web/src/views/PickPanels.tsx:63`（经 `legacy.fn`）。
- `platformsOf`（L7388，常量）：各停站所用站台（含草稿中的换股）。 调用：editor.js L6651、L7396、L7986；新页面 `editor-web/src/views/Inspector.tsx:130`、`editor-web/src/views/Navigator.tsx:97` 等 4 处（经 `legacy.fn`）。
- `stopsDialog`（L7395，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线停站对话框（在站台图上选每站股道、停/通过）。新页面为 `RouteStops`。 调用：editor.js L1805、L7970、L8697；新页面 `editor-web/src/views/TrackDiagram.tsx:107`（经 `legacy.fn`）。
- `stopListDialog`（L7456，函数）：路线停站增删排序对话框（立即发给游戏）。新页面为 `StopOrder`。 调用：editor.js L7443。
- `stopHangers`（L7527，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：阻止删除某停站的东西（直通、指令、运用起止）。 调用：editor.js L7501；新页面 `editor-web/src/views/StopsEditor.tsx:235`（经 `legacy.fn`）。
- `renumberTimetable`（L7544，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线停站改变后的时刻表重编号。 调用：editor.js L7603、L7615。
- `renumberLinksInto`（L7562，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：其他路线直通接入本路线的站序重编号。 调用：editor.js L7606、L7620。
- `renumberDuties`（L7575，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：运用中本路线班段的站序重编号。 调用：editor.js L7610、L7625。
- `sendStopList`（L7598，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：把路线新停站发给游戏，并在草稿中做同样重编号；不行时返回原因。 调用：editor.js L7480；新页面 `editor-web/src/views/StopsEditor.tsx:247`（经 `legacy.fn`）。
- `linePieces`（L7654，函数）：线路组配线图中每段车站取自哪条路线的配线图。 调用：editor.js L7690。
- `lineDiagramDialog`（L7689，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：线路组配线图对话框（由各路线配线图拼成）。新页面改为 `views/TrackDiagram.tsx`。 调用：editor.js L8695。
- `lineDiagramHtml`（L7760，函数）：线路组配线图 HTML。 调用：editor.js L7694。
- `routeDiagramDialog`（L7909，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路线配线图对话框（POST `api/route-layout`，可缩放）。新页面改为 `views/TrackDiagram.tsx`。 调用：editor.js L8697。
- `routeDiagramHtml`（L7977，函数）：路线配线图 HTML（拉直的路径、轨道、站台、隔离状态）。 调用：editor.js L7912。
- `deleteTypeDialog`（L8026，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：说明仍引用该种别的东西后，在草稿中标记删除。 调用：editor.js L8698、L8719；新页面 `editor-web/src/views/Layers.tsx:337`（经 `legacy.fn`）。
- `keepType`（L8049，函数）：取消种别的删除标记。 调用：editor.js L8698、L8719；新页面 `editor-web/src/views/Layers.tsx:337`（经 `legacy.fn`）。
- `removeCreated`（L8059，函数）：从草稿中删除一个新建项。 调用：editor.js L8699。
- `throughDialog`（L8065，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：新建直通关系对话框。新页面为 `views/LinePanels.tsx` 的 `Through`。 调用：editor.js L5299、L8730；新页面 `editor-web/src/views/Layers.tsx:302`（经 `legacy.fn`）。
- `addInputs`（L8134，常量）：添加班次输入框的内容（按浏览者保存在 `localStorage`）。 调用：editor.js L4934、L8180、L8181 等 10 处。
- `servicesToAdd`（L8147，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：要添加的车次（起止加间隔，或模式）。 调用：editor.js L8184；新页面 `editor-web/src/views/SummaryTable.tsx:356`（经 `legacy.fn`）。
- `clearTable`（L8160，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：清空一张表的全部车次（进草稿）。 调用：editor.js L8897、L8942；新页面 `editor-web/src/views/SummaryTable.tsx:244`、`editor-web/src/views/SummaryTable.tsx:392`（经 `legacy.fn`）。
- `addServices`（L8177，函数）：汇总时刻表"添加"：按输入添加班次（用 `TimetableBatch.fitSpan` 调整时段内已有车次，多余的先确认删除）。 调用：editor.js L8892、L8995。
- `selectService`（L8224，函数）：选中车次（必要时切到其路线），居中显示，重画。 调用：editor.js L9205、L9237、L9290；新页面 `editor-web/src/views/RunningDiagram.tsx:221`、`editor-web/src/views/RunningDiagram.tsx:238`（经 `legacy.fn`）。

## Banner and status（第 8234 行起）

顶部提示条、同步状态（问题列表与对话框）、报警与路网故障预案（D2）、状态栏、`renderAll`。

- `renderBanner`（L8235，函数）：顶部提示条：连接问题、缺时刻、冲突、应用结果、运用消息等。新页面为 `shell/Banner.tsx`，本函数经 `RENDERS` 改为通知 React。 调用：editor.js L297、L300、L354 等 17 处；新页面 `editor-web/src/legacy/dialogs.ts:20`、`editor-web/src/legacy/dialogs.ts:31` 等 5 处（经 `legacy.fn`）。
- `syncProblems`（L8287，函数）：页面与游戏不一致之处和游戏未做成的事（列表 `{level, text, route?, actions?, recall?}`）。新页面为 `core/sync.ts`。 调用：editor.js L8400、L8427。
- `renderSyncButton`（L8396，函数）：同步状态按钮（问题数、颜色）。 调用：editor.js L678。
- `turnPairsOf`（L8410，函数）：车厂各路线同站换站台的组合及实测用时。 调用：editor.js L8347、L8431。
- `syncDialog`（L8426，函数）：同步状态对话框（问题、收回/预案按钮、换站台用时）。新页面为 `shell/Sync.tsx`。 调用：editor.js L8839。
- `alarmReasonText`（L8469，函数）：列车报警原因（锁死、等进路、晚点及处置状态，D2）。 调用：editor.js L8339、L8343。
- `systemByAnchor`（L8483，常量）：按锚点找路网（D2）。 调用：editor.js L2866。
- `plansByAnchor`（L8488，常量）：锚点对应的预案 `{anchor, plans, adhoc, system}`（路网自己的或接入的）。 调用：editor.js L1484、L8333、L8458 等 4 处。
- `systemOfRoute`（L8496，常量）：路线所在路网。 调用：editor.js L2983、L8499、L8697；新页面 `editor-web/src/views/DispatchConsole.tsx:66`（经 `legacy.fn`）。
- `systemOfLine`（L8497，常量）：线路组所在路网（取其路线中第一个有路网的）。 调用：editor.js L8695。
- `systemTitle`（L8502，函数）：按所含线路给路网命名。 调用：editor.js L1485、L2936、L8307 等 6 处。
- `contingencyMeasureFields`（L8508，函数）：预案或临时处置中"怎么处理列车"的字段 HTML（D2）。 调用：editor.js L8551、L8604。
- `readMeasureFields`（L8525，函数）：从表单读回处置字段（折返方式、折返站、之后、跳站、备车接替），指定站折返未选站时返回错误。 调用：editor.js L8573、L8607。
- `contingencyDialog`（L8539，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：路网故障预案对话框（D2，写草稿）。新页面为 `views/LinePanels.tsx` 的 `Contingency`。 调用：editor.js L8695、L8697。
- `adhocDialog`（L8601，函数）：调度员对单列车的临时处置对话框（D2）。 调用：editor.js L8459。
- `renderStatus`（L8614，函数）：底部状态栏（选中车次、线路数、本线班次、同步时间或未连接）。 调用：editor.js L676、L8640、L8927。
- `renderAll`（L8625，函数）：全部重画：模拟按钮与重新模拟、树、视图、路网、运行图、检查器、汇总时刻表、提示条、状态栏、草稿状态。新页面中经 `RENDERS` 改为 `env.render("renderAll")`，由 `legacy/engine.ts` 的 `renderLegacy` 画旧视图并通知 React。 调用：editor.js L122、L193、L232 等 64 处；新页面 `editor-web/src/legacy/dialogs.ts:56`、`editor-web/src/legacy/dialogs.ts:105` 等 47 处（经 `legacy.fn`）。

## Tree folding（第 8645 行起）

左侧树的折叠。

- `portrait`（L8646，常量）：竖屏且宽度 ≤1100 的媒体查询。 调用：editor.js L1850、L9303、L9309。
- `setTreeOpen`（L8647，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：打开/收起左侧树（`body.tree-closed`），重画运行图。 调用：editor.js L1850、L8710、L8711 等 5 处。

## Events（第 8654 行起）

本节主要是不属于声明的事件注册语句（新页面中同样执行，注册在 `legacy/stage.ts` 舞台上的旧元素上，新页面没有的按钮落在占位元素上不会触发）：
- `#tree` 的 click / contextmenu（右键线路/路线/新种别的菜单）/ keydown；`#btn-tree`（时刻工作区折叠树，媒体库显示时由 `media.js` 处理）、`#scrim`；
- 视图标签 `#tab-diagram`/`#tab-table`/`#tab-network`、`#btn-split`、`#btn-apply`（`applyDraft`）、`#btn-delete`（删除/取消删除种别）、`#btn-new`（新建菜单）、`#banner`（提示条内按钮）、`#inspector` 的 click/change（检查器内表单）；
- 模拟按钮 `#btn-simulate`/`#btn-resimulate`/`#btn-sim-now`、缩放 `#btn-zoom-in`/`#btn-zoom-out`、`#btn-sync`（`syncDialog`）、`#chk-access`、`#chk-depots`、`#btn-no-train`、星期 `#day`；
- `#view-table` 的 click/contextmenu（`cellMenu`）/change/keydown（`editCell`）；
- 运行图画布的 pointerdown/move/up/leave、contextmenu（右键一条线：选中它和之后的班次或一起平移）、wheel；
- 文档 keydown（Esc 等）、`ResizeObserver`、`mtr:workspace`（从媒体库回来时重画并立即问游戏）、每 30 秒重画（当前时刻线）、窗口 resize、竖屏变化、深色模式变化。

- `perfText`（L8958，函数）：表的最低性能显示值：加减速 m/s²、最高速度 km/h，未设为空。 调用：editor.js L4932；新页面 `editor-web/src/views/SummaryTable.tsx:264`、`editor-web/src/views/SummaryTable.tsx:283` 等 4 处（经 `legacy.fn`）。
- `drag`、`lastClick`（L9005，变量）：运行图拖动状态 `drag`、上次点击 `lastClick`（判双击）。 调用：editor.js L1888、L4393、L4536 等 50 处。
- `dragFine`（L9007，变量）：拖动是否按秒取整（按住 Alt）。 调用：editor.js L9016、L9097、L9112。
- `hitHandle`（L9008，常量）：7 像素内的停站拖柄。 调用：editor.js L9093、L9155。
- `dragDwell`（L9014，函数）：拖停站拖柄：单个车次改本班停站时间（例外）；整条路线选中时改表的出发时刻。 调用：editor.js L9114。
- `servicesToMove`（L9033，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：拖动一条线时移动的车次：所在多选范围、选中车次或双击选中整条路线的全部；编辑运用时不移动。 调用：editor.js L9095；新页面 `editor-web/src/views/RunningDiagram.tsx:119`（经 `legacy.fn`）。
- `moveShift`（L9047，函数）：拖动距离 → 时间偏移（按缩放取整，Alt 或放大时按秒）。 调用：editor.js L9122、L9134。
- `moveServices`（L9053，函数；新页面中由 `env` 提供（`gen-legacy` 的 `FROM_ENV`），此处原实现只在旧页面运行）：把一组车次同移相同时间（保持各站和运行时间），写草稿。 调用：editor.js L9193；新页面 `editor-web/src/views/RunningDiagram.tsx:207`（经 `legacy.fn`）。

## Start（第 9306 行起）

启动代码（新页面不生成，由 store 完成）：`setTreeOpen`、初始 `viewStart`、`renderAll`，异步 `loadDraft` 后开始 `poll`；`nowDay` 和 `pickToday` 定义在这里（`gen-legacy` 保留 `nowDay`，排除 `pickToday`）。

- `nowDay`（L9308，常量）：当前运营日编号。 调用：editor.js L4036、L4585、L4674 等 4 处；新页面 `editor-web/src/views/RunningDiagram.tsx:343`（经 `legacy.fn`）。
- `pickToday`（L9317，常量）：首次收到数据后选定"今天"的星期，把视野放在首班前，然后停止（`setInterval` 句柄）。新页面中不生成（`gen-legacy` 排除），由 `pushData` 完成。 调用：editor.js 内无。

## 未确定之处
- 本文档中各函数的说明主要依据其上方注释和开头代码；较长的绘制函数（`draw`、`drawNetworkFrame`、`renderTree`、`renderTable`、`cellMenu`、`depotDialog`、`newTypeDialog`、`newLineDialog`、`lineDiagramHtml` 等）只概述主要内容，未逐行展开。
- 调用统计由脚本按名字匹配得出，像 `key`、`data`、`state` 这类常见名字会把同名局部变量或属性也算进去，个别数字不准。

