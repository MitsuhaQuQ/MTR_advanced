# core/duties.ts

旧 `editor.js` 的运用（duty）规则（退役旧代码第 5 步）：查找与编号运用、在草稿中编辑车厂运用、运行日类型（day group）、班段对应的车次和时刻、某天哪些车次有车跑（coverage）、路线终点处车次的接续配对（terminal pairs）、运用有什么问题、什么东西阻止删除路线的某个停站、以及路线停站改变后运用和草稿的重新编号。函数接收它们读的数据、草稿和星期；缓存与绘图留给调用方。几乎全部经 `legacy/retired.ts` 的 `dutyRules`（以 `D` 导入）注入旧代码，视图通过 `legacy.fn.xxx` 调用；`core/depotMove.ts` 直接导入其中三个。

术语：一个车厂的运用 `duties = { mode: "online" | "diagram", sidings: 存车线 ID → Slot[] }`；`Slot`（运用位，一列车）是若干"日"条目，每个条目 `days`（星期位掩码，0=周一）、`segments`（班段 `{route, service, from, to}`，`to < 0` 表示到终点）、`stay`（留在线上过夜）、`next`（过夜后第二天接本存车线第几个位，-1 为自己）、`duty`（运用号，十进制字符串）、`nextDuty`（第二天接的别的运用号，"0" 为无）、`returnSiding`、`fromOvernight`、`start`（"prefer"/"only"/"any"）、以及可选的 `waits`、`outLead`、`outEntrance`、`backEntrance`、`outPath`、`backPath`、`exceptions`。

## 转出
- 从 `core/depots.ts` 转出 `dutiesOf`、`stopLabel`、`turnTimeOf`。

## 内部
- `pairs()`：页面全局的 `TerminalPairs`（旧页面脚本 `terminal.js`）。
- `sortedObject(object)`：按键排序的对象副本。

