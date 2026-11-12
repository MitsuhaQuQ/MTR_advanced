# core/format.ts

编辑器读写名称、时刻、星期的方式（退役旧代码第 2 步：旧的 `clock`、`durationText`、`parseClock`、`typedTime`、`parsePattern`、`formatDuration`、`daysText`、`tableName`、`routeTitle`、`stopName`、`orderDepotName`、`systemTitle` 和星期名）。把运营日时刻与钟点互转的函数接收 `Day`（运营日起点和时区偏移），不再读旧代码的 `data`。`test/format.test.ts` 把旧函数与这些并排运行比较。视图通过 `views/now.ts` 使用它；`core/dispatch.ts`、`core/duties.ts`、`core/timetable.ts`、`legacy/dialogs.ts` 直接导入。

## 转出
- 从 `core/model.ts` 转出 `DAY`、`mod`、`shortName`、`colorOf`、`daysText`、`tableName`、`routeTitle`、`stopName`；从 `i18n.ts` 转出 `trc`。

## 类型
- `Day`：`{ dayStartMillis, offsetMillis }`（毫秒）。

## 导出
- `WEEKDAY_NAMES_ZH`（周一…周日）、`WEEKDAY_SHORT_ZH`（一…日）。
- `weekdayName(day)`、`weekdayShort(day)`：按当前语言的星期名（`day` 0=周一），每次调用都重新翻译。调用者：`core/duties.ts:9`、`views/now.ts`。
- `clock(day, millis, seconds = false)`：运营日内毫秒 → "07:05"，`seconds` 且有秒时 "07:05:30"。调用者：`views/now.ts:234`（行号指 `clock` 包装处，见该文件）、`core/duties.ts`。
- `durationText(millis)`："45 秒"、"3 分钟"、"3 分 20 秒"（四舍五入到秒）。调用者：`core/timetable.ts:177`、`core/timetable.ts:187`。
- `formatDuration(millis)`：满一分钟"X 分 Y 秒"，否则"Y 秒"。调用者：`core/dispatch.ts:8`、`core/duties.ts:9`。
- `parseClock(text)`：解析钟点输入（`07:10`、`7:10:30`、`0710`，支持全角数字和冒号、句点），返回自午夜起的毫秒；小时最大 29；非法返回 null。调用者：`core/timetable.ts:11`、`views/SummaryTable.tsx`（经 `now.ts`）。
- `typedTime(day, text, near)`：输入的钟点 → 离原值 `near` 最近（±半天）的运营日毫秒；非法 null。调用者：`views/now.ts` → `views/SummaryTable.tsx:49`。
- `parsePattern(day, value)`："07:10" 或 "07:10 + 10 * 00:03"（支持全角符号、`×`）→ 车次时刻数组（游戏日内毫秒，减去 `offsetMillis` 并取模）；间隔为 0、数量超过 1000 或格式错返回 null。调用者：`core/timetable.ts:253`、`views/StopsEditor.tsx:571`（经 `now.ts`）。
- `orderDepotName(data, order)`：出/回库指令中的车厂短名；`order.depot === "0"` 或找不到时"自动选的车厂"。调用者：`core/duties.ts:246`、`views/now.ts`。
- `systemTitle(data, system)`：路网标题：所含路线的线路名去重，超过 3 条时"A、B、C 等 N 条线"。调用者：`views/now.ts` → `views/DispatchConsole.tsx`。
