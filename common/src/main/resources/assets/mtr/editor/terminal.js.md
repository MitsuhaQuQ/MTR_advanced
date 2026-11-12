# terminal.js

计算列车在路线终点之后接着跑哪一班、期间停在哪里，与页面分开以便单独检查；游戏的冲突检查以同样方式配对（`transitsim.plan.TerminalPairs`）。顺序：玩家设定的接续（一班从末站直通到另一班首站），然后是运用中前后相连的两班，最后是列车到达后从该站最早发车的一班（推断）。在到达站台原地折返的列车停在该站台直到下一班发车；有轮用折返站台（站前折返）时取第一个空闲的（同游戏中列车的选择）；都不空时用站台外可以等候的折返线（混合折返）。时刻为运营日毫秒。普通脚本（IIFE）：浏览器中为 `window.TerminalPairs`，Node 中为 `module.exports`。编辑器页面（`editor-web/src/core/duties.ts` 的 `terminalPairs`、`views/RunningDiagram.tsx`）和剩余旧代码 `legacy/oldEditor.js`（`MAX_WAIT`）都通过全局使用。旧页面 `editor.js` 已在退役第 9 步删除。

## 常量
- `MIN_TURN = 30000`：列车离开末站到下一班之间的最短时间（规划器的取值）。
- `LONG_WAIT = 5 分钟`：等下一班不少于这么久时，改到空闲的折返线等（混合折返）。
- `MAX_WAIT = 2 小时`：等下一班超过这么久就视为回库。使用者：旧 `editor.js`（已删除）。

## 导出函数
### `pair(input)`
- 输入：
  - `runs`：当天车次 `{ id, routeId, platforms, stations, times: [[到, 发]…], first, last（服务的首末站序）, suspended, returns, deploy, through, terminalPlatforms, loopWait }`；
  - `manual`：车次 → 玩家设定的下一班；
  - `duties`：每列车当天的车次序列；
  - `turnTimes`：站台 → 站台 → 换站台用时（站台到自身为绕站外折返线一圈的时间）；
  - `holdIns`：站台 → 绕折返线一圈中进去那段的时间（第 4 轮；缺省一半）；
  - `holdCounts`：站台 → 站外可同时等候的列车数（折返线与存车位，运用补完第 4 步；缺省 1）；
  - `minTurn`：全网换端最短时间（第 4 轮，缺省 `MIN_TURN`）。
- 过程：
  1. `link(from, to, source)`：两班可接（`from` 终到且未停运/回库、`to` 从首站始发且非出库/停运，均未被占用，同站或同站台）时建立配对 `{ from, to, kind: "same" | "change", source, platform, moved, loop }`；环线续圈时若时间重叠记 `conflict`/`overlap` 和 `LOOP_OVERLAP` 警告。
  2. 依次按玩家设定、运用、就近建立配对。就近：按到达时刻处理每个尚未配对且非直通的终到车次，在同站找可接的始发车次：环线只接同路线、同站台、在等候上限（`loopWait`，缺省 30 分钟）内的下一圈；其他须满足离开 + `minTurn`（+ 换站台用时，没有路则不可）不晚于对方首站到达、等候不超过 `MAX_WAIT`；优先同站台，其次最早发车。
  3. 占用：不在此折返的车次在各站按停站时间占用站台；按到达顺序为每个"同站台"折返安排位置：等候 ≥ `LONG_WAIT` 时先尝试站外折返线（`onRail`：按 `holdIns` 拆分进出时间，检查站台前后空闲与折返线容量 `placesFree`，成功则 `kind = "hold"` 并记 `railStart`/`railEnd`）；否则取第一个空闲的本站台或轮用站台（`moved` 表示换了站台，并给本站台上被挤开的配对计 `displaced`）；再不行用折返线；都不行记 `conflict` 和 `NO_FREE_PLATFORM` 警告。
  4. 有 `displaced` 的配对记 `HOLDS_PLATFORM` 警告（长时间占用站台迫使别的车换站台或进折返线）。
  5. 为每个配对计算 `fromDeparture`、`toArrival`（同站台折返时为下一班发车/本班到达，否则为本班离开/下一班首站到达）。
- 返回 `{ next: Map<车次 ID, 配对>, previous: Map<车次 ID, 配对>, warnings }`。
- 调用者：旧 `editor.js`（已删除）、`editor-web/src/core/duties.ts:214`。

### `loopConnection(arriveX, leaveX, lastY, firstY)`
- 环线续圈的画线：在最后一行等候，再在同一横坐标跳回第一行。`leaveX < arriveX` 时返回 null，否则返回三点折线。调用者：旧 `editor.js`（已删除）、`editor-web/src/views/RunningDiagram.tsx:620`。

## 内部函数
- `add(map, key, entry)`：向 Map 中的列表追加。
- `blockedBy(list, start, end, minTurn)`：与 `[start, end]`（前后加 `minTurn`）时间重叠的占用。
- `pair` 内的局部函数：`ends`、`starts`、`arrival`、`leaving`、`departure`、`firstArrival`、`turnTime`、`isLoop`、`goesRound`、`link`、`free`、`placesFree`、`onRail`（含义见上）。

## 导出
- `api = { MIN_TURN, LONG_WAIT, MAX_WAIT, pair, loopConnection }`：有 `module.exports` 时导出，否则设为 `root.TerminalPairs`。
