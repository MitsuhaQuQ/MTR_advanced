# core/dispatch.ts

旧 `editor.js` 的调度与模拟部分（退役旧代码第 7 步）：服务器按草稿模拟一天（发请求、等回答、出了什么问题、车厂运用跑得如何）、调度台（某路网的列车、每列车的状况、发送命令）、环线列车如何循环。状态保存在传入的对象里（页面的 `sim` 与调度台状态），向游戏发请求和绘图由调用方负责。主要经 `legacy/retired.ts` 的 `dispatchRules`（以 `P` 导入）注入旧代码，视图 `views/Inspector.tsx` 也直接导入。

## 常量与类型
- `SIM_LATE_MILLIS = 30000`：超过即算晚点（毫秒）。`SIM_MAX_LISTED = 300`：模拟问题最多列出条数（`views/Inspector.tsx:210`）。
- `SIM_KINDS`：模拟问题种类 → 中文名：`LATE` 晚点、`WAITING_FOR_PLATFORM` 等站台、`HELD_ON_LINE` 区间扣停、`LONG_DWELL` 站内久停、`DEADLOCK` 锁死、`NOT_RUN` 未开行（`views/Inspector.tsx:216`）。
- `FIRST_DAY_WEEKDAY = 3`：游戏日编号 0 对应的星期（0=周一，所以是周四）。
- `Sim`：页面的模拟状态：`on`、`request`（请求号）、`state`（`idle`/`running`/`done`/`busy`/`permission`/`error`/`disabled`…）、`result`、`trips`（`路线@车次` → 行程）、`notRun`、`listed`、`madeAt`、`line`、`weekday`、`timer`、`polling`、`notSimulated`、`ignored`、`missingPaths`、`scopeDepots`、`allDepots`、`checkDepot`（正在验证运用的车厂）、`fromNow`。
- `SimIO`：`send(body)` 发起模拟、`fetch()` 查询状态、`wait(ms)`。
- `ConsoleState`：调度台：`open`、`data`、`timer`、`system`（所选路网锚点）、`selected`（所选列车）。`CONSOLE_POLL_MS = 2000`：调度台刷新间隔（`legacy/retired.ts:338`）。
- `MAX_LAYOVER_MILLIS = 30 分钟`、`MAX_STAY_MILLIS = 8 天`：环线折返/停留上限。

## 模拟
- `newSim()`：初始 `Sim`。`src` 中未见调用（旧代码可能使用，未确定）。
- `clearSim(sim)`：关模拟时清空状态、结果、行程、未开行、列表、`checkDepot`。调用者：`legacy/retired.ts:315`。
- `nowDay(data)`：当前运营日编号（自 `dayStartMillis` 起的天数；无数据时按纪元）。调用者：`views/RunningDiagram.tsx:343`（经 `fn.nowDay`）。
- `simFromNow(sim, data, weekday)`：只有"今天"能从游戏里列车的当前状态接着模拟（G0）。调用者：`legacy/retired.ts:277`、`legacy/retired.ts:324`。
- `simRoutes(line, checkDepot)`：要模拟的路线：当前线路组的，加上正在验证运用的车厂的。调用者：`legacy/retired.ts:277`。
- `startSimulation(sim, io, ask)`：记下线路与星期，`io.send({weekday, fromNow, routes, operations})`；回答有 `error` 则状态为它，否则记下 `request` 并置 `running`；异常为 `error`。调用者：`legacy/retired.ts:277`。
- `pollSimulation(sim, io)`：正在轮询则返回 false；否则在 `sim.on && state==="running"` 期间每 0.5 s `io.fetch()`：别的页面发起了更新的请求则置 `idle`；本请求有结果时复制状态与统计，`done` 时 `setSimResult`。异常置 `error`。返回 true。调用者：`legacy/retired.ts:283`。
- `setSimResult(sim, result, now)`：保存结果和时间，建 `trips` 和 `notRun`（`NOT_RUN` 问题）。调用者：本文件、`legacy/retired.ts:323`。
- `simStatusText(sim)`：模拟状态的简短文字（模拟中/按某时刻的草稿模拟/忙/无权限/失败/未开启），关着时空串。`src` 中未见直接调用（未确定）。
- `simLate(stop)`：`stop` 为 `[站序, 到, 发, 计划到, 计划发, 通过]`（运营日毫秒，-1 为无），返回到/发晚点的较大者。调用者：`legacy/retired.ts:325`、`views/RunningDiagram.tsx:741`（经 `fn.simLate`）。
- `dutyCheckText(sim, depot)`：正在验证该车厂运用时的一行结论（验证中 / 找不到路径的运用数、未开行、晚点、锁死次数 / 全部开行准点）。调用者：`legacy/retired.ts:327`、`views/DutyDetail.tsx:137`。
- `simServiceText(sim, s)`：某车次在模拟中的一行结论：没车跑 / 最多晚多少 / 准点。调用者：`legacy/retired.ts:326`（`simTooltip`）。
- `simProblems(sim, data, line, checkDepot)`：当前线路组（及验证车厂的路线）上的模拟问题（锁死不论在哪都列），以及未模拟内容的说明（改站序/新建删除种别未模拟、草稿对不上、找不到路径、只模拟了部分车厂）。返回 `{ routes, problems, notes }`。调用者：`views/Inspector.tsx:196`。

## 调度台
- `consoleTrains(data, consoleState)`：调度台数据中的列车，选了路网时只取该路网路线上的。调用者：`legacy/retired.ts:346`、`views/DispatchConsole.tsx:25`、`views/Layers.tsx:154`（后两者经 `fn`）。
- `consoleTrainName(train)`：列车名："存车线 车 N"（`slot < 0` 时"存车线的车"）。调用者：`legacy/retired.ts:347`、`views/DispatchConsole.tsx:74`、`views/Layers.tsx:155`。
- `consoleTrainState(train)`：列车状况：不在运用中、晚点、等进路、`dispatch` 位（1 扣停、8 延长停站、16 将在指定站折返、2 下一站清客、4 本段跑完回库）、跳站、已提前折返、已交给备车、不载客，用"，"连接。调用者：`legacy/retired.ts:348`、`views/DispatchConsole.tsx:62`。
- `dispatchOperation(operation)`：包装成 `{ operations: [{ type: "dispatch", ...operation }] }`。调用者：`legacy/retired.ts:352`（发往 `api/apply`）。

## 环线
- `isLoop(r)`：多于两站且首末站同一站台。调用者：`core/duties.ts:193`、`core/duties.ts:252`、`legacy/retired.ts:359`。
- `loopWaitMillis(data, r)`：环线允许的等待：有车厂把它设为"允许长时间停在线上"（`cycleRoutes`）时 8 天，否则 30 分钟。调用者：`core/duties.ts:193`。
- `loopEntryStop(draft, r)`：车厂列车接入环线的停站：时刻表 `entryPlatform` 对应的中间站且不是通过站，否则首站 0。调用者：`legacy/retired.ts:360`、`views/Inspector.tsx:236`、`views/SummaryTable.tsx:312`。
- `loopRuns(data, r, pairs)`：按运行图同样的有效接续（`pairs` 中 `loop` 且不 `overlap` 的）把未停运车次串成每列车的若干圈。返回 `{ next（车次 → 下一圈）, trains（每列车的圈列表）, maxWait }`。调用者：`legacy/retired.ts:361`（`pairs` 为 `terminalNow().next`）。
