# core/railPick.ts

在实景路网图上一次选很多段轨道（0.83.13，用户 2026-11-15：“没有办法快速选择全部需要的轨道”），给归属层的画笔用。纯函数：轨道按旧地图的数据传入，`rails[i]` 前两个数是两端节点序号（`net.data.rails`），`points[i]` 是从第一个节点到第二个节点的 x, z, x, z, …（`net.railPoints`，画轨道颜色用的同一组点）。包围盒和节点邻接按数组用 WeakMap 缓存（地图数据整个替换，不就地改）。被 `views/OwnershipPanel.tsx` 使用；测试 `test/railPick.test.ts`。

## 函数
- `railsAtNodes(rails)`（导出）：节点 → 连着它的轨道。
- `railLength(points)`（导出）：一条轨道沿点的长度。
- `railPath(rails, points, from, to)`（导出）：从一段到另一段沿轨道最短的路，两端都含，按顺序；没有路为空数组，同一段为 `[from]`。以“轨道 + 离开它的节点”为状态做 Dijkstra（代价为轨道长度）；经过节点时进来的方向和出去的方向夹角须小于直角（列车不在道岔处折返），只有完全没有这样的路时才不管方向再找一次。
- `railsAlong(rails, start)`（导出）：从一段往两头走，只穿过恰好连着两条轨道的节点，到道岔、交叉或尽头为止；环线只走一圈。第一个是 `start`。
- `segmentDistance(...)`（导出）：两条线段的距离（相交为 0）。
- `railsNearSegment(points, ax, az, bx, bz, reach)`（导出）：和线段 a–b 距离不超过 `reach` 的轨道（拖动时鼠标两次移动之间经过的）。
- `railsInBox(points, x0, z0, x1, z1)`（导出）：有点在框里或有一段穿过框边的轨道；两角顺序不限。
- 内部：`boxes`（各轨道包围盒）、`leaving`（轨道离开节点的单位方向，跳过和节点重合的点）、`search`、`pointSegment`、`segmentsCross`、`Heap`（二叉堆）。
