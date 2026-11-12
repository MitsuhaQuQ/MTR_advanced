# legacy/dialogs.ts

退役旧代码第 8 步：旧页面最后的一些小对话框和 `prompt`/`confirm` 改为新页面的侧面板（`views/AskPanels.tsx` 等，经 `panelHost`），通过 `gen-legacy` 的 `FROM_ENV` 交给旧引擎。每个回答做的事与以前一样；规则在 `src/core`。由 `legacy/engine.ts:122` 以 `dialogRules(() => legacy, target => panelHost.current?.(target))` 展开进 `env`。旧代码和新页面视图都以 `legacy.fn.<名字>` 调用这些函数。

导入别名：`T` = `core/timetable.ts`、`S` = `core/services.ts`、`X` = `core/settings.ts`、`D` = `core/duties.ts`、`L` = `core/stationLayout.ts`。内部类型 `Legacy = { fn, v }`。

## `dialogRules(get, open)`（导出）
- 参数：`get()` 取得 `legacy`；`open(target)` 打开一个 `PanelTarget` 侧面板。
- 返回：旧函数名 → 实现的对象（见下）。

### 内部辅助
- `ask(target)`：打开问答面板 `{kind: "ask", ...target}`（`AskTarget` 见 `legacy/engine.ts`）。
- `find(id)`：按 `路线@车次` 找车次。
- `notice(text)`：`v.dutyNotice = text` 并 `fn.renderBanner()`。
- `depotRequest(path, depot, done)`：POST `path`，body `{depot: id}`；`ok` 时提示 `done`，否则"没有权限，或者游戏里没有这个车厂"；网络失败提示"发送失败"。
- `editTimetable(r, change)`：转给 `fn.editTimetable`（`legacy/retired.ts`）。
- `generate(depot)`（异步）：一键生成运用：提示"正在生成运用…"；POST `api/plan-duties`；没有存车线时提示；否则 `fn.editDuties` 把结果的 `sidings` 写入草稿，记录 `v.plannedReturns`；服务器允许模拟时把 `v.sim.checkDepot` 设为该车厂并开始/重新模拟（S6 内核验证）；把按运营线路联排说明、最多同时在线列车数、无车班次、缺时刻路线、`SHORT_TURNAROUND` 问题（`fn.shortTurnaroundText`）、指令问题（`v.isOrderIssue`/`fn.orderIssueText`）和其他问题拼进 `v.dutyNotice`。失败时提示链接失效或游戏可能已关闭。最后 `fn.renderAll()`。

### 返回的函数
- `shiftLaterDialog(s)`：问"这一班和之后的 N 班平移多少"（分钟或 `90s`、`1:30`、`-0:45`），`T.parseShift` 解析，`editTimetable(T.shiftLaterEdit)`。调用者：`views/RunningDiagram.tsx:256`（经 `fn`）。
- `respaceRange()`：选中范围不少于 2 班时，问相邻间隔分钟数（默认取前两班差），确认时按当时的选中范围 `T.respaceEdit`，完成后清空范围。调用者：旧代码（`src` 中未见直接调用）。
- `clearTable(r, index)`：表有车次时，危险确认"清空…的 N 个班次"，清空范围并 `editTimetable(T.clearTableEdit)`。调用者：`views/SummaryTable.tsx:244`。
- `deleteLogicalLine(line)`：危险确认后 `X.deleteLogicalLine`，`saveDraft`、`renderAll`、`renderNetworkTools`、`drawNetwork`。
- `generateDuties(depot)`：草稿或游戏中已有运用时先确认替换，再 `generate`。调用者：`views/DutyBoard.tsx:68`。
- `replanDialog(depot)`：说明重新排运用（附 `fn.replanText`），确认后 POST `api/replan-duties`。调用者：`views/DutyBoard.tsx:69`。
- `placeTrainsDialog(depot)`：说明"按时刻表摆车"，确认后 POST `api/place-trains`。调用者：`views/DutyBoard.tsx:43`。
- `deleteTypeDialog(r)`：统计跑该种别的车厂、运用中它的班次数、直通到它的班次数并列出影响；危险确认后在草稿条目设 `deleted = true`（非冲突时清错误），`saveDraft`、`renderAll`。调用者：`views/Layers.tsx:337`（经 `fn`，按钮代码，未完全展开确认）。
- `stationWaitDialog(station)`：车站等候分界输入（秒，留空用全网值，0–3600），`X.setStationWait`，`saveDraft`、`renderAll`。调用者：旧代码（`src` 中未见直接调用）。
- `logicalDraftDialog()`：按当前算出的归属生成逻辑线路初稿：归属未读到时 `fn.ensureNetworkFor` 并提示稍后再试；没有任何归属时提示；否则按区段归属的运营线路组合分组，每组建一条逻辑线路（只属一条联络线的组标为联络线、橙色 `0xE8891A`，否则取第一条线路的颜色或灰色，名字由 `fn.ownerText` 拼成），区段写入 `railLines`，各运营线路勾上对应逻辑线路（`lineTracks`），全部经 `fn.draftSettingsPart` 进草稿。调用者：`views/OwnershipPanel.tsx:160`。
- `serviceOrderDialog(s, kind, stop)`：打开 `serviceOrder` 面板（出库/回库指令）。
- `logicalLineDialog(line, then?)`：打开 `logicalLine` 面板（新建时 `lineId` 为 null，`then` 为建好后的回调）。调用者：`views/OwnershipPanel.tsx:146`、`views/OwnershipPanel.tsx:159`。
- `ownersDialog(index)`：打开 `owners` 面板（区段归属）。
- `lineTracksDialog(lineKey)`：打开 `lineTracks` 面板（运营线路走的逻辑线路）。
- `finishPicking()`：打开 `finishPicking` 面板（`views/PickPanels.tsx`）。
- `turnOf(r, kind)`：`L.turnOf(v.draft, r, kind)`。
- `toggleTurnPlatform(r, kind, platformId)`：`L.toggleTurnPlatform`，`T.tidyDraft`，`saveDraft`。
- `deadheadMenu(e, leg)`：运行图上出库/回库回送线的右键菜单：找到车厂、存车线和该天的运用条目（找不到则开空菜单）。出库：出库提前量…（问秒数，不少于出库路线用时，写入 `outLead` 并标 `EXCEPTION.OUT_LEAD`）、出库路径（默认"最近车站后顺线路方向"或车厂规则中命名的出库路径，标 `OUT_PATH`）、按规则恢复（清 `OUT_LEAD|OUT_PATH|ENTRANCES`）。回库：回库存车线（同车型存车线，标 `BACK_SIDING`）、回库路径（标 `BACK_PATH`）、按规则恢复。都可"选中这份运用"（设 `v.state.slot`）。修改经 `fn.editDutyException`。调用者：`views/RunningDiagram.tsx:249`。
