# core/timetable.ts

在草稿中修改时刻表的规则（退役旧代码第 4 步）：路线在草稿中的条目；改动作用在游戏时刻表的副本上，只在与游戏不同时保留；各种改动本身（某站时刻、移动车次、停运、删除、直通、新增、等间隔排开、清空表）；以及多选车次。这些函数作用于草稿和普通时刻表对象；旧引擎调用它们（`gen-legacy` 的 `FROM_ENV`，经 `legacy/retired.ts` 的 `timetableRules` 以 `T` 导入），并补上改动之后的事（保存草稿、重建车次、重绘）。`test/timetable.test.ts` 与旧函数并排运行。`core/duties.ts`、`core/dispatch.ts`、`legacy/dialogs.ts`、`views/LinePanels.tsx` 也直接使用。

时刻表对象（编辑器格式，见 `core/draft.ts` 的 `gameTimetable`）：`enabled`、`entryPlatform`、`pass[]`、`allowLonger[]`、`tables[]`；每个表 `name`、`weekdays`、`marginPercent`、最低性能、`services`（游戏日内毫秒）、`returns`、`suspended`、`orders`（`{service, kind:"deploy"|"return", stop, depot}`）、`links`（`{service, route, toService, fromStop, toStop}`）、`stops`（每站 `{arrival, departure, overtake}`，设定时间单位为秒，-1 为跟前一站）、`exceptions`（`{service, stops:[{stop, run, dwell, pass, overtake}]}`，-1 为跟表）。

## 转出
- `normalize`（即 `draft.normalizeTimetable`）、`fromGame`（即 `draft.gameTimetable`）。

## 内部
- `Batch()`：页面全局 `TimetableBatch`（旧页面脚本 `batch.js`）。
- `retimeAll(table, services, shift)`：把多个车次同移 `shift` 毫秒（取模一天）：往后移从最晚的开始，往前移从最早的开始，避免互相落位；遇错误返回错误文字。
- `tableRun(s)`：与车次同表的所有车次。

## 草稿
- `gameTimetable(r)`：游戏中的时刻表（编辑器格式），缓存在 `r.gameTimetable`。调用者：`core/duties.ts:292`、`core/duties.ts:336`、`legacy/retired.ts:43`。
- `draftOf(draft, r)`：路线的草稿条目或 undefined。调用者：`legacy/retired.ts:33`、`legacy/retired.ts:40`。
- `draftEntry(draft, r)`：取得或新建路线草稿条目（新建时 `base = r.revision`、`parts` 复制，游戏按部分检查版本）。直接修改 `draft`。调用者：本文件 `applyEdit`、`legacy/retired.ts:41`。
- `timetableOf(draft, r)`：草稿中的时刻表，没有则游戏的。调用者：`core/duties.ts`、`core/dispatch.ts:192`、`legacy/retired.ts:46`。
- `tidyDraft(draft, id)`：条目只剩 `base/force/error/conflict` 时删除（就地修改，与 `draft.ts` 的 `tidy` 同规则但不复制）。调用者：本文件、`legacy/retired.ts:42`。
- `type Change = (timetable) => string | void`：一次改动，返回提示或失败原因。
- `applyEdit(draft, r, change)`：在草稿中改路线时刻表：没有草稿时刻表则从游戏的深拷贝开始；执行 `change`；新改为通过的站，把各表该站时刻置 -1、例外中该站的 `run/dwell/overtake` 置 -1；`normalizeTimetable`；与游戏相同则删去草稿时刻表；非冲突时清错误；`tidyDraft`。直接修改 `draft`。返回提示或空串。调用者：`legacy/retired.ts:23`（`editTimetable`，之后 `saveDraft`、`buildServices`、`renderAll`）。

## 一个表的车次
- `setStopException(table, service, stop, changes)`：改一个车次在一站的 `run`（区间运行秒数）/`dwell`（停站秒数）/`pass`（1 通过、0 停车、-1 跟表）/`overtake`；改成通过且未给 `run` 时 `run` 复位；全为 -1 的站例外删除，空例外删除。调用者：本文件 `changeTimeEdit`、`legacy/retired.ts:48`。
- `syncExceptions(table, from, services)`：让这些车次的各站例外与 `from` 相同（先删它们的例外，再复制）。返回提示。调用者：`legacy/retired.ts:49`。
- `retimeService(table, oldService, newService)`：车次改时刻，连同直通、回库、停运、指令、例外一起改；目标时刻已有车次时返回错误文字。调用者：本文件、`legacy/retired.ts:50`。
- `setSuspended(table, services, suspended)`：设为停运或恢复（只保留表中存在的车次，排序）。返回提示。调用者：`legacy/retired.ts:51`。
- `removeService(table, service)`：删除车次及其直通、回库、停运、指令、例外。调用者：`legacy/retired.ts:52`。
- `setServiceOrder(table, service, kind, stop, depot)`：设置或清除出库（`deploy`）/回库（`return`）指令；`depot` 为 `"0"` 表示任意车厂，`null` 表示清除。调用者：`legacy/retired.ts:53`。

