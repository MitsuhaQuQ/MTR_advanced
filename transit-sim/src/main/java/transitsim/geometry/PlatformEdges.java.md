# PlatformEdges.java

斜线、曲线站台的站台边几何（0.88.0，一、二档）。车身是两转向架之间的弦，弯道上车中部向弯内凸出矢高 `车长²/(8R)`，两端在轨道上。弯内站台的边线外移矢高，车中部保持常规 0.5 格缝；弯外站台边线不变，车中部缝变大。两种情况最大缝都是 0.5 + 矢高。

在整体中的位置：游戏 `CurvedPSD`/`BlockCurvedPSD`/`ItemCurvedPSD`/`RenderCurvedPSD`/`BendingVertexConsumer`（0.91.0）、`BlockEdgeScreenDoor`/`ItemEdgeScreenDoor`/`RenderEdgeScreenDoor`（0.90.0 曲线屏蔽门）、`mtr.data.PlatformCurves`、`BlockPlatformEdge`（方块状态和形状）、`ItemPlatformEdge`（铺边）、`RenderPlatformEdge`（轮廓）、`PlatformGaps`（实测阈值）、`VehicleRidingServer`（渡板长度）、`EditorData`（网页提醒）。

## 常量
- `STANDARD_GAP` 0.5：直线普通站台的车侧缝（车宽 2，轨道格与站台格之间空一格）。
- `WARN_EXTRA_GAP` 0.5：比常规宽出这么多要提醒。
- `MAX_BRIDGE` 1.5：渡板最多跨的宽度。
- `MARKING_REACH` 1（0.89.0）：离边线一格内的整格方块仍按切线返回（不是 `FULL`），好沿切口画站台标线。
- `ANGLES` 64、`CUTS` 57（`CUT_CENTRE` 28，0.89.0 起）、`CELLS` 16：方块状态的方向数、切线距中心的十六分之一格数、柱数。

## 方法
- `signedSagitta(前半车点, 该点, 后半车点, 站台方向)`：弦中点相对该点在站台方向上的分量，弯内为正、弯外为负、直线为 0。
- `sagitta(车长, 半径)`、`edgeOffset(半车宽, 带符号矢高)`、`largestGap(带符号矢高)`、`smallestRadius(车长)`。
- `cutOf(方块角, 边线上一点, 站台方向)`：返回 `pack(angle, cut)`，整格站台 `FULL`，整格不是 `EMPTY`。
- `mask(angle, cut)`：16×16 柱，[x*16+z]，缓存。
- `outline(angle, cut)`：站台部分的多边形顶点（0..1），最后两点为切口两端。
- `wallSegment(angle, cut)`（0.90.0）：边线在方块内的一段 {x1,z1,x2,z2}，沿 `alongX/alongZ` 方向（朝轨道方向顺时针转 90°）；短于 `SHORTEST_WALL`（1/16）为 null。
- `wallMask(angle, cut, 起深, 止深)`（0.91.0）：边线后两深度之间的柱，曲线屏蔽门、安全门各部分的形状。
- `bend(angle, cut)` → `Bend`（0.91.0）：`point(朝向, x, z)` 把直线门方块里的点映射到边线段上（离轨道一侧的深度不变，横向位置变为沿线位置；直线贴边时不变）、`direction` 转方向、`beyond(左/右)` 沿线下一格的点、`nearestFacing` 最近的四向。
- `wallMask(angle, cut)`（0.90.0）：曲线屏蔽门墙的柱，边线后 `WALL_COLLISION_CELLS`（3）个十六分之一格；画的厚度 `WALL_THICKNESS` 2/16。

测试：`PlatformEdgesTest`。
