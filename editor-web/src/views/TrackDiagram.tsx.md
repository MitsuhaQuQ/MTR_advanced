# views/TrackDiagram.tsx

路线或线路组的配线示意图对话框（`core/schematic.ts`）：沿路线的股道画在平行的道上，加上道岔、带编号的站台、车站，按跑在上面的路线着色，并显示隔离状态（寻路边界、联络线独占、已放行）。在新页面中取代旧页面的拉直画法；从导航树路线或线路的菜单打开（旧代码调用 `routeDiagramDialog` / `lineDiagramDialog`，在 `legacy/engine.ts` 中改接到本文件的 `diagramHost`）。右键一段轨道弹出和以前一样的隔离菜单（旧代码 `fn.railMenu`）。由 `App.tsx` 常驻挂载。

## 常量（内部）
- `GAP = 26`（道间距）、`TOP = 64`（顶部站名区高度）、`PAD = 32`（左右留白）、`BOTTOM = 28`，像素。

## 导出
### `TrackDiagram()`（组件）
- 无 props。挂载时把 `diagramHost.current` 设为 `setTarget`（卸载时清空）；有目标时渲染 `Dialog`，关闭时清目标。
- 调用者：`App.tsx:77`。

## 内部
- `type Loaded`：`{ layout: RouteLayout | null, network, error }`。
- `trunkOf(target)`：图所沿的路线：`route` 目标即该路线；`line` 目标取该线路组停站最多的路线。
- `Dialog({ target, onClose })`：
  - 加载：POST `api/route-layout`（`{route, halfWidth: 24, fresh}`，首次 `fresh: true`）；未 `ready` 时每 0.7 秒重试，最多 15 次；路网数据用旧代码已读的 `v.net.data`（运输方式相同），否则 POST `api/network`（`{mode, fresh:false, version:-1}`）。
  - Esc 关闭。
  - `schematic = buildSchematic(layout, usageOf(network, trunk.id), trunk.id, 本线（组）各路线的站台 fn.platformsOf)`；`tick` 变化（隔离被改）时重算。
  - 首次让整条线适应宽度（缩放 0.3–6）；工具栏缩小/放大（0.1–20）/全图/关闭。
  - 未找到路径时提示先在停站编辑里修好股道。
  - 点有停站序号的车站：关闭后（有编辑权限且多于一站时）`fn.stopsDialog(trunk, stop)` 打开停站编辑。
- `statusOf(key, status)`：草稿中设/取消的寻路边界覆盖服务器给的隔离状态（3 为边界，取消边界后为 0）。
- `Drawing({ schematic, trunk, zoom, onChanged, onOpenStop })`：SVG：车站竖虚线、站名与"第 N 站"；长区间压缩处的 `//` 记号；每段轨道灰底、其他路线细彩线（最多 3 条，偏移排开）、本路线粗线、隔离状态虚线（边界紫点线、联络线独占橙虚线、已放行灰虚线），透明粗线承接右键（`menu`：无权限时菜单只显示"没有编辑权限"，否则 `fn.railMenu(event, trunk, {a, b, status}, onChanged)`）和提示（走这段的路线与状态）；站台块（本线站台用路线色，提示用 `topologyOf(...).trackLabel`）；道岔和寻路边界节点圆点。
- `Legend({ layout, line })`：图例说明；线路组时说明按停站最多的种别画；不在路径上的停站数；`truncated` 时说明只画了一部分。
