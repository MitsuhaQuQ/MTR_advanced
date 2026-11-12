# core/orthogonalTracks.ts

把 `core/trackSchematic.ts` 得到的真实轨道图（`TrackMap`）变成只有横、竖和 45° 线段的示意配线图：站台保持笔直平行并转到最近的 45° 方向，车站两端的道岔留在真实画法的位置，站与站之间的轨道链按真实走向抽象为八方向折线，并排的轨道一起转弯、保持并排。被 `views/NetworkSchematic.tsx:40`（"示意"形状时）使用。

## 常量与内部函数
- `EIGHT`：八个方向的单位向量（k·45°）。
- `nearestDirection(dx, dy)`：最近的八方向序号 0–7。
- `BEND_TOLERANCE = 8`：画法可以忽略真实轨道多少弯曲（绘制单位）。
- `BESIDE_TRACKS = 28`：判定两段轨道"并排"的距离。
- `simplify(points, tolerance)`：Douglas–Peucker 折线简化。
- `split(v, k)`：把向量 `v` 分解为 `α·EIGHT[k] + β·EIGHT[e]`（`e` 为 k 左右相邻 45° 的方向，α、β ≥ 0），不在两者之间返回 null。返回 `{ along, other, e }`。

## 导出函数
- `elbow(a, b, vertical)`：横/竖主干加一段最长 32 的 45° 连接的折线；`vertical` 决定先竖还是先横。`src` 中未见调用（未确定是否仍在用）。
- `followTrack(real, a, b, directions?)`：把从 a 到 b 的真实轨道 `real` 抽象成横、竖、45° 各段：先简化出拐点（给了 `directions` 时直接用 `real` 作拐点、按给定方向），把最接近同一方向的连续拐点合成一段"run"，每段沿该方向画，剩余部分沿相邻 45° 方向补；太短而会形成小台阶的剩余部分留到下一段吸收；每段瞄准真实拐点（拐点随 a、b 的移动按长度比例平移），使画法靠近真实轨道，并排轨道折法一致。最后合并共线的连续段。返回折线点。调用者：本文件 `orthogonalTracks`（`routeChain`）。
- `sharedDirections(bendsOf)`：与旁边轨道一起决定每段的方向：把附近且走向相近（30° 以内，按轴线计，反向也算）的其他轨道段按长度加权平均（倍角平均），再取最近的八方向。返回每条链每段的方向序号。调用者：本文件 `orthogonalTracks`。
- `straightThenDiagonal(a, b)`：沿较长轴直行、中间一段 45° 的折线。`src` 中未见调用（未确定）。
- `orthogonalTracks(input)`：
  1. 复制输入；节点起始位置取真实画法中的位置。
  2. 每个车站的站台轨道（及不属车站的单独站台）：以第一条站台的方向取整到 45°，绕站台组中心旋转，各站台重新画成该方向的直线，两端节点随之移动。
  3. 所有节点对齐到 4 的网格；站台轨道再规整为从对齐起点出发、长度为 4 的倍数的直线。
  4. 以站台节点和度数不为 2 的节点为锚点，收集锚点之间的非站台轨道链（`collectChain`）；对每条链的真实拐点 `simplify`，用 `sharedDirections` 统一方向，再 `followTrack` 生成折线，按原长度比例切回每段轨道（`routeChain`），更新中间节点。
  5. 整体平移到左上留 60；重算车站名位置、尽头、分岔、宽高（+220/+140）。
  - 返回新的 `TrackMap`（不修改输入）。调用者：`views/NetworkSchematic.tsx:40`。
