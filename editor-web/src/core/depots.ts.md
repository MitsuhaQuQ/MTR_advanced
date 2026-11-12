# core/depots.ts

从 `editor.js` 移来的车厂小工具（`dutiesOf`、`shortfallText`、`replanText`、`depotLines`、`turnTimeOf`、`turnPairsOf` 等）。被 `core/sync.ts`、`core/duties.ts`（转出 `dutiesOf`、`stopLabel`、`turnTimeOf`）、`shell/Sync.tsx`、`views/DepotPanel.tsx` 使用；部分经 `legacy/retired.ts` 作为旧代码的同名函数。

## 导出函数
- `dutiesOf(draft, depot)`：车厂的运用：草稿中有则用草稿的；否则用游戏设定的 `depot.duties`（缺省 `{mode:"online", sidings:{}}`）；若设定不是运用图模式而游戏自动排出的 `depot.autoDuties` 是运用图且非空，则返回自动的并加 `auto: true`。调用者：`core/sync.ts:99`（本文件导入处）、`legacy/retired.ts:124`。
- `shortfallText(shortfall)`：缺车说明：多个车池且有缺口时按车池分别说明（需要几列、现有几列、每天几班没车；`needed < 0` 表示运用不按周重复、算不出）；否则整体说明。再附上无论多少车都跑不了的班次说明（区分"出入库规则未指定车站"和"出不去/折返不过去"），以及建议数量。调用者：`core/sync.ts:112`；视图里的 `fn.shortfallText`（`views/DutyBoard.tsx:74`、`views/Navigator.tsx:280`）调用的是旧代码的同名函数，是否已换成本函数未确定。
- `missingTrains(shortfall)`：缺几列：服务器给了 `missing` 就用它；否则 `needed < 0` 为 -1，其余 `max(0, needed - available)`。`src` 中直接调用未见；`views/Navigator.tsx:282` 用的是 `fn.missingTrains`（未确定来源）。
- `replanText(shortfall)`：解释"运用按原设置排、未自动重排"：把 `shortfall.replan`（`名称:旧:新;…`）中的 `minReverse`（换端最短时间）、`buffers`（实测余量）、`ways`（轨道变了）译成文字。无 `replan` 返回空串。调用者：`core/sync.ts:119`。
- `depotLines(model, depot)`：车厂负责的路线所属线路组 `[lineKey, 线路短名][]`（去重）。调用者：`core/sync.ts:116`；`views/DepotPanel.tsx:235` 的 `fn.depotLines` 来源未确定。
- `sameLine(key1, key2)`：两个线路键（如 `TRAIN|s1|s1`）是否指同一线路：运输方式相同且至少一种语言的名称相同（去空白），与服务器 `DepotRules.sameLine` 一致。调用者：`views/DepotPanel.tsx:284`、`views/DepotPanel.tsx:286`、`views/DepotPanel.tsx:288`。
- `turnTimeOf(depot, from, to)`：实测的同站换站台用时（毫秒）；没量过为 `undefined`，量过但没有路为 `null`。调用者：`core/duties.ts:261`、`legacy/retired.ts:156`、本文件 `turnPairsOf`。
- `stopLabel(stop)`："车站短名 站台名"。调用者：`core/sync.ts:98`、`core/duties.ts:262`、`shell/Sync.tsx:24`。
- `turnPairsOf(model, depot)`：车厂各路线"上一班终点 → 下一班起点"在同一车站但不同站台的组合（去重），附实测用时；未实测时返回空数组。返回 `{from, to, millis}[]`。调用者：`core/sync.ts:98`、`shell/Sync.tsx:24`。
- `alarmReasonText(alarm)`：列车报警原因：锁死 / 等进路 N 分钟 / 晚点 N 分钟，加上跳站、提前折返状态（`turn` 1/2/3）、备车接替状态（`takeover` 1–4）、是否有调度命令。调用者：`core/sync.ts:90`、`core/sync.ts:94`。
