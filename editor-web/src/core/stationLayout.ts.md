# core/stationLayout.ts

单个车站自己的配线图（旧代码 `stationDiagramHtml`、`loadDiagram` 与折返站台部分；取代旧停站对话框中的车站部分）：游戏中车站周围的轨道（`api/layout`，服务器 `EditorLayout`），旋转到列车沿所选股道从左向右行驶，视野由车站自己的股道决定，可平移和缩放。还有路线在草稿中的折返站台（终点）与优先出发站台（首站）。被 `views/StationTracks.tsx` 使用，`legacy/dialogs.ts` 也把 `turnOf`、`toggleTurnPlatform` 交给旧代码。

## 类型
- `LayoutData`：`api/layout` 的回答：`ready?`、`box`（`[minX, minZ, maxX, maxZ]` 世界坐标）、`rails`（每条轨道采样点 `[x, z, x, z, …]`）、`turnouts`（道岔点）、`truncated?`、`platforms`（`id`、`name`、`station`、`stationName`、`passingTrack?`、`line: [x1, z1, x2, z2]`）。
- `Point`：`[x, y]`（旋转后的世界单位）。
- `StationView`：`viewBox`（`[minX, minY, width, height]`，旋转后世界单位）、`width`/`height`（绘制像素）、`unit`（每像素的世界单位）、`rails`、`turnouts`、`platforms`（`{ platform, a, b, ours（是否为可选股道）, start（左端，放名字） }`）。
- `TurnKind`：`"terminalPlatforms" | "startPlatforms"`。

## 函数
- `stationView(data, station, tracks, pickedId, forwards, margin, pan, frame = "tracks")`：算车站配线图的视野。
  - 参数：`station` 车站 ID；`tracks` 该站在路线轨道上可选的股道 ID 集合；`pickedId` 所选股道（视图朝它的方向旋转，没有则按本站第一个站台）；`forwards` 列车是否从股道线的第一端沿线行驶；`margin` 股道外多显示的格数；`pan` 平移（旋转后单位）；`frame` 视野依据：`"tracks"`（路线在本站的股道，新页面）或 `"station"`（全站站台，旧对话框的做法）。
  - 以 `box` 中心为原点旋转；视野宽按依据点加 `margin`，高至少为宽的 0.3；绘制宽 560 像素，高 160–360。
  - 返回 `StationView`。调用者：`views/StationTracks.tsx:97`。
- `zoomMargin(margin, zoomIn)`：缩放后的 `margin`：放大乘 0.6、缩小乘 1.6，限制在 16–256。调用者：`views/StationTracks.tsx:62`。
- `turnOf(draft, r, kind)`：路线的折返站台或优先出发站台列表：草稿中有则用草稿，否则游戏的。调用者：`legacy/dialogs.ts:226`、`views/StationTracks.tsx:177`。
- `toggleTurnPlatform(draft, r, kind, platformId)`：在列表末尾加入或移出一个站台（写入 `draftEntry`）；与游戏相同则删除该字段；非冲突时清错误。直接修改 `draft`，整理和保存由调用方负责。调用者：`legacy/dialogs.ts:229`、`views/StationTracks.tsx:183`。
