# views/now.ts

给视图用的 `core/format.ts` / `core/services.ts` 包装：把需要"运营日"（`Day`：运营日起点和时区偏移）或数据的函数绑定到页面当前显示的数据上。在退役旧代码第 3 步把数据模型移过来之前，这份数据是旧引擎的 `legacy.v.data`（`legacy/engine.ts`）。被 `views/` 下多数组件导入。

## 内部
- `day()`：返回 `legacy.v.data`，没有数据时 `{dayStartMillis:0, offsetMillis:0}`。

## 导出
- `clock(millis, seconds = false)`：运营日内时刻 → "HH:MM"（有秒且 `seconds` 时 "HH:MM:SS"）。见 `format.clock`。调用者：`views/AskPanels.tsx:14`、`views/DepotExtras.tsx:12`、`views/SummaryTable.tsx:15`。
- `typedTime(text, near)`：输入的钟点 → 离 `near` 最近的运营日毫秒，失败 null。调用者：`views/SummaryTable.tsx:49`。
- `parsePattern(value)`：如 "07:10 + 10 * 00:03" → 车次时刻数组，失败 null。调用者：`views/StopsEditor.tsx:571`。
- `orderDepotName(order)`：出/回库指令指定的车厂名，或"自动选的车厂"。调用者：`views/SummaryTable.tsx:15`（导入）。
- `systemTitle(system)`：路网标题（按所含线路名）。调用者：`views/DispatchConsole.tsx:11`（导入）。
- `findService(id)`：按 `路线@车次` ID 查车次（`services.findService` 绑定 `legacy.v.data.routeById`）。调用者：`views/SummaryTable.tsx:15`（导入）。
- `currentLine()`：当前显示的线路组（`services.currentLine(legacy.v.data, legacy.v.state.line)`）。调用者：`views/StopsEditor.tsx:21`、`views/SummaryTable.tsx:15`。
- `timesOf`：即 `services.timesOf`（车次在各站的到发时刻）。调用者：`views/DepotExtras.tsx:12`、`views/SummaryTable.tsx:15`。
- `weekdaysShort()`：按当前语言的星期短名数组（一 二 三 …）。调用者：`views/DepotExtras.tsx:206`、`views/DepotPanel.tsx:237`。
- 原样转出 `core/format.ts` 的：`shortName`、`colorOf`、`mod`、`daysText`、`tableName`、`routeTitle`、`stopName`、`durationText`、`parseClock`、`formatDuration`、`weekdayName`、`weekdayShort`、`trc`、`DAY`（含义见 [format.ts.md](../core/format.ts.md)）。
