# core/draft.ts

草稿（`api/draft`）及其应用方式，从 `editor.js` 移来（`loadDraft`、`tidyDraft`、`draftOperations`、`applyDraft`、`resolveConflict`、`routeChanges`、`draftChangesDialog`）。草稿的形状与旧页面共享，两边必须读写一致；`test/draft.test.ts` 在同样的草稿上把旧函数和这些并排运行。这里的函数从不修改参数，都返回新草稿，使撤销简单。被 `state/store.tsx`（规整、生成操作、应用结算）、`shell/Review.tsx`、`shell/Banner.tsx`、`legacy/engine.ts`、`core/timetable.ts`、`core/sync.ts` 使用。

## 类型
- `Draft`：草稿。`routes`（路线 ID → 条目：`base` 版本、`parts`、`force`、`error`、`conflict`、`deleted`、`platforms`、`timetable`、`managed`、`connectingLine`、`speedLimit`、`connectingReleased`、`terminalPlatforms`、`startPlatforms`…）；`created`（新建的路线/种别）；`duties`（车厂 ID → `{duties, error?}`）；`boundaries`（"a,b" → 寻路边界设定）；`depots`（车厂 ID → 车厂设置）；`depotRules`（车厂 ID → `{rules}`）；`platformStations`（站台 → 车站）；`platformSpeeds`（站台 → 过站限速 km/h）；`platformStops`（站台 → 停车位置）；`networkSettings`（全网设置或 null）；`contingencies`（路网锚点 → 预案列表）；以及其他任意字段。
- `Owner`：每条操作对应的对象，用于把错误放回：路线 ID 字符串、`created` 中的对象本身，或 `{ depotOf | depotRulesOf | dutiesOf | boundaryOf | contingenciesOf | platformSpeedOf | platformStopOf | networkSettingsOf | platformStationOf: key }`。
- `ApplyResult`：`{ ok, general: 通用错误信息[], applied: 成功项数, created: 新建路线 ID[] }`。
- `DropTarget`：可单独放弃的草稿部分：某路线、第 `index` 个新建项、全网设置、全部边界、某车厂的 `duties/depots/depotRules/contingencies`、全部过站限速/停车位置/站台归属。
- `ChangeItem`：一条改动文字，或 `{ title, lines }`（一个时刻表及其下的改动）。
- `ChangeSection`：审阅面板的一节：`key`、`title`、`error?`、`conflict?`、`items`、`route?`（可跳转的路线）、`drop?`。

## 内部工具
- `clone(value)`：JSON 深拷贝（`undefined` 原样返回）。
- `isObject(value)`：非空对象判断。
- `orderDepotName(model, order)`：出/回库指令的车厂名，`"0"` 或找不到时"自动选的车厂"。
- `poolFleetText(fleet, sum)`：车辆池配属说明：`fleet` 为空时按存车线相加（`sum < 0` 为按需要），负数为按需要，否则 N 列。
- `stopPositionText(position)`：停车位置：0 停在出站端，负数按车长居中，正数离出站端 N 格。
- `topologyOf(model)`：`model.topology`，缺省 `{platforms:[], stations:[]}`。
- `logicalLinesShown(model, draft)`：游戏中的逻辑线路叠加草稿中 `networkSettings.logicalLines` 的增删改后的 `Map<id, line>`。
- `systemTitle(model, system)`：路网标题（同 `format.systemTitle`）。
- `plansByAnchor(model, anchor)`：在 `model.contingencySystems` 中按锚点找路网（或其接入的其他系统 `others`），返回 `{anchor, plans, system}` 或 null。

## 导出函数
- `emptyDraft()`：各部分为空的新草稿。调用者：`state/store.tsx:52`、`shell/Review.tsx:21`。
- `normalizeDraft(saved)`：读来的草稿补齐所有部分（`loadDraft`）：无 `routes` 时视为空；有 `conflict` 无 `error` 的路线补上冲突说明；`created` 不是数组则置空；各对象部分缺失则 `{}`；`networkSettings` 非对象则 null。返回深拷贝。调用者：`legacy/engine.ts:190`、`state/store.tsx:117`、`state/store.tsx:155`。
- `draftCount(draft)`：草稿改动项数（各部分键数之和，全网设置算 1）。调用者：`App.tsx`（顶栏）、`shell/Review.tsx:13`。
- `conflictCount(draft)`：有冲突的路线数。调用者同上。
- `tidy(draft, id)`：某路线条目只剩 `base/force/error/conflict` 时删除它（`tidyDraft`）。`src` 中未找到调用（测试可能用到，未确定）。
- `draftOperations(model, draft)`：草稿 → 发给游戏的操作列表及每条的 `owners`（`draftOperations`）。顺序：
  1. 每条路线（有冲突的跳过，不阻挡其他路线）：`deleted` 只发 `deleteRoute`；否则依次 `platforms`、`timetable`（这两条之后同一路线的操作不再带 `base`，因为版本已变）、`managed`、`connectingLine`、`speedLimit`、`connectingRelease`、`turnPlatforms`（缺的一侧用游戏中的值）。各带 `base`、`parts`、`force`。
  2. `depot`（车厂负责的线路、存车线、车辆池配属等，先于运用）；
  3. `depotRules`（出入库规则，先于按其排的运用）；
  4. `duties`；5. `pathBoundary`（键拆成 `a`,`b`）；6. `contingencies`；7. `platformSpeed`；8. `platformStop`；9. `networkSettings`；10. `platformStation`；11. `newRoute`（新建项）。
  - 返回 `{ operations, owners }`。调用者：`state/store.tsx:161`；旧代码 `fn.draftOperations()`（`legacy/retired.ts:277`，旧代码版本，签名不同）。