## 导出函数
- `canonDuties(duties)`：运用的规范 JSON（只含 `mode` 和非空存车线，键排序），用来判断草稿是否改回了游戏里的样子。调用者：本文件 `applyDutyEdit`、`legacy/retired.ts:123`。
- `routeIsLoop(r)`：环线：标记为环形（`circular`）或首末同站台，且多于两站。调用者：`legacy/retired.ts:125`。
- `dutyOn(slot, weekday)`：运用位在某星期的条目，没有为 null。调用者：本文件多处、`legacy/retired.ts:126`。
- `segmentKey(segment)`：`路线@车次`。调用者：本文件、`legacy/retired.ts:127`。
- `startOf(slot)`：运用位的出车方式（S8）："prefer"/"only"/"any"，缺省 "prefer"。调用者：`core/depotMove.ts:52`、`legacy/retired.ts:128`。
- `newDutyId()`：新运用号：`1e15` 起的随机长整数的十进制字符串。调用者：`core/depotMove.ts:51`、本文件 `dutyForEditing`。
- `dutyIdOf(slot)`：运用位自己的运用号（任一条目中非 "0" 的 `duty`），没有为 null。调用者：`core/depotMove.ts:51`、本文件。
- `findDutySlot(duties, id)`：按运用号找运用位，返回 `{siding, index, slot}` 或 null。调用者：本文件 `dutyProblems`、`legacy/retired.ts:131`。
- `dutyNumber(draft, depot, sidingId, index)`：玩家看到的运用编号：在所属车辆池内按存车线顺序连续编号（从 1 起）。调用者：本文件、`legacy/retired.ts:132`。
- `relinkOvernight(duties)`：重算 `fromOvernight`：凡被前一天"留在线上过夜且 `nextDuty` 指向它"的条目，在第二天的星期上标记为由过夜车接续（这天不从存车线出车）。直接修改 `duties`。调用者：本文件 `applyDutyEdit`、`legacy/retired.ts:133`。
- `poolSidings(depot, sidingId)`：与该存车线同车型的存车线（列车可回到、可接其运用的）。调用者：`legacy/retired.ts:134`。
- `segmentService(data, segment)`：班段在当前构建日对应的车次对象（`r.services` 中），当天不运行为 null。调用者：本文件、`legacy/retired.ts:143`、`views/DutyBoard.tsx:110`。
- `lastStopOf(segment, r)`：班段的最后一站序号（`to < 0` 时为终点）。
- `segmentStart(data, segment)` / `segmentEnd(data, segment)`：班段在起站的到达时刻 / 在末站的出发时刻（运营日毫秒）；无车次时为 `Infinity` / `-Infinity`。调用者：`legacy/retired.ts:145`、`views/DutyBoard.tsx:110`、`views/DepotExtras.tsx:198`。
- `applyDutyEdit(data, draft, depot, change)`：在草稿中编辑车厂运用：草稿中没有则以 `dutiesOf` 的深拷贝（去掉 `auto`）为起点；执行 `change(duties)`；各条目的班段按开始时刻排序；`relinkOvernight`；清错误；若与游戏中的相同（`canonDuties`）则从草稿删去。直接修改 `draft`。返回 `change` 返回的字符串（提示），否则空串。调用者：`legacy/retired.ts:137`（`editDuties`，随后 `saveDraft`、`renderAll`）。
- `dayGroup(data, draft, depot, weekday)`：运行日类型：与该星期运行车厂各路线相同时刻表的所有星期，返回位掩码。编辑某天的运用会改同组所有天。调用者：本文件、`legacy/retired.ts:141`。
- `dutyForEditing(data, draft, depot, slot, weekday)`：取得该星期所属运行日类型的条目用于编辑：若当前条目恰好覆盖该组则直接返回；否则新建条目（复制班段、过夜设置、运用号、`nextDuty`、`returnSiding`、`start`，以及等候、出库提前、出入口、回送路径、例外），把该组星期从原条目移出（空的删掉），追加到运用位。直接修改 `slot`。调用者：`legacy/retired.ts:142`。
- `dutyAssignments(data, draft, weekday)`：某天每个车次由谁跑：`路线@车次` → `[{depot, siding, index}]`。调用者：本文件、`legacy/retired.ts:150`、`views/RunningDiagram.tsx:421`。
- `coverage(data, draft, weekday)`：某天按运用图跑的车厂负责的路线（`routes`），以及其中有时刻、未停运却没有车跑的车次（`noTrain`）。返回 `Coverage = {weekday, routes, noTrain}`。调用者：`legacy/retired.ts:114`（`coverageNow`，缓存于 `v.coverage`）。
- `minReverseMillis(data, draft)`：换端最短时间：草稿的全网设置优先，其次游戏设置，缺省 30000 ms。调用者：本文件、`legacy/retired.ts:155`。
- `terminalPairs(data, draft, weekday)`：某天每列车在路线终点后接哪一班、期间在哪等（`terminal.js` 的 `TerminalPairs.pair`）：输入为所有有时刻路线的车次（站台、车站、时刻、首末服务站、停运、回库、轮用站台、出库、直通、环线等待上限）、玩家手设的接续（从终点直通到另一路线首站的 `link`）、运用的班段序列、各车厂实测的换站台时间、`holdIns`、`holdCounts`、`minTurn`。返回结果加上 `weekday`。调用者：`legacy/retired.ts:120`（`terminalNow`，缓存于 `v.terminal`）。
- `nextSlotOf(day, index)`：过夜车第二天跑的运用位：`next >= 0` 时为它，否则自己。调用者：`legacy/retired.ts:152`、`views/DutyDetail.tsx:66`。
- `dutyEndPlatform(data, day)` / `dutyStartPlatform(data, day)`：条目最后班段的终到站台 / 第一班段的起始站台 ID。调用者：`legacy/retired.ts:153`、`legacy/retired.ts:154`、`views/DutyDetail.tsx:62`。
- `dutyProblems(data, draft, depot, sidingId, index, weekday)`：某运用在某天的问题，返回 `{i（班段序号，-1 表示整份）, text}[]`：时刻表里没有这一班；线路不在本车厂；停运；出库指令的车次不在运用开头 / 指定了别的车厂出车；同一班分给了多列车；与上一班衔接时间不够、站台对不上、同站台折返不足 `minReverse`、换站台没有路或时间不足（`minReverse + 实测`，环线绕回首站不算换端）；过夜相关：第二天接的运用不存在 / 没有运用 / 不从停着的站台出发 / 两列过夜车接同一个位。调用者：`legacy/retired.ts:151`、`views/DutyBoard.tsx:166`。
- `stopHangers(data, draft, r, stop)`：阻止删除路线某停站的东西（游戏和草稿两边都查）：从该站出发的直通、在该站的出入库指令、其他路线直通接入该站、车厂运用在该站起止。返回去重后的说明列表。调用者：`legacy/retired.ts:158`、`views/StopsEditor.tsx:235`。
- `renumberDuties(duties, routeId, newOf)`：把运用中该路线的班段起止站序按 `newOf`（旧站序 → 新站序）重编；从首站起、到终点止的保持不变。有变化返回深拷贝，否则 null。调用者：本文件 `stopListChange`、`legacy/retired.ts:159`。
- `stopListChange(data, draft, r, list)`：路线的新停站列表（`StopListItem = {platformId, from（旧站序，新加为 -1）, pass}`）。少于两站返回错误字符串。否则生成操作：`stops`、本路线重编后的 `timetable`、其他路线中直通接入本路线的重编 `timetable`、各车厂运用图模式下重编的 `duties`（全部 `force: true`）；并在草稿中做同样重编（本路线草稿时刻表重编、删除其 `platforms`；其他路线草稿中的直通；草稿运用）。直接修改 `draft`。返回 `{ operations }`。调用者：`legacy/retired.ts:163`（`sendStopList`，随后 `saveDraft` 并 POST `api/apply`）。

## 类型
- `Coverage`：见 `coverage`。
- `StopListItem`：见 `stopListChange`。
