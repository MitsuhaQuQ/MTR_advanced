# legacy/retired.ts

"退役"的旧代码函数，现在由新页面的模块实现（退役旧代码第 4–7 步）。这些函数经 `env` 交给旧引擎（原由生成器 `gen-legacy.mjs` 的 `FROM_ENV` 从旧代码里拿掉；第 9 步后旧代码已是源码 `oldEditor.js`，`env` 里的名字直接解构）：旧代码里仍然存在的旧函数、以及新页面自己的调用（`legacy.fn` 找不到时回退到 `env`），全部走新规则。每个函数在规则本身之后保留旧函数原来做的事：保存草稿（`fn.saveDraft`）、重建车次（`fn.buildServices`）、重绘（`fn.renderAll` 等）、表下方的提示（`v.tableMessage`）、以及（第 8 步替换之前的）旧提示框。四个工厂函数都接收 `get: () => Legacy`（取得 `legacy` 的 `{fn, v}`），在 `legacy/engine.ts:118`–`legacy/engine.ts:121` 展开进 `env`。

内部类型 `Legacy = { fn, v }`。导入别名：`T` = `core/timetable.ts`，`S` = `core/services.ts`，`X` = `core/settings.ts`，`P` = `core/dispatch.ts`，`D` = `core/duties.ts`。

## `timetableRules(get)`（第 4 步：时刻表规则）
内部：
- `find(id)`：按 `路线@车次` 找车次。
- `editTimetable(r, change)`：`T.applyEdit(v.draft, r, change)`，然后 `fn.saveDraft()`、`fn.buildServices()`，选中车次已不存在则清空，`v.tableMessage = 提示`，`fn.renderAll()`。
- `times`：一个 `DraftTimes`（`core/draftTimes.ts`），发送函数为 POST `api/times`，回答后重建车次并重绘。

返回的旧函数名 → 实现：
- `timesSource(r)` → `times.source`；`sendTimes()` → `times.flush`。
- `draftOf`、`draftEntry`、`tidyDraft`、`timetableOf`、`servicesToAdd`：绑定 `v.draft`/`v.data` 的 `T` 同名函数；`gameTimetable`、`fromGame`、`normalize`、`setStopException`、`syncExceptions`、`retimeService`、`setSuspended`、`removeService`、`setServiceOrder`、`laterServices`、`parseShift`、`renumberTimetable`、`renumberLinksInto`：直接用 `T` 的。
- `editTimetable`：见上。调用者：`legacy/dialogs.ts:68`、`legacy/dialogs.ts:84`、`legacy/dialogs.ts:95` 等。
- `changeTime(s, stop, kind, time, toLater)`：单元格改时刻（Shift+Enter 时连同之后各班），`editTimetable(T.changeTimeEdit(...))`；改首站时 `T.selectMoved` 重新选中。
- `moveServices(ids, shift)`：拖动移动车次，`editTimetable(T.moveServicesEdit(...))`；成功后选中状态和范围改到新时刻并 `renderAll`。
- `setLink(s, fromStop, target, toStop)`：`editTimetable(T.setLinkEdit(...))`。
- `inRange`、`rangeServices`、`chooseRange`、`toggleChosen`：绑定 `v.state` 的 `T` 函数。
- `chooseLater(s)`：`T.chooseLater`，提示"选中 N 班…"，`renderAll`。
- `servicesToMove(s)`：`T.servicesToMove(v.state, s, canEdit, find)`。

## `dutyRules(get)`（第 5 步：运用规则）
内部：
- `at()`：`{ data, draft, weekday }`。
- `coverageNow()`：`D.coverage` 的结果，缓存在 `v.coverage`（星期变了重算；`pushDraft`/旧 `setData` 清空）。
- `terminalNow()`：`D.terminalPairs` 的结果，缓存在 `v.terminal`。

返回：
- 直接或绑定 `v.draft`/`v.data`/`v.state.weekday` 的 `D` 函数：`canonDuties`、`dutiesOf`、`routeIsLoop`、`dutyOn`、`segmentKey`、`startOf`、`newDutyId`、`dutyIdOf`、`findDutySlot`、`dutyNumber`、`relinkOvernight`、`poolSidings`、`dayGroup`、`dutyForEditing`、`segmentService`、`lastStopOf`、`segmentStart`、`segmentEnd`、`dutyAssignments`、`dutyProblems`、`nextSlotOf`、`dutyEndPlatform`、`dutyStartPlatform`、`minReverseMillis`、`turnTimeOf`、`stopLabel`、`stopHangers`、`renumberDuties`。
- `editDuties(depot, change)`：`v.dutyNotice = D.applyDutyEdit(...)`，`fn.saveDraft()`、`fn.renderAll()`。调用者：`views/MoveLinePanel.tsx:64`、`views/MoveLinePanel.tsx:68` 等（经 `fn`）。
- `coverageNow`、`hasNoTrain(s)`（车次是否无车跑）、`terminalNow`。
- `sendStopList(r, list)`：`D.stopListChange`；返回错误文字则原样返回；否则 `fn.saveDraft()`，异步 POST `api/apply` 发送操作，结果写入 `v.dutyNotice`（成功说明或"停站没改成/发送失败"），`fn.renderBanner()`，`v.revision = null` 并 `fn.poll(true)` 立即重读数据。返回 undefined。调用者：`views/StopsEditor.tsx`（经 `fn.sendStopList`，未逐行确认）。

