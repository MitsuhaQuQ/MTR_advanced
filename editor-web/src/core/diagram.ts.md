# core/diagram.ts

运行图的几何计算，与绘制分开（来自旧页面的 `computeLayout`、`axisSteps`、`clampView`、`draw`、`hitService`、`hitHandle`）：各车站行的位置、时间轴、每个车次穿过各站的折线、车次的画法（颜色、线宽、浓淡）、停站拖柄的位置，以及鼠标下的车次或拖柄。`test/diagram.test.ts` 与旧代码画同一天的结果对照。被 `views/RunningDiagram.tsx`（以 `D` 导入）使用；`servedStops` 也被 `core/duties.ts` 使用。

## 常量
- `LABEL_W = 120`：左侧站名栏宽（像素）。`AXIS_H = 28`：顶部时间轴高。`MIN_ROW_GAP = 22`：行间距下限。`PAD_BOTTOM = 30`：底部留白。`DEPOT_ROW_ROOM = 26`：上下为车厂出入库留出的高度。`DAY`：一天毫秒数。
- `mod`（内部）：非负取模。

## 类型
- `View`：`viewStart`（左边缘对应的运营日毫秒）、`pxPerMin`（每分钟像素）、`scrollY`、`width`、`height`（像素）。
- `Point`：`[x, y]` 或 `[x, y, true]`（`true` 表示跳到另一段的虚线点）。
- `RouteRows`、`ServiceIn`（内部）：车次所需字段：路线的 `rows`、`leaveRows`、`stops`，以及 `deployOrder`/`returnOrder`。
- `Look`：`{ color: "route" | "muted" | "warn", width, alpha }`。

## 函数
- `xOf(view, millis)` / `millisAt(view, x)`：时刻 ↔ 横坐标。调用者：`views/RunningDiagram.tsx:95`、`views/RunningDiagram.tsx:106`。
- `layoutRows(gaps, height, depotRoom)`：按各行间距权重 `gaps` 把行铺满高度（单位间距不小于 `MIN_ROW_GAP`），返回 `{ ys（各行未滚动的 y）, contentHeight }`。调用者：`views/RunningDiagram.tsx:303`。
- `axisSteps(pxPerMin)`：时间轴步长：网格线间距不小于 10 像素、标签间距不小于 44 像素，均取整齐时刻（10 秒到 3 小时）。返回 `{ grid, label }`（毫秒）。调用者：本文件 `axisTicks`。
- `axisTicks(view, clockOffset)`：视野内的刻度：`{ millis, x, hour（按钟点是否整点，加 `clockOffset` 即运营日起点与时区偏移）, labelled, seconds（标签是否需显示秒） }[]`。调用者：`views/RunningDiagram.tsx:326`。
- `clampView(view, contentHeight)`：把视野限制在当天前后（起点不早于 -1 小时、终点不晚于次日 6 时）和内容高度内。返回新 `View`。调用者：`views/RunningDiagram.tsx:304`。
- `servedStops(s)`：车次实际运营的首末站序：从出库指令站（否则 0）到回库指令站（否则终点）。调用者：`core/duties.ts:195`、`views/SummaryTable.tsx:80`（经 `fn`）。
- `servicePath(s, times, x, yOfRow)`：车次折线：在每站到达行画到达点；离开行不同时加一个标记为 `true` 的跳转点；有停站时间且非末站时加离开点。`times` 为各站 `[到, 发]`，`x`、`yOfRow` 为换算函数。调用者：`views/RunningDiagram.tsx:436`。
- `inView(times, x, width)`：车次是否在视野附近（左右放宽 40 像素）。调用者：`views/RunningDiagram.tsx:435`。
- `serviceLook(f)`：车次画法：选中线宽 2.5、无车 1、有车跑 2、其他 1.5；颜色为路线色，模拟中未开行或（不突出无车时的）无车为灰，突出无车时无车为警告色；他线选中时淡化到 0.25，无时刻 ≤0.5，停运灰且 ≤0.45，不突出时无车 ≤0.35，突出无车时有车的 ≤0.25。只用颜色和浓淡区分，不用虚线。调用者：`views/RunningDiagram.tsx:460`。
- `segmentDistance(x, y, a, b)`：点到线段距离。调用者：本文件 `hitPath`、`views/RunningDiagram.tsx:76`、`views/RunningDiagram.tsx:81`。
- `hitPath(drawn, x, y)`：6 像素内最近的车次，距离相同时后画的优先（同旧页面）。返回车次或 null。调用者：`views/RunningDiagram.tsx:73`。
- `dwellHandles(s, times, x, yOfRow)`：选中车次的停站拖柄：除首末站和通过站外，每站出发点。返回 `{ x, y, s, i }[]`。调用者：`views/RunningDiagram.tsx:495`。
- `hitHandle(handles, x, y)`：7 像素方框内的拖柄。调用者：`views/RunningDiagram.tsx:117`、`views/RunningDiagram.tsx:174`。