## 改动（返回 `Change` 的工厂函数等）
- `laterServices(s)`：同表中运营日内晚于该车次的车次。调用者：本文件、`legacy/retired.ts:54`。
- `changeTimeEdit(s, times, stop, kind, time, services)`：单元格输入时刻：首站出发→移动车次（`retimeAll`）；`kind === "dep"`→设该站停站秒数；否则（到达）→设从上一站起的运行秒数（至少 1）。`services` 为这一班或连同之后各班。多班时返回提示。调用者：`legacy/retired.ts:64`（`changeTime`）。
- `selectMoved(state, r, s, shift)`：首站时刻改后，按新时刻重新选中该车次（改 `state.selected`）。调用者：`legacy/retired.ts:65`。
- `moveServicesEdit(tableIndex, services, shift, blockedText, onBlocked)`：多个车次同移 `shift`（全移或都不移）：会落在不移动的车次上时调用 `onBlocked` 并返回"已经有一班"。成功返回"N 班推后/提前了 X"。调用者：`legacy/retired.ts:81`（`moveServices`）。
- `shiftLaterEdit(s, shift)`：该车次及同表之后各车次同移。调用者：`legacy/dialogs.ts:68`。
- `parseShift(text)`：解析时间偏移为毫秒：分钟（`5`、`-2.5`、`5m`、`5分`）、秒（`90s`、`90秒`）、`m:ss`、`1m30s`/`1分30秒`；无法解析为 null。调用者：`legacy/retired.ts:55`。
- `respaceEdit(run, minutes, done)`：把选中的一串车次从第一班起按 `minutes` 等间隔排开（目标时刻由 `TimetableBatch.respace` 算出），往后移的从尾部、往前移的从头部依次改；完成后调用 `done`。调用者：`legacy/dialogs.ts:84`。
- `setLinkEdit(s, fromStop, target, toStop)`：设置车次在某站（或终点）直通到另一路线的车次 `target`；直通后不再回库。调用者：`legacy/retired.ts:89`（`setLink`）。
- `clearTableEdit(index, tableName)`：清空某表的车次、直通、回库、停运、指令、例外（表本身、各站设定和星期保留）。调用者：`legacy/dialogs.ts:95`。
- `servicesToAdd(day, startText, endText, intervalText)`：要新增的车次：从开始每隔间隔到结束（落在结束上的也算，`TimetableBatch.stepTimes`）；只填开始时为那一班；结束和间隔都空时按开始的模式（`parsePattern`）。间隔为分钟数或 `04:30`（分:秒），至少 1 秒。返回游戏日内毫秒数组或 null。调用者：`legacy/retired.ts:58`、`views/SummaryTable.tsx:356`（经 `fn`）。
- `liftCommonPasses(tt)`：（0.65.27）所有有车次的表中每个车次都靠例外通过的中间站，提升为路线本身的通过站：设 `pass[stop] = true`，清该站各表时刻和例外的 `pass`，删去变空的例外。首末站不处理；没有车次的表不参与。直接修改 `tt`。返回被提升的站序。调用者：`views/LinePanels.tsx:364`。
- `renumberTimetable(tt, list, newOf)`：路线停站改变后的时刻表（深拷贝）：`pass`、`allowLonger` 按新列表；各表站设定按 `from` 取旧值（新加的站或由停车改为通过的站置 -1）；指令、直通起站、例外按 `newOf`（旧 → 新，-1 为删除）重编，删除的去掉；改为通过的站其例外的 `run/dwell/overtake` 置 -1；最后 `normalizeTimetable`。调用者：`core/duties.ts:336`、`legacy/retired.ts:56`。
- `renumberLinksInto(tt, routeId, newOf)`：其他路线直通接入本路线的 `toStop` 重编；有变化返回副本，否则 null。调用者：`core/duties.ts:339`、`legacy/retired.ts:57`。

## 选择车次
- `type Choice`：旧页面状态中的选择：`selected`（选中车次）、`selectedRoute`（整条路线选中）、`range`（`{anchor, ids, toggled?}`）、`slot?`。
- `inRange(state, s)`：车次是否在选中范围内。调用者：本文件、`legacy/retired.ts:91`。
- `rangeServices(state, find)`：范围内的车次对象。调用者：本文件、`legacy/retired.ts:92`。
- `chooseRange(state, s, find)`：Shift 点击：从上次选中的车次到这一班（同路线同表才行，`TimetableBatch.runBetween`），否则清空范围。返回是否选中。调用者：`legacy/retired.ts:93`、`views/RunningDiagram.tsx:232`。
- `toggleChosen(state, s, span, find)`：Ctrl 点击：加入或移出一个车次；加 Shift（`span`）时从上次 Ctrl 点的车次到这一班整体加入或移出（这一班原已选中则移出）。更新 `range`、`selected`，清 `selectedRoute`。返回 true。调用者：`legacy/retired.ts:94`、`views/RunningDiagram.tsx:231`。
- `chooseLater(state, s)`：选中该车次及同表之后的全部。返回数量。调用者：`legacy/retired.ts:97`。
- `servicesToMove(state, s, canEdit, find)`：拖动时要移动的车次 ID：在选中范围内则为范围内没有单独例外的车次（被拖的那班除外），整条路线选中则同表无例外的车次，否则仅被拖的；正在编辑运用位（`state.slot`）或无编辑权限时 null。调用者：`legacy/retired.ts:101`。
