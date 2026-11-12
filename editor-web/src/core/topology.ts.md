# core/topology.ts

轨道上的站台及其之间的通路（退役旧代码第 1 步：旧的 `buildTopology`、`isUsable`、`trackInfo`/`trackLabel`/`trackShort`、`stationTracks`、`reachFrom`、`legCost`、`solveStops`、`findCorridor`、`orderByLayout`、`topologyModel`、`stopsSummaryOf`、`routeProblems`）。数据来自 `api/data` 的 `topology`。"状态"（state）= 站台 × 2 + 方向（0/1，列车沿站台运行的方向），即 `s >> 1` 为站台序号、`s & 1` 为方向。`test/topology.test.ts` 在同一路网上与旧函数并排运行。被 `views/StopsEditor.tsx`、`views/StationTracks.tsx`、`views/Inspector.tsx`、`views/Navigator.tsx` 使用。

## 类型与常量
- `Way`：站台某方向：`usable`、`edges`（`[目标状态, 代价, 目标状态, 代价, …]`，代价为沿轨道距离，单位未确定）。
- `TopoPlatform`：`id`、`name`、`station`、`stationName`、`network`（所在连通线网编号）、`mode?`、`line?`（`[x1, z1, x2, z2]`）、`ways: [Way, Way]`、`track?`（`direction`：`up`/`down`/`both`；`kind`：`main`/`passing` 等）、`passingTrack?`（手设的待避线标记）。
- `TopologySource`：`{ platforms, networkCount?, stations?, overlapping? }`。
- `StopsModel`：停站选择器模型：`layers`（每站可选股道的站台序号）、`picks`（选中的）、`stop`（停车/通过）、`pins`（固定股道：站序 → 站台序号）、`color`、`conflict`（要提示的冲突文字）。
- `Solution`：成功 `{ ok: true, picks, reversals（掉头的站序）, legs（各区间代价）, feasible（每站有可行通路的股道）, arrive, depart（各站到达/出发状态） }`；失败 `{ ok: false, feasible }`。
- `Problem`：`{ stop, level: "error" | "warn", text }`。
- `TRACK_DIRECTIONS`：`up` 上行、`down` 下行、`both` 双向；`TRACK_SHORT`：上/下/双。
- `compareTuples`（内部）：按 `[换股数, 掉头数, 代价]` 字典序比较。

## `class Topology`
- 字段：`platforms`、`index`（站台 ID → 序号）、`byStation`（`车站|线网` → 站台序号）、`networkCount`、`stations`、`overlapping`；私有缓存 `reach`（起点状态 → Dijkstra 结果）、`problems`（路线 → 问题，按站台列表为键）。
- `constructor(source)`：建索引。
- `isUsable(s)`：状态是否可用（该站台该方向 `usable`）。
- `platformLabel(i)`：站台名或 "?"。`stationLabel(i)`：车站短名或"（不属于车站）"。调用者：`views/StationTracks.tsx:60`、`views/StopsEditor.tsx:54`、`views/StopsEditor.tsx:98`。
- `trackInfo(id, passingTrack?)`：股道的方向与种类（手设待避优先），返回 `{direction, kind}`。
- `trackLabel(id, name, passingTrack?)`：如"上行 3道 正线"。调用者：`views/StationTracks.tsx:123`、`views/StationTracks.tsx:195`、`views/StopsEditor.tsx:115`。
- `trackShort(id, name, passingTrack?)`：窄格用，如"上 3 待避"。调用者：`views/StationTracks.tsx:122`、`views/StopsEditor.tsx:122`。
- `stationTracks(i)`：同车站同线网的股道（含自身），按名称自然排序。调用者：`views/StopsEditor.tsx:340`、`views/StopsEditor.tsx:517`、本类 `model`/`routeProblems`。
- `reachFrom(start)`：从一个状态出发、不掉头（经过站台继续走）可达的所有状态及代价：二叉堆 Dijkstra，最多处理 20000 个状态；结果 `{dist, prev}` 缓存。
- `legCost(from, to)`：两个站台任意方向之间的最小代价，不通为 `Infinity`。调用者：`views/StopsEditor.tsx:63`、本类 `routeProblems`。
- `solveStops(layers, current, pins)`：为一串车站选最佳股道（动态规划）：固定站只能用固定股道；依次最小化"与 `current` 不同的股道数"、"掉头次数"（在中间站以相反方向离开）、"总代价"。同时从两端求出每站有可行通路的股道 `feasible`。返回 `Solution`。调用者：`views/StationTracks.tsx:59`、`views/StopsEditor.tsx:50`、`views/StopsEditor.tsx:61`。
- `findCorridor(stages)`：沿轨道从起点经过中间各站（按顺序）到终点的路：每段取最短，中途车站掉头加 1e7 代价（只在别无他路时）。`stages` 为各阶段可选股道列表。返回途经每个车站的股道序号（同站连续只记一次），无路为 null。调用者：`views/StopsEditor.tsx:353`、`views/StopsEditor.tsx:533`。
- `orderByLayout(layers)`：把每站的股道按从前一站到后一站方向上的左右位置排序（最左在前），无法判断方向时按第一条股道方向；同位置按名称。站台名是玩家随意输入的，不能说明位置。
- `model(tracks, stop, color)`：由各站股道生成选择器模型（每站提供同站其他股道，按 `orderByLayout` 排）。调用者：`views/StopsEditor.tsx:147`。
- `stopsSummary(model)`："6 站，停 5 站，通过 1 站：…"。调用者：`views/StopsEditor.tsx:183`。
- `routeProblems(r, ids)`：路线股道的问题（按站台列表缓存）：停站站台没有轨道；与首站不在同一线网；两站之间走不通，或各段走得通但连起来要反复掉头；中途掉头（警告）；某区间比换股可达的最短路长 1.5 倍加 50 以上（绕路警告）。`ids` 为草稿中各停站站台 ID。调用者：`views/Inspector.tsx:130`、`views/Navigator.tsx:97`。

## 函数
- `topologyOf(source)`（导出）：每个游戏发来的拓扑对象只建一个 `Topology`（`WeakMap` 缓存，缓存随拓扑对象存亡）；`source` 为空时新建空拓扑。调用者：`views/Inspector.tsx:129`、`views/Navigator.tsx:97`、`views/StationTracks.tsx:17`、`views/StopsEditor.tsx:24`。
