# core/model.ts

页面使用的游戏数据模型：`api/data` 加上旧页面 `setData` 建立的查找表（`routeById`、`lines`）。从 `editor.js` 移过来的逻辑，原来读旧代码全局 `data` 的，现在接收一个 `Model`。另有一组与名称、时刻、星期有关的小工具（`core/format.ts` 再转出其中一部分）。被 `state/store.tsx`（建模型）、`core/draft.ts`（改动说明）、`core/format.ts`、`views/RunningDiagram.tsx` 使用。

## 类型
- `Line`：线路组：`key`（`lineKey`）、`name`、`color`（RGB 整数）、`routes`。
- `Model`：`EditorData` 加 `routeById: Map`、`depotById(id)`、`lines: Line[]`，以及其余未定类型的字段（`[other]: any`）。

## 导出
- `buildModel(data)`：建 `routeById`；按 `lineKey` 分组建 `Line`（名称和颜色取该组第一条路线的 `line`/`lineColor`），按名称排序；`depotById` 线性查找。返回 `Model`。调用者：`state/store.tsx:151`。
- `DAY`：一天的毫秒数。
- `mod(a, b)`：非负取模。
- `shortName(name)`：名字写成"中文|English"（或用 `||` 分隔）时取第一段；空时"（未命名）"。
- `colorOf(value)`：RGB 整数 → `#rrggbb`。
- `WEEKDAY_SHORT`（内部）：一…日。
- `clock(model, millis, seconds = false)`：运营日内毫秒 → 当地钟点 "HH:MM"（`seconds` 且有秒时加秒）；计算 `mod(millis + dayStartMillis + offsetMillis, DAY)`。
- `serviceClock(model, service)`：时刻表里的车次时刻（游戏日内毫秒）→ 钟点（带秒）：先减去 `dayStartMillis`。调用者：`core/draft.ts:365`、`core/draft.ts:377`、`core/draft.ts:392`。
- `someServices(model, services)`：前 12 个车次钟点用"、"连接，多于 12 个时加"等 N 班"。调用者：`core/draft.ts:355`、`core/draft.ts:371`。
- `daysText(mask)`：星期位掩码 → "一二三…"（分隔符 `trc("weekday separator", "")`），0 为"不运行"。
- `tableName(table, index)`：时刻表名，空时"表 N"（从 1 起）。
- `setTimeText(seconds)`：停站设定时间：负数为"跟前一站"，否则 "+M:SS"。调用者：`core/draft.ts:360`、`core/draft.ts:361`。
- `routeTitle(r)`：路线短名 + " → 终点站短名"。
- `untimedNotes(routes)`：有时刻表但没有各站时刻的路线，每条一行说明运行线为何没画：找不到路（附 `pathFailure`）或正在向服务端取。调用者：`views/RunningDiagram.tsx:575`。
- `stopName(r, stop)`：第 `stop` 个停站的短名。