## `settingsRules(get)`（第 6 步：全网设置）
内部：
- `keysOf(index)`：旧实景地图中轨道序号 → `{ key: v.netRailKey(index), section: v.sectionOf(index) 的各轨道键 }`。
- `done(network = false)`：`fn.saveDraft()`、`fn.renderAll()`；`network` 时还 `fn.renderNetworkTools()`、`fn.drawNetwork()`。
- `platformItems(part, choices)`：返回函数 `(platformId) => 菜单项`：当前值打勾，选中后 `X.setPlatformSetting` 并 `done()`；站台不存在或无编辑权限返回 null。

返回：
- `draftSettings`、`savedSettings`、`compareRailKeys`、`logicalLinesShown`、`usesLogicalLines`、`railLinesShown`、`newLogicalId`、`draftOwnerOverrides`、`savedOwnerOverrides`、`waitThresholdMillis`、`rulesOf`、`sameVehicle`、`depotEntryOf`：绑定后的 `X` 函数。
- `railLogicalLine(index)`、`railLogicalDrafted(index)`、`railOwnersShown(index)`：按轨道序号所在区段调用 `X.sectionLogicalLine`、`X.sectionLogicalDrafted`、`X.sectionOwnersShown`（后者用 `v.net.ownerOf` 中服务器算出的归属）。
- `draftSettingsPart(part, change)`：`X.setSettingsPart` 后 `done(true)`。
- `setRailLogicalLine(index, id)`：`X.setSectionLogicalLine` 后 `done(true)`。
- `setOwnerOverride(index, lines)`：`X.setSectionOwners` 后 `done()`、`fn.drawNetwork()`。
- `platformSpeedItems(platformId)`、`platformStopItems(platformId)`：过站限速、停车位置菜单（停车位置文字用旧 `fn.stopPositionText`）。
- `platformStationItems(platformId)`：站台所属车站菜单："自动"加最近 8 个车站（当前设定的车站不在其中时追加）。
- `storeDepotEntry(depot, entry, sameAdded?)`：`X.storeDepotEntry` 后 `done()`。调用者：`views/MoveLinePanel.tsx:59`、`views/MoveLinePanel.tsx:60`（经 `fn`）。
- `setBoundary(a, b, boundary, wasBoundary)`：用 `v.railKey(a, b)` 得键，`X.setBoundary`，删除该键的 `v.boundaryErrors`，`done()`。

## `dispatchRules(get)`（第 7 步：调度与模拟）
内部：
- `io: SimIO`：`send` → POST `api/simulate`；`fetch` → GET `api/simulate`；`wait` → `setTimeout`。
- `simulate()`：清计时器；有当前线路组或正在验证的车厂且模拟开着时，`P.startSimulation`（操作为 `fn.draftOperations().operations`，即旧代码按草稿生成的操作），`fn.renderSimStatus()`，再 `pollSimulation()`。
- `pollSimulation()`：`P.pollSimulation` 完成后 `renderSimStatus`、`draw`、`renderInspector`、`renderBanner`。
- `scheduleSimulation()`：草稿、星期或线路变化后 2 秒再模拟（连续改动只模拟一次）。调用者：`legacy/engine.ts:167`（`renderLegacy`）。
- `loadConsole()`：GET `api/console` 存入 `v.consoleState.data`（失败为 null），`renderConsole`、`drawNetwork`。

返回：
- `setSimulation(on)`：开/关模拟；开时 `simulate()`，关时 `P.clearSim`；重绘状态、图、检查器。调用者：`legacy/engine.ts:166`。
- `scheduleSimulation`、`simulate`、`pollSimulation`、`loadConsole`。
- `setSimResult`、`simFromNow`、`simLate`、`dutyCheckText`：绑定 `v.sim` 的 `P` 函数。
- `simTooltip(s)`：模拟开着且有结果时返回 `<div class="hint">…</div>`（`P.simServiceText`），否则空串。
- `toggleConsole(open)`：开关调度台：设置 `#net-console-toggle` 的 `aria-pressed`（旧的 `#net-console` 面板已删，列车卡片由 `views/DispatchConsole.tsx` 画）；打开时立即加载并每 `CONSOLE_POLL_MS` 在页面可见且处于路网视图时刷新；关闭时停止。之后 `renderConsole`（只通知新页面重绘）、`drawNetwork`。
- `consoleTrains`、`consoleTrainName`、`consoleTrainState`：`P` 同名函数。调用者：`views/DispatchConsole.tsx:25`、`views/Layers.tsx:154`（经 `fn`）。
- `consoleCommand(button, operation)`：禁用按钮，POST `api/apply`（`P.dispatchOperation(operation)`）；成功按钮文字改"已发送"，0.6 秒后重新加载调度台，返回 true；失败恢复按钮，返回 false（0.77.0）。
- `isLoop`、`loopEntryStop`、`loopRuns`（`pairs` 用 `fn.terminalNow().next`）。