- `settleApply(draft, owners, result)`：游戏回答后结算（`applyDraft` 的后半）：按 `result.errors[].index` 找到失败的 owner：车厂/运用把错误写进条目，边界与站台归属的错误单独返回到 `boundaryErrors`（键为边界键或 `station:<站台>`），新建项的 `CONFLICT` 改写为可读说明，路线条目设置 `conflict`（错误为 `CONFLICT` 或 `CONFLICT:<玩家>` 时，`isConflict`）、`conflictBy`（玩家名，0.80.2）和 `error`。成功的路线、新建项、车厂各部分、全网设置、预案、边界从草稿中删去。返回 `{ draft, result: ApplyResult, boundaryErrors }`（`general` 为 `index < 0` 的错误）。调用者：`state/store.tsx:166`。
- `resolveConflict(model, draft, id, keep)`：处理冲突：`keep` 为真时保留草稿并 `force: true`，`base`/`parts` 更新为游戏当前版本，去掉 `conflict`、`error`；否则删除该路线条目。调用者：`shell/Banner.tsx:31`、`shell/Banner.tsx:32`、`shell/Review.tsx:47`。
- `drop(draft, target)`：按 `DropTarget` 放弃一部分草稿，返回新草稿。调用者：`shell/Review.tsx:64`。
- `normalizeTimetable(timetable)`：把时刻表整理成编辑器发送的形式（就地修改并返回）：各表的 `services`、`returns`、`suspended` 升序，`orders` 按车次和种类排序，`links`、`exceptions` 按车次排序，例外内 `stops` 按站序排序。调用者：`core/timetable.ts:54`、`core/timetable.ts:314`。
- `gameTimetable(r)`：路线在游戏中的时刻表，转成编辑器格式（`enabled`、`entryPlatform`、各站 `pass`、`allowLonger`、各表的名称/星期/余量/最低性能/车次/回库/停运/指令/直通/各站设定/例外），并 `normalizeTimetable`。调用者：经 `core/timetable.ts` 转出为 `fromGame`/`gameTimetable`，`core/duties.ts:292`、`core/duties.ts:336`。
- `routeChanges(model, r, entry)`：一条路线草稿相对游戏版本的改动说明（`routeChanges`）：删除、管理权、联络线、限速、联络线放行、轮用站台、优先股道、换股道（站台名由所有路线的停站及候选站台拼出）；有时刻表时比较启用、停站/通过、出库接入站，以及每个表的新建/删除/改名/星期/余量/增删车次/各站到发和待避/直通/回库/停运恢复/出回库指令/例外。返回 `ChangeItem[]`。调用者：本文件 `changeSections`。
- `platformText(model, platformId)`：站台的"车站名 站台名"；同 ID 出现两次时以最后一个为准（同旧页面）；找不到返回 ID。调用者：`core/sync.ts:124`、`core/sync.ts:125`、`core/sync.ts:128`。
- `stationNameOf(model, stationId)`：车站短名；`"0"` 或空为"自动（按车站范围和最近的站台）"；找不到为"已删除的车站"。调用者：`core/sync.ts:134`、旧代码 `fn.stationNameOf`（`legacy/retired.ts:244`，未确定是否为本函数）。
- `ownerText(model, owner)`：区段归属者的名称：`connecting:<路线>` 为"联络线 X"，否则线路组名，找不到时取 `|` 后部分。调用者：本文件；旧代码 `fn.ownerText`（`legacy/dialogs.ts:208`，未确定是否为本函数）。
- `changeSections(model, draft, boundaryErrors = {})`：审阅面板的各节：每条路线（已删除的路线只给放弃；有冲突的不给放弃，只能用冲突按钮）、每个新建项、各路网预案、各车厂设置（负责线路、是否允许线上久等、存车线、车型配属、车型种别）、各车厂运用（模式与运用数）、隔离（寻路边界设/取消段数，附第一个边界错误）、过站限速、全网设置（换端最短时间、等候分界、各站等候分界、逻辑线路增删改、轨道所属逻辑线路、线路组走哪些逻辑线路、区段归属覆盖）、停车位置、站台归属。调用者：`shell/Review.tsx:14`。
- `isConflict(message)` / `conflictBy(message)`（0.80.2）：游戏回的冲突错误 `CONFLICT` 或 `CONFLICT:<玩家>`，后者带出改它的玩家。
- `EDITOR_PARTS`、`changedSince(entry, r)`（0.80.2）：服务端检查的线路部分（时刻表、停站、轻轨设置）；草稿记下的这些部分里有和游戏现在不同的，就是现在写入会冲突。`sync.ts` 用它在写入前提示。
