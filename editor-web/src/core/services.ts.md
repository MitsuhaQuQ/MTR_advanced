# core/services.ts

编辑器显示的数据，由 `api/data` 和草稿生成（退役旧代码第 3 步：旧 `setData` 的线路组、`revisits`、`buildStations`、`toPlans`、`plansFor`、`buildServices`、`findService`、`timesOf`、`currentLine`）。旧引擎现在调用这些（`scripts/gen-legacy.mjs` 的 `FROM_ENV`，经 `legacy/engine.ts` 的 `env` 注入），所以页面只有一套线路、行、车次，尚未移过来的旧规则改的也是同一批对象。`test/services.test.ts` 把旧函数与这些并排运行。

## 常量与类型
- `SECTION_GAP = 1.5`：运行图左侧车站行中，新一段（section）开头上方的间距（以"行"为单位的相对值）。
- `Plan`：某车次在某站的计划：`arrival`、`departure`（自首站发车起的毫秒）、`overtake`（待避）、`passes`（通过）、`tooFast`（时刻比最快运行还快，含义按字段名）。
- `TimesSource`：每个时刻表一项：`times`（各站 `[到, 发, 待避, 通过, 过快]`），`exceptions`（车次 → 该车次自己的各站时刻）。
- `Row`（内部）：车站行 `{ id, name, base }`，`base` 指向同站的第一行（自身即为原始行）。

## 导出函数
- `groupLines(routes)`：按 `lineKey` 分组为线路组 `{key, name, color, routes}`，按名称排序。`src` 中未见调用（旧代码或测试可能使用，未确定）。
- `revisits(stations, platforms)`：对每个停站，若此前到过同一车站，返回 `{ earlier（首次序号）, retrace（是否原路折回：前一站与首次到达时的前/后一站相同）, samePlatform（是否同一站台） }`，否则 null。调用者：本文件 `buildStations`。
- `buildStations(line)`：线路组运行图左侧的车站行，按段排列（见旧 `buildStations`）：最长路线排第一段；支线、两站间另一条路、环线、Q 形线各有处理（Q 形回到分出站的另一站台时，余下部分单独成行；回到原站台时环在上、引入段在下；一端自由、另一端接在别段末端的段会接续过去；挂在别段中间的从所挂车站往下画）。然后为每条路线用动态规划选出各停站所在行（跨段换行代价 100，换到同站另一组行代价 150，同组 1），得到 `r.rows`（到达行）和 `r.leaveRows`（离开行）。最后按各相邻行间最快运行时间（来自 `r.tables[0].plans`）与平均值之比设定行间距 `gap`（一半按比例、一半均匀，段首为 `SECTION_GAP`）。副作用：设置 `line.rows`（`{id, name, section, sectionStart, repeat, gap, group}`）和每条路线的 `rows`、`leaveRows`。调用者：`legacy/engine.ts:106`（作为旧代码的 `buildStations`）。
- `toPlans(times)`：游戏给出的各站 `[到, 发, 待避, 通过, 过快]` → `Plan[]`（后三项为 1 表示真）。调用者：`legacy/engine.ts:112`、本文件 `plansFor`。
- `gameSource(r)`：路线在游戏中的各表时刻作为 `TimesSource`，缓存在 `r.gameSource`。调用者：`core/draftTimes.ts:27`、`core/draftTimes.ts:36`、`legacy/engine.ts:113`。
- `plansFor(r, source, tableIndex, service)`：某表各车次的计划时刻，`service` 非 null 且有例外时用该车次自己的；时刻数与停站数不符（尚未知道）时返回全 0 的计划。调用者：`legacy/engine.ts:111`、本文件 `routeServices`。
- `routeServices(r, timetable, source, weekday, dayStartMillis)`：路线在某星期（0=周一）的车次：对每个当天运行的表，每个车次只取第一个包含它的表；每个车次对象含 `id`（`路线@车次`）、`route`、`service`（游戏日内毫秒）、`table`、`tableIndex`、`link`（直通）、`returns`（回库）、`suspended`（停运）、`deployOrder`/`returnOrder`（出/回库指令）、`start`（运营日内毫秒，使午夜后的末班排在首班之后）、`plans`（有例外用自己的）、`tablePlans`、`exceptions`（站序 → 例外设定的 Map 或 null）。按 `start` 排序返回。调用者：`legacy/engine.ts:109`（`buildServices`）。
- `findService(routeById, id)`：按 `路线@车次` 在 `r.services` 中查找车次，没有为 null。调用者：`legacy/engine.ts:114`、`views/now.ts:17`。
- `timesOf(s)`：车次在各站的 `[到, 发]`（运营日内毫秒）。调用者：`legacy/engine.ts:115`、`views/now.ts:19`。
- `currentLine(data, lineKey)`：在 `data.lines` 中按键找当前线路组，没有为 null。调用者：`legacy/engine.ts:116`、`views/now.ts:18`。
