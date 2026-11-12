# batch.js

时刻表批量编辑的计算，与页面分开以便单独检查：在两个时刻之间均匀排车次、把一串车次重新等间隔排开、在一个车次旁边插入新车次、选中一串车次、把一段时间的车次套到新的时刻上。时刻为一天内的毫秒数，过了午夜回绕。普通脚本（IIFE）：浏览器中挂在 `window.TimetableBatch`，Node 中作为 `module.exports`（测试用）。编辑器页面加载它（与构建出的 `index.html` 在同一文件夹，不打包）：`core/timetable.ts` 以 `globalThis.TimetableBatch` 使用，`views/SummaryTable.tsx` 直接调用 `fitSpan`，剩余旧代码 `legacy/oldEditor.js` 以 `const Batch = window.TimetableBatch` 使用（`besideTime`）。旧页面已在退役第 9 步删除。

## 内部
- `DAY`：一天毫秒数；`mod(a, b)`：非负取模。

## 函数（都在导出的 `api` 中）
- `evenTimes(start, end, count)`：从 `start` 到 `end`（含两端）均匀的 `count` 个时刻（取整到秒）；`count` 为 1 时只有起点；`end` 早于 `start` 视为跨午夜；`count < 1` 返回空数组。`editor.js` 与新页面中未找到调用（未确定是否仍在使用）。
- `stepTimes(start, end, step)`：从 `start` 起每隔 `step` 一个时刻，直到 `end`（恰好落在 `end` 上的也算），最多 1000 个；跨午夜同上；`step <= 0` 返回空数组。调用者：旧 `editor.js`（已删除）、`editor-web/src/core/timetable.ts:258`。
- `respace(times, gap)`：保持第一个时刻不变，其余按 `gap` 等间隔排开。调用者：旧 `editor.js`（已删除）、`editor-web/src/core/timetable.ts:209`。
- `besideTime(time, side, before, after)`：在某车次旁插入新车次的时刻：`side` 为 -1 在前、1 在后；该侧有邻车次时取两者中点（取整到秒）；没有时向外取与另一侧邻车次相同的间隔；两侧都没有时 10 分钟。调用者：旧 `editor.js`（已删除）。
- `runBetween(ids, from, to)`：有序车次 ID 中从 `from` 到 `to`（不论先后）的连续一段；任一不存在返回空数组。调用者：旧 `editor.js`（已删除）、旧 `editor.js`（已删除）、`editor-web/src/core/timetable.ts:346`、`editor-web/src/core/timetable.ts:360`。
- `inSpan(time, start, end)`：时刻是否在 `[start, end]` 内（跨午夜同上）。调用者：本文件 `fitSpan`。
- `fitSpan(services, start, end, targets)`：把表中 `[start, end]` 内的车次套到新时刻 `targets` 上（相当于重新按间隔排这一段）：已在新时刻上的保留（`keep`）；其余按顺序移到剩下的新时刻（`moves`，`[from, to]`，往后移的从末尾开始、往前移的从开头开始，避免互相落位）；多出的新时刻新增（`adds`），多出的旧车次删除（`removes`）。返回 `{ keep, moves, adds, removes }`。调用者：旧 `editor.js`（已删除）、`editor-web/src/views/SummaryTable.tsx:362`。

## 导出
- `api = { DAY, evenTimes, stepTimes, respace, besideTime, runBetween, inSpan, fitSpan }`：有 `module.exports` 时导出，否则设为 `root.TimetableBatch`（`root` 为 `window` 或 `this`）。
