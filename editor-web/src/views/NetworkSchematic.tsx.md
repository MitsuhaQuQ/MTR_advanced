# views/NetworkSchematic.tsx

全网的配线图与逻辑图，两者共用同一份路网图数据（旧代码按自己的计时器读进 `legacy.v.net.data`，即 `api/network`）：
- `"tracks"` 模式（`core/trackSchematic.ts`）：真实轨道和共享道岔节点，统一距离压缩，站台画在股道旁，股道间距加大，存车线有标注；不用推断的车站咽喉或独立布局的走廊取代。`shape === "schematic"` 时再经 `core/orthogonalTracks.ts` 变成横竖 45° 示意图。
- `"logical"` 模式（归属层，`core/abstractNet.ts`）：车站画成显示股道的小框，车站之间的区段画成平行线，颜色为其逻辑线路的颜色（没有逻辑线路时为归属线路的颜色）；手持"画笔"时点一段区间会把它的所有轨道刷进画笔的逻辑线路（`views/OwnershipPanel.tsx` 的 `paint`/`useBrush`）。

车站名在任何缩放下保持大小。点车站看它的股道；"在实际图上看"把旧实景地图移到该处。由 `views/Layers.tsx` 在配线层、归属层、调度层使用。

## 常量与类型
- `GAP = 5`（逻辑图区段内平行线间距）、`LANE = 7`（车站框内每股间距）、`BOX_W = 44`（车站框宽），单位屏幕像素。
- `TrainMark`（导出）：配线图上要标的列车：`id`、`name`、`rail`（车头所在轨道序号）、`late`、`selected`。使用者：`views/Layers.tsx:154`、`views/TrackMapDrawing.tsx`。

## 导出
### `NetworkSchematic({ onShowReal, mode = "tracks", shape = "real", trains, onTrain })`（组件）
- props：`onShowReal()` 切换到实景地图；`mode` `"tracks"`/`"logical"`；`shape` `"real"`/`"schematic"`；`trains?` 要标的列车；`onTrain?(id)` 点列车。
- 每 0.4 秒检查 `v.net.data` 是否换了（旧代码只重画它的画布，不通知 React）。
- 计算（`useMemo`）：`realMap = trackSchematic(data, 拓扑站台)`、`trackMap`（示意形状时 `orthogonalTracks(realMap)`）、`schematic = abstractNetwork(data, 拓扑站台)`。
- `fit()`：把整张图缩放居中放进容器（倍数 0.05–2.5）；只在 `fitKey`（图层、形状、运输方式、股道图是否已出）变化时和随后容器前两次尺寸变化时调用——路网与拓扑定时重读成新对象不再触发，缩放不会跳回（0.77.0）；"全图"按钮也调用。
- 头部：有多种运输方式时的下拉（改 `v.net.mode`、`v.net.version = -1`，`fn.loadNetwork(false)` 让旧代码重读）、车站与区间数、说明、"全图"。
- 交互：滚轮以鼠标为中心缩放（0.05–8 倍）；拖动平移（移动超过 3 像素才算拖动，拖动后的点击不算选择）。
- 绘制：`tracks` 模式交给 `TrackMapDrawing`（整体 `translate/scale`）；`logical` 模式画 `LogicalCorridor`、`StationGlyph`、道岔/尽头圆点（屏幕坐标，不随缩放变大）。
- 选中车站后显示 `StationCard`；"在实际图上看"把 `v.net.cx/cz` 设为车站世界坐标、`v.net.scale = 2`，再 `onShowReal()`。
- 调用者：`views/Layers.tsx:93`、`views/Layers.tsx:169`、`views/Layers.tsx:196`。

## 内部组件与函数
- `StationGlyph({ place, x, y, on, onClick })`：逻辑图上的车站框：按车站轴向旋转，每股一条线（站台实线加粗，正线虚线，颜色为第一种路线颜色），下方站名（竖直车站下移更多），悬停提示股数和站台数。
- `StationCard({ place, onClose, onShowReal })`：左上角车站卡片：列出各股（站台号或"正线 / 存车线"、颜色），"在实际图上看"按钮。
- `railColors(rail)`：逻辑图上轨道的颜色：使用逻辑线路时为其逻辑线路颜色（`fn.railLogicalLine`），否则为各归属线路颜色（`fn.railOwnersShown`、`fn.ownerColor`）；归属未读到时为空。
- `paintCorridor(c, brush)`：把走廊轨道所在的每个区段（`v.net.ownerOf` 的 `section`，没有则按轨道）各刷一次（`paint(rail, brush)`，改草稿）。
- `LogicalCorridor({ corridor, a, b, sx, sy, onPaint })`：两地点之间按股数画平行线，颜色轮流取区段颜色（没有颜色时灰色虚线）；有画笔时加一条透明粗线作点击区域。
