# views/StationTracks.tsx

停站编辑器里单个车站自己的配线图（取代旧停站对话框的车站部分 `oldStopsDialog`）：游戏中车站周围的轨道，旋转成列车沿所选股道从左向右行驶。本站在路线轨道上的股道可以像停站条一样点击固定股道（`pinTrack`）；其他站台显示为灰色。拖动平移，＋/− 显示更少或更多周围轨道。下方是游戏算出的本站咽喉，以及在首站/末站时的优先出发站台/轮用折返站台。几何计算在 `core/stationLayout.ts`；轨道来自 `api/layout`（游戏还在读地图时重复请求）。由 `views/StopsEditor.tsx` 在选中某站时显示。

## 内部常量与类型
- `T()`：当前拓扑（`topologyOf(legacy.v.data?.topology)`）。`hint`：提示文字样式。
- `Load`：`{ key（请求参数指纹）, data: LayoutData | null, error }`。

## 导出
### `StationTracks({ r, model, i, onPin, onStop, onBack })`（组件）
- props：`r` 路线；`model: StopsModel`（`views/StopsEditor.tsx`）；`i` 第几站；`onPin(p)` 固定或取消本站某股道（站台序号，同停站条）；`onStop(i)` 切到另一站的配线图；`onBack()` 回到车站列表。
- 状态：`margin`（周围显示格数，默认 48，`zoomMargin` 调整）、`pan`。
- 加载：POST `api/layout`（经旧代码 `fn.sendJson`，body `{station, margin, tracks: 本站可选股道 ID}`）；未 `ready` 时每 0.6 秒重试，最多 8 次。
- `solution = T().solveStops(model.layers, model.picks, model.pins)`，用于决定行驶方向和不可行股道。
- 界面：返回按钮、标题、上一站/下一站、放大/缩小；前后站名；`Diagram`；`truncated` 时提示；`Throat`；`TurnPlatforms`。
- 调用者：`views/StopsEditor.tsx:202`。

## 内部组件
### `Diagram({ data, station, tracks, model, i, solution, margin, pan, setPan, onPin })`
- 行驶方向：有解时取到达状态（首站取出发状态）的方向位，`stationView(...)` 得到视图。
- 拖动（不在股道上按下时）按像素换算世界单位平移，松开时累加到 `pan`。
- 画：轨道灰线、道岔点、各站台：本站可选股道（`mine`）可点击/键盘选择 `onPin(index)`，选中股道用路线色加粗，固定的有选择色底带，不可行的画虚线；名称用 `trackShort`，提示用 `trackLabel` 和状态说明；其他站台灰色并注明所属车站或"和这条线的轨道不连"。

### `Throat({ r, i })`
- 本站咽喉（`stop.throat`，游戏按线路路径算出）：进站/出站经过的道岔组数、联锁区数；若某车厂关闭了咽喉锁（`depot.throatLocksOffBy`）显示 `fn.lockOffText(depot)`；与其他路线进路交叉（道岔或联锁区，带坐标）的列表。

### `TurnPlatforms({ r, model, i })`
- 只在首站或末站显示。`kind` 为 `startPlatforms`（首站，优先股道）或 `terminalPlatforms`（末站，轮用折返站台）；当前列表 `turnOf(v.draft, r, kind)`。
- 可选项：本站候选站台（`stop.candidates`），末站不含本线自己的站台。
- 勾选：`toggleTurnPlatform(v.draft, r, kind, id)`、`fn.tidyDraft(r.id)`、`fn.saveDraft()`、`fn.renderAll()`；勾选顺序即尝试顺序，显示"第 N 个"。
- 末站说明折返方式：有站后途经点（`stop.waypoints > 0`）时为站后折返或站前加站后，否则站前折返。
