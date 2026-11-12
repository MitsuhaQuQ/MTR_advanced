# views/StopsEditor.tsx

一个编辑器同时处理路线的停站和新建线路组/种别，显示在页面旁（新页面中取代旧的停站对话框、停站列表对话框和新建线路对话框；旧代码对 `stopsDialog` / `newLineDialog` / `newTypeDialog` 的调用经 `legacy/engine.ts` 的 `stopsHost` 来到这里）。
- 路线在左侧画成路线色的竖线，每站一个点：停车实心，通过空心。每站按配线图的左右顺序并排列出股道，用到的那股用路线色标出。点一股把它固定，其他站换到仍走得通、改动最少的股道（旧求解器 `solveStops`）；没有通路可用的股道变淡。站与站之间显示距离或"走不通"；在站掉头会标出。
- "增删和排序停站…"切换到增加、删除、重排车站，像以前一样立即发给游戏（`sendStopList`）。
- "站内配线…"在同一面板里显示该站自己的配线图（`views/StationTracks.tsx`），含咽喉和折返站台；在那里固定股道与在条带上相同。

规则来自 `core/topology.ts`（`T()`），写草稿经旧代码函数（`fn.draftEntry`、`fn.tidyDraft`、`fn.editTimetable`、`fn.saveDraft`、`fn.renderAll` 等）。由 `App.tsx` 常驻挂载。

## 类型与内部常量
- `T()`：当前拓扑 `topologyOf(legacy.v.data?.topology)`。
- `StopsModel`（导出）：旧代码的选择器模型（`topologyModel`）：`layers`（每站可选股道的站台序号）、`picks`、`stop`（是否停车）、`pins`（固定的股道）、`color`、`conflict`（固定股道连不起来时的说明）。使用者：`views/StationTracks.tsx`。
- `StationChoice`（内部）：新建线路时的车站选项 `{ key: 车站|线网, station, network, track（该站某股道序号）, name }`。
- `MODE_NAMES`：运输方式中文名。

## 导出
### `StopsEditor()`（组件）
- 挂载时设置 `stopsHost.current = setTarget`。按 `StopsTarget`：`route` → `RouteStops`；`type` → `NewType`；`new` → `NewLine`。
- 调用者：`App.tsx:78`（`<StopsEditor />`）。

### `pinTrack(model, i, p)`
- 固定第 `i` 站的股道 `p`（已固定在此则取消），再用 `solveStops` 让其他站换到最佳可行股道；无解时只改这一站并设置 `conflict`（列出固定的车站）。返回新模型（不修改原模型）。调用者：本文件 `Strip`、`views/StopsEditor.tsx:202`（`StationTracks` 的 `onPin`）。

## 内部函数与组件
- `analyse(model)`：各区间代价 `legs`（`legCost`，无路为 `Infinity`）、掉头站 `reversals`（仅当求解结果与当前选择一致时）、各站可行股道 `feasible`。
- `Strip({ model, setModel, original, onStation })`：车站条带：每站的点与竖线、站序、站名、"改了股道"（与游戏 `original` 不同）/"掉头"/"固定"标签、"站内配线…"（有 `onStation` 时）、停车/通过切换（首末站固定停车）、股道按钮（点击 `pinTrack`；名称 `trackShort`，提示 `trackLabel`）、区间距离"↓ N 格"或"沿轨道走不通"。
- `modelOf(r)`：由路线草稿中的站台（`fn.platformsOf(r)`）和通过设置（`fn.timetableOf(r).pass`）建模型；有站台不在轨道上时返回 null。
- `RouteStops({ routeId, stop, onClose })`：路线停站面板：
  - 初始可直接打开第 `stop` 站的配线图（`station` 状态）。
  - 统计改动处数（股道不同 + 中间站停/通过与游戏不同）。
  - `save()`：有冲突时报错；新站台列表与游戏相同则删除草稿 `platforms`，否则写入；`fn.tidyDraft`；`fn.editTimetable(r, tt => tt.pass = …)`（首末站不通过）。
  - 按钮：放进草稿、取消、还原（重建模型）、"增删和排序停站…"（切到 `StopOrder`）、"线路配线图"（关闭后 `diagramHost` 打开）。
- `StopOrder({ r, onBack, onClose })`：增删和排序停站：列表项 `{ platformId, from（旧站序，新加为 -1）, pass }`；上移/下移、删除（先用 `fn.stopHangers(r, from)` 检查挂着的直通/指令/运用，有则拒绝）、在后面插入（选车站和站台）、中间站通过勾选；首末站总是停车。"发给游戏"（两步确认，不经草稿）调用 `fn.sendStopList(r, list)`，返回错误文字则显示，否则关闭。站台名用 `fn.platformText`。
- `stationsOf(mode)`：某运输方式下每个车站在每个线网各一项；同名车站在多个线网时名称后加"（轨道网 N）"；按中文排序。
- `NewLine({ onClose })`：新建线路组：运输方式、线路组名、种别、颜色、起点（及起点股道）、终点（同线网）、途经站、是否反方向一起建。按参数指纹 `planKey` 重算：`findCorridor([起点股道, 各途经站股道…, 终点股道])` 得车站序列，`T().model(...)`，起点股道固定时加 pin，再 `solveStops`；反方向同理。`nameOf(model)`：两个方向时按首末站台中点的位置判断上行/下行（`v.TRACK_DIRECTIONS`）加在名称后。`create()`：校验（组名必填且不重复、种别必填、有路、无冲突），每个方向向 `v.draft.created` 追加新建项（`source: ""`、`mode`、`lineName`、`lineKey`、名称、种别、颜色、各站 `platformId`/`pass`、空车次），`fn.saveDraft()`、`fn.renderAll()`。也可"改在全网配线图上选站…"（`fn.startPicking()`）。
- `NewType({ lineKey, baseId, onClose })`：给线路组新建种别（旧 `newTypeDialog`）：以组内一条路线为底（给出时刻表、车厂和各站偏好股道）；车站不照抄，而是沿轨道从起点经途经站到终点最短走法经过的车站（只在底路线所在线网）。`make(...)` 在 `findCorridor` 后，把每站偏好股道设为底路线（反方向用 `fn.counterpartOf(base)`）在该站用的股道再 `solveStops`。`titleOf(i)`：第一个方向可用自定义线路名，否则 `fn.typedName(源路线, 种别)`；反方向没有对应路线时加" 返"（`fn.withSuffix`）。`create()`：种别必填；班次模式（可空，`parsePattern`，非法时提示 `v.PATTERN_HINT`）；每个方向向 `v.draft.created` 追加（`source: 源路线 ID`、`lineKey`、名称、种别、颜色、各站、`services`、`table`（源路线当天运行的表序号）、`depot`（"加入车厂"选择，空为不加入））；展开该线路组的树节点（`v.state.openNodes`），`fn.saveDraft()`、`fn.renderAll()`。
